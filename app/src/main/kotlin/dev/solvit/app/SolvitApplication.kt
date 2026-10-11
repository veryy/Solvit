package dev.solvit.app

import android.app.Application
import dev.solvit.chat.*
import dev.solvit.core.*
import dev.solvit.provider.OpenAiPlugin
import dev.solvit.storage.SqliteStoragePlugin
import dev.solvit.updates.ReleaseUpdatesPlugin
import dev.solvit.market.MarketplacePlugin
import dev.solvit.market.RolesPlugin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SolvitApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableReady = MutableStateFlow<Result<ChatService>?>(null)
    val ready = mutableReady.asStateFlow()
    lateinit var settings: SettingsStore
        private set
    lateinit var updates: UpdateService
        private set
    lateinit var marketplace: MarketplaceService
        private set
    lateinit var roles: RoleService
        private set
    override fun onCreate() {
        super.onCreate()
        settings = EncryptedSettingsStore(this)
        scope.launch {
            val manager = PluginManager(scope = scope)
            val marketPlugin = MarketplacePlugin(this@SolvitApplication, manager)
            try {
                // Deliberately unordered: dependency declarations control activation.
                listOf(ChatServicePlugin(), ChatAgentPlugin(), ToolsPlugin(), ContextHooksPlugin(), RolesPlugin(this@SolvitApplication),
                    OpenAiPlugin(), SqliteStoragePlugin(this@SolvitApplication), ReleaseUpdatesPlugin(), marketPlugin).forEach { manager.load(it) }
                manager.enableAll()
                updates = manager.registry.require(UpdateServiceKey)
                marketplace = manager.registry.require(MarketplaceServiceKey)
                roles = manager.registry.require(RoleServiceKey)
                marketPlugin.service.restore()
                mutableReady.value = Result.success(manager.registry.require(Services.Chat))
            } catch (e: Exception) {
                try { manager.shutdown() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                mutableReady.value = Result.failure(e)
            }
        }
    }
}
