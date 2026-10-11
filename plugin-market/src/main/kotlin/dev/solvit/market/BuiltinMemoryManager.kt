package dev.solvit.market

import android.content.Context
import dev.solvit.plugins.ScriptExecutor
import dev.solvit.plugins.ScriptHost
import dev.solvit.plugins.ScriptPackage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Explicit human management of owned data, independent of plugin installation or grants.
 * Only immutable APK code is used; downloaded extensions and their lifecycle/hooks never run.
 */
class BuiltinMemoryManager(context: Context) {
    private val app = context.applicationContext
    private val data = PluginDataStore(app)
    private val pkg by lazy {
        ScriptPackage.parse(app.assets.open("builtin-plugins/dev.solvit.memory.solvit.json").use { it.readBytes() })
            .also { check(it.manifest.id == ID && it.manifest.apiVersion == 8) }
    }
    suspend fun snapshot(): JsonObject = invoke("memory_export", buildJsonObject {})
    suspend fun save(text: String, category: String, tags: List<String>, id: String? = null): JsonObject {
        require(text.isNotBlank() && text.length <= 2000 && category.isNotBlank() && category.length <= 40)
        require(tags.size <= 12 && tags.all { it.isNotBlank() && it.length <= 40 })
        return invoke("memory_put", buildJsonObject {
            put("text", text); put("category", category); put("tags", JsonArray(tags.map(::JsonPrimitive)))
            if (id != null) { require(id.length in 1..80); put("id", id) }
        })
    }
    suspend fun delete(id: String): JsonObject {
        require(id.length in 1..80)
        return invoke("memory_delete", buildJsonObject { put("id", id) })
    }
    private suspend fun invoke(tool: String, arguments: JsonObject): JsonObject {
        val host = ScriptHost { manifest, operation, args ->
            check(manifest.id == ID && args["key"] == JsonPrimitive("memory.v1"))
            when (operation) {
                "storage.get" -> { check(args.keys == setOf("key")); data.get(ID, "memory.v1") }
                "storage.set" -> {
                    check(args.keys == setOf("key", "value"))
                    data.set(ID, "memory.v1", args.getValue("value"), true) {}; JsonNull
                }
                "storage.compareAndSet" -> {
                    check(args.keys == setOf("key", "expected", "value"))
                    JsonPrimitive(data.compareAndSet(ID, "memory.v1", args.getValue("expected"), args.getValue("value")) {})
                }
                else -> error("Memory management does not expose this capability")
            }
        }
        val executor = ScriptExecutor(pkg, host, invokeLifecycle = false)
        try {
            executor.start()
            return Json.parseToJsonElement(executor.execute(tool, arguments.toString())).jsonObject
        } finally { withContext(NonCancellable) { executor.stop() } }
    }
    private companion object { const val ID = "dev.solvit.memory" }
}
