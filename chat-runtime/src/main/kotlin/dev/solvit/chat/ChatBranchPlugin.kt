package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Branches are snapshots: deleting or continuing the original cannot mutate the copy. */
class DefaultChatBranchService(private val store: ChatStore, private val roles: RoleService? = null,
    private val workspaces: WorkspaceService? = null) : ChatBranchService {
    private val lock = Mutex()
    override suspend fun fork(sessionId: String, point: BranchPoint): ChatSession = lock.withLock {
        val role = roles?.selected(sessionId)
        val workspace = workspaces?.bound(sessionId)
        // Complete the local commit/bind or rollback even if the UI leaves mid-operation.
        withContext(NonCancellable) {
            val branch = store.forkSession(sessionId, point)
            try {
                roles?.select(branch.id, role?.id)
                if (workspace != null) workspaces?.bind(branch.id, workspace.id)
                branch
            } catch (e: Exception) {
                for (cleanup in listOf<suspend () -> Unit>(
                    { store.deleteSession(branch.id) }, { roles?.select(branch.id, null) }, { workspaces?.unbind(branch.id) })) {
                    try { cleanup() } catch (failure: Exception) { e.addSuppressed(failure) }
                }
                throw e
            }
        }
    }
}

class ChatBranchPlugin : SolvitPlugin {
    override val id = "builtin.chat.branches"
    override val requires = setOf(Services.Storage, RoleServiceKey, WorkspaceServiceKey)
    override val provides = setOf(ChatBranchServiceKey)
    override suspend fun enable(context: PluginContext) {
        context.register(ChatBranchServiceKey, DefaultChatBranchService(context.require(Services.Storage),
            context.require(RoleServiceKey), context.require(WorkspaceServiceKey)))
    }
}
