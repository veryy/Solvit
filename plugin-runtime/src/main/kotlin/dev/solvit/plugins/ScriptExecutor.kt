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

fun interface ScriptHost {
    suspend fun call(plugin: ScriptManifest, operation: String, arguments: JsonObject): JsonElement
}

/** One engine per enabled plugin; no Java reflection or implicit OS access. */
class ScriptExecutor(private val pkg: ScriptPackage, private val host: ScriptHost, private val timeoutMs: Long = 30_000,
    private val background: Boolean = false, private val invokeLifecycle: Boolean = true) {
    private val mutex = Mutex()
    @Volatile private var engine: QuickJs? = null
    @Volatile private var executionJob: Job? = null
    @Volatile private var executionContext: CoroutineContext? = null
    @Volatile private var readOnlyHost = false
    @Volatile private var nativeToolFailure: ToolFailureCode? = null

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
                    check(!background || operation in setOf("storage.get", "storage.set", "storage.compareAndSet", "clock.now", "model.complete")) { "后台 Hook 不允许此操作" }
                    check(!readOnlyHost || operation in setOf("storage.get", "clock.now", "workspace.instructions")) { "上下文 Hook 不允许此操作" }
                    val arguments = Json.parseToJsonElement(args[1] as String).jsonObject
                    if (operation == "workspacePage.query" && arguments["operation"]?.jsonPrimitive?.content in
                        setOf("readChunk","editBegin","editAppend","editFinish","editDiscard"))
                        check(pkg.manifest.apiVersion >= 18) { "完整工作区文本预览和编辑需要 API18" }
                    if (operation == "workspacePage.query" && arguments["operation"]?.jsonPrimitive?.content in setOf("changes", "changeContent"))
                        check(pkg.manifest.apiVersion >= 17) { "历史文件差异读取需要 API17" }
                    val permission = when (operation) {
                        "storage.get", "storage.set", "storage.compareAndSet", "jobs.list", "jobs.perform" -> "storage"
                        "model.complete" -> "model"
                        "gitPage.query", "gitPage.perform", "gitPage.credentials", "git.status", "git.diff", "git.history" -> "git"
                        "http.request", "web.visit" -> "network"
                        "workspace.info", "workspace.read", "workspace.write", "workspace.createShell", "workspace.instructions", "workspacePage.query", "workspacePage.perform" -> "workspace"
                        "workspace.start", "workspace.status", "workspace.cancel" -> "shell"
                        "terminal.open", "terminal.status", "terminal.input", "terminal.interrupt", "terminal.close" -> "shell"
                        "plugins.draft" -> "plugins"
                        "settings.get", "settings.compareAndSet", "clock.now", "documents.open", "documents.save" -> null
                        else -> error("宿主未开放此操作")
                    }
                    check(permission == null && pkg.manifest.apiVersion >= 2 || permission in pkg.manifest.permissions) { "插件未声明 $permission 权限" }
                    check(operation != "workspace.instructions" || pkg.manifest.apiVersion >= 16 && readOnlyHost) { "项目规则读取仅供 API16 上下文 Hook 使用" }
                    if (operation == "workspace.instructions") require(arguments.isEmpty()) { "项目规则不接受路径或工作区参数" }
                    check(operation != "clock.now" || pkg.manifest.apiVersion >= 3) { "当前时间服务需要 API 3" }
                    check(!operation.startsWith("workspace.") && !operation.startsWith("plugins.") || pkg.manifest.apiVersion >= 4) { "工作区与插件开发需要 API 4" }
                    check(!operation.startsWith("terminal.") || pkg.manifest.apiVersion >= 5) { "交互终端需要 API 5" }
                    check(!operation.startsWith("terminal.") || pkg.manifest.apiVersion < 12 || "terminal.pty" in pkg.manifest.nativeServices) { "插件未声明 terminal.pty 服务" }
                    check(operation != "web.visit" || pkg.manifest.apiVersion >= 6) { "浏览器观察需要 API 6" }
                    check(operation != "web.visit" || pkg.manifest.apiVersion < 12 || "web.pages" in pkg.manifest.nativeServices) { "插件未声明 web.pages 服务" }
                    check(operation !in setOf("model.complete", "storage.compareAndSet") || pkg.manifest.apiVersion >= 8) { "此操作需要 API 8" }
                    check(!operation.startsWith("jobs.") || pkg.manifest.apiVersion >= 9) { "任务页面需要 API 9" }
                    check(operation != "model.complete" || background) { "模型调用只允许在后台 Hook 中使用" }
                    check(operation != "settings.compareAndSet" || pkg.manifest.apiVersion >= 10 && !invokeLifecycle) { "配置写入仅供 API10 插件页面使用" }
                    check(!operation.startsWith("documents.") || pkg.manifest.apiVersion >= 11 && !invokeLifecycle && !background) { "文件选择仅供 API11 插件页面使用" }
                    check(!operation.startsWith("workspacePage.") || pkg.manifest.apiVersion >= 12 && !invokeLifecycle && !background) { "工作区页面需要 API12" }
                    check(!operation.startsWith("gitPage.") || pkg.manifest.apiVersion >= 19 && !invokeLifecycle && !background &&
                        "workspace.git" in pkg.manifest.nativeServices && "workspace" in pkg.manifest.permissions) { "Git页面需要API19、Git服务及工作区权限" }
                    check(!operation.startsWith("git.") || pkg.manifest.apiVersion >= 19 && "workspace.git" in pkg.manifest.nativeServices &&
                        "workspace" in pkg.manifest.permissions) { "Git工具需要API19、Git服务及工作区权限" }
                    val parent = executionContext ?: error("宿主调用没有活动的插件执行")
                    // QuickJS async bindings have their own scope. Explicitly
                    // parent native work to this invocation, including cleanup.
                    withContext(parent) {
                        if (operation.startsWith("jobs.") || operation.startsWith("documents.") || operation == "settings.compareAndSet") {
                            val page = currentCoroutineContext()[ScriptPageExecutionContext] ?: error("任务管理仅供插件页面使用")
                            check(page.pluginId == pkg.manifest.id)
                            page.ensureAuthorized()
                        }
                        if (background) {
                            val execution = currentCoroutineContext()[PostTurnExecutionContext] ?: error("后台执行缺少任务授权")
                            check(execution.target.pluginId == pkg.manifest.id)
                            execution.ensureAuthorized()
                        }
                        if (operation == "documents.open") {
                            require(arguments.isEmpty()) { "不能指定文件URI或路径" }
                            val selected = checkNotNull(currentCoroutineContext()[ScriptPageExecutionContext]).openDocument()
                            (selected?.let { buildJsonObject {
                                put("name", it.name)
                                it.text?.let { text -> put("text", text) }
                                it.files?.let { files -> putJsonObject("files") { files.forEach { (path, text) -> put(path, text) } } }
                            } } ?: JsonNull).toString()
                        } else if (operation == "documents.save") {
                            require(arguments.keys == setOf("text")) { "文件保存只接受文本" }
                            val text = arguments.getValue("text").jsonPrimitive.also { require(it.isString) }.content
                            JsonPrimitive(checkNotNull(currentCoroutineContext()[ScriptPageExecutionContext]).saveDocument(text)).toString()
                        } else if (operation.startsWith("workspacePage.")) {
                            val page = checkNotNull(currentCoroutineContext()[ScriptPageExecutionContext]) { "工作区页面缺少授权" }
                            check(page.pluginId == pkg.manifest.id)
                            if (operation == "workspacePage.perform") {
                                require(arguments.isEmpty()) { "原生操作只能来自已确认的页面控件" }
                                page.performWorkspace(pkg.manifest.apiVersion, "environment.linux" in pkg.manifest.nativeServices &&
                                    pkg.manifest.permissions.containsAll(setOf("workspace", "shell", "network")),
                                    "workspace.files" in pkg.manifest.nativeServices && "workspace" in pkg.manifest.permissions)
                            } else {
                                require(arguments.keys == setOf("operation", "arguments"))
                                val query = arguments.getValue("operation").jsonPrimitive.also { require(it.isString) }.content
                                check(query != "environmentStatus" || pkg.manifest.apiVersion >= 21 && "environment.linux" in pkg.manifest.nativeServices &&
                                    pkg.manifest.permissions.containsAll(setOf("workspace", "shell", "network"))) { "Linux 环境查询需要 API21 及服务声明" }
                                page.queryWorkspace(query, arguments.getValue("arguments").jsonObject, pkg.manifest.apiVersion,
                                    "workspace.files" in pkg.manifest.nativeServices && "workspace" in pkg.manifest.permissions)
                            }
                        } else if (operation.startsWith("gitPage.")) {
                            val page = checkNotNull(currentCoroutineContext()[ScriptPageExecutionContext]) { "Git页面缺少授权" }
                            check(page.pluginId == pkg.manifest.id)
                            if (operation == "gitPage.credentials") {
                                require(arguments.isEmpty()) { "凭据输入只能来自宿主表单" }
                                JsonPrimitive(page.editGitCredentials(pkg.manifest.apiVersion, "network" in pkg.manifest.permissions).name).toString()
                            } else if (operation == "gitPage.perform") {
                                require(arguments.isEmpty()) { "Git原生操作只能来自已确认的页面控件" }
                                page.performGit(pkg.manifest.apiVersion, "network" in pkg.manifest.permissions)
                            } else {
                                require(arguments.keys == setOf("operation", "arguments"))
                                val query = arguments.getValue("operation").jsonPrimitive.also { require(it.isString) }.content
                                page.queryGit(query, arguments.getValue("arguments").jsonObject,
                                    pkg.manifest.apiVersion, "network" in pkg.manifest.permissions)
                            }
                        } else if (operation == "clock.now") {
                            clockSnapshot().toString()
                        } else try { host.call(pkg.manifest, operation, arguments).toString() }
                        catch(e: ToolExecutionException) { nativeToolFailure=e.code;throw e }
                    }
                }
                withTimeout(timeoutMs) {
                    executionJob = currentCoroutineContext().job
                    executionContext = currentCoroutineContext()
                    try {
                    js.evaluate<Any?>("""
                        globalThis.ctx = Object.freeze({
                          documents: Object.freeze({
                            open: async () => JSON.parse(await __solvitHostCall('documents.open', '{}')),
                            save: async text => JSON.parse(await __solvitHostCall('documents.save', JSON.stringify({text})))
                          }),
                          jobs: Object.freeze({
                            list: async (options = {}) => JSON.parse(await __solvitHostCall('jobs.list', JSON.stringify(options))),
                            perform: async () => JSON.parse(await __solvitHostCall('jobs.perform', '{}'))
                          }),
                          git: Object.freeze({call: async (operation,args = {}) => JSON.parse(await __solvitHostCall('git.' + operation, JSON.stringify(args)))}),
                          gitPage: Object.freeze({
                            query: async (operation,args = {}) => JSON.parse(await __solvitHostCall('gitPage.query', JSON.stringify({operation,arguments:args}))),
                            perform: async () => JSON.parse(await __solvitHostCall('gitPage.perform', '{}')),
                            credentials: async () => JSON.parse(await __solvitHostCall('gitPage.credentials', '{}'))
                          }),
                          workspacePage: Object.freeze({
                            query: async (operation,args = {}) => JSON.parse(await __solvitHostCall('workspacePage.query', JSON.stringify({operation,arguments:args}))),
                            perform: async () => JSON.parse(await __solvitHostCall('workspacePage.perform', '{}'))
                          }),
                          workspace: Object.freeze({ call: async (operation,args = {}) => JSON.parse(await __solvitHostCall('workspace.' + operation, JSON.stringify(args))),
                            instructions: async () => JSON.parse(await __solvitHostCall('workspace.instructions', '{}')) }),
                          terminal: Object.freeze({ call: async (operation,args = {}) => JSON.parse(await __solvitHostCall('terminal.' + operation, JSON.stringify(args))) }),
                          plugins: Object.freeze({ draft: async args => JSON.parse(await __solvitHostCall('plugins.draft', JSON.stringify(args))) }),
                          clock: Object.freeze({ now: async () => JSON.parse(await __solvitHostCall('clock.now', '{}')) }),
                          settings: Object.freeze({
                            get: async key => JSON.parse(await __solvitHostCall('settings.get', JSON.stringify({key}))),
                            compareAndSet: async (key,expected,value) => JSON.parse(await __solvitHostCall('settings.compareAndSet', JSON.stringify({key,expected,value})))
                          }),
                          storage: Object.freeze({
                            get: async key => JSON.parse(await __solvitHostCall('storage.get', JSON.stringify({key}))),
                            set: async (key,value) => JSON.parse(await __solvitHostCall('storage.set', JSON.stringify({key,value}))),
                            compareAndSet: async (key,expected,value) => JSON.parse(await __solvitHostCall('storage.compareAndSet', JSON.stringify({key,expected,value})))
                          }),
                          model: Object.freeze({ complete: async options => JSON.parse(await __solvitHostCall('model.complete', JSON.stringify(options))) }),
                          web: Object.freeze({ visit: async url => JSON.parse(await __solvitHostCall('web.visit', JSON.stringify({url}))) }),
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
                    if (pkg.manifest.agentEnabledSetting != null) js.evaluate<Any?>("if (typeof solvitPlugin.agent !== 'function') throw new Error('Missing agent strategy');")
                    if ("afterTurn" in pkg.manifest.hooks) js.evaluate<Any?>("if (typeof solvitPlugin.afterTurn !== 'function') throw new Error('Missing afterTurn hook');")
                    for (page in pkg.manifest.pages) {
                        js.evaluate<Any?>("if (typeof solvitPlugin.pages?.[${JsonPrimitive(page.id)}] !== 'function') throw new Error('Missing page handler');")
                    }
                    if (!background && invokeLifecycle) js.evaluate<Any?>("if (solvitPlugin.activate) await solvitPlugin.activate(ctx);")
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

    internal suspend fun page(pageId: String, locale: String, event: JsonObject?): ScriptPageView {
        check(!background && !invokeLifecycle && pkg.manifest.apiVersion >= 9)
        require(pkg.manifest.pages.any { it.id == pageId }) { "Plugin page not declared" }
        require(locale.matches(Regex("[a-zA-Z0-9_-]{1,20}")))
        val input = buildJsonObject { put("locale", locale); put("event", event ?: JsonNull) }
        return ScriptPageView.parse(invoke("solvitPlugin.pages[${JsonPrimitive(pageId)}](JSON.parse(${JsonPrimitive(input.toString())}), ctx)", false))
    }

    suspend fun beforeModel(input: ContextInput): String {
        check("beforeModel" in pkg.manifest.hooks)
        check(engine != null) { "插件未启用" }
        // Read only schema-declared public fields. Secret fields never enter the Hook payload.
        val publicSettings = if (pkg.manifest.apiVersion >= 7) publicSettings() else null
        val payload = buildJsonObject {
            if (publicSettings != null) put("settings", publicSettings)
            put("userText", input.userText)
            put("role", roleJson(input.role))
        }
        val result = withContext(ScriptHookInvocation(input)) {
            invoke("solvitPlugin.beforeModel(JSON.parse(${JsonPrimitive(payload.toString())}), ${contextExpression(input.role)})", true)
        }
        val value = Json.parseToJsonElement(result)
        require(value is JsonPrimitive && value.isString) { "上下文 Hook 必须返回文本" }
        return value.content
    }

    private suspend fun publicSettings(): JsonObject = buildJsonObject {
            for (field in pkg.manifest.settings.filter { it.kind != "secret" }) {
                val value = host.call(pkg.manifest, "settings.get", buildJsonObject { put("key", field.key) })
                require(value is JsonNull || value is JsonPrimitive && value.isString && value.content.length <= 4096)
                put(field.key, value)
            }
        }
    suspend fun afterTurn(input: PostTurnInput) {
        check(background && pkg.manifest.apiVersion >= 8 && "afterTurn" in pkg.manifest.hooks)
        val execution = currentCoroutineContext()[PostTurnExecutionContext] ?: error("后台执行缺少任务授权")
        execution.ensureAuthorized()
        val role = input.turn.roleId?.let { AgentRole(it, input.turn.roleName!!, pluginIds = input.turn.allowedPluginIds.orEmpty()) }
        val payload = buildJsonObject {
            put("settings", publicSettings())
            put("userText", input.userText)
            put("assistantText", input.assistantText)
            putJsonObject("source") {
                put("turnId", input.turn.id); put("sessionId", input.turn.sessionId)
                put("userMessageId", input.turn.userMessageId); put("assistantMessageId", input.turn.assistantMessageId)
                put("roleId", input.turn.roleId?.let(::JsonPrimitive) ?: JsonNull)
                put("roleName", input.turn.roleName?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
        val result = invoke("solvitPlugin.afterTurn(JSON.parse(${JsonPrimitive(payload.toString())}), ${contextExpression(role)})", false)
        require(result == "null") { "后台 Hook 必须完成且不返回正文" }
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
            // Confirmed human file selection or native credential entry gets a bounded input allowance.
            val page = currentCoroutineContext()[ScriptPageExecutionContext]
            val deadline = if (!invokeLifecycle && (pkg.manifest.apiVersion >= 11 && page?.documentInvocation == true ||
                pkg.manifest.apiVersion >= 20 && page?.credentialInvocation == true)) 180_000L else timeoutMs
            // Let the native execution deadline fire first; this also bounds
            // promises waiting on asynchronous host operations.
            withTimeout(deadline + 1_000) {
                executionJob = currentCoroutineContext().job
                val nativeCalls = SupervisorJob(executionJob)
                executionContext = currentCoroutineContext() + nativeCalls
                readOnlyHost = readOnly
                nativeToolFailure = null
                try {
                    js.evaluationTimeoutMillis = deadline
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
                            return JSON.stringify({ok: false, error: String(error?.message ?? error), code: typeof error?.solvitCode === 'string' ? error.solvitCode : null});
                          }
                        })()
                    """.trimIndent())).jsonObject
                    if (!outcome.getValue("ok").jsonPrimitive.boolean && background) {
                        val code = outcome["code"]?.jsonPrimitive?.contentOrNull
                        runCatching { PostTurnFailure.valueOf(code.orEmpty()) }.getOrNull()?.let {
                            currentCoroutineContext()[PostTurnExecutionContext]?.recordFailure(it)
                        }
                    }
                    if(!outcome.getValue("ok").jsonPrimitive.boolean) nativeToolFailure?.let { throw ToolExecutionException(it) }
                    check(outcome.getValue("ok").jsonPrimitive.boolean) {
                        outcome.getValue("error").jsonPrimitive.content
                    }
                    outcome.getValue("result").jsonPrimitive.content
                } finally {
                    // QuickJS evaluation can return on cancellation before its
                    // async binding has finished native HTTP/Shell cleanup.
                    withContext(NonCancellable) { nativeCalls.cancel(); nativeCalls.join() }
                    executionJob = null; executionContext = null; readOnlyHost = false; nativeToolFailure = null
                    js.evaluationTimeoutMillis = timeoutMs
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
                        try { if (!background && invokeLifecycle) js.evaluate<Any?>("if (solvitPlugin.deactivate) await solvitPlugin.deactivate(ctx);") }
                        finally { executionJob = null; executionContext = null }
                    }
                }
            } finally { js.close(); engine = null }
        }
    }
}

