package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Bounded orchestration; native and JS tools share the same authorization gate. */
class ToolAgentRuntime(
    private val provider: ModelProvider,
    private val tools: ToolRegistry,
    private val maxRounds: Int = 8,
    private val maxCalls: Int = 16,
    private val hooks: ContextHookRegistry? = null,
) : AgentRuntime {
    init { require(maxRounds > 0 && maxCalls > 0) }
    override fun run(request: ModelRequest): Flow<ModelChunk> = flow {
        val advertised = tools.descriptors().filter { it.chatVisible && request.agentRole?.allows(it.pluginId) != false }.take(64)
        val allowed = advertised.map { it.name }.toSet()
        val messages = request.messages.toMutableList()
        if (advertised.isNotEmpty()) messages.add(0, ModelMessage(Role.SYSTEM,
            "你可以调用本轮提供的插件工具。涉及实时信息或需要外部数据时，请使用相应工具或插件上下文；不要仅凭训练知识猜测。" +
                "工具调用会由用户确认，结果返回后再回答。如果没有可用工具，请说明限制。"))
        val context = hooks?.prepare(ContextInput(request.agentRole, messages.lastOrNull { it.role == Role.USER }?.content.orEmpty())).orEmpty()
        if (context.isNotEmpty()) {
            // System context belongs before the transcript and persists through tool rounds.
            messages.add(0, ModelMessage(Role.SYSTEM, context.joinToString("\n\n")))
        }
        val seen = mutableSetOf<String>()
        var executions = 0
        for (round in 0 until maxRounds) {
            val text = StringBuilder()
            var calls = emptyList<ToolCall>()
            var completed = false
            provider.stream(request.copy(messages = messages.toList(), tools = advertised)).collect { chunk ->
                check(!completed) { "Provider emitted after completion" }
                when (chunk) {
                    is ModelChunk.Text -> { text.append(chunk.value); emit(chunk) }
                    is ModelChunk.ToolCalls -> {
                        check(calls.isEmpty()) { "Duplicate tool call batch" }
                        calls = chunk.calls
                    }
                    ModelChunk.Done -> completed = true
                    else -> error("Provider emitted an Agent event")
                }
            }
            check(completed) { "模型响应提前结束" }
            if (calls.isEmpty()) { emit(ModelChunk.Done); return@flow }
            if (round == maxRounds - 1 || executions + calls.size > maxCalls) throw ModelException("已达到工具执行上限，请继续对话")
            if (calls.any { it.name !in allowed || !seen.add(it.id) }) throw ModelException("模型调用了未提供或重复的工具")
            val assistant = ModelMessage(Role.ASSISTANT, text.toString(), calls)
            messages += assistant
            emit(ModelChunk.Transcript(assistant))
            for (call in calls) {
                executions++
                emit(ModelChunk.ToolActivity(call, ToolStage.CONFIRMING))
                val gate = object : ToolAuthorization {
                    override suspend fun authorize(descriptor: ToolDescriptor) = false
                    override suspend fun authorizeCall(descriptor: ToolDescriptor, actual: ToolCall): Boolean {
                        return request.authorization.authorizeCall(descriptor, actual)
                    }
                }
                val outcome = try {
                    val result = tools.executeCall(call, gate, request.agentRole) { emit(ModelChunk.ToolActivity(call, ToolStage.RUNNING)) }
                    check(result.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "工具返回结果超出限制" }
                    ToolStage.SUCCEEDED to result
                } catch (e: ToolDeniedException) {
                    ToolStage.DENIED to "用户拒绝执行此工具。"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    // Exceptions may contain host credentials or remote diagnostics.
                    ToolStage.FAILED to "工具执行失败，请检查插件状态、权限和参数。"
                }
                emit(ModelChunk.ToolActivity(call, outcome.first, outcome.second))
                val result = ModelMessage(Role.TOOL, outcome.second, toolCallId = call.id)
                messages += result
                emit(ModelChunk.Transcript(result))
            }
        }
    }
}
