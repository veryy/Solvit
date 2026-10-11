package dev.solvit.app

import android.app.Application
import dev.solvit.chat.*
import dev.solvit.core.*
import dev.solvit.provider.OpenAiPlugin
import dev.solvit.storage.SqliteStoragePlugin
import dev.solvit.updates.ReleaseUpdatesPlugin
import dev.solvit.market.MarketplacePlugin
import dev.solvit.market.RolesPlugin
import dev.solvit.market.SkillLibraryPlugin
import dev.solvit.market.PluginDraftsPlugin
import dev.solvit.workspace.WorkspacePlugin
import dev.solvit.terminal.TerminalPlugin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SolvitApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableReady = MutableStateFlow<Result<ChatService>?>(null)
    val ready = mutableReady.asStateFlow()
    lateinit var toolApprovals: ToolApprovalStore
        private set
    lateinit var branches: ChatBranchService
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var updateDownloader: AppUpdateDownloader
        private set
    lateinit var appearance: AppearanceStore
        private set
    lateinit var updates: UpdateService
        private set
    lateinit var marketplace: MarketplaceService
        private set
    lateinit var roles: RoleService
        private set
    lateinit var skills: SkillLibraryService
        private set
    lateinit var modelCatalog: ModelCatalogService
        private set
    lateinit var workspaces: WorkspaceService
        private set
    lateinit var systemExecution: SystemExecutionService
        private set
    lateinit var terminals: TerminalService
        private set
    lateinit var pluginDrafts: PluginDraftService
        private set
    override fun onCreate() {
        super.onCreate()
        settings = EncryptedSettingsStore(this)
        toolApprovals = ToolApprovalStore(this)
        appearance = AppearanceStore(this)
        updateDownloader = AppUpdateDownloader(this, scope)
        scope.launch {
            val manager = PluginManager(scope = scope)
            val marketPlugin = MarketplacePlugin(this@SolvitApplication, manager, useWorkspaces = true, drafts = { pluginDrafts })
            try {
                // Deliberately unordered: dependency declarations control activation.
                listOf(ToolApprovalsPlugin(toolApprovals), ChatBranchPlugin(), TerminalPlugin(), PluginDraftsPlugin(this@SolvitApplication), ChatServicePlugin(useWorkspaces = true), WorkspacePlugin(this@SolvitApplication), ChatAgentPlugin(), ToolsPlugin(enforcePolicies = true, enforceSystem = true), ContextHooksPlugin(), RolesPlugin(this@SolvitApplication), SkillLibraryPlugin(),
                    OpenAiPlugin(), SqliteStoragePlugin(this@SolvitApplication), ReleaseUpdatesPlugin(), marketPlugin).forEach { manager.load(it) }
                manager.enableAll()
                branches = manager.registry.require(ChatBranchServiceKey)
                updates = manager.registry.require(UpdateServiceKey)
                marketplace = manager.registry.require(MarketplaceServiceKey)
                roles = manager.registry.require(RoleServiceKey)
                skills = manager.registry.require(SkillLibraryServiceKey)
                modelCatalog = manager.registry.require(ModelCatalogServiceKey)
                workspaces = manager.registry.require(WorkspaceServiceKey)
                systemExecution = manager.registry.require(SystemExecutionServiceKey)
                terminals = manager.registry.require(TerminalServiceKey)
                pluginDrafts = manager.registry.require(PluginDraftServiceKey)
                marketPlugin.service.restore()
                mutableReady.value = Result.success(manager.registry.require(Services.Chat))
            } catch (e: Exception) {
                try { manager.shutdown() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                mutableReady.value = Result.failure(e)
            }
        }
    }
}