class ScriptToolPlugin(
    private val pkg: ScriptPackage,
    private val host: ScriptHost,
    private val agentAuthorization: suspend () -> Unit = {},
) : SolvitPlugin {
    override val id = pkg.manifest.id
    override val dependencies = pkg.manifest.dependencies
    override val requires = buildSet {
        add(Services.Tools)
        if (pkg.manifest.agentEnabledSetting != null) add(AgentDriversServiceKey)
        if ("beforeModel" in pkg.manifest.hooks) add(ContextHookServiceKey)
        if ("afterTurn" in pkg.manifest.hooks) add(PostTurnHookServiceKey)
        if (pkg.manifest.permissions.any { it in setOf("workspace", "shell", "plugins") }) add(WorkspaceServiceKey)
        if ("workspace.git" in pkg.manifest.nativeServices) add(WorkspaceGitServiceKey)
        if ("environment.linux" in pkg.manifest.nativeServices) add(LinuxEnvironmentServiceKey)
        if ("terminal.pty" in pkg.manifest.nativeServices || pkg.manifest.apiVersion in 5..11 && "shell" in pkg.manifest.permissions) add(TerminalServiceKey)
        if ("web.pages" in pkg.manifest.nativeServices || pkg.manifest.apiVersion in 6..11 && "network" in pkg.manifest.permissions) add(WebPageServiceKey)
    }
    override suspend fun enable(context: PluginContext) {
        val executor = ScriptExecutor(pkg, host)
        if (WorkspaceServiceKey in requires) context.own { context.require(WorkspaceServiceKey).cancelOwner(id) }
        if (TerminalServiceKey in requires) context.own { context.require(TerminalServiceKey).cancelOwner(id) }
        context.own { executor.stop() }
        executor.start()
        if (pkg.manifest.agentEnabledSetting != null) {
            val unregister = context.require(AgentDriversServiceKey).register(id, scriptPluginAgent(pkg, agentAuthorization)) {
                val value = host.call(pkg.manifest, "settings.get", buildJsonObject { put("key", pkg.manifest.agentEnabledSetting) })
                value is JsonPrimitive && value.isString && value.content == "true"
            }
            context.own { unregister() }
        }
        if ("afterTurn" in pkg.manifest.hooks) {
            val lifecycle = Any()
            val running = mutableSetOf<ScriptExecutor>()
            var disposed = false
            val target = PostTurnTarget(id, pkg.manifest.version, java.security.MessageDigest.getInstance("SHA-256")
                .digest(pkg.payload).joinToString("") { "%02x".format(it) })
            context.own {
                val active = synchronized(lifecycle) { disposed = true; running.toList() }
                active.forEach { it.stop() }
            }
            val unregister = context.require(PostTurnHookServiceKey).register(object : PostTurnHook {
                override val target = target
                override suspend fun enabled(): Boolean {
                    val value = host.call(pkg.manifest, "settings.get", buildJsonObject { put("key", pkg.manifest.afterTurnEnabledSetting!!) })
                    return value is JsonPrimitive && value.isString && value.content == "true"
                }
                override suspend fun process(input: PostTurnInput, model: PostTurnModel) {
                    val cold = ScriptExecutor(pkg, host, timeoutMs = 60_000, background = true)
                    synchronized(lifecycle) { if (disposed) throw PostTurnRevokedException(); running += cold }
                    try {
                        currentCoroutineContext()[PostTurnExecutionContext]!!.ensureAuthorized()
                        cold.start(); cold.afterTurn(input)
                    } finally {
                        withContext(NonCancellable) { cold.stop() }
                        synchronized(lifecycle) { running -= cold }
                    }
                }
            })
            context.own { unregister() }
        }
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
