package dev.solvit.market

import android.content.Context
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PostTurnHostTest {
    @Test fun nativePermissionsBoundInferenceAndAtomicWritesAcrossActualQuickJsBridge() = runBlocking<Unit> {
        val context: Context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val client = OkHttpClient()
        val id = "test.background"
        val prefs = context.getSharedPreferences("extension_permissions", Context.MODE_PRIVATE)
        val data = context.getSharedPreferences("extension_data.$id", Context.MODE_PRIVATE)
        data.edit().clear().commit()
        val payload = buildJsonObject {
            putJsonObject("manifest") {
                put("id", id); put("name", "Background"); put("description", "Test"); put("version", "1.0.0"); put("apiVersion", 8)
                put("permissions", JsonArray(listOf("storage", "model").map(::JsonPrimitive)))
                put("dependencies", JsonArray(emptyList())); put("tools", JsonArray(emptyList()))
                put("hooks", JsonArray(listOf(JsonPrimitive("afterTurn")))); put("afterTurnEnabledSetting", "autoSave")
                putJsonArray("settings") { add(buildJsonObject {
                    put("key", "autoSave"); put("label", "Save"); put("kind", "boolean"); put("defaultValue", "false")
                }) }
            }
            put("source", """globalThis.solvitPlugin={tools:{},afterTurn:async(input,ctx)=>{
              const previous=await ctx.storage.get('memory');
              const fact=await ctx.model.complete({instructions:'extract',input:input.userText});
              if(!await ctx.storage.compareAndSet('memory',previous,{text:fact,source:input.source.turnId}))throw new Error('Conflict');
            }};""")
        }.toString().toByteArray()
        try {
            manager.load(ToolsPlugin()); manager.load(PostTurnHooksPlugin()); manager.enableAll()
            val configuration = EncryptedPluginConfiguration(context) { SecretKeySpec(ByteArray(32), "AES") }
            val market = AndroidMarketplace(context, manager, client, manager.registry.require(Services.Tools), configuration)
            market.restore(); market.installLocal(payload)
            assertFailsWith<IllegalStateException> { market.enable(id, setOf("storage")) }
            market.enable(id, setOf("storage", "model"))
            val registry = manager.registry.require(PostTurnHookServiceKey)
            assertTrue(registry.admit(null).isEmpty()) // Installed and enabled still requires opt-in.
            market.disable(id); market.configure(id, mapOf("autoSave" to "true")); market.enable(id, setOf("storage", "model"))
            val target = registry.admit(null).single(); val hook = registry.resolve(target)!!
            val input = PostTurnInput(CompletedChatTurn("answer", "session", "question", "answer", null, null, null,
                "profile", "openai", "model", 1, listOf(target)), "喜欢 Kotlin", "好的")
            var modelCalls = 0
            val model = PostTurnModel { instructions, text ->
                modelCalls++; assertEquals("extract", instructions); assertEquals("喜欢 Kotlin", text); "喜欢 Kotlin"
            }
            withContext(PostTurnExecutionContext(target, model) {}) { hook.process(input, model) }
            assertEquals(1, modelCalls)
            assertEquals("answer", Json.parseToJsonElement(data.getString("memory", null)!!).jsonObject.getValue("source").jsonPrimitive.content)
            val snapshot = data.getString("memory", null)
            // Revoke model while inference is in flight, leaving storage granted.
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val waiting = PostTurnModel { _, _ -> entered.complete(Unit); release.await(); "must not save" }
            val execution = PostTurnExecutionContext(target, waiting) {}
            val task = async(execution) { runCatching { hook.process(input, waiting) } }
            withTimeout(5_000) { entered.await() }
            prefs.edit().putStringSet("grants.$id", setOf("storage")).commit(); release.complete(Unit)
            assertTrue(withTimeout(5_000) { task.await() }.isFailure)
            assertEquals(PostTurnFailure.PERMISSION, execution.nativeFailure)
            assertEquals(snapshot, data.getString("memory", null))
            market.uninstall(id)
        } finally {
            manager.shutdown(); scope.cancel()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
