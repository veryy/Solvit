package dev.solvit.core

/** Observed execution facts, separate from model context. Interrupted work is never replayed. */
enum class ExecutionStatus { RUNNING, COMPLETE, CANCELLED, FAILED, PROCESS_INTERRUPTED }
data class ExecutionRun(val id: String, val sessionId: String, val userMessageId: String,
    val roleId: String?, val workspaceId: String?, val startedAt: Long, val updatedAt: Long = startedAt,
    val status: ExecutionStatus = ExecutionStatus.RUNNING) {
    fun validate() {
        require(listOf(id, sessionId, userMessageId).all { it.isNotBlank() && it.length <= 128 })
        require(listOfNotNull(roleId, workspaceId).all { it.isNotBlank() && it.length <= 128 })
        require(startedAt >= 0 && updatedAt >= startedAt)
    }
}
enum class ExecutionKind { STEP_STARTED, TOOL_PROPOSED, TOOL_STARTED, TOOL_FINISHED, STEP_FINISHED }
data class ExecutionEvent(val step: Int, val kind: ExecutionKind, val call: ToolCall? = null,
    val pluginId: String? = null, val callId: String? = null, val stage: ToolStage? = null,
    val result: String? = null, val truncated: Boolean = false) {
    fun validate() {
        require(step in 1..64)
        when (kind) {
            ExecutionKind.STEP_STARTED, ExecutionKind.STEP_FINISHED ->
                require(call == null && pluginId == null && callId == null && stage == null && result == null && !truncated)
            ExecutionKind.TOOL_PROPOSED -> {
                require(call != null && call.id.isNotBlank() && call.id.length <= 128)
                require(call.name.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
                require(call.argumentsJson.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
                require(pluginId != null && pluginId.isNotBlank() && pluginId.length <= 128)
                require(callId == null && stage == null && result == null)
            }
            ExecutionKind.TOOL_STARTED -> {
                require(callId != null && callId.isNotBlank() && callId.length <= 128)
                require(call == null && pluginId == null && stage == null && result == null && !truncated)
            }
            ExecutionKind.TOOL_FINISHED -> {
                require(callId != null && callId.isNotBlank() && callId.length <= 128)
                require(call == null && pluginId == null && stage in setOf(ToolStage.SUCCEEDED, ToolStage.FAILED, ToolStage.DENIED))
                require(result != null && result.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
            }
        }
    }
}
data class RecordedExecutionEvent(val sequence: Int, val recordedAt: Long, val event: ExecutionEvent)

/** Enforces ordered boundaries and exact call identities for durable writers and readers. */
class ExecutionJournalState {
    private var step = 0
    private var open = false
    private var dispatched = false
    private val calls = mutableMapOf<String, Pair<Int, ExecutionKind>>()
    val stepOpen get() = open
    fun accept(event: ExecutionEvent) {
        event.validate()
        when (event.kind) {
            ExecutionKind.STEP_STARTED -> {
                require(!open && event.step == step + 1); step = event.step; open = true; dispatched = false
            }
            ExecutionKind.STEP_FINISHED -> {
                require(open && event.step == step && calls.values.none { it.first == step && it.second != ExecutionKind.TOOL_FINISHED })
                open = false
            }
            ExecutionKind.TOOL_PROPOSED -> {
                require(open && event.step == step && !dispatched && calls.size < 64)
                val id = requireNotNull(event.call).id
                require(id !in calls); calls[id] = step to event.kind
            }
            ExecutionKind.TOOL_STARTED -> {
                require(open && event.step == step && calls[event.callId] == (step to ExecutionKind.TOOL_PROPOSED))
                dispatched = true; calls[requireNotNull(event.callId)] = step to event.kind
            }
            ExecutionKind.TOOL_FINISHED -> {
                val prior = requireNotNull(calls[event.callId])
                require(open && event.step == step && prior.first == step &&
                    (prior.second == ExecutionKind.TOOL_STARTED || prior.second == ExecutionKind.TOOL_PROPOSED))
                // Denials must precede dispatch; success must follow the recorded dispatch boundary.
                require(if (event.stage == ToolStage.DENIED) prior.second == ExecutionKind.TOOL_PROPOSED
                    else event.stage != ToolStage.SUCCEEDED || prior.second == ExecutionKind.TOOL_STARTED)
                dispatched = true; calls[requireNotNull(event.callId)] = step to event.kind
            }
        }
    }
}
fun interface ExecutionJournal {
    /** A batch must become durable before returning, or fail before any following side effect. */
    suspend fun append(events: List<ExecutionEvent>)
}
val NoExecutionJournal = ExecutionJournal { }
class ExecutionJournalException(cause: Throwable? = null) : IllegalStateException("无法保存执行记录，已停止后续操作", cause)
