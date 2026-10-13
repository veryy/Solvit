package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.chat.*
import dev.solvit.storage.SqliteChatStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SkillsChatIntegrationTest {
    @Test fun importsRoleBoundSkillIntoRealChatToolLoopAndUnloadsItsHook() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val client = OkHttpClient()
        val store = SqliteChatStore(context)
        try {
            manager.load(ToolsPlugin()); manager.load(ContextHooksPlugin()); manager.load(PostTurnHooksPlugin()); manager.load(RolesPlugin(context)); manager.enableAll()
            val tools = manager.registry.require(Services.Tools)
            val hooks = manager.registry.require(ContextHookServiceKey)
            val roles = manager.registry.require(RoleServiceKey)
            val market = AndroidMarketplace(context, manager, client, tools)
            market.restore()
            assertTrue(market.installed.value.any { it.id == "dev.solvit.skills" && !it.enabled })
            assertFailsWith<IllegalStateException> { market.enable("dev.solvit.skills", emptySet()) }
            market.enable("dev.solvit.skills", setOf("storage"))
            market.enable("dev.solvit.memory", setOf("storage", "model"))
            val library = NativeSkillLibrary(market)
            library.importSkill(SkillBundle(null, mapOf(
                "SKILL.md" to "---\nname: writing\ndescription: Help write prose\n---\nPRIVATE_BODY_SENTINEL: use concrete sentences.",
                "references/guide.md" to "Concrete examples."
            )), false)
            assertEquals(setOf("writing", "code-review"), library.list().map { it.name }.toSet())
            val role = AgentRole("writing-role", "Writing", "ROLE_PROMPT_SENTINEL", setOf("dev.solvit.skills"), setOf("writing"))
            roles.save(role)
            val session = store.createSession()
            roles.select(session.id, role.id)
            var round = 0
            val provider = object : ModelProvider {
                override val id = "test"
                override fun stream(request: ModelRequest): Flow<ModelChunk> {
                    assertEquals(role, request.agentRole)
                    assertEquals(1, request.messages.count { it.role == Role.SYSTEM })
                    assertTrue(request.messages.single { it.role == Role.SYSTEM }.content.contains(role.prompt))
                    assertFalse(request.messages.single { it.role == Role.SYSTEM }.content.contains("插件上下文（dev.solvit.skills）"))
                    assertEquals(setOf("dev_solvit_skills_skills_list", "dev_solvit_skills_skills_load", "dev_solvit_skills_skills_read"), request.tools.map { it.name }.toSet())
                    return if (round++ == 0) {
                        val prompts = request.messages.single { it.role == Role.USER }.content
                        assertTrue(prompts.contains("writing")); assertFalse(prompts.contains("code-review"))
                        assertFalse(prompts.contains("PRIVATE_BODY_SENTINEL"))
                        flowOf(ModelChunk.ToolCalls(listOf(ToolCall("load", "dev_solvit_skills_skills_load", """{"name":"writing"}"""))), ModelChunk.Done)
                    } else {
                        assertTrue(request.messages.last().content.contains("PRIVATE_BODY_SENTINEL"))
                        flowOf(ModelChunk.Text("使用写作技能完成回复"), ModelChunk.Done)
                    }
                }
            }
            val gate = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
            val chat = DefaultChatService(store, ToolAgentRuntime(provider, tools, hooks = hooks), EventBus(), roles)
            val chunks = chat.send(session.id, "帮我改写", ModelConfig(), gate).toList()
            assertEquals(2, round)
            assertTrue(chunks.filterIsInstance<ModelChunk.ToolActivity>().any { it.stage == ToolStage.SUCCEEDED })
            assertTrue(store.history(session.id).any { it.role == Role.TOOL && it.content.contains("PRIVATE_BODY_SENTINEL") })
            assertEquals(MessageStatus.COMPLETE, store.history(session.id).last().status)
            assertEquals("帮我改写", store.history(session.id).single { it.role == Role.USER }.content)
            val restricted = role.copy(skillIds = emptySet())
            assertTrue(hooks.prepare(ContextInput(restricted, "write")).isEmpty())
            assertFailsWith<IllegalStateException> {
                tools.executeCall(ToolCall("denied-skill", "dev_solvit_skills_skills_load", """{"name":"writing"}"""), gate, restricted)
            }
            market.disable("dev.solvit.skills")
            assertTrue(hooks.prepare(ContextInput(role, "write")).isEmpty())
            assertFalse(tools.descriptors().any { it.pluginId == "dev.solvit.skills" })
            assertTrue(tools.descriptors().any { it.pluginId == "dev.solvit.memory" })
        } finally {
            manager.shutdown(); scope.cancel(); store.close()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
