package dev.solvit.market

import android.content.Context
import android.util.AtomicFile
import dev.solvit.core.*
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
import kotlin.coroutines.resumeWithException

private const val MARKET_URL = "https://solvit.207.57.126.73.sslip.io"

class AndroidMarketplace(
    private val context: Context,
    private val manager: PluginManager,
    private val client: OkHttpClient,
    private val tools: ToolRegistry,
    private val config: EncryptedPluginConfiguration = EncryptedPluginConfiguration(context),
) : MarketplaceService {
    private val directory = File(context.filesDir, "extensions").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("extension_permissions", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val packages = linkedMapOf<String, ScriptPackage>()
    private val failures = mutableMapOf<String, String>()
    private val mutableInstalled = MutableStateFlow<List<InstalledExtension>>(emptyList())
    override val installed = mutableInstalled.asStateFlow()
    private var latestCatalog = emptyList<MarketEntry>()

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

    override suspend fun catalog(): List<MarketEntry> {
        val root = Json.parseToJsonElement(getBytes("$MARKET_URL/api/v1/plugins?hostApi=3", 1024 * 1024).toString(Charsets.UTF_8)).jsonObject
        check(root.getValue("apiVersion").jsonPrimitive.int == 1) { "市场 API 版本不兼容" }
        val entries = root.getValue("plugins").jsonArray.map { element ->
            val m = element.jsonObject
            val pkg = ScriptPackage.parse(buildJsonObject { put("manifest", m); put("source", "// Metadata only") }.toString().toByteArray())
            val sha = m.getValue("sha256").jsonPrimitive.content
            val bytes = m.getValue("bytes").jsonPrimitive.long
            val path = m.getValue("downloadPath").jsonPrimitive.content
            check(sha.matches(Regex("[a-f0-9]{64}")) && bytes in 1..ScriptPackage.MAX_BYTES.toLong()) { "市场包校验数据无效" }
            check(path == "/api/v1/packages/${pkg.manifest.id}/${pkg.manifest.version}") { "市场下载地址无效" }
            MarketEntry(pkg.manifest.id, pkg.manifest.name, pkg.manifest.description, pkg.manifest.version,
                pkg.manifest.permissions, pkg.manifest.dependencies, sha, bytes, path)
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
        val values = withContext(Dispatchers.IO) { config.read(id) }
        check(pkg.manifest.settings.filter { it.required }.all { !values[it.key].isNullOrBlank() }) { "请先填写插件配置" }
        if (manager.states.value[id] == PluginState.ENABLED) return
        if (manager.states.value[id] in setOf(PluginState.LOADED, PluginState.INITIALIZED, PluginState.DISABLED)) manager.unload(id)
        prefs.edit().putStringSet("grants.$id", grants.toSet()).commit()
        manager.load(ScriptToolPlugin(pkg, ScriptHost { manifest, operation, arguments -> hostCall(manifest, operation, arguments) }))
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
        try { manager.disable(id) }
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
        if (manager.states.value[id] in setOf(PluginState.LOADED, PluginState.INITIALIZED, PluginState.DISABLED, PluginState.ENABLED)) manager.unload(id)
        withContext(Dispatchers.IO) { AtomicFile(File(directory, "$id.solvit.json")).delete() }
        prefs.edit().remove("enabled.$id").remove("grants.$id").commit()
        withContext(Dispatchers.IO) { config.remove(id) }
        packages.remove(id); failures.remove(id)
        publish()
    }

    override suspend fun configuration(id: String): Map<String, String> = mutex.withLock {
        val pkg = packages[id] ?: error("插件未安装")
        withContext(Dispatchers.IO) { config.read(id) }.filterKeys { key -> pkg.manifest.settings.any { it.key == key } }
    }
    override suspend fun configure(id: String, values: Map<String, String>) = mutex.withLock {
        val pkg = packages[id] ?: error("插件未安装")
        check(manager.states.value[id] != PluginState.ENABLED) { "请先停用插件再修改配置" }
        require(values.keys.all { key -> pkg.manifest.settings.any { it.key == key } })
        pkg.manifest.settings.forEach { field ->
            val value = values[field.key].orEmpty()
            require(value.length <= 4096 && (!field.required || value.isNotBlank())) { "插件配置不完整" }
            if (field.kind == "url" && value.isNotBlank()) {
                val uri = URI(value)
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "插件地址需要 HTTPS" }
            }
            if (field.kind == "secret") require(!value.contains('\n') && !value.contains('\r'))
        }
        withContext(Dispatchers.IO) { config.write(id, values) }
    }

    override suspend fun runTool(name: String, argumentsJson: String, authorization: ToolAuthorization): String =
        tools.execute(name, argumentsJson, authorization)

    private fun publish() {
        mutableInstalled.value = packages.values.map { pkg ->
            val enabled = manager.states.value[pkg.manifest.id] == PluginState.ENABLED
            val prefix = pkg.manifest.id.replace('.', '_').replace('-', '_') + "_"
            val names = pkg.manifest.tools.map { prefix + it.name }.toSet()
            InstalledExtension(pkg.manifest.id, pkg.manifest.name, pkg.manifest.version, pkg.manifest.permissions,
                enabled, if (enabled) tools.descriptors().filter { it.name in names } else emptyList(), failures[pkg.manifest.id], pkg.manifest.settings)
        }
    }

    private suspend fun hostCall(manifest: ScriptManifest, operation: String, arguments: JsonObject): JsonElement {
        if (operation == "settings.get") {
            check(manifest.apiVersion >= 2)
            val key = arguments.getValue("key").jsonPrimitive.content
            check(manifest.settings.any { it.key == key }) { "插件未声明此配置字段" }
            return withContext(Dispatchers.IO) { config.read(manifest.id)[key]?.let(::JsonPrimitive) ?: JsonNull }
        }
        val permission = if (operation.startsWith("storage.")) "storage" else if (operation == "http.request") "network" else error("未开放的宿主操作")
        check(permission in manifest.permissions && permission in prefs.getStringSet("grants.${manifest.id}", emptySet()).orEmpty()) { "插件权限未授予" }
        return when (operation) {
            "storage.get", "storage.set" -> withContext(Dispatchers.IO) {
                val key = arguments.getValue("key").jsonPrimitive.content
                require(key.length in 1..256) { "存储键长度无效" }
                val storage = context.getSharedPreferences("extension_data.${manifest.id}", Context.MODE_PRIVATE)
                if (operation == "storage.get") storage.getString(key, null)?.let { Json.parseToJsonElement(it) } ?: JsonNull
                else {
                    val value = arguments.getValue("value").toString()
                    require(value.length <= 256 * 1024) { "存储值过大" }
                    check(storage.edit().putString(key, value).commit()) { "插件数据保存失败" }
                    JsonNull
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
                            client.newCall(cancellationRequest).await().use { }
                    }
                }
                val response = request(outgoing, 1024 * 1024, allowHttpError = true, onCancelled = onCancelled)
                buildJsonObject {
                    put("body", response.bytes.toString(Charsets.UTF_8)); put("status", response.status)
                    putJsonObject("headers") { response.headers.forEach { (name, value) -> put(name, value) } }
                }
            }
            else -> error("未开放的宿主操作")
        }
    }

    private suspend fun getBytes(url: String, maxBytes: Int): ByteArray {
        val response = request(Request.Builder().url(url).build(), maxBytes)
        return response.bytes
    }

    private data class HttpResult(val bytes: ByteArray, val status: Int, val headers: Map<String, String>)

    private suspend fun request(request: Request, maxBytes: Int, allowHttpError: Boolean = false, onCancelled: (suspend () -> Unit)? = null): HttpResult {
        val call = client.newCall(request)
        return coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try { call.await().use { response ->
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
            } } catch (e: CancellationException) {
                call.cancel()
                if (onCancelled != null) withContext(NonCancellable) {
                    try { withTimeout(1_000) { onCancelled() } } catch (_: Exception) { /* Cleanup must not prevent stopping. */ }
                }
                throw e
            } finally { cancellation.cancelAndJoin(); call.cancel() }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
        })
    }
}

class MarketplacePlugin(private val context: Context, private val manager: PluginManager) : SolvitPlugin {
    override val id = "builtin.plugins.marketplace"
    override val requires = setOf(Services.Tools)
    override val provides = setOf(MarketplaceServiceKey)
    lateinit var service: AndroidMarketplace
        private set
    override suspend fun enable(context: PluginContext) {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
        context.own { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        service = AndroidMarketplace(this.context, manager, client, context.require(Services.Tools))
        context.register(MarketplaceServiceKey, service)
    }
}
