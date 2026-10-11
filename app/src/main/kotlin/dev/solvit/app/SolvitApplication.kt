package dev.solvit.app

import android.app.Application
import dev.solvit.chat.*
import dev.solvit.core.*
import dev.solvit.provider.OpenAiPlugin
import dev.solvit.anthropic.AnthropicPlugin
import dev.solvit.storage.SqliteStoragePlugin
import dev.solvit.updates.ReleaseUpdatesPlugin
import dev.solvit.market.MarketplacePlugin
import dev.solvit.market.RolesPlugin
import dev.solvit.market.SkillLibraryPlugin
import dev.solvit.market.PluginDraftsPlugin
import dev.solvit.workspace.WorkspacePlugin
import dev.solvit.terminal.TerminalPlugin
import dev.solvit.web.WebPagesPlugin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SolvitApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableReady = MutableStateFlow<Result<ChatService>?>(null)
    val ready = mutableReady.asStateFlow()
    lateinit var modelProfiles: SettingsModelProfiles
        private set
    lateinit var language: LanguageStore
        private set
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
    lateinit var modelProviders: ModelProviderRegistry
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
    lateinit var contextPreparation: ContextPreparationService
        private set
    lateinit var requestInspection: RequestInspectionService
        private set
    override fun onCreate() {
        super.onCreate()
        settings = EncryptedSettingsStore(this)
        toolApprovals = ToolApprovalStore(this)
        appearance = AppearanceStore(this)
        language = LanguageStore(this)
        updateDownloader = AppUpdateDownloader(this, scope)
        modelProfiles = SettingsModelProfiles(settings)
        scope.launch {
            modelProfiles.initialize()
            val manager = PluginManager(scope = scope)
            val marketPlugin = MarketplacePlugin(this@SolvitApplication, manager, useWorkspaces = true, drafts = { pluginDrafts }, useWebPages = true)
            try {
                // Deliberately unordered: dependency declarations control activation.
                listOf(PostTurnHooksPlugin(), ContextBudgetPlugin(), ContextPreparationPlugin(), WebPagesPlugin(this@SolvitApplication), RequestInspectionPlugin(), ModelProvidersPlugin(), ModelRoutingPlugin(), ModelProfilesPlugin(modelProfiles), ToolApprovalsPlugin(toolApprovals), ChatBranchPlugin(), TerminalPlugin(), PluginDraftsPlugin(this@SolvitApplication), ChatServicePlugin(useWorkspaces = true), WorkspacePlugin(this@SolvitApplication), ChatAgentPlugin(), ToolsPlugin(enforcePolicies = true, enforceSystem = true), ContextHooksPlugin(), RolesPlugin(this@SolvitApplication), SkillLibraryPlugin(),
                    OpenAiPlugin(), AnthropicPlugin(), SqliteStoragePlugin(this@SolvitApplication), ReleaseUpdatesPlugin(), marketPlugin).forEach { manager.load(it) }
                manager.enableAll()
                branches = manager.registry.require(ChatBranchServiceKey)
                contextPreparation = manager.registry.require(ContextPreparationServiceKey)
                requestInspection = manager.registry.require(RequestInspectionServiceKey)
                updates = manager.registry.require(UpdateServiceKey)
                marketplace = manager.registry.require(MarketplaceServiceKey)
                roles = manager.registry.require(RoleServiceKey)
                skills = manager.registry.require(SkillLibraryServiceKey)
                modelProviders = manager.registry.require(ModelProvidersServiceKey)
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
