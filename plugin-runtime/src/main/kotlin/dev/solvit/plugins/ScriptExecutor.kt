package dev.solvit.plugins

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import dev.solvit.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.coroutines.CoroutineContext
import java.time.ZonedDateTime
import java.util.TimeZone

fun interface ScriptHost {
    suspend fun call(plugin: ScriptManifest, operation: String, arguments: JsonObject): JsonElement
}

/** One engine per enabled plugin; no Java reflection or implicit OS access. */
class ScriptExecutor(private val pkg: ScriptPackage, private val host: ScriptHost, private val timeoutMs: Long = 30_000) {
    private val mutex = Mutex()
    @Volatile private var engine: QuickJs? = null
    @Volatile private var executionJob: Job? = null
    @Volatile private var executionContext: CoroutineContext? = null
    @Volatile private var readOnlyHost = false

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
                    check(!readOnlyHost || operation in setOf("storage.get", "clock.now")) { "上下文 Hook 只允许读取插件存储和当前时间" }
                    val arguments = Json.parseToJsonElement(args[1] as String).jsonObject
                    val permission = when (operation) {
                        "storage.get", "storage.set" -> "storage"
                        "http.request" -> "network"
                        "workspace.info", "workspace.read", "workspace.write", "workspace.createShell" -> "workspace"
                        "workspace.start", "workspace.status", "workspace.cancel" -> "shell"
                        "plugins.draft" -> "plugins"
                        "settings.get", "clock.now" -> null
                        else -> error("宿主未开放此操作")
                    }
                    check(permission == null && pkg.manifest.apiVersion >= 2 || permission in pkg.manifest.permissions) { "插件未声明 $permission 权限" }
                    check(operation != "clock.now" || pkg.manifest.apiVersion >= 3) { "当前时间服务需要 API 3" }
                    check(!operation.startsWith("workspace.") && !operation.startsWith("plugins.") || pkg.manifest.apiVersion >= 4) { "工作区与插件开发需要 API 4" }
                    val parent = executionContext ?: error("宿主调用没有活动的插件执行")
                    // QuickJS async bindings have their own scope. Explicitly
                    // parent native work to this invocation, including cleanup.
                    withContext(parent) {
                        if (operation == "clock.now") {
                            val now = ZonedDateTime.now(TimeZone.getDefault().toZoneId())
                            buildJsonObject {
                                put("utc", now.toInstant().toString())
                                put("localTime", now.toOffsetDateTime().toString())
                                put("timezone", now.zone.id)
                                put("timezoneOffsetMinutes", -now.offset.totalSeconds / 60)
                            }.toString()
                        } else host.call(pkg.manifest, operation, arguments).toString()
                    }
                }
                withTimeout(timeoutMs) {
                    executionJob = currentCoroutineContext().job
                    executionContext = currentCoroutineContext()
                    try {
                    js.evaluate<Any?>("""
                        globalThis.ctx = Object.freeze({
                          workspace: Object.freeze({ call: async (operation,args = {}) => JSON.parse(await __solvitHostCall('workspace.' + operation, JSON.stringify(args))) }),
                          plugins: Object.freeze({ draft: async args => JSON.parse(await __solvitHostCall('plugins.draft', JSON.stringify(args))) }),
                          clock: Object.freeze({ now: async () => JSON.parse(await __solvitHostCall('clock.now', '{}')) }),
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
                    if ("beforeModel" in pkg.manifest.hooks) {
                        js.evaluate<Any?>("if (typeof solvitPlugin.beforeModel !== 'function') throw new Error('Missing beforeModel hook');")
                    }
                    js.evaluate<Any?>("if (solvitPlugin.activate) await solvitPlugin.activate(ctx);")
                    } finally { executionJob = null; executionContext = null }
                }
                engine = js
            } catch (e: Throwable) { js.close(); throw e }
        }
    }

    suspend fun execute(tool: String, arguments: String): String {
        require(pkg.manifest.tools.any { it.name == tool }) { "插件没有声明此工具" }
        val input = Json.parseToJsonElement(arguments).jsonObject
        val role = currentCoroutineContext()[AgentRoleContext]?.role
        return invoke("solvitPlugin.tools[${JsonPrimitive(tool)}](JSON.parse(${JsonPrimitive(input.toString())}), ${contextExpression(role)})", false)
    }

    suspend fun beforeModel(input: ContextInput): String {
        check("beforeModel" in pkg.manifest.hooks)
        val payload = buildJsonObject {
            put("userText", input.userText)
            put("role", roleJson(input.role))
        }
        val result = invoke("solvitPlugin.beforeModel(JSON.parse(${JsonPrimitive(payload.toString())}), ${contextExpression(input.role)})", true)
        val value = Json.parseToJsonElement(result)
        require(value is JsonPrimitive && value.isString) { "上下文 Hook 必须返回文本" }
        return value.content
    }

    private fun roleJson(role: AgentRole?): JsonElement = role?.let { buildJsonObject {
        put("id", it.id); put("name", it.name)
        put("skillIds", JsonArray(it.skillIds.sorted().map(::JsonPrimitive)))
    } } ?: JsonNull

    private fun contextExpression(role: AgentRole?) =
        "Object.freeze({...ctx,role:JSON.parse(${JsonPrimitive(roleJson(role).toString())})})"

    private suspend fun invoke(expression: String, readOnly: Boolean): String = mutex.withLock {
        val js = engine ?: error("插件未启用")
        withContext(Dispatchers.Default) {
            // Let the native execution deadline fire first; this also bounds
            // promises waiting on asynchronous host operations.
            withTimeout(timeoutMs + 1_000) {
                executionJob = currentCoroutineContext().job
                val nativeCalls = SupervisorJob(executionJob)
                executionContext = currentCoroutineContext() + nativeCalls
                readOnlyHost = readOnly
                try {
                    // Resolve the JS evaluation with an explicit result envelope.
                    // After Kotlin cancels an evaluation, a native binding may
                    // still reject its JS promise. Catch that rejection inside
                    // JS so it cannot poison the next invocation or deactivate.
                    val outcome = Json.parseToJsonElement(js.evaluate<String>("""
                        await (async () => {
                          try {
                            const value = await ($expression);
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
                } finally {
                    // QuickJS evaluation can return on cancellation before its
                    // async binding has finished native HTTP/Shell cleanup.
                    withContext(NonCancellable) { nativeCalls.cancel(); nativeCalls.join() }
                    executionJob = null; executionContext = null; readOnlyHost = false
                }
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
                        executionContext = currentCoroutineContext()
                        try { js.evaluate<Any?>("if (solvitPlugin.deactivate) await solvitPlugin.deactivate(ctx);") }
                        finally { executionJob = null; executionContext = null }
                    }
                }
            } finally { js.close(); engine = null }
        }
    }
}

