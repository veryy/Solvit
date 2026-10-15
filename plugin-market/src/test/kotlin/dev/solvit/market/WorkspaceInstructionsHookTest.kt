package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.chat.ToolAgentRuntime
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkspaceInstructionsHookTest {
    private val id = "test.project-rules"
    private suspend fun fixture(transform: (AndroidWorkspaces) -> WorkspaceService = { it },
        block: suspend (AndroidMarketplace, AndroidWorkspaces, ContextHookRegistry, ToolRegistry) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val client = OkHttpClient()
        val workspaces = AndroidWorkspaces(context, scope)
        val reader = transform(workspaces)
        try {
            manager.load(ToolsPlugin()); manager.load(ContextHooksPlugin()); manager.load(PostTurnHooksPlugin())
            manager.load(object : SolvitPlugin {
                override val id = "host.workspace-fixture"
                override val provides = setOf(WorkspaceServiceKey)
                override suspend fun enable(context: PluginContext) { context.register(WorkspaceServiceKey, reader) }
            })
            manager.enableAll()
            val config = EncryptedPluginConfiguration(context) { SecretKeySpec(ByteArray(32) { 5 }, "AES") }
            val tools = manager.registry.require(Services.Tools)
            val market = AndroidMarketplace(context, manager, client, tools, config, workspaces = reader)
            market.restore()
            block(market, workspaces, manager.registry.require(ContextHookServiceKey), tools)
        } finally {
            manager.shutdown(); workspaces.close(); scope.cancel()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
    private suspend fun install(market: AndroidMarketplace, source: String = """
      globalThis.solvitPlugin={tools:{},beforeModel:async(input,ctx)=>{
        if('conversation' in input || 'workspaceId' in input) throw new Error('Native scope leaked');
        const value=await __solvitHostCall('workspace.instructions','{}');
        const data=JSON.parse(value);
        return data.available ? data.sources.filter(s=>s.status==='TEXT').map(s=>s.path+':'+s.text).join('\n') : '';
      }};
    """.trimIndent()) {
        val manifest = """{"id":"$id","name":"Rules","description":"fixture","version":"1.0.0","apiVersion":16,
          "permissions":["workspace"],"dependencies":[],"tools":[],"hooks":["beforeModel"]}"""
        market.installLocal(buildJsonObject { put("manifest", Json.parseToJsonElement(manifest)); put("source", source) }.toString().toByteArray())
        market.enable(id, setOf("workspace"))
    }
    @Test fun revocationAfterNativeReadPreventsReturningAnyPlaintextToJavaScript() = runBlocking<Unit> {
        var nativeRead = false
        fixture(transform = { actual -> object : WorkspaceService by actual {
            override suspend fun instructionSources(workspaceId: String, authorize: suspend () -> Unit): List<WorkspaceInstructionSource> {
                val sources = actual.instructionSources(workspaceId, authorize)
                nativeRead = sources.any { it.text == "READ_THEN_REVOKED" }
                check(RuntimeEnvironment.getApplication().getSharedPreferences("extension_permissions", 0).edit()
                    .putStringSet("grants.$id", emptySet()).commit())
                return sources
            }
        } }) { market, ws, hooks, _ ->
            install(market)
            val a = ws.forChat("revoke-after-read"); ws.write(a.id, "AGENTS.md", "READ_THEN_REVOKED")
            val report = hooks.prepareReport(ContextInput(null, "rules", ConversationContext("revoke-after-read", a.id)))
            assertTrue(nativeRead); assertTrue(report.context.isEmpty())
            assertEquals(ContextHookFailure.FAILED, report.problems.single().failure)
        }
    }

    @Test fun releasedApi24WorkspaceUpgradesWithoutResettingRulesGrantsOrCustomCode()=runBlocking<Unit> {
        fixture { market,_,_,_ ->
            val id="dev.solvit.workspace"
            market.disable(id);market.configure(id,mapOf("projectRules" to "false"))
            val original=javaClass.getResourceAsStream("/legacy/dev.solvit.workspace-1.7.0.solvit.json")!!.use { it.readBytes() }
            assertEquals("d212d6741bde9a5db30a9d307c506389343d9c705f66011d4c0a50e54c041360",
                java.security.MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it.toInt() and 255) })
            val app=RuntimeEnvironment.getApplication()
            val file=java.io.File(app.filesDir,"extensions/$id.solvit.json").apply { writeBytes(original) }
            val prefs=app.getSharedPreferences("extension_permissions",0)
            check(prefs.edit().putBoolean("seeded.$id.solvit.json",true).putBoolean("enabled.$id",true)
                .putStringSet("grants.$id",setOf("workspace","shell")).commit())
            market.restore()
            assertEquals("1.10.0",market.installed.value.single { it.id==id }.version)
            assertTrue(market.installed.value.single { it.id==id }.enabled)
            assertEquals("false",market.configuration(id)["projectRules"])
            assertEquals(setOf("workspace","shell"),prefs.getStringSet("grants.$id",emptySet()))
            market.disable(id)
            val modified=original+"\n".toByteArray();file.writeBytes(modified);market.restore()
            assertContentEquals(modified,file.readBytes())
            market.uninstall(id);market.restore();assertFalse(file.exists())
        }
    }
    @Test fun releasedApi26WorkspaceRetainsSettingsGrantsCustomCodeAndUninstall()=runBlocking<Unit> {
        releasedPackageUpgrade("dev.solvit.workspace","1.9.0","1.10.0",
            "c4bd5bfa19dd9bca67d6913c02a48bc2624858d9be5eb32f7863c6a0273d5f5e",setOf("workspace","shell"))
    }
    @Test fun releasedGitRetainsDisabledStateGrantsCustomCodeAndUninstall()=runBlocking<Unit> {
        releasedPackageUpgrade("dev.solvit.git","1.2.5","1.2.6",
            "ee8cab14b1992ee28651c0ce510d9e046d66a396bc559d2d7293a2aadeb277b4",setOf("workspace","git","network"))
    }
    private suspend fun releasedPackageUpgrade(plugin:String,oldVersion:String,newVersion:String,sha:String,grants:Set<String>) {
        fixture { market,_,_,_ ->
            market.disable(plugin)
            if(plugin=="dev.solvit.workspace")market.configure(plugin,mapOf("projectRules" to "false"))
            val old=javaClass.getResourceAsStream("/legacy/$plugin-$oldVersion.solvit.json")!!.use { it.readBytes() }
            assertEquals(sha,java.security.MessageDigest.getInstance("SHA-256").digest(old).joinToString(""){"%02x".format(it)})
            val app=RuntimeEnvironment.getApplication()
            val file=java.io.File(app.filesDir,"extensions/$plugin.solvit.json").apply { writeBytes(old) }
            val prefs=app.getSharedPreferences("extension_permissions",0)
            check(prefs.edit().putBoolean("seeded.$plugin.solvit.json",true).putBoolean("enabled.$plugin",false)
                .putStringSet("grants.$plugin",grants).commit())
            market.restore()
            assertEquals(newVersion,market.installed.value.single { it.id==plugin }.version)
            assertFalse(market.installed.value.single { it.id==plugin }.enabled)
            assertEquals(grants,prefs.getStringSet("grants.$plugin",emptySet()))
            if(plugin=="dev.solvit.workspace")assertEquals("false",market.configuration(plugin)["projectRules"])
            val modified=old+"\n".toByteArray();file.writeBytes(modified);market.restore()
            assertContentEquals(modified,file.readBytes())
            market.uninstall(plugin);market.restore();assertFalse(file.exists())
        }
    }
    @Test fun releasedApi25WorkspaceUpgradesWithoutResettingRulesGrantsOrCustomCode()=runBlocking<Unit> {
        fixture { market,_,_,_ ->
            val id="dev.solvit.workspace"
            market.disable(id);market.configure(id,mapOf("projectRules" to "false"))
            val original=javaClass.getResourceAsStream("/legacy/dev.solvit.workspace-1.8.0.solvit.json")!!.use { it.readBytes() }
            assertEquals("e5ad26b643d323bd3b622094121316b94eaaf17400d62397c91243c5e7a3fe5d",
                java.security.MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it.toInt() and 255) })
            val app=RuntimeEnvironment.getApplication()
            val file=java.io.File(app.filesDir,"extensions/$id.solvit.json").apply { writeBytes(original) }
            val prefs=app.getSharedPreferences("extension_permissions",0)
            check(prefs.edit().putBoolean("seeded.$id.solvit.json",true).putBoolean("enabled.$id",true)
                .putStringSet("grants.$id",setOf("workspace","shell")).commit())
            market.restore()
            assertEquals("1.10.0",market.installed.value.single { it.id==id }.version)
            assertTrue(market.installed.value.single { it.id==id }.enabled)
            assertEquals("false",market.configuration(id)["projectRules"])
            assertEquals(setOf("workspace","shell"),prefs.getStringSet("grants.$id",emptySet()))
            market.disable(id)
            val modified=original+"\n".toByteArray();file.writeBytes(modified);market.restore()
            assertContentEquals(modified,file.readBytes())
            market.uninstall(id);market.restore();assertFalse(file.exists())
        }
    }
    @Test fun originalHtmlWorkspaceUpgradesAndModifiedOrUninstalledPackageIsPreserved() = runBlocking<Unit> {
        fixture { market, _, _, _ ->
            val original = javaClass.getResourceAsStream("/legacy/dev.solvit.workspace-1.1.0.solvit.json")!!.use { it.readBytes() }
            val app = RuntimeEnvironment.getApplication()
            val file = java.io.File(app.filesDir, "extensions/dev.solvit.workspace.solvit.json")
            file.writeBytes(original)
            val prefs = app.getSharedPreferences("extension_permissions", 0)
            check(prefs.edit().putBoolean("seeded.dev.solvit.workspace.solvit.json", true)
                .putBoolean("enabled.dev.solvit.workspace", true).putStringSet("grants.dev.solvit.workspace", setOf("workspace", "shell")).commit())
            market.restore()
            val upgraded = market.installed.value.single { it.id == "dev.solvit.workspace" }
            assertEquals("1.10.0", upgraded.version); assertTrue(upgraded.enabled)
            assertEquals(setOf("workspace", "shell"), prefs.getStringSet("grants.dev.solvit.workspace", emptySet()))
            market.disable(upgraded.id)
            val modified = original + "\n".toByteArray()
            file.writeBytes(modified); market.restore(); assertContentEquals(modified, file.readBytes())
            market.uninstall(upgraded.id); market.restore()
            assertFalse(file.exists()); assertFalse(market.installed.value.any { it.id == upgraded.id })
        }
    }

    @Test fun remotelyBuiltTsWorkspacePackageProjectsRulesAndHonorsItsSavedSwitch() = runBlocking<Unit> {
        fixture { market, ws, hooks, _ ->
            val plugin = "dev.solvit.workspace"
            market.enable(plugin, setOf("workspace", "shell"))
            assertEquals("1.10.0", market.installed.value.single { it.id == plugin }.version)
            val a = ws.forChat("ts-chat"); ws.write(a.id, "AGENTS.md", "TS_RULE_ACTUAL")
            val input = ContextInput(AgentRole("ts-role", "TS role", pluginIds = setOf(plugin)), "work", ConversationContext("ts-chat", a.id))
            val first = hooks.prepareReport(input)
            assertTrue(first.problems.isEmpty(), first.problems.toString())
            assertTrue(first.context.single().contains("TS_RULE_ACTUAL"))
            ws.write(a.id, "AGENTS.md", "TS_RULE_CHANGED")
            val changed = hooks.prepare(input).single()
            assertTrue(changed.contains("TS_RULE_CHANGED")); assertFalse(changed.contains("TS_RULE_ACTUAL"))
            market.disable(plugin); market.configure(plugin, mapOf("projectRules" to "false"))
            market.enable(plugin, setOf("workspace", "shell"))
            assertTrue(hooks.prepare(input).isEmpty())
        }
    }

    @Test fun originalApi16WorkspaceUpgradesWithoutLosingItsSavedRuleSwitchOrGrants() = runBlocking<Unit> {
        fixture { market, _, _, _ ->
            val id="dev.solvit.workspace"
            market.disable(id);market.configure(id,mapOf("projectRules" to "false"))
            val original=javaClass.getResourceAsStream("/legacy/dev.solvit.workspace-1.2.0.solvit.json")!!.use { it.readBytes() }
            val app=RuntimeEnvironment.getApplication()
            val file=java.io.File(app.filesDir,"extensions/$id.solvit.json");file.writeBytes(original)
            val prefs=app.getSharedPreferences("extension_permissions",0)
            check(prefs.edit().putBoolean("seeded.$id.solvit.json",true).putBoolean("enabled.$id",true)
                .putStringSet("grants.$id",setOf("workspace","shell")).commit())
            market.restore()
            val upgraded=market.installed.value.single { it.id==id }
            assertEquals("1.10.0",upgraded.version);assertTrue(upgraded.enabled)
            assertEquals("false",market.configuration(id)["projectRules"])
            assertEquals(setOf("workspace","shell"),prefs.getStringSet("grants.$id",emptySet()))
            assertEquals(listOf("main"),upgraded.pages.map { it.id })
            assertFails { market.openPage(id,"changes","zh") }
            market.disable(id)
            val modified=original+"\n".toByteArray();file.writeBytes(modified);market.restore()
            assertContentEquals(modified,file.readBytes())
            market.uninstall(id);market.restore();assertFalse(file.exists())
        }
    }

    @Test fun originalApi18WorkspaceUpgradesToImportsAndKeepsUserSettingsOrModifiedBytes() = runBlocking<Unit> {
        fixture { market, _, _, _ ->
            val id = "dev.solvit.workspace"
            market.disable(id); market.configure(id, mapOf("projectRules" to "false"))
            val original = javaClass.getResourceAsStream("/legacy/dev.solvit.workspace-1.4.0.solvit.json")!!.use { it.readBytes() }
            val app = RuntimeEnvironment.getApplication()
            val file = java.io.File(app.filesDir, "extensions/$id.solvit.json").apply { writeBytes(original) }
            val prefs = app.getSharedPreferences("extension_permissions", 0)
            check(prefs.edit().putBoolean("seeded.$id.solvit.json", true).putBoolean("enabled.$id", true)
                .putStringSet("grants.$id", setOf("workspace", "shell")).commit())
            market.restore()
            val upgraded = market.installed.value.single { it.id == id }
            assertEquals("1.10.0", upgraded.version); assertTrue(upgraded.enabled)
            assertEquals("false", market.configuration(id)["projectRules"])
            assertEquals(setOf("workspace", "shell"), prefs.getStringSet("grants.$id", emptySet()))
            val manifest = dev.solvit.plugins.ScriptPackage.parse(file.readBytes()).manifest
            assertEquals(27, manifest.apiVersion); assertEquals(setOf("workspace.files"), manifest.nativeServices)
            market.disable(id)
            val modified = original + "\n".toByteArray(); file.writeBytes(modified); market.restore()
            assertContentEquals(modified, file.readBytes())
            market.uninstall(id); market.restore(); assertFalse(file.exists())
        }
    }

    @Test fun originalApi22WorkspaceUpgradesToPdfPreviewsAndKeepsUserSettingsOrModifiedBytes() = runBlocking<Unit> {
        fixture { market, _, _, _ ->
            val id = "dev.solvit.workspace"
            market.disable(id); market.configure(id, mapOf("projectRules" to "false"))
            val original = javaClass.getResourceAsStream("/legacy/dev.solvit.workspace-1.5.0.solvit.json")!!.use { it.readBytes() }
            val app = RuntimeEnvironment.getApplication()
            val file = java.io.File(app.filesDir, "extensions/$id.solvit.json").apply { writeBytes(original) }
            val prefs = app.getSharedPreferences("extension_permissions", 0)
            check(prefs.edit().putBoolean("seeded.$id.solvit.json", true).putBoolean("enabled.$id", true)
                .putStringSet("grants.$id", setOf("workspace", "shell")).commit())
            market.restore()
            val upgraded = market.installed.value.single { it.id == id }
            assertEquals("1.10.0", upgraded.version); assertTrue(upgraded.enabled)
            assertEquals("false", market.configuration(id)["projectRules"])
            assertEquals(setOf("workspace", "shell"), prefs.getStringSet("grants.$id", emptySet()))
            val manifest = dev.solvit.plugins.ScriptPackage.parse(file.readBytes()).manifest
            assertEquals(27, manifest.apiVersion); assertEquals(setOf("workspace.files"), manifest.nativeServices)
            market.disable(id)
            val modified = original + "\n".toByteArray(); file.writeBytes(modified); market.restore()
            assertContentEquals(modified, file.readBytes())
            market.uninstall(id); market.restore(); assertFalse(file.exists())
        }
    }

    @Test fun originalApi23WorkspaceUpgradesToChatAttachmentsAndKeepsUserSettingsOrModifiedBytes() = runBlocking<Unit> {
        fixture { market, _, _, _ ->
            val id = "dev.solvit.workspace"
            market.disable(id); market.configure(id, mapOf("projectRules" to "false"))
            val original = javaClass.getResourceAsStream("/legacy/dev.solvit.workspace-1.6.0.solvit.json")!!.use { it.readBytes() }
            val app = RuntimeEnvironment.getApplication()
            val file = java.io.File(app.filesDir, "extensions/$id.solvit.json").apply { writeBytes(original) }
            val prefs = app.getSharedPreferences("extension_permissions", 0)
            check(prefs.edit().putBoolean("seeded.$id.solvit.json", true).putBoolean("enabled.$id", true)
                .putStringSet("grants.$id", setOf("workspace", "shell")).commit())
            market.restore()
            val upgraded = market.installed.value.single { it.id == id }
            assertEquals("1.10.0", upgraded.version); assertTrue(upgraded.enabled)
            assertEquals("false", market.configuration(id)["projectRules"])
            assertEquals(setOf("workspace", "shell"), prefs.getStringSet("grants.$id", emptySet()))
            val manifest = dev.solvit.plugins.ScriptPackage.parse(file.readBytes()).manifest
            assertEquals(27, manifest.apiVersion); assertEquals(setOf("workspace.files"), manifest.nativeServices)
            market.disable(id)
            val modified = original + "\n".toByteArray(); file.writeBytes(modified); market.restore()
            assertContentEquals(modified, file.readBytes())
            market.uninstall(id); market.restore(); assertFalse(file.exists())
        }
    }

    @Test fun originalApi17WorkspaceUpgradesWithoutLosingItsSavedRuleSwitchOrGrants() = runBlocking<Unit> {
        fixture { market, _, _, _ ->
            val id="dev.solvit.workspace"
            market.disable(id);market.configure(id,mapOf("projectRules" to "false"))
            val original=javaClass.getResourceAsStream("/legacy/dev.solvit.workspace-1.3.0.solvit.json")!!.use { it.readBytes() }
            val app=RuntimeEnvironment.getApplication()
            val file=java.io.File(app.filesDir,"extensions/$id.solvit.json");file.writeBytes(original)
            val prefs=app.getSharedPreferences("extension_permissions",0)
            check(prefs.edit().putBoolean("seeded.$id.solvit.json",true).putBoolean("enabled.$id",true)
                .putStringSet("grants.$id",setOf("workspace","shell")).commit())
            market.restore()
            val upgraded=market.installed.value.single { it.id==id }
            assertEquals("1.10.0",upgraded.version);assertTrue(upgraded.enabled)
            assertEquals("false",market.configuration(id)["projectRules"])
            assertEquals(setOf("workspace","shell"),prefs.getStringSet("grants.$id",emptySet()))
            assertEquals(listOf("main"),upgraded.pages.map { it.id })
            assertFails { market.openPage(id,"changes","zh") }
            market.disable(id)
            val modified=original+"\n".toByteArray();file.writeBytes(modified);market.restore()
            assertContentEquals(modified,file.readBytes())
            market.uninstall(id);market.restore();assertFalse(file.exists())
        }
    }

    @Test fun actualQuickJsReadsOnlyCapturedConversationAndRefreshesEditedDeletedRules() = runBlocking<Unit> {
        fixture { market, ws, hooks, _ ->
            install(market)
            val a = ws.forChat("a"); val b = ws.forChat("b")
            ws.write(a.id, "AGENTS.md", "RULE_A"); ws.write(b.id, "AGENTS.md", "PRIVATE_B")
            val input = ContextInput(null, "rules", ConversationContext("a", a.id))
            val first = hooks.prepareReport(input)
            assertTrue(first.problems.isEmpty(), first.problems.toString())
            assertTrue(first.context.single().contains("RULE_A")); assertFalse(first.context.joinToString().contains("PRIVATE_B"))
            ws.write(a.id, "AGENTS.md", "RULE_C")
            val changed = hooks.prepare(input).single()
            assertTrue(changed.contains("RULE_C")); assertFalse(changed.contains("RULE_A"))
            java.io.File(ws.location(a.id), "AGENTS.md").delete()
            assertTrue(hooks.prepare(input).isEmpty())
            assertTrue(hooks.prepare(ContextInput(null, "unbound")).isEmpty())
            assertNull(ws.bound("unbound"))
        }
    }
    @Test fun actualAgentModelRequestGetsRulesWithoutToolCallsAndPreservesIdentity() = runBlocking<Unit> {
        fixture { market, ws, hooks, tools ->
            install(market)
            val a = ws.forChat("chat"); ws.write(a.id, "CLAUDE.md", "BUILD_WITH_RULES")
            var captured: ModelRequest? = null
            val provider = object : ModelProvider {
                override val id = "fixture"
                override fun stream(request: ModelRequest): Flow<ModelChunk> {
                    captured = request; return flowOf(ModelChunk.Text("fixture response"), ModelChunk.Done)
                }
            }
            val runtime = ToolAgentRuntime(provider, tools, hooks = hooks)
            val role = AgentRole("rules-role", "Rules identity", "Keep this identity", setOf(id))
            runtime.run(ModelRequest(ModelConfig(), listOf(ModelMessage(Role.SYSTEM, role.prompt), ModelMessage(Role.USER, "work")),
                agentRole = role, conversation = ConversationContext("chat", a.id), sessionId = "chat")).toList()
            val request = assertNotNull(captured)
            assertTrue(request.contextProblems.isEmpty(), request.contextProblems.toString())
            assertEquals(role.prompt, request.messages.single { it.role == Role.SYSTEM }.content)
            assertTrue(request.messages.single { it.role == Role.USER }.content.contains("BUILD_WITH_RULES"))
            assertTrue(request.tools.isEmpty())
        }
    }
    @Test fun revokedGrantRoleExclusionAndChangedBindingNeverReturnPrivateRules() = runBlocking<Unit> {
        fixture { market, ws, hooks, _ ->
            install(market)
            val a = ws.forChat("a"); val b = ws.forChat("b"); ws.write(a.id, "AGENTS.md", "PRIVATE")
            val input = ContextInput(null, "rules", ConversationContext("a", a.id))
            assertTrue(hooks.prepare(input).single().contains("PRIVATE"))
            val restricted = input.copy(role = AgentRole("restricted", "Restricted"))
            assertTrue(hooks.prepareReport(restricted).context.isEmpty())
            ws.bind("a", b.id)
            assertTrue(hooks.prepareReport(input).context.isEmpty())
            ws.bind("a", a.id)
            check(RuntimeEnvironment.getApplication().getSharedPreferences("extension_permissions", 0).edit()
                .putStringSet("grants.$id", emptySet()).commit())
            val revoked = hooks.prepareReport(input)
            assertTrue(revoked.context.isEmpty()); assertEquals(ContextHookFailure.FAILED, revoked.problems.single().failure)
            market.disable(id)
            assertTrue(hooks.prepareReport(input).problems.isEmpty())
        }
    }
    @Test fun scriptCannotSupplyOtherWorkspaceOrUseOrdinaryReadFromHook() = runBlocking<Unit> {
        fixture { market, ws, hooks, _ ->
            val a = ws.forChat("a"); val b = ws.forChat("b"); ws.write(b.id, "AGENTS.md", "PRIVATE_B")
            for (source in listOf(
                "globalThis.solvitPlugin={tools:{},beforeModel:async()=>await __solvitHostCall('workspace.instructions','{\"workspaceId\":\"${b.id}\"}')};",
                "globalThis.solvitPlugin={tools:{},beforeModel:async()=>await __solvitHostCall('workspace.read','{\"path\":\"AGENTS.md\"}')};")) {
                install(market, source)
                val report = hooks.prepareReport(ContextInput(null, "rules", ConversationContext("a", a.id)))
                assertTrue(report.context.isEmpty()); assertEquals(ContextHookFailure.FAILED, report.problems.single().failure)
                market.disable(id)
            }
        }
    }
}
