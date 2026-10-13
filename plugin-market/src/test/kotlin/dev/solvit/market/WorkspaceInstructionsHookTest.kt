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
    private suspend fun fixture(block: suspend (AndroidMarketplace, AndroidWorkspaces, ContextHookRegistry, ToolRegistry) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val client = OkHttpClient()
        val workspaces = AndroidWorkspaces(context, scope)
        try {
            manager.load(ToolsPlugin()); manager.load(ContextHooksPlugin()); manager.load(PostTurnHooksPlugin()); manager.enableAll()
            val config = EncryptedPluginConfiguration(context) { SecretKeySpec(ByteArray(32) { 5 }, "AES") }
            val tools = manager.registry.require(Services.Tools)
            val market = AndroidMarketplace(context, manager, client, tools, config, workspaces = workspaces)
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
