package dev.solvit.core

import kotlinx.coroutines.flow.StateFlow

data class MarketEntry(
    val id: String, val name: String, val description: String, val version: String,
    val permissions: Set<String>, val dependencies: Set<String>,
    val sha256: String, val bytes: Long, val downloadPath: String,
)
data class ExtensionSetting(val key: String, val label: String, val kind: String, val required: Boolean = false,
    val defaultValue: String? = null, val pageManaged: Boolean = false)
data class ExtensionPage(val id: String, val title: String)
data class ExtensionPageSnapshot(val revision: Long, val json: String)
data class ExtensionPageDocument(val entry: String, val sensitive: Boolean = false)
data class ExtensionPageResource(val mimeType: String, val content: String)
interface ExtensionPageSession {
    val closed: StateFlow<Boolean>
    suspend fun open(): ExtensionPageSnapshot
    suspend fun dispatch(revision: Long, nodeId: String, value: String? = null,
        fields: Map<String, String> = emptyMap(), confirmed: Boolean = false): ExtensionPageSnapshot
    suspend fun document(): ExtensionPageDocument? = null
    suspend fun resource(path: String): ExtensionPageResource { error("此页面没有离线资源") }
    /** Attached by the page owner before open; never available to model tools or Hooks. */
    fun attachDocuments(access: PageDocumentAccess) { }
    fun attachGitCredentialPrompt(prompt: PageGitCredentialPrompt) { }
    fun attachWorkspace(access: PageWorkspaceAccess) { }
    fun attachGit(access: PageGitAccess) { }
    fun attachTerminal(access: PageTerminalAccess) { }
    suspend fun terminalAccess(): PageTerminalAccess? = null
    suspend fun close()
}

data class InstalledExtension(
    val id: String, val name: String, val version: String, val permissions: Set<String>,
    val enabled: Boolean, val tools: List<ToolDescriptor>, val failure: String? = null,
    val settings: List<ExtensionSetting> = emptyList(),
    val contextHooks: Set<String> = emptySet(),
    val pages: List<ExtensionPage> = emptyList(),
)

interface MarketplaceService {
    val installed: StateFlow<List<InstalledExtension>>
    suspend fun catalog(): List<MarketEntry>
    suspend fun install(entry: MarketEntry)
    suspend fun installLocal(payload: ByteArray) { error("此宿主未开放本地插件安装") }
    suspend fun enable(id: String, grantedPermissions: Set<String>)
    suspend fun disable(id: String)
    suspend fun uninstall(id: String)
    suspend fun configuration(id: String): Map<String, String>
    suspend fun configure(id: String, values: Map<String, String>)
    suspend fun runTool(name: String, argumentsJson: String, authorization: ToolAuthorization): String
    suspend fun openPage(pluginId: String, pageId: String, locale: String): ExtensionPageSession { error("此宿主未开放插件页面") }
}

val MarketplaceServiceKey = ServiceKey("plugins.marketplace", MarketplaceService::class)
