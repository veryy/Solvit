package dev.solvit.market

import android.content.Context
import dev.solvit.plugins.ScriptExecutor
import dev.solvit.plugins.ScriptHost
import dev.solvit.plugins.ScriptTool
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
    suspend fun pruneRetiredSources(isRetired: suspend (String) -> Boolean): Int {
        val sources = snapshot()["completedSources"]?.jsonArray.orEmpty()
        val ids = sources.map { it.jsonObject.getValue("turnId").jsonPrimitive.content }.distinct()
        val retired = ids.filter { isRetired(it) }
        if (retired.isEmpty()) return 0
        val result = invoke(PRUNE, buildJsonObject { put("turnIds", JsonArray(retired.map(::JsonPrimitive))) }) {
            // Recheck at the actual storage write lock, not only at UI selection time.
            check(retired.all { isRetired(it) }) { "A source still has a durable task" }
        }
        return result.getValue("deleted").jsonPrimitive.int.also { check(it in 0..512) }
    }
    private suspend fun invoke(tool: String, arguments: JsonObject, verifyWrite: suspend () -> Unit = {}): JsonObject {
        val host = ScriptHost { manifest, operation, args ->
            check(manifest.id == ID && args["key"] == JsonPrimitive("memory.v1"))
            when (operation) {
                "storage.get" -> { check(args.keys == setOf("key")); data.get(ID, "memory.v1") }
                "storage.set" -> {
                    check(args.keys == setOf("key", "value"))
                    data.set(ID, "memory.v1", args.getValue("value"), true, verifyWrite); JsonNull
                }
                "storage.compareAndSet" -> {
                    check(args.keys == setOf("key", "expected", "value"))
                    JsonPrimitive(data.compareAndSet(ID, "memory.v1", args.getValue("expected"), args.getValue("value"), verifyWrite))
                }
                else -> error("Memory management does not expose this capability")
            }
        }
        val managementPackage = if (tool == PRUNE) {
            check(pkg.manifest.tools.none { it.name == PRUNE })
            pkg.copy(manifest = pkg.manifest.copy(tools = pkg.manifest.tools + ScriptTool(
                PRUNE, "Internal verified source retirement", buildJsonObject { put("type", "object") }, chatVisible = false)))
        } else pkg
        val executor = ScriptExecutor(managementPackage, host, invokeLifecycle = false)
        try {
            executor.start()
            return Json.parseToJsonElement(executor.execute(tool, arguments.toString())).jsonObject
        } finally { withContext(NonCancellable) { executor.stop() } }
    }
    private companion object { const val ID = "dev.solvit.memory"; const val PRUNE = "memory_prune_sources" }
}
