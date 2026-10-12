package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.plugins.ScriptPageHostAction
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** One captured conversation. A background rebind invalidates it; it never follows another chat. */
class WorkspacePageBridge(private val service: WorkspaceService, private val drafts: PluginDraftService?,
    private val chatId: String, private val canInstallDrafts: Boolean = false,
    private val activeChat: () -> String? = { chatId }) : PageWorkspaceAccess {
    init { require(chatId.isNotBlank() && chatId.length <= 128) }
    private val mutex = Mutex()
    private var binding = service.state.value.bindings[chatId]
    private suspend fun verify(authorize: suspend () -> Unit) {
        authorize()
        check(activeChat() == chatId && service.state.value.bindings[chatId] == binding) { "对话或工作区已变化，请重新打开页面。" }
    }
    private fun workspace() = checkNotNull(binding) { "此对话尚未绑定工作区" }
    private fun snapshot(): JsonObject = buildJsonObject {
        put("workspaceId", binding?.let(::JsonPrimitive) ?: JsonNull)
        put("draftInstallAllowed", canInstallDrafts)
        put("workspaces", buildJsonArray { service.state.value.workspaces.forEach { w -> add(buildJsonObject { put("id", w.id); put("name", w.name) }) } })
        put("shells", buildJsonArray { service.state.value.shells.filter { it.workspaceId == binding }.forEach { s -> add(buildJsonObject { put("id", s.id); put("name", s.name) }) } })
    }
    private suspend fun <T> scoped(authorize: suspend () -> Unit, allowedNewWorkspace: () -> String? = { null }, block: suspend () -> T): T =
        withContext(WorkspacePageAuthority { target, session ->
            verify(authorize)
            check(session == null || session == chatId) { "不能操作其他对话" }
            check(target == null || target == binding || target == allowedNewWorkspace()) { "不能操作其他工作区" }
        }) { block() }

    override suspend fun query(operation: String, argumentsJson: String, authorize: suspend () -> Unit): String = mutex.withLock {
        verify(authorize)
        val args = Json.parseToJsonElement(argumentsJson).jsonObject
        fun text(key: String) = args.getValue(key).jsonPrimitive.also { require(it.isString && it.content.length <= 1024) }.content
        val result = scoped(authorize) {
            when (operation) {
                "snapshot" -> { require(args.isEmpty()); snapshot() }
                "list" -> {
                    require(args.keys == setOf("path", "offset"))
                    val offset = args.getValue("offset").jsonPrimitive.also { require(!it.isString) }.int.also { require(it in 0..1000) }
                    val files = service.list(workspace(), text("path"))
                    buildJsonObject {
                        put("total", files.size); put("offset", offset)
                        put("files", buildJsonArray { files.drop(offset).take(40).forEach { f -> add(buildJsonObject {
                            put("name", f.name); put("path", f.path); put("directory", f.directory); put("bytes", f.bytes)
                        }) } })
                    }
                }
                "read" -> {
                    require(args.keys == setOf("path"))
                    service.readForEdit(workspace(), text("path")).let { buildJsonObject { put("text", it.text); put("sha256", it.sha256) } }
                }
                "drafts" -> {
                    require(args.isEmpty())
                    buildJsonArray { drafts?.drafts?.value.orEmpty().filter { it.workspaceId == workspace() }.takeLast(40).reversed().forEach { d -> add(buildJsonObject {
                        put("id", d.id); put("name", d.name); put("pluginId", d.pluginId); put("version", d.version); put("sha256", d.sha256)
                        put("permissions", JsonArray(d.permissions.map(::JsonPrimitive)))
                    }) } }
                }
                "draft" -> {
                    require(args.keys == setOf("id", "offset"))
                    val draft = checkNotNull(drafts).drafts.value.first { it.id == text("id") && it.workspaceId == workspace() }
                    val offset = args.getValue("offset").jsonPrimitive.also { require(!it.isString) }.int.also { require(it in 0..2_097_152) }
                    val payload = drafts.payload(draft.id)
                    buildJsonObject { put("text", payload.drop(offset).take(16000)); put("total", payload.length); put("sha256", draft.sha256) }
                }
                else -> error("未开放的工作区页面查询")
            }
        }
        verify(authorize)
        result.toString()
    }

    override suspend fun perform(control: PageWorkspaceControl, authorize: suspend () -> Unit): String = mutex.withLock {
        verify(authorize)
        // Revalidate even when an implementation other than QuickJS invokes this capability.
        val args = Json.parseToJsonElement(control.argumentsJson).jsonObject
        ScriptPageHostAction.workspace(buildJsonObject { put("kind", "workspace.perform"); put("operation", control.operation); put("arguments", args) })
        fun text(key: String) = args.getValue(key).jsonPrimitive.content
        var created: String? = null
        var nextBinding = binding
        scoped(authorize, { created }) {
            when (control.operation) {
                "create" -> {
                    val value = service.create(text("name")); created = value.id
                    service.createShell(value.id, "Shell 1")
                    service.bind(chatId, value.id); nextBinding = value.id
                }
                "bind" -> { service.bind(chatId, text("id")); nextBinding = text("id") }
                "unbind" -> { service.unbind(chatId); nextBinding = null }
                "createShell" -> service.createShell(workspace(), text("name"))
                "write" -> service.writeForEdit(workspace(), text("path"), text("text"), args.getValue("expectedSha256").let { if (it == JsonNull) null else it.jsonPrimitive.content })
                "installDraft" -> {
                    check(canInstallDrafts) { "此页面未获插件草稿安装授权" }
                    val d = checkNotNull(drafts).drafts.value.first { it.id == text("id") && it.workspaceId == workspace() && it.sha256 == text("sha256") }
                    verify(authorize); drafts.install(d.id, d.sha256)
                }
                else -> error("未开放的工作区页面操作")
            }
        }
        binding = nextBinding
        verify(authorize)
        snapshot().toString()
    }
}