class ScriptToolPlugin(private val pkg: ScriptPackage, private val host: ScriptHost) : SolvitPlugin {
    override val id = pkg.manifest.id
    override val dependencies = pkg.manifest.dependencies
    override val requires = buildSet {
        add(Services.Tools)
        if (pkg.manifest.hooks.isNotEmpty()) add(ContextHookServiceKey)
        if (pkg.manifest.permissions.any { it in setOf("workspace", "shell", "plugins") }) add(WorkspaceServiceKey)
    }
    override suspend fun enable(context: PluginContext) {
        val executor = ScriptExecutor(pkg, host)
        if (WorkspaceServiceKey in requires) context.own { context.require(WorkspaceServiceKey).cancelOwner(id) }
        context.own { executor.stop() }
        executor.start()
        if ("beforeModel" in pkg.manifest.hooks) {
            val unregister = context.require(ContextHookServiceKey).register(id, ContextHook { input -> executor.beforeModel(input) })
            context.own { unregister() }
        }
        val tools = context.require(Services.Tools)
        for (tool in pkg.manifest.tools) {
            val unregister = tools.register(object : SolvitTool {
                override val descriptor = ToolDescriptor(
                    name = id.replace('.', '_').replace('-', '_') + "_" + tool.name,
                    description = tool.description,
                    inputSchema = tool.inputSchema.toString(),
                    requiresConfirmation = true,
                    pluginId = id,
                    chatVisible = tool.chatVisible,
                    pluginVersion = pkg.manifest.version,
                    capabilities = pkg.manifest.permissions,
                    pluginDigest = java.security.MessageDigest.getInstance("SHA-256").digest(pkg.payload).joinToString("") { "%02x".format(it) },
                )
                override suspend fun execute(argumentsJson: String): String = executor.execute(tool.name, argumentsJson)
            })
            context.own { unregister() }
        }
    }
}
