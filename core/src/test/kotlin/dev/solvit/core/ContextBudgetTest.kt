package dev.solvit.core

import kotlin.test.*

class ContextBudgetTest {
    private val meter = Utf8ContextTokenMeter()
    private val planner = BalancedContextBudgetPlanner(meter)
    private val policy = ContextBudgetPolicy(4_096, 512, 128)
    @Test fun accountsForToolsNativeReasoningUnicodeAndOutputReservation() {
        val plain = ModelMessage(Role.ASSISTANT, "a")
        val native = plain.copy(providerTranscript = ProviderTranscript("anthropic", "test", "x".repeat(2_000)))
        assertTrue(meter.messageTokens(native) > meter.messageTokens(plain))
        assertEquals(6L, meter.textTokens("中文"))
        val request = ModelRequest(ModelConfig(), listOf(ModelMessage(Role.USER, "question")),
            listOf(ToolDescriptor("tool", "d".repeat(4_000), "{}")))
        val measured = meter.measure(request, policy)
        assertFalse(measured.fits); assertTrue(measured.toolTokens >= 4_000)
        assertEquals(3_456, measured.inputBudget)
    }
    @Test fun keepsIdentityCurrentTaskAndCompleteToolPairs() {
        val messages = listOf(ModelMessage(Role.SYSTEM, "You are a programmer"),
            ModelMessage(Role.USER, "old task"), ModelMessage(Role.ASSISTANT, "x".repeat(4_000), listOf(ToolCall("c", "run", "{}"))),
            ModelMessage(Role.TOOL, "failure", toolCallId = "c"), ModelMessage(Role.ASSISTANT, "fixed"),
            ModelMessage(Role.USER, "current unfinished task"), ModelMessage(Role.ASSISTANT, "", listOf(ToolCall("now", "run", "{}"))),
            ModelMessage(Role.TOOL, "current output", toolCallId = "now"))
        val region = assertNotNull(planner.plan(ModelRequest(ModelConfig(), messages), policy).region)
        assertEquals(1, region.start); assertEquals(5, region.endExclusive)
        assertEquals(Role.USER, messages[region.endExclusive].role)
    }
    @Test fun refusesUnbalancedOrIndivisibleHistoryInsteadOfDiscardingCurrentTask() {
        val messages = listOf(ModelMessage(Role.USER, "current"), ModelMessage(Role.ASSISTANT, "x".repeat(5_000), listOf(ToolCall("c", "run", "{}"))),
            ModelMessage(Role.TOOL, "output", toolCallId = "c"))
        val plan = planner.plan(ModelRequest(ModelConfig(), messages), policy)
        assertFalse(plan.measurement.fits); assertNull(plan.region)
        val broken = listOf(ModelMessage(Role.USER, "old"), ModelMessage(Role.ASSISTANT, "x".repeat(5_000), listOf(ToolCall("c", "run", "{}"))),
            ModelMessage(Role.USER, "current"))
        assertNull(planner.plan(ModelRequest(ModelConfig(), broken), policy).region)
    }
    @Test fun validatesReservationAndDoesNotCompactWhenDisabledOrUnderBudget() {
        assertFailsWith<IllegalArgumentException> { ContextBudgetPolicy(1_024, 1_024, 0).validate() }
        val request = ModelRequest(ModelConfig(), listOf(ModelMessage(Role.USER, "old"), ModelMessage(Role.ASSISTANT, "x".repeat(5_000)), ModelMessage(Role.USER, "current")))
        assertNull(planner.plan(request, policy.copy(automaticSummary = false)).region)
        assertNull(planner.plan(request.copy(messages = listOf(ModelMessage(Role.USER, "small"))), policy).region)
    }
}
