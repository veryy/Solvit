package dev.solvit.core

/** Capacity is configured by the user/adapter, never inferred from the model's name. */
data class ContextBudgetPolicy(
    val windowTokens: Int = 65_536,
    val outputTokens: Int = 8_192,
    val headroomTokens: Int = 2_048,
    val automaticSummary: Boolean = true,
) {
    fun validate() {
        require(windowTokens in 1_024..2_000_000) { "上下文窗口需为 1024 到 2000000" }
        require(outputTokens in 1..1_000_000) { "输出预留需为 1 到 1000000" }
        require(headroomTokens >= 0 && outputTokens.toLong() + headroomTokens < windowTokens) { "上下文窗口必须大于输出预留与压缩余量之和" }
    }
    val inputTokens: Int get() { validate(); return windowTokens - outputTokens - headroomTokens }
}

data class ContextMeasurement(
    val estimatedTokens: Long,
    val messageTokens: Long,
    val toolTokens: Long,
    val inputBudget: Int,
    val estimator: String,
) {
    val fits: Boolean get() = estimatedTokens <= inputBudget
}

interface ContextTokenMeter {
    val id: String
    fun textTokens(text: String): Long
    fun messageTokens(message: ModelMessage): Long
    fun measure(request: ModelRequest, policy: ContextBudgetPolicy): ContextMeasurement
}

/** Portable conservative byte estimate, not Provider usage or a model-specific tokenizer. */
class Utf8ContextTokenMeter : ContextTokenMeter {
    override val id = "utf8-conservative-estimate"
    override fun textTokens(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()
    override fun messageTokens(message: ModelMessage): Long {
        // Native content may contain signed reasoning that canonical text does not represent.
        val canonical = textTokens(message.content) + textTokens(message.reasoning) +
            message.toolCalls.sumOf { textTokens(it.id) + textTokens(it.name) + textTokens(it.argumentsJson) + 32 } +
            textTokens(message.toolCallId.orEmpty())
        val native = message.providerTranscript?.let { textTokens(it.blocksJson) } ?: 0L
        return maxOf(canonical, native) + 32
    }
    override fun measure(request: ModelRequest, policy: ContextBudgetPolicy): ContextMeasurement {
        val messages = request.messages.sumOf(::messageTokens)
        val tools = request.tools.sumOf { textTokens(it.name) + textTokens(it.description) + textTokens(it.inputSchema) + 64 }
        return ContextMeasurement(messages + tools + 128, messages, tools, policy.inputTokens, id)
    }
}

/** A balanced prefix selected for summarization. Original messages are never deleted. */
data class ContextCompactionRegion(val start: Int, val endExclusive: Int, val estimatedTokens: Long)
data class ContextBudgetPlan(val measurement: ContextMeasurement, val region: ContextCompactionRegion?)
interface ContextBudgetPlanner {
    fun plan(request: ModelRequest, policy: ContextBudgetPolicy): ContextBudgetPlan
}
val ContextBudgetPlannerServiceKey = ServiceKey("context.budget-planner", ContextBudgetPlanner::class)

/** Replacement service seam for native or future JS context strategies. */
class ContextBudgetPlugin : SolvitPlugin {
    override val id = "builtin.context.budget"
    override val provides = setOf(ContextBudgetPlannerServiceKey)
    override suspend fun enable(context: PluginContext) {
        context.register(ContextBudgetPlannerServiceKey, BalancedContextBudgetPlanner())
    }
}

class BalancedContextBudgetPlanner(private val meter: ContextTokenMeter = Utf8ContextTokenMeter()) : ContextBudgetPlanner {
    override fun plan(request: ModelRequest, policy: ContextBudgetPolicy): ContextBudgetPlan {
        val measurement = meter.measure(request, policy)
        if (measurement.fits || !policy.automaticSummary) return ContextBudgetPlan(measurement, null)
        val messages = request.messages
        // Keep the complete current user turn (including all of its tool groups) verbatim.
        val lastUser = messages.indexOfLast { it.role == Role.USER }
        if (lastUser <= 0) return ContextBudgetPlan(measurement, null)
        val start = messages.indexOfFirst { it.role != Role.SYSTEM }
        if (start < 0 || start >= lastUser) return ContextBudgetPlan(measurement, null)
        if (messages.drop(start).any { it.role == Role.SYSTEM })
            throw ModelException("系统规则必须独立于可压缩历史")
        // Prefer keeping a recent tail; grow the selected prefix until pressure is relieved.
        val recentBudget = maxOf(256L, policy.inputTokens.toLong() / 4)
        var kept = messages.drop(lastUser).sumOf(meter::messageTokens)
        var cut = lastUser
        while (cut > start && kept < recentBudget) {
            kept += meter.messageTokens(messages[cut - 1]); cut--
        }
        // Only whole closed user turns are admissible. Never split call/result groups.
        val boundaries = (start + 1..lastUser).filter { messages[it].role == Role.USER && balanced(messages, start, it) }
        if (boundaries.isEmpty()) return ContextBudgetPlan(measurement, null)
        val initial = boundaries.lastOrNull { it <= cut } ?: boundaries.first()
        var selected = initial
        // Leave room for a bounded summary, even when the recent tail alone is too large.
        val summaryReserve = minOf(2_048L, policy.inputTokens.toLong() / 8)
        while (selected < lastUser && measurement.estimatedTokens -
            messages.subList(start, selected).sumOf(meter::messageTokens) + summaryReserve > policy.inputTokens) {
            selected = boundaries.firstOrNull { it > selected } ?: break
        }
        val region = ContextCompactionRegion(start, selected, messages.subList(start, selected).sumOf(meter::messageTokens))
        return ContextBudgetPlan(measurement, region)
    }

    private fun balanced(messages: List<ModelMessage>, start: Int, end: Int): Boolean {
        val pending = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        for (message in messages.subList(start, end)) {
            when (message.role) {
                Role.ASSISTANT -> {
                    if (pending.isNotEmpty()) return false
                    for (call in message.toolCalls) {
                        if (!seen.add(call.id)) return false
                        pending += call.id
                    }
                }
                Role.TOOL -> {
                    val id = message.toolCallId ?: return false
                    if (!pending.remove(id)) return false
                }
                Role.USER, Role.SYSTEM -> if (pending.isNotEmpty()) return false
            }
        }
        return pending.isEmpty()
    }
}
