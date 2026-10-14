package dev.solvit.app

import android.app.Application
import dev.solvit.chat.*
import dev.solvit.plugins.ScriptAgentDriverPlugin
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
import dev.solvit.git.WorkspaceGitPlugin
import dev.solvit.terminal.TerminalPlugin
import dev.solvit.environment.LinuxEnvironmentPlugin
import dev.solvit.web.WebPagesPlugin
import dev.solvit.documents.DocumentTextPlugin
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
    lateinit var attachments: ChatAttachmentStore
        private set
    lateinit var documents: DocumentTextService
        private set
    lateinit var modelCatalog: ModelCatalogService
        private set
    lateinit var workspaces: WorkspaceService
        private set
    lateinit var workspaceGit: WorkspaceGitService
        private set
    lateinit var systemExecution: SystemExecutionService
        private set
    lateinit var terminals: TerminalService
        private set
    lateinit var linuxEnvironment: LinuxEnvironmentService
        private set
    lateinit var pluginDrafts: PluginDraftService
        private set
    lateinit var contextPreparation: ContextPreparationService
        private set
    lateinit var postTurnWorker: PostTurnWorker
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
            val marketPlugin = MarketplacePlugin(this@SolvitApplication, manager, useWorkspaces = true, drafts = { pluginDrafts }, useWebPages = true, useGit = true)
            try {
                // Deliberately unordered: dependency declarations control activation.
                listOf(DocumentTextPlugin(this@SolvitApplication), LinuxEnvironmentPlugin(this@SolvitApplication), PostTurnWorkerPlugin(), PostTurnHooksPlugin(), ContextBudgetPlugin(), ContextPreparationPlugin(), WebPagesPlugin(this@SolvitApplication), RequestInspectionPlugin(), ModelProvidersPlugin(), ModelRoutingPlugin(), ModelProfilesPlugin(modelProfiles), ToolApprovalsPlugin(toolApprovals), ChatBranchPlugin(), WorkspaceGitPlugin(this@SolvitApplication), TerminalPlugin(useLinux = true), PluginDraftsPlugin(this@SolvitApplication), ChatServicePlugin(useWorkspaces = true, useAttachments = true, useDocuments = true), WorkspacePlugin(this@SolvitApplication, useLinux = true), ScriptAgentDriverPlugin(assets.open("agent/chat-driver.js").bufferedReader().use { it.readText() }), ChatAgentPlugin(requireDriver = true), ToolsPlugin(enforcePolicies = true, enforceSystem = true), ContextHooksPlugin(), RolesPlugin(this@SolvitApplication), SkillLibraryPlugin(),
                    OpenAiPlugin(), AnthropicPlugin(), SqliteStoragePlugin(this@SolvitApplication), ReleaseUpdatesPlugin(), marketPlugin).forEach { manager.load(it) }
                manager.enableAll()
                branches = manager.registry.require(ChatBranchServiceKey)
                contextPreparation = manager.registry.require(ContextPreparationServiceKey)
                postTurnWorker = manager.registry.require(PostTurnWorkerServiceKey)
                requestInspection = manager.registry.require(RequestInspectionServiceKey)
                updates = manager.registry.require(UpdateServiceKey)
                marketplace = manager.registry.require(MarketplaceServiceKey)
                roles = manager.registry.require(RoleServiceKey)
                skills = manager.registry.require(SkillLibraryServiceKey)
                modelProviders = manager.registry.require(ModelProvidersServiceKey)
                modelCatalog = manager.registry.require(ModelCatalogServiceKey)
                attachments = manager.registry.require(ChatAttachmentStoreKey)
                documents = manager.registry.require(DocumentTextServiceKey)
                workspaces = manager.registry.require(WorkspaceServiceKey)
                workspaceGit = manager.registry.require(WorkspaceGitServiceKey)
                systemExecution = manager.registry.require(SystemExecutionServiceKey)
                terminals = manager.registry.require(TerminalServiceKey)
                linuxEnvironment = manager.registry.require(LinuxEnvironmentServiceKey)
                pluginDrafts = manager.registry.require(PluginDraftServiceKey)
                marketPlugin.service.restore()
                postTurnWorker.start()
                val chat = manager.registry.require(Services.Chat)
                // Keep initialization in the background, publish the UI transition on Main.
                // Otherwise a resumed test frame can initialize Android lazy prefetch on
                // the publishing worker, which has no Choreographer/Looper.
                withContext(Dispatchers.Main.immediate) { mutableReady.value = Result.success(chat) }
            } catch (e: Exception) {
                try { manager.shutdown() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                withContext(Dispatchers.Main.immediate) { mutableReady.value = Result.failure(e) }
            }
        }
    }
}
