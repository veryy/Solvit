package dev.solvit.market

import android.content.Context
import dev.solvit.core.*
import dev.solvit.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

/** Package validation has no network, file, Shell or installation host capabilities. */
class AndroidPluginDrafts(context: Context, private val workspaces: WorkspaceService,
    private val marketplace: MarketplaceService) : PluginDraftService {
    private val prefs = context.getSharedPreferences("plugin_drafts", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val mutableDrafts = MutableStateFlow(load())
    override val drafts = mutableDrafts.asStateFlow()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun load(): List<PluginDraft> {
        val raw = prefs.getString("drafts", null) ?: return emptyList()
        return Json.parseToJsonElement(raw).jsonArray.map { element ->
            val d = element.jsonObject
            fun text(key: String) = d.getValue(key).jsonPrimitive.content
            PluginDraft(text("id"), text("workspace"), text("pluginId"), text("name"), text("version"),
                d.getValue("permissions").jsonArray.map { it.jsonPrimitive.content }.toSet(), text("path"), text("sha256"))
        }
    }
    private fun save(value: List<PluginDraft>) {
        val json = JsonArray(value.map { draft -> buildJsonObject {
            put("id", draft.id); put("workspace", draft.workspaceId); put("pluginId", draft.pluginId)
            put("name", draft.name); put("version", draft.version); put("path", draft.path); put("sha256", draft.sha256)
            put("permissions", JsonArray(draft.permissions.sorted().map(::JsonPrimitive)))
        } })
        check(prefs.edit().putString("drafts", json.toString()).commit()) { "插件草稿保存失败" }
        mutableDrafts.value = value
    }
    override suspend fun prepare(workspaceId: String, manifestJson: String, source: String): PluginDraft = lock.withLock {
        require(source.length <= 200_000 && manifestJson.length <= 32_000) { "插件草稿过大" }
        val bytes = buildJsonObject { put("manifest", Json.parseToJsonElement(manifestJson).jsonObject); put("source", source) }.toString().toByteArray()
        require(bytes.size <= 256 * 1024) { "插件草稿超过 256 KiB" }
        val pkg = ScriptPackage.parse(bytes)
        val executor = ScriptExecutor(pkg, ScriptHost { _, _, _ -> error("草稿校验阶段禁止调用宿主；请将操作放在工具函数中") }, timeoutMs = 5_000)
        try { executor.start() } finally { withContext(NonCancellable) { executor.stop() } }
        val id = UUID.randomUUID().toString()
        val path = "plugin-drafts/$id.solvit.json"
        workspaces.write(workspaceId, path, bytes.toString(Charsets.UTF_8))
        val draft = PluginDraft(id, workspaceId, pkg.manifest.id, pkg.manifest.name, pkg.manifest.version, pkg.manifest.permissions, path, digest(bytes))
        withContext(Dispatchers.IO) { save((drafts.value + draft).takeLast(64)) }
        draft
    }
    private suspend fun checked(id: String): Pair<PluginDraft, String> {
        val draft = drafts.value.firstOrNull { it.id == id } ?: error("插件草稿不存在")
        val text = workspaces.read(draft.workspaceId, draft.path)
        check(digest(text.toByteArray()) == draft.sha256) { "草稿文件已变化，请重新校验后再安装" }
        return draft to text
    }
    override suspend fun payload(id: String): String = lock.withLock { checked(id).second }
    override suspend fun install(id: String, expectedSha256: String) = lock.withLock {
        val (draft, text) = checked(id)
        check(draft.sha256 == expectedSha256) { "草稿与查看的版本不一致" }
        marketplace.installLocal(text.toByteArray())
    }
}

class PluginDraftsPlugin(private val androidContext: Context) : SolvitPlugin {
    override val id = "builtin.plugins.drafts"
    override val requires = setOf(WorkspaceServiceKey, MarketplaceServiceKey)
    override val provides = setOf(PluginDraftServiceKey)
    override suspend fun enable(context: PluginContext) {
        context.register(PluginDraftServiceKey, AndroidPluginDrafts(androidContext, context.require(WorkspaceServiceKey), context.require(MarketplaceServiceKey)))
    }
}
