package dev.solvit.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Metadata only: never stores prompt text, endpoint, headers, keys or tool arguments. */
data class PromptSourceInfo(val name: String, val source: String, val characters: Int)
data class PreparedRequestInfo(val sessionId: String?, val roleName: String, val providerId: String,
    val model: String, val messageCount: Int, val systemCharacters: Int,
    val sources: List<PromptSourceInfo>, val tools: List<String>, val preparedAtMs: Long,
    val contextMeasurement: ContextMeasurement? = null, val contextProblems: List<ContextHookProblem> = emptyList())

class RequestInspectionService {
    private val mutable = MutableStateFlow<PreparedRequestInfo?>(null)
    val latest = mutable.asStateFlow()
    fun record(request: ModelRequest) {
        mutable.value = PreparedRequestInfo(request.sessionId, request.agentRole?.name ?: "Solvit",
            request.config.providerId, request.config.model, request.messages.size,
            request.messages.filter { it.role == Role.SYSTEM }.sumOf { it.content.length },
            request.promptSources.toList(), request.tools.map { it.name }, System.nanoTime() / 1_000_000, request.contextMeasurement,
            request.contextProblems.toList())
    }
}
val RequestInspectionServiceKey = ServiceKey("agent.request-inspection", RequestInspectionService::class)
class RequestInspectionPlugin : SolvitPlugin {
    override val id = "builtin.request-inspection"
    override val provides = setOf(RequestInspectionServiceKey)
    override suspend fun enable(context: PluginContext) { context.register(RequestInspectionServiceKey, RequestInspectionService()) }
}
