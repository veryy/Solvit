package dev.solvit.plugins

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import dev.solvit.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

fun interface ScriptHost {
    suspend fun call(plugin: ScriptManifest, operation: String, arguments: JsonObject): JsonElement
}

/** One engine per enabled plugin; no Java reflection or implicit OS access. */
class ScriptExecutor(private val pkg: ScriptPackage, private val host: ScriptHost, private val timeoutMs: Long = 30_000) {
    private val mutex = Mutex()
    @Volatile private var engine: QuickJs? = null

    suspend fun start() = mutex.withLock {
        check(engine == null) { "插件已启用" }
        withContext(Dispatchers.Default) {
            val js = QuickJs.create(Dispatchers.IO)
            try {
                js.memoryLimit = 32L * 1024 * 1024
                js.maxStackSize = 512L * 1024
                js.evaluationTimeoutMillis = timeoutMs
                js.asyncFunction("__solvitHostCall") { args ->
                    val operation = args[0] as String
                    val arguments = Json.parseToJsonElement(args[1] as String).jsonObject
                    val permission = when (operation) {
                        "storage.get", "storage.set" -> "storage"
                        "http.request" -> "network"
                        else -> error("宿主未开放此操作")
                    }
                    check(permission in pkg.manifest.permissions) { "插件未声明 $permission 权限" }
                    host.call(pkg.manifest, operation, arguments).toString()
                }
                withTimeout(timeoutMs) {
                    js.evaluate<Any?>("""
                        globalThis.ctx = Object.freeze({
                          storage: Object.freeze({
                            get: async key => JSON.parse(await __solvitHostCall('storage.get', JSON.stringify({key}))),
                            set: async (key,value) => JSON.parse(await __solvitHostCall('storage.set', JSON.stringify({key,value})))
                          }),
                          http: Object.freeze({
                            request: async options => JSON.parse(await __solvitHostCall('http.request', JSON.stringify(options)))
                          })
                        });
                    """.trimIndent())
                    js.evaluate<Any?>(pkg.source)
                    js.evaluate<Any?>("""
                        if (!globalThis.solvitPlugin || typeof solvitPlugin.tools !== 'object') throw new Error('Missing plugin.tools');
                    """.trimIndent())
                    for (tool in pkg.manifest.tools) {
                        js.evaluate<Any?>("if (typeof solvitPlugin.tools[${JsonPrimitive(tool.name)}] !== 'function') throw new Error('Missing tool handler');")
                    }
                    js.evaluate<Any?>("if (solvitPlugin.activate) await solvitPlugin.activate(ctx);")
                }
                engine = js
            } catch (e: Throwable) { js.close(); throw e }
        }
    }

    suspend fun execute(tool: String, arguments: String): String = mutex.withLock {
        require(pkg.manifest.tools.any { it.name == tool }) { "插件没有声明此工具" }
        val input = Json.parseToJsonElement(arguments).jsonObject
        val js = engine ?: error("插件未启用")
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMs) {
                js.evaluate<String>("JSON.stringify(await solvitPlugin.tools[${JsonPrimitive(tool)}](JSON.parse(${JsonPrimitive(input.toString())}), ctx))")
            }
        }
    }

    suspend fun stop() {
        // Interrupt before taking the mutex, so an infinite loop cannot block unload.
        engine?.interruptEvaluation()
        mutex.withLock {
            val js = engine ?: return@withLock
            try {
                withContext(Dispatchers.Default) {
                    withTimeout(2_000) { js.evaluate<Any?>("if (solvitPlugin.deactivate) await solvitPlugin.deactivate(ctx);") }
                }
            } finally { js.close(); engine = null }
        }
    }
}

class ScriptToolPlugin(private val pkg: ScriptPackage, private val host: ScriptHost) : SolvitPlugin {
    override val id = pkg.manifest.id
    override val dependencies = pkg.manifest.dependencies
    override val requires = setOf(Services.Tools)
    override suspend fun enable(context: PluginContext) {
        val executor = ScriptExecutor(pkg, host)
        context.own { executor.stop() }
        executor.start()
        val tools = context.require(Services.Tools)
        for (tool in pkg.manifest.tools) {
            val unregister = tools.register(object : SolvitTool {
                override val descriptor = ToolDescriptor(
                    name = id.replace('.', '_').replace('-', '_') + "_" + tool.name,
                    description = tool.description,
                    inputSchema = tool.inputSchema.toString(),
                    requiresConfirmation = true,
                )
                override suspend fun execute(argumentsJson: String): String = executor.execute(tool.name, argumentsJson)
            })
            context.own { unregister() }
        }
    }
}
