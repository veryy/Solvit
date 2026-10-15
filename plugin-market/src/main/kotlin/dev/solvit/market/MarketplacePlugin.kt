package dev.solvit.market

import android.content.Context
import android.util.AtomicFile
import dev.solvit.core.*
import dev.solvit.http.readResponse
import dev.solvit.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

private const val MARKET_URL = "https://solvit.207.57.126.73.sslip.io"

class AndroidMarketplace(
    private val context: Context,
    private val manager: PluginManager,
    private val client: OkHttpClient,
    private val tools: ToolRegistry,
    private val config: EncryptedPluginConfiguration = EncryptedPluginConfiguration(context),
    private val workspaces: WorkspaceService? = null,
    private val drafts: (() -> PluginDraftService)? = null,
    private val terminals: TerminalService? = null,
    private val webPages: WebPageService? = null,
    private val git: WorkspaceGitService? = null,
) : MarketplaceService {
    private val directory = File(context.filesDir, "extensions").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("extension_permissions", Context.MODE_PRIVATE)
    private val data = PluginDataStore(context)
    private val mutex = Mutex()
    private val packages = linkedMapOf<String, ScriptPackage>()
    private val failures = mutableMapOf<String, String>()
    private val mutableInstalled = MutableStateFlow<List<InstalledExtension>>(emptyList())
    override val installed = mutableInstalled.asStateFlow()
    private var latestCatalog = emptyList<MarketEntry>()
    private val pages = MarketplacePages(manager, { pkg ->
        check(manager.states.value[pkg.manifest.id] == PluginState.ENABLED && prefs.getBoolean("enabled.${pkg.manifest.id}", false)) { "请先启用插件" }
        check(prefs.getStringSet("grants.${pkg.manifest.id}", emptySet()).orEmpty().containsAll(pkg.manifest.permissions)) { "插件权限已撤销" }
    }, ScriptHost { manifest, operation, arguments -> hostCall(manifest, operation, arguments) })

    override suspend fun openPage(pluginId: String, pageId: String, locale: String): ExtensionPageSession = mutex.withLock {
        val pkg = packages[pluginId] ?: error("插件未安装")
        check(pkg.manifest.pages.single { it.id == pageId }.context == "management") { "此页面须从对话执行记录打开" }
        pages.open(pkg, pageId, locale)
    }

    suspend fun restore() = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Ship useful JS packages offline, but require explicit permission
            // grants before activation. Never resurrect a user-uninstalled seed.
            context.assets.list("builtin-plugins").orEmpty().filter { it.endsWith(".solvit.json") }.forEach { name ->
                if (!prefs.getBoolean("seeded.$name", false)) {
                    val payload = context.assets.open("builtin-plugins/$name").use { it.readBytes() }
                    val pkg = ScriptPackage.parse(payload)
                    check(name == "${pkg.manifest.id}.solvit.json")
                    val base = File(directory, name)
                    if (!base.exists()) {
                        val file = AtomicFile(base)
                        val stream = file.startWrite()
                        try { stream.write(payload); file.finishWrite(stream) }
                        catch (e: Throwable) { file.failWrite(stream); throw e }
                    }
                    check(prefs.edit().putBoolean("seeded.$name", true).commit()) { "内置插件配置保存失败" }
                }
                // Upgrade byte-identical original bundled packages. Keep
                // enabled state and never replace a modified/user-installed package.
                if (name in setOf("dev.solvit.clock.solvit.json", "dev.solvit.web-search.solvit.json", "dev.solvit.mcp.solvit.json", "dev.solvit.memory.solvit.json", "dev.solvit.skills.solvit.json", "dev.solvit.workspace.solvit.json", "dev.solvit.terminal.solvit.json")) {
                    val base = File(directory, name)
                    if (base.isFile && base.length() <= ScriptPackage.MAX_BYTES) {
                        val original = AtomicFile(base).readFully()
                        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
                        val originals = if (name == "dev.solvit.terminal.solvit.json") setOf("eca040c2851d415ababda789a45d49bbbdb547c8173dd6db2616bc83c4678ee0", "443a011bc592520919740aa8784d87c01f5dacbf7ca612d8012114f7603d9f56", "e05c420559d3f6a14924fbc455cefe71f510844c3f0802a457ff754c3fd2f4d8", "d124c4f16262d0d559eb0b45c21c790e79f8ee1aaacfc9b1464397a1520682a3")
                            else if (name == "dev.solvit.workspace.solvit.json") setOf("e5ad26b643d323bd3b622094121316b94eaaf17400d62397c91243c5e7a3fe5d", "d212d6741bde9a5db30a9d307c506389343d9c705f66011d4c0a50e54c041360", "7e365776b2df302b79d338980e5ce990f233d7f3a7762399a89c8fa3a50283b6", "399e6a23148c4043903788adeb2eb7203504710c3fe95b6af9a39d89b38bc72a", "675764bbd4c1b438b0c6de228aa865742d760521facf57e358e4abc8b931db41", "95531ef7faaf38df4f1d56954505c848243bdcb2e4a773ac588b74e471ff037b", "545abc7b667f31468777e378d4c7620709f9e2e03fca6f4c1b490e24bd4b0bbe", "4afcd1206d15bb0f1fba79a93f0e623b0a6e29ff3172b54cadb833b9ea8471e4", "7d80dbfc71c177b3a2a81df5c15bfa05b6d0cb274baf536a5d179dad55ce0004")
                            else if (name == "dev.solvit.mcp.solvit.json") setOf("c2c4ad230e7ce37d841314deb162bc8e70d84ec40f1c1bbb8be47b0bb95d80fe", "111d17a33de9272b9b645e54ddc599aaed909e45960231b3254ba4dcb05f0f2c")
                            else if (name == "dev.solvit.skills.solvit.json") setOf("0c51650719024e18c45a38b8e631946972881d1953302ea92751263a276308a6", "585a182857fb58b2870f048cbdecef12d90b91859a09fe6870a3e0cbe359a3b2")
                            else if (name == "dev.solvit.memory.solvit.json") setOf("8650268525bf39cc49ce39f5e4bdfc3d3039c6f7e01ddd5829225bb98231c719", "096b82abcd7f09de4a3445e05a13c3fc16c7f3a1c6e8b0d07c85f0bbd1741ffd", "7cce19572a842767f37dc21ade02762b08da4248a11558658debce9ebdd2915e")
                            else if (name == "dev.solvit.web-search.solvit.json") setOf("91dc9957e6c259626dbeba07d8ceb19799f36d000688e2532b322e0f860a409f", "914771c881ee879d0abecaecca48bb5627f9c6509afdf9ff06efe5af90b218bd", "175c0f5b7fdff2307d59462d31318a9773df27b8ac342a7376fc63c2bc96e39a") else setOf("511c83d2ad716cf0ddc9d343046cbd308fd0e178ad5ee0bbc6ad27e358a34445",
                                "0f32b5b2e5e684b1d95b275b71d72864f9463cbd55eb549f150e2196a6e80ed7")
                        if (digest in originals) {
                            val payload = context.assets.open("builtin-plugins/$name").use { it.readBytes() }
                            ScriptPackage.parse(payload)
                            val atomic = AtomicFile(base); val stream = atomic.startWrite()
                            try { stream.write(payload); atomic.finishWrite(stream) }
                            catch (e: Throwable) { atomic.failWrite(stream); throw e }
                        }
                    }
                }
            }
            directory.listFiles()?.filter { it.name.endsWith(".solvit.json") }?.forEach { file ->
                try {
                    check(file.length() <= ScriptPackage.MAX_BYTES) { "插件文件过大" }
                    val pkg = ScriptPackage.parse(AtomicFile(file).readFully())
                    check(file.name == "${pkg.manifest.id}.solvit.json") { "插件文件名无效" }
                    packages[pkg.manifest.id] = pkg
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.e("SolvitPlugins", "Cannot restore ${file.name}", e) }
            }
        }
        val pending = packages.keys.filter { prefs.getBoolean("enabled.$it", false) }.toMutableSet()
        while (pending.isNotEmpty()) {
            val id = pending.firstOrNull { candidate -> packages.getValue(candidate).manifest.dependencies.none { it in pending } }
            if (id == null) {
                pending.forEach { failures[it] = "插件依赖循环，请检查后重新启用" }
                break
            }
            try { activate(id, prefs.getStringSet("grants.$id", emptySet()).orEmpty()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures[id] = e.message ?: "插件启用失败" }
            pending.remove(id)
        }
        publish()
    }

    override suspend fun installLocal(payload: ByteArray) = mutex.withLock {
        val pkg = ScriptPackage.parse(payload)
        val id = pkg.manifest.id
        pages.closePlugin(id)
        if (manager.states.value[id] == PluginState.ENABLED) manager.unload(id)
        withContext(Dispatchers.IO) {
            val file = AtomicFile(File(directory, "$id.solvit.json")); val stream = file.startWrite()
            try { stream.write(payload); file.finishWrite(stream) }
            catch (e: Throwable) { file.failWrite(stream); throw e }
        }
        check(prefs.edit().putBoolean("enabled.$id", false).remove("grants.$id").commit())
        packages[id] = pkg; failures.remove(id); publish()
    }

    override suspend fun catalog(): List<MarketEntry> {
        val root = Json.parseToJsonElement(getBytes("$MARKET_URL/api/v1/plugins?hostApi=26", 1024 * 1024).toString(Charsets.UTF_8)).jsonObject
        check(root.getValue("apiVersion").jsonPrimitive.int == 1) { "市场 API 版本不兼容" }
        val entries = root.getValue("plugins").jsonArray.map { element ->
            val m = element.jsonObject
            val manifest = ScriptPackage.parseManifest(m)
            val sha = m.getValue("sha256").jsonPrimitive.content
            val bytes = m.getValue("bytes").jsonPrimitive.long
            val path = m.getValue("downloadPath").jsonPrimitive.content
            check(sha.matches(Regex("[a-f0-9]{64}")) && bytes in 1..ScriptPackage.MAX_BYTES.toLong()) { "市场包校验数据无效" }
            check(path == "/api/v1/packages/${manifest.id}/${manifest.version}") { "市场下载地址无效" }
            MarketEntry(manifest.id, manifest.name, manifest.description, manifest.version,
                manifest.permissions, manifest.dependencies, sha, bytes, path)
        }
        check(entries.map { it.id }.toSet().size == entries.size) { "市场存在重复插件" }
        latestCatalog = entries
        return entries
    }

    override suspend fun install(entry: MarketEntry) = mutex.withLock {
        check(entry in latestCatalog) { "请先刷新市场目录" }
        val bytes = getBytes(MARKET_URL + entry.downloadPath, ScriptPackage.MAX_BYTES)
        check(bytes.size.toLong() == entry.bytes) { "插件包长度校验失败" }
        val pkg = ScriptPackage.parse(bytes, entry.sha256)
        check(pkg.manifest.id == entry.id && pkg.manifest.version == entry.version &&
            pkg.manifest.permissions == entry.permissions && pkg.manifest.dependencies == entry.dependencies) { "插件包与市场声明不符" }
        pages.closePlugin(entry.id)
        if (packages.containsKey(entry.id) && manager.states.value[entry.id] == PluginState.ENABLED) manager.unload(entry.id)
        withContext(Dispatchers.IO) {
            val file = AtomicFile(File(directory, "${entry.id}.solvit.json"))
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (e: Throwable) { file.failWrite(stream); throw e }
        }
        prefs.edit().putBoolean("enabled.${entry.id}", false).remove("grants.${entry.id}").commit()
        packages[entry.id] = pkg
        failures.remove(entry.id)
        publish()
    }

    override suspend fun enable(id: String, grantedPermissions: Set<String>): Unit = mutex.withLock {
        try { activate(id, grantedPermissions); failures.remove(id); Unit }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failures[id] = e.message ?: "启用失败"; throw e }
        finally { publish() }
    }

    private suspend fun activate(id: String, grants: Set<String>) {
        val pkg = packages[id] ?: error("插件未安装")
        check(grants == pkg.manifest.permissions) { "请确认插件所需权限" }
        check(pkg.manifest.dependencies.all { manager.states.value[it] == PluginState.ENABLED }) { "请先安装并启用依赖插件" }
        val values = configurationValues(pkg.manifest)
        check(pkg.manifest.settings.filter { it.required }.all { !values[it.key].isNullOrBlank() }) { "请先填写插件配置" }
        if (manager.states.value[id] == PluginState.ENABLED) return
        if (manager.states.value[id] in setOf(PluginState.LOADED, PluginState.INITIALIZED, PluginState.DISABLED)) manager.unload(id)
        prefs.edit().putStringSet("grants.$id", grants.toSet()).commit()
        manager.load(ScriptToolPlugin(pkg, ScriptHost { manifest, operation, arguments -> hostCall(manifest, operation, arguments) }) {
            check(packages[id] === pkg && manager.states.value[id] == PluginState.ENABLED &&
                prefs.getBoolean("enabled.$id", false) &&
                prefs.getStringSet("grants.$id", emptySet()).orEmpty().containsAll(pkg.manifest.permissions)) {
                "Agent 插件已停用、被替换或权限已撤销"
            }
        })
        try { manager.enableAll() }
        catch (e: Throwable) {
            withContext(NonCancellable) {
                try { manager.unload(id) } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
            }
            throw e
        }
        prefs.edit().putBoolean("enabled.$id", true).commit()
    }

    override suspend fun disable(id: String) = mutex.withLock {
        check(id in packages) { "插件未安装" }
        pages.closePlugin(id)
        try {
            if (manager.states.value[id] in setOf(PluginState.LOADED, PluginState.INITIALIZED, PluginState.DISABLED, PluginState.ENABLED))
                manager.disable(id)
        }
        finally {
            if (manager.states.value[id] != PluginState.ENABLED) {
                check(prefs.edit().putBoolean("enabled.$id", false).commit()) { "插件停用配置保存失败" }
                failures.remove(id)
            }
            publish()
        }
    }

    override suspend fun uninstall(id: String) = mutex.withLock {
        check(id in packages) { "插件未安装" }
        pages.closePlugin(id)
        if (manager.states.value[id] in setOf(PluginState.LOADED, PluginState.INITIALIZED, PluginState.DISABLED, PluginState.ENABLED)) manager.unload(id)
        withContext(Dispatchers.IO) { AtomicFile(File(directory, "$id.solvit.json")).delete() }
        prefs.edit().remove("enabled.$id").remove("grants.$id").commit()
        withContext(Dispatchers.IO) { config.remove(id) }
        packages.remove(id); failures.remove(id)
        publish()
    }

    private suspend fun configurationValues(manifest: ScriptManifest): Map<String, String> {
        val stored = withContext(Dispatchers.IO) { config.read(manifest.id) }
        return manifest.settings.mapNotNull { field ->
            (stored[field.key] ?: field.defaultValue ?: if (field.kind == "boolean") "false" else null)?.let { field.key to it }
        }.toMap()
    }

    override suspend fun configuration(id: String): Map<String, String> = mutex.withLock {
        val pkg = packages[id] ?: error("插件未安装")
        configurationValues(pkg.manifest)
    }
    override suspend fun configure(id: String, values: Map<String, String>) = mutex.withLock {
        val pkg = packages[id] ?: error("插件未安装")
        check(manager.states.value[id] != PluginState.ENABLED) { "请先停用插件再修改配置" }
        validateConfiguration(pkg.manifest, values)
        withContext(Dispatchers.IO) { config.write(id, values) }
    }

    private fun validateConfiguration(manifest: ScriptManifest, values: Map<String, String>) {
        require(values.keys.all { key -> manifest.settings.any { it.key == key } })
        manifest.settings.forEach { field ->
            val value = values[field.key] ?: field.defaultValue ?: if (field.kind == "boolean") "false" else ""
            require(value.length <= 4096 && (!field.required || value.isNotBlank())) { "插件配置不完整" }
            if (field.kind == "boolean") require(value in setOf("true", "false")) { "开关配置必须为 true 或 false" }
            if (field.kind == "url" && value.isNotBlank()) {
                val uri = URI(value)
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "插件地址需要 HTTPS" }
            }
            if (field.kind == "secret") require(!value.contains('\n') && !value.contains('\r'))
        }
    }

    override suspend fun runTool(name: String, argumentsJson: String, authorization: ToolAuthorization): String =
        tools.execute(name, argumentsJson, authorization)

    private fun publish() {
        mutableInstalled.value = packages.values.map { pkg ->
            val enabled = manager.states.value[pkg.manifest.id] == PluginState.ENABLED
            val prefix = pkg.manifest.id.replace('.', '_').replace('-', '_') + "_"
            val names = pkg.manifest.tools.map { prefix + it.name }.toSet()
            InstalledExtension(pkg.manifest.id, pkg.manifest.name, pkg.manifest.version, pkg.manifest.permissions,
                enabled, if (enabled) tools.descriptors().filter { it.name in names } else emptyList(), failures[pkg.manifest.id], pkg.manifest.settings, pkg.manifest.hooks,
                pkg.manifest.pages.filter { it.context == "management" }.map { ExtensionPage(it.id, it.title) })
        }
    }

    private suspend fun hostCall(manifest: ScriptManifest, operation: String, arguments: JsonObject): JsonElement {
        val page = currentCoroutineContext()[ScriptPageExecutionContext]
        if (page != null) { check(page.pluginId == manifest.id); page.ensureAuthorized() }
        if (operation == "settings.get") {
            check(manifest.apiVersion >= 2)
            val key = arguments.getValue("key").jsonPrimitive.content
            val field = manifest.settings.singleOrNull { it.key == key } ?: error("插件未声明此配置字段")
            return (configurationValues(manifest)[field.key])?.let(::JsonPrimitive) ?: JsonNull
        }
        if (operation == "settings.compareAndSet") {
            check(manifest.apiVersion >= 10 && page != null) { "配置写入仅供插件页面使用" }
            require(arguments.keys == setOf("key", "expected", "value"))
            val key = arguments.getValue("key").jsonPrimitive.also { require(it.isString) }.content
            val expected = arguments.getValue("expected").let { if (it == JsonNull) null else it.jsonPrimitive.also { p -> require(p.isString) }.content }
            val value = arguments.getValue("value").jsonPrimitive.also { require(it.isString) }.content
            require(manifest.settings.any { it.key == key }) { "插件未声明此配置字段" }
            require(key != manifest.agentEnabledSetting) { "Agent 接管开关必须由用户在宿主配置中修改" }
            return mutex.withLock {
                page.ensureAuthorized()
                if (configurationValues(manifest)[key] != expected) JsonPrimitive(false)
                else {
                    val next = config.read(manifest.id) + (key to value)
                    validateConfiguration(manifest, next)
                    withContext(Dispatchers.IO) { page.ensureAuthorized(); config.write(manifest.id, next) }
                    JsonPrimitive(true)
                }
            }
        }
        val permission = when (operation) {
            "storage.get", "storage.set", "storage.compareAndSet", "jobs.list", "jobs.perform" -> "storage"
            "model.complete" -> "model"
            "git.status", "git.diff", "git.history" -> "git"
            "http.request", "web.visit" -> "network"
            "workspace.info", "workspace.read", "workspace.write", "workspace.createShell", "workspace.instructions" -> "workspace"
            "workspace.start", "workspace.status", "workspace.cancel" -> "shell"
            "terminal.open", "terminal.status", "terminal.input", "terminal.interrupt", "terminal.close" -> "shell"
            "plugins.draft" -> "plugins"
            else -> error("未开放的宿主操作")
        }
        val background = currentCoroutineContext()[PostTurnExecutionContext]
        fun verifyGrants() {
            val grants = prefs.getStringSet("grants.${manifest.id}", emptySet()).orEmpty()
            val allowed = permission in manifest.permissions && permission in grants &&
                (background == null || grants.containsAll(manifest.permissions))
            if (!allowed && background != null) {
                background.recordFailure(PostTurnFailure.PERMISSION)
                throw PostTurnWorkException(PostTurnFailure.PERMISSION)
            }
            check(allowed) { "插件权限未授予" }
        }
        verifyGrants()
        if (operation == "workspace.instructions") {
            check(manifest.apiVersion >= 16 && page == null && background == null)
            require(arguments.isEmpty()) { "项目规则不接受参数" }
            val hook = currentCoroutineContext()[ScriptHookInvocation] ?: error("项目规则需要当前上下文 Hook")
            val service = workspaces ?: error("工作区服务不可用")
            suspend fun authorize() {
                currentCoroutineContext().ensureActive()
                verifyGrants()
                check(packages[manifest.id]?.manifest == manifest && manager.states.value[manifest.id] == PluginState.ENABLED &&
                    prefs.getBoolean("enabled.${manifest.id}", false)) { "插件已停用或被替换" }
                check(hook.input.role?.allows(manifest.id) != false) { "角色未绑定此插件" }
                hook.input.conversation?.let { conversation ->
                    check(service.state.value.bindings[conversation.sessionId] == conversation.workspaceId) { "对话工作区已变化" }
                }
            }
            authorize()
            val conversation = hook.input.conversation
            val sources = if (conversation == null) emptyList() else service.instructionSources(conversation.workspaceId) { authorize() }
            authorize()
            require(sources.size <= 4 && sources.map { it.path }.distinct().size == sources.size)
            sources.forEach(WorkspaceInstructionSource::validate)
            return buildJsonObject {
                put("available", conversation != null)
                putJsonArray("sources") { sources.forEach { source -> add(buildJsonObject {
                    put("path", source.path); put("status", source.status.name)
                    source.text?.let { put("text", it) }; source.sha256?.let { put("sha256", it) }
                }) } }
            }
        }
        if (operation.startsWith("jobs.")) {
            check(manifest.apiVersion >= 9 && page != null && background == null) { "任务管理仅供插件页面使用" }
            val tasks = if (manager.registry.contains(PostTurnTaskServiceKey)) manager.registry.require(PostTurnTaskServiceKey) else null
            val authorize: suspend () -> Unit = { page.ensureAuthorized(); verifyGrants() }
            return PostTurnPageBridge(tasks).call(manifest.id, operation, arguments, page, authorize) {
                if (manifest.id == "dev.solvit.memory") BuiltinMemoryManager(context).pruneRetiredSources { turnId ->
                    checkNotNull(tasks).retired(manifest.id, turnId, authorize)
                } else 0
            }
        }
        if (background != null) {
            check(manifest.apiVersion >= 8 && background.target.pluginId == manifest.id && background.target.version == manifest.version)
            background.ensureAuthorized()
        }
        if (operation == "model.complete") {
            check(manifest.apiVersion >= 8 && background != null) { "模型调用需要已授权的后台任务" }
            require(arguments.keys == setOf("instructions", "input"))
            return JsonPrimitive(background.complete(arguments.getValue("instructions").jsonPrimitive.content,
                arguments.getValue("input").jsonPrimitive.content))
        }
        if (operation == "web.visit") {
            check(manifest.apiVersion >= 6) { "浏览器观察需要 API 6" }
            val active = currentCoroutineContext()
            active.ensureActive()
            val invocation = active[ToolInvocationContext] ?: error("网页访问必须通过工具授权")
            check(invocation.descriptor.pluginId == manifest.id && active[AgentRoleContext]?.role?.allows(manifest.id) != false)
            require(arguments.keys == setOf("url")) { "网页观察只接受 URL" }
            val page = (webPages ?: error("网页观察服务不可用")).visit(arguments.getValue("url").jsonPrimitive.content)
            return buildJsonObject {
                put("url", page.url); put("title", page.title); put("text", page.text); put("truncated", page.truncated); put("searchResults", page.searchResults)
                put("links", buildJsonArray { page.links.forEach { link -> add(buildJsonObject {
                    put("url", link.url); put("title", link.title); put("snippet", link.snippet)
                }) } })
            }
        }
        if (operation.startsWith("git.")) {
            check(manifest.apiVersion >= 19 && "workspace.git" in manifest.nativeServices &&
                manifest.permissions.containsAll(setOf("workspace", "git")))
            check(page == null && background == null) { "Git工具只能在已授权的AI工具调用中使用" }
            val context = currentCoroutineContext()
            val invocation = context[ToolInvocationContext] ?: error("Git操作必须通过工具授权")
            val conversation = context[ConversationContext] ?: error("请在绑定工作区的AI对话中调用Git工具")
            val workspace = checkNotNull(workspaces)
            suspend fun authorizeGit() {
                currentCoroutineContext().ensureActive()
                verifyGrants()
                check(prefs.getStringSet("grants.${manifest.id}", emptySet()).orEmpty().containsAll(setOf("workspace", "git")))
                check(manager.states.value[manifest.id] == PluginState.ENABLED && prefs.getBoolean("enabled.${manifest.id}", false))
                check(packages[manifest.id]?.manifest == manifest) { "Git插件已被替换" }
                check(invocation.descriptor.pluginId == manifest.id && context[AgentRoleContext]?.role?.allows(manifest.id) != false)
                check(Json.parseToJsonElement(invocation.call.argumentsJson) == arguments) { "Git参数与用户确认内容不一致" }
                check(workspace.state.value.bindings[conversation.sessionId] == conversation.workspaceId) { "对话工作区已变化" }
            }
            authorizeGit()
            return GitToolBridge(checkNotNull(git) { "Git服务不可用" }).query(operation, arguments, conversation, ::authorizeGit)
        }
        if (operation.startsWith("workspace.") || operation.startsWith("terminal.") || operation == "plugins.draft") {
            check(manifest.apiVersion >= 4)
            check(!operation.startsWith("terminal.") || manifest.apiVersion >= 5)
            val context = currentCoroutineContext()
            context.ensureActive()
            val invocation = context[ToolInvocationContext] ?: error("工作区操作必须通过工具授权")
            check(invocation.descriptor.pluginId == manifest.id && context[AgentRoleContext]?.role?.allows(manifest.id) != false)
            // Bind actual native operations to the exact approved JSON. A script
            // cannot hide a different command, path or plugin source behind it.
            check(Json.parseToJsonElement(invocation.call.argumentsJson) == arguments) { "宿主参数与用户确认内容不一致" }
            val conversation = context[ConversationContext] ?: error("请在绑定工作区的 AI 对话中调用此工具")
            val service = workspaces ?: error("工作区服务不可用")
            val workspace = conversation.workspaceId
            check(service.state.value.bindings[conversation.sessionId] == workspace) { "对话工作区已变化，请重新调用" }
            if (operation in setOf("workspace.write", "workspace.createShell", "workspace.start", "workspace.cancel", "plugins.draft")) invocation.claim(operation)
            fun string(key: String) = arguments.getValue(key).jsonPrimitive.content
            if (operation.startsWith("terminal.")) {
                val terminal = terminals ?: error("交互终端服务不可用")
                return when (operation) {
                    "terminal.open" -> terminalJson(terminal.open(workspace, string("shellId")))
                    "terminal.status" -> terminalJson(terminal.status(workspace, string("id")))
                    "terminal.input" -> terminalJson(terminal.input(workspace, string("id"), string("text")))
                    "terminal.interrupt" -> terminalJson(terminal.interrupt(workspace, string("id")))
                    "terminal.close" -> { terminal.close(workspace, string("id")); JsonNull }
                    else -> error("未知终端操作")
                }
            }
            return when (operation) {
                "workspace.info" -> buildJsonObject {
                    put("workspaceId", workspace)
                    put("name", service.state.value.workspaces.first { it.id == workspace }.name)
                    put("shells", JsonArray(service.state.value.shells.filter { it.workspaceId == workspace }.sortedByDescending { it.runtime == ShellRuntime.UBUNTU }.map { buildJsonObject { put("id", it.id); put("name", it.name); put("runtime", it.runtime.name) } }))
                }
                "workspace.read" -> JsonPrimitive(service.read(workspace, string("path")))
                "workspace.write" -> writeEvidenceJson(service.writeObserved(workspace, string("path"), string("text")))
                "workspace.createShell" -> service.createShell(workspace, string("name")).let { buildJsonObject { put("id", it.id); put("name", it.name) } }
                "workspace.start" -> shellJson(service.run(workspace, string("shellId"), string("command")))
                "workspace.status" -> {
                    val job = service.jobs.value.firstOrNull { it.id == string("id") && it.workspaceId == workspace } ?: error("此工作区不存在该任务")
                    check(job.owner == manifest.id && job.roleId == context[AgentRoleContext]?.role?.id) { "任务属于其他插件或角色" }
                    shellJson(job)
                }
                "workspace.cancel" -> { service.cancel(workspace, string("id")); JsonNull }
                "plugins.draft" -> (drafts?.invoke() ?: error("插件草稿服务不可用")).prepare(workspace, string("manifestJson"), string("source")).let { draft ->
                    buildJsonObject { put("id", draft.id); put("path", draft.path); put("sha256", draft.sha256); put("status", "已校验，等待用户在工作区查看并安装；尚未启用") }
                }
                else -> error("未知工作区操作")
            }
        }
        return when (operation) {
            "storage.get", "storage.set", "storage.compareAndSet" -> {
                val key = arguments.getValue("key").jsonPrimitive.content
                suspend fun verifyWrite() {
                    currentCoroutineContext().ensureActive()
                    verifyGrants()
                    background?.ensureAuthorized()
                    page?.ensureAuthorized()
                }
                try {
                    when (operation) {
                        "storage.get" -> { require(arguments.keys == setOf("key")); data.get(manifest.id, key) { verifyWrite() } }
                        "storage.compareAndSet" -> {
                            check(manifest.apiVersion >= 8)
                            require(arguments.keys == setOf("key", "expected", "value"))
                            JsonPrimitive(data.compareAndSet(manifest.id, key, arguments.getValue("expected"), arguments.getValue("value")) { verifyWrite() })
                        }
                        else -> {
                            require(arguments.keys == setOf("key", "value"))
                            data.set(manifest.id, key, arguments.getValue("value"), manifest.apiVersion >= 8) { verifyWrite() }
                            JsonNull
                        }
                    }
                } catch (e: PostTurnRevokedException) { throw e }
                catch (e: PostTurnWorkException) { background?.recordFailure(e.failure); throw e }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    background?.recordFailure(PostTurnFailure.STORAGE)
                    throw e
                }
            }
            "http.request" -> {
                val url = arguments.getValue("url").jsonPrimitive.content
                val uri = URI(url)
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "插件网络请求需要 HTTPS" }
                val method = arguments["method"]?.jsonPrimitive?.content ?: "GET"
                require(method in setOf("GET", "POST", "DELETE")) { "不支持的 HTTP 方法" }
                val builder = Request.Builder().url(url)
                val headers = arguments["headers"]?.jsonObject.orEmpty()
                require(headers.size <= 16)
                headers.forEach { (name, value) ->
                    require(name.lowercase() !in setOf("host", "connection", "content-length", "transfer-encoding", "cookie"))
                    val text = value.jsonPrimitive.content
                    require(text.length <= 4096 && !text.contains('\r') && !text.contains('\n'))
                    builder.header(name, text)
                }
                val payload = arguments["body"]?.jsonPrimitive?.content.orEmpty()
                require(payload.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
                when (method) {
                    "POST" -> builder.post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    "DELETE" -> builder.delete()
                    else -> require(payload.isEmpty())
                }
                val outgoing = builder.build()
                val cancellationBody = arguments["cancelBody"]?.jsonPrimitive?.content
                val onCancelled: (suspend () -> Unit)? = cancellationBody?.let { body ->
                    require(method == "POST" && body.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
                    val cancellationRequest = outgoing.newBuilder().post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                    suspend {
                        if ("network" in prefs.getStringSet("grants.${manifest.id}", emptySet()).orEmpty())
                            client.newCall(cancellationRequest).readResponse { }
                    }
                }
                val timeout = arguments["timeoutMs"]?.jsonPrimitive?.long
                if (timeout != null) require(manifest.apiVersion >= 6 && timeout in 200..20_000) { "网络超时需要 API 6，范围为 200..20000 毫秒" }
                val response = request(outgoing, 1024 * 1024, allowHttpError = true, onCancelled = onCancelled, timeoutMs = timeout)
                buildJsonObject {
                    put("body", response.bytes.toString(Charsets.UTF_8)); put("status", response.status)
                    putJsonObject("headers") { response.headers.forEach { (name, value) -> put(name, value) } }
                }
            }
            else -> error("未开放的宿主操作")
        }
    }

    private fun shellJson(job: ShellJob): JsonObject = buildJsonObject {
        put("id", job.id); put("shellId", job.shellSessionId); put("stage", job.stage.name)
        put("workspaceId", job.workspaceId); put("cwd", job.cwd); put("mode", job.mode.name)
        put("output", job.output.joinToString("") { it.text }); put("exitCode", job.exitCode?.let(::JsonPrimitive) ?: JsonNull)
        put("truncated", job.truncated); put("failure", job.failure)
    }
    private fun writeEvidenceJson(value: WorkspaceWriteEvidence): JsonObject = buildJsonObject {
        put("workspaceId",value.workspaceId);put("path",value.path);put("verified",value.verified)
        put("changed",value.changed?.let(::JsonPrimitive) ?: JsonNull)
        fun observation(side: FileObservation)=buildJsonObject {
            put("kind",side.kind.name);put("bytes",side.bytes?.let(::JsonPrimitive) ?: JsonNull)
            put("sha256",side.sha256);put("excerpt",side.excerpt);put("excerptTruncated",side.excerptTruncated)
        }
        put("before",observation(value.before));put("after",observation(value.after))
        put("scope","This write only; excerpts may be truncated. Shell changes outside this write are not captured.")
    }

    private suspend fun getBytes(url: String, maxBytes: Int): ByteArray {
        val response = request(Request.Builder().url(url).build(), maxBytes)
        return response.bytes
    }

    private data class HttpResult(val bytes: ByteArray, val status: Int, val headers: Map<String, String>)

    private suspend fun request(request: Request, maxBytes: Int, allowHttpError: Boolean = false, onCancelled: (suspend () -> Unit)? = null, timeoutMs: Long? = null): HttpResult {
        val call = client.newCall(request)
        if (timeoutMs != null) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        return coroutineScope {
            val requestJob = currentCoroutineContext().job
            var completed = false
            try { call.readResponse { response ->
            if (!allowHttpError && !response.isSuccessful) throw IOException("请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("服务返回空响应")
            if (body.contentLength() > maxBytes) throw IOException("服务返回内容过大")
            val bytes = withContext(Dispatchers.IO) {
                val buffer = Buffer()
                val source = body.source()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer, minOf(8192L, maxBytes.toLong() + 1 - buffer.size))
                    if (read == -1L) break
                    check(buffer.size <= maxBytes) { "服务返回内容过大" }
                }
                buffer.readByteArray()
            }
            HttpResult(bytes, response.code, listOf("content-type", "mcp-session-id", "mcp-protocol-version")
                .mapNotNull { name -> response.header(name)?.let { name to it } }.toMap())
                .also { completed = true }
            } } finally {
                // OkHttp's socket failure can race coroutine cancellation, so an
                // IOException may arrive instead of CancellationException. Use
                // the invocation's job state and finish cleanup before returning.
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!completed && !requestJob.isActive && onCancelled != null) {
                        try { withTimeout(1_000) { onCancelled() } } catch (_: Exception) { /* Cleanup must not prevent stopping. */ }
                    }
                }
            }
        }
    }

}

