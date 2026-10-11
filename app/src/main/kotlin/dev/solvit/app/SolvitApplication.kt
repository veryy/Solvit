package dev.solvit.app

import android.app.Application
import dev.solvit.chat.*
import dev.solvit.core.*
import dev.solvit.provider.OpenAiPlugin
import dev.solvit.storage.SqliteStoragePlugin
import dev.solvit.updates.ReleaseUpdatesPlugin
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
    override fun onCreate() {
        super.onCreate()
        settings = EncryptedSettingsStore(this)
        scope.launch {
            val manager = PluginManager(scope = scope)
            try {
                // Deliberately unordered: dependency declarations control activation.
                listOf(ChatServicePlugin(), ChatAgentPlugin(), ToolsPlugin(),
                    OpenAiPlugin(), SqliteStoragePlugin(this@SolvitApplication), ReleaseUpdatesPlugin()).forEach { manager.load(it) }
                manager.enableAll()
                updates = manager.registry.require(UpdateServiceKey)
                mutableReady.value = Result.success(manager.registry.require(Services.Chat))
            } catch (e: Exception) {
                try { manager.shutdown() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                mutableReady.value = Result.failure(e)
            }
        }
    }
}
