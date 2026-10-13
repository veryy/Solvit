package dev.solvit.market

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BuiltinMcpUpgradeTest {
    @Test fun originalHtmlPackagesUpgradeWithoutChangingSkillsOrMultipleEncryptedConnections() = runBlocking<Unit> {
        fixture { market, config, mcpFile ->
            val app = RuntimeEnvironment.getApplication()
            val skillsFile = File(mcpFile.parentFile, "dev.solvit.skills.solvit.json")
            for ((id, file) in listOf("skills" to skillsFile, "mcp" to mcpFile)) {
                file.writeBytes(javaClass.getResourceAsStream("/$id-1.1.0.solvit.json")!!.use { it.readBytes() })
                app.getSharedPreferences("extension_permissions", 0).edit()
                    .putBoolean("seeded.dev.solvit.$id.solvit.json", true).putBoolean("enabled.dev.solvit.$id", true)
                    .putStringSet("grants.dev.solvit.$id", setOf(if (id == "skills") "storage" else "network")).commit()
            }
            val skillData = """{"format":1,"skills":[]} """.trim()
            val data = app.getSharedPreferences("extension_data.dev.solvit.skills", 0)
            data.edit().putString("skills.v1", skillData).commit()
            val profiles = """[{"name":"docs","url":"https://docs.example.invalid/mcp","token":"fixture-docs","disabled":false},{"name":"code","url":"https://code.example.invalid/mcp","token":"fixture-code","disabled":true}]"""
            config.write("dev.solvit.mcp", mapOf("connections" to profiles))
            market.restore()
            for (id in listOf("dev.solvit.skills", "dev.solvit.mcp")) {
                val installed = market.installed.value.single { it.id == id }
                assertEquals("1.1.1", installed.version); assertTrue(installed.enabled)
                val page = market.openPage(id, "main", "en")
                try { page.open(); assertNotNull(page.document()) } finally { page.close() }
            }
            assertEquals(skillData, data.getString("skills.v1", null))
            assertEquals(profiles, config.read("dev.solvit.mcp")["connections"])
            assertFalse(app.getSharedPreferences("extension_configuration", 0).getString("dev.solvit.mcp", "")!!.contains("fixture-docs"))
        }
    }
    @Test fun originalSkillsUpgradeKeepsDocumentsActivationAndGrants() = runBlocking<Unit> {
        fixture { market, _, _ ->
            val app = RuntimeEnvironment.getApplication()
            val file = File(app.filesDir, "extensions/dev.solvit.skills.solvit.json")
            file.writeBytes(javaClass.getResourceAsStream("/skills-1.0.2.solvit.json")!!.use { it.readBytes() })
            val doc = "---\nname: old-writing\ndescription: Original user skill\n---\nKeep this instruction."
            val saved = buildJsonObject {
                put("format", 1)
                putJsonArray("skills") { add(buildJsonObject {
                    put("name", "old-writing"); put("description", "Original user skill"); put("body", "Keep this instruction.")
                    putJsonObject("files") { put("SKILL.md", doc) }
                }) }
            }.toString()
            val data = app.getSharedPreferences("extension_data.dev.solvit.skills", 0)
            data.edit().putString("skills.v1", saved).commit()
            val prefs = app.getSharedPreferences("extension_permissions", 0)
            prefs.edit().putBoolean("seeded.dev.solvit.skills.solvit.json", true)
                .putBoolean("enabled.dev.solvit.skills", true).putStringSet("grants.dev.solvit.skills", setOf("storage")).commit()
            market.restore()
            val upgraded = market.installed.value.single { it.id == "dev.solvit.skills" }
            assertEquals("1.1.1", upgraded.version); assertTrue(upgraded.enabled)
            assertEquals(setOf("storage"), prefs.getStringSet("grants.dev.solvit.skills", emptySet()))
            assertEquals(saved, data.getString("skills.v1", null))
            val page = market.openPage("dev.solvit.skills", "main", "en")
            try {
                assertTrue(page.open().json.contains("old-writing")); assertNotNull(page.document())
                assertTrue(page.resource("ui/index.html").content.contains("content"))
            } finally { page.close() }
            assertEquals(saved, data.getString("skills.v1", null))
        }
    }
    @Test fun modifiedSkillsAreKeptAndAnUninstalledSkillsSeedIsNotRestored() = runBlocking<Unit> {
        fixture { market, _, _ ->
            val app = RuntimeEnvironment.getApplication()
            val file = File(app.filesDir, "extensions/dev.solvit.skills.solvit.json")
            val modified = javaClass.getResourceAsStream("/skills-1.0.2.solvit.json")!!.use { it.readBytes() } + "\n".toByteArray()
            file.writeBytes(modified)
            app.getSharedPreferences("extension_permissions", 0).edit().putBoolean("seeded.dev.solvit.skills.solvit.json", true).commit()
            market.restore(); assertContentEquals(modified, file.readBytes())
            assertEquals("1.0.2", market.installed.value.single { it.id == "dev.solvit.skills" }.version)
            market.uninstall("dev.solvit.skills"); market.restore()
            assertFalse(file.exists()); assertFalse(market.installed.value.any { it.id == "dev.solvit.skills" })
        }
    }
    private suspend fun fixture(block: suspend (AndroidMarketplace, EncryptedPluginConfiguration, File) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val client = OkHttpClient()
        val config = EncryptedPluginConfiguration(app) { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
        val file = File(app.filesDir, "extensions/dev.solvit.mcp.solvit.json")
        file.parentFile!!.mkdirs()
        app.getSharedPreferences("extension_permissions", 0).edit()
            .putBoolean("seeded.dev.solvit.mcp.solvit.json", true).commit()
        try {
            manager.load(ToolsPlugin())
            manager.load(ContextHooksPlugin()); manager.load(PostTurnHooksPlugin())
            manager.load(object : SolvitPlugin {
                override val id = "test.web"
                override val provides = setOf(WebPageServiceKey)
                override suspend fun enable(context: PluginContext) {
                    context.register(WebPageServiceKey, object : WebPageService {
                        override suspend fun visit(url: String): WebPage = error("Upgrade must not access the network")
                    })
                }
            })
            manager.enableAll()
            block(AndroidMarketplace(app, manager, client, manager.registry.require(Services.Tools), config), config, file)
        } finally {
            manager.shutdown(); scope.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun originalMcpUpgradesWithoutLosingEnabledStateOrEncryptedLegacyConfiguration() = runBlocking<Unit> {
        fixture { market, config, file ->
            file.writeBytes(javaClass.getResourceAsStream("/mcp-1.0.0.solvit.json")!!.use { it.readBytes() })
            config.write("dev.solvit.mcp", mapOf("url" to "https://example.invalid/mcp", "token" to "fixture-upgrade-token"))
            val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("extension_permissions", 0)
            prefs.edit().putBoolean("enabled.dev.solvit.mcp", true).putStringSet("grants.dev.solvit.mcp", setOf("network")).commit()
            market.restore()
            val installed = market.installed.value.single { it.id == "dev.solvit.mcp" }
            assertEquals("1.1.1", installed.version); assertTrue(installed.enabled)
            assertEquals("fixture-upgrade-token", config.read("dev.solvit.mcp")["token"])
            val page = market.openPage("dev.solvit.mcp", "main", "en")
            try {
                val view = page.open().json
                assertNotNull(page.document())
                assertTrue(view.contains("default")); assertFalse(view.contains("fixture-upgrade-token"))
            } finally { page.close() }
        }
    }
    @Test fun modifiedMcpIsPreservedAndAnUninstalledSeedIsNotResurrected() = runBlocking<Unit> {
        fixture { market, _, file ->
            val original = javaClass.getResourceAsStream("/mcp-1.0.0.solvit.json")!!.use { it.readBytes() }
            val modified = (original.toString(Charsets.UTF_8) + "\n").toByteArray()
            file.writeBytes(modified)
            market.restore()
            assertContentEquals(modified, file.readBytes())
            assertEquals("1.0.0", market.installed.value.single { it.id == "dev.solvit.mcp" }.version)
            market.uninstall("dev.solvit.mcp")
            market.restore()
            assertFalse(file.exists()); assertFalse(market.installed.value.any { it.id == "dev.solvit.mcp" })
        }
    }
    @Test fun originalMemoryPageUpgradesWithoutLosingFactsOrExistingGrants() = runBlocking<Unit> {
        fixture { market, _, _ ->
            val app = RuntimeEnvironment.getApplication()
            val memoryFile = File(app.filesDir, "extensions/dev.solvit.memory.solvit.json")
            memoryFile.writeBytes(javaClass.getResourceAsStream("/memory-1.3.1.solvit.json")!!.use { it.readBytes() })
            val prefs = app.getSharedPreferences("extension_permissions", 0)
            prefs.edit().putBoolean("seeded.dev.solvit.memory.solvit.json", true)
                .putBoolean("enabled.dev.solvit.memory", true).putStringSet("grants.dev.solvit.memory", setOf("storage", "model")).commit()
            val manager = BuiltinMemoryManager(app)
            val fact = manager.save("Memory retained through HTML upgrade", "fact", emptyList())
                .getValue("fact").jsonObject.getValue("id").jsonPrimitive.content
            market.restore()
            val installed = market.installed.value.single { it.id == "dev.solvit.memory" }
            assertEquals("1.4.0", installed.version); assertTrue(installed.enabled)
            val page = market.openPage("dev.solvit.memory", "main", "en")
            try {
                assertTrue(page.open().json.contains(fact))
                assertEquals("ui/index.html", page.document()!!.entry)
                assertTrue(manager.snapshot().toString().contains("Memory retained through HTML upgrade"))
            } finally { page.close() }
        }
    }
    @Test fun originalWebSearchUpgradesWithoutLosingEnabledStateOrEncryptedProviderSettings() = runBlocking<Unit> {
        fixture { market, config, _ ->
            val app = RuntimeEnvironment.getApplication()
            val file = File(app.filesDir, "extensions/dev.solvit.web-search.solvit.json")
            file.writeBytes(javaClass.getResourceAsStream("/web-search-1.2.0.solvit.json")!!.use { it.readBytes() })
            config.write("dev.solvit.web-search", mapOf("provider" to "tavily", "apiKey" to "fixture-search-upgrade-token"))
            app.getSharedPreferences("extension_permissions", 0).edit()
                .putBoolean("seeded.dev.solvit.web-search.solvit.json", true)
                .putBoolean("enabled.dev.solvit.web-search", true)
                .putStringSet("grants.dev.solvit.web-search", setOf("network")).commit()
            market.restore()
            val installed = market.installed.value.single { it.id == "dev.solvit.web-search" }
            assertEquals("1.2.2", installed.version); assertTrue(installed.enabled)
            assertEquals("tavily", config.read("dev.solvit.web-search")["provider"])
            assertEquals("fixture-search-upgrade-token", config.read("dev.solvit.web-search")["apiKey"])
        }
    }

}
