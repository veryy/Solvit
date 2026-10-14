package dev.solvit.git

import android.content.Context
import dev.solvit.core.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Owns bound native sessions, including adapters retained after plugin disable. */
class AndroidWorkspaceGit(private val workspaces: WorkspaceService, context: Context? = null) : WorkspaceRemoteGitService {
    private val credentials = context?.let { GitCredentialVault(it.applicationContext) }
    private val lock = Any()
    private var closed = false
    private val sessions = mutableSetOf<BoundWorkspaceGit>()

    private fun verifyOpen() = synchronized(lock) {
        check(!closed) { "Git服务已停用" }
    }

    override fun bind(chatId: String, activeChat: () -> String?,
        authorizeRead: suspend () -> Unit, authorizeWrite: suspend () -> Unit): WorkspaceGitSession =
        bindRemote(chatId, activeChat, authorizeRead, authorizeWrite, { error("Git网络访问尚未授权") })

    override fun bindRemote(chatId: String, activeChat: () -> String?,
        authorizeRead: suspend () -> Unit, authorizeWrite: suspend () -> Unit,
        authorizeNetwork: suspend (GitRemoteEndpoint) -> Unit): WorkspaceRemoteGitSession = synchronized(lock) {
        verifyOpen()
        lateinit var session: BoundWorkspaceGit
        session = BoundWorkspaceGit(workspaces, chatId, activeChat,
            authorizeWrite = { verifyOpen(); authorizeWrite(); verifyOpen() },
            onClose = { synchronized(lock) { sessions.remove(session) }; Unit },
            authorizeNetwork = { endpoint -> verifyOpen(); authorizeNetwork(endpoint); verifyOpen() },
            authorize = { verifyOpen(); authorizeRead(); verifyOpen() })
        session.borrowCredentials = { endpoint ->
            verifyOpen()
            credentials?.let { if (it.configured(endpoint)) it.borrow(endpoint) else null }
        }
        sessions += session
        session
    }

    override suspend fun close() = withContext(NonCancellable) {
        val owned = synchronized(lock) {
            closed = true
            sessions.toList().also { sessions.clear() }
        }
        // Never hold the service lock while waiting for an operation's session mutex.
        owned.forEach { it.close() }
    }
}

/** Business UI and AI tools remain JS plugins; Kotlin supplies the native primitive. */
class WorkspaceGitPlugin(private val androidContext: Context? = null) : SolvitPlugin {
    override val id = "builtin.workspace-git"
    override val requires = setOf(WorkspaceServiceKey)
    override val provides = setOf(WorkspaceGitServiceKey)
    override suspend fun enable(context: PluginContext) {
        val service = AndroidWorkspaceGit(context.require(WorkspaceServiceKey), androidContext)
        context.own { service.close() }
        context.register(WorkspaceGitServiceKey, service)
    }
}