private fun terminalJson(session: TerminalSession): JsonObject = buildJsonObject {
    put("id", session.id); put("shellId", session.shellId); put("active", session.active)
    put("output", session.output); put("revision", session.revision); put("truncated", session.truncated)
    put("mode", session.mode.name); put("exitCode", session.exitCode?.let(::JsonPrimitive) ?: JsonNull)
    put("failure", session.failure?.let(::JsonPrimitive) ?: JsonNull)
}

class MarketplacePlugin(private val context: Context, private val manager: PluginManager,
    private val useWorkspaces: Boolean = false, private val drafts: (() -> PluginDraftService)? = null,
    private val useWebPages: Boolean = false, private val useGit: Boolean = false) : SolvitPlugin {
    override val id = "builtin.plugins.marketplace"
    override val requires = buildSet { add(Services.Tools); if (useWorkspaces) { add(WorkspaceServiceKey); add(TerminalServiceKey) }; if (useWebPages) add(WebPageServiceKey); if (useGit) { add(WorkspaceGitServiceKey); add(WorkspaceServiceKey) } }
    override val provides = setOf(MarketplaceServiceKey)
    lateinit var service: AndroidMarketplace
        private set
    override suspend fun enable(context: PluginContext) {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
        context.own { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        service = AndroidMarketplace(this.context, manager, client, context.require(Services.Tools),
            workspaces = if (useWorkspaces) context.require(WorkspaceServiceKey) else null, drafts = drafts,
            terminals = if (useWorkspaces) context.require(TerminalServiceKey) else null,
            webPages = if (useWebPages) context.require(WebPageServiceKey) else null,
            git = if (useGit) context.require(WorkspaceGitServiceKey) else null)
        context.register(MarketplaceServiceKey, service)
    }
}
