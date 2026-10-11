package dev.solvit.plugins

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import dev.solvit.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
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
    @Volatile private var executionJob: Job? = null

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
                        "settings.get" -> null
                        else -> error("宿主未开放此操作")
                    }
                    check(permission == null && pkg.manifest.apiVersion >= 2 || permission in pkg.manifest.permissions) { "插件未声明 $permission 权限" }
                    val parent = executionJob ?: error("宿主调用没有活动的插件执行")
                    // QuickJS async bindings have their own scope. Explicitly
                    // parent native work to this invocation, including cleanup.
                    withContext(parent) { host.call(pkg.manifest, operation, arguments).toString() }
                }
                withTimeout(timeoutMs) {
                    executionJob = currentCoroutineContext().job
                    try {
                    js.evaluate<Any?>("""
                        globalThis.ctx = Object.freeze({
                          settings: Object.freeze({ get: async key => JSON.parse(await __solvitHostCall('settings.get', JSON.stringify({key}))) }),
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
                    } finally { executionJob = null }
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
            // Let the native execution deadline fire first; this also bounds
            // promises waiting on asynchronous host operations.
            withTimeout(timeoutMs + 1_000) {
                executionJob = currentCoroutineContext().job
                try {
                    // Resolve the JS evaluation with an explicit result envelope.
                    // After Kotlin cancels an evaluation, a native binding may
                    // still reject its JS promise. Catch that rejection inside
                    // JS so it cannot poison the next invocation or deactivate.
                    val outcome = Json.parseToJsonElement(js.evaluate<String>("""
                        await (async () => {
                          try {
                            const value = await solvitPlugin.tools[${JsonPrimitive(tool)}](JSON.parse(${JsonPrimitive(input.toString())}), ctx);
                            return JSON.stringify({ok: true, result: JSON.stringify(value) ?? 'null'});
                          } catch (error) {
                            return JSON.stringify({ok: false, error: String(error?.message ?? error)});
                          }
                        })()
                    """.trimIndent())).jsonObject
                    check(outcome.getValue("ok").jsonPrimitive.boolean) {
                        outcome.getValue("error").jsonPrimitive.content
                    }
                    outcome.getValue("result").jsonPrimitive.content
                } finally { executionJob = null }
            }
        }
    }

    suspend fun stop() {
        // Interrupt before taking the mutex, so an infinite loop cannot block unload.
        executionJob?.cancel()
        engine?.interruptEvaluation()
        mutex.withLock {
            val js = engine ?: return@withLock
            try {
                withContext(Dispatchers.Default) {
                    withTimeout(2_000) {
                        executionJob = currentCoroutineContext().job
                        try { js.evaluate<Any?>("if (solvitPlugin.deactivate) await solvitPlugin.deactivate(ctx);") }
                        finally { executionJob = null }
                    }
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
