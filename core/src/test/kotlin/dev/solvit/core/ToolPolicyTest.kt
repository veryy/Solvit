package dev.solvit.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ToolPolicyTest {
    private class Policies : ToolPolicyService {
        override val policies = MutableStateFlow<Map<String, ToolPolicy>>(emptyMap())
        override suspend fun set(tool: ToolDescriptor, policy: ToolPolicy) { policies.value = policies.value + (toolPolicyKey(tool) to policy) }
    }
    @Test fun policiesCannotBypassRolesAndExecutionReceivesExactApprovedCall() = runTest {
        val policies = Policies()
        val registry = ToolRegistry(EventBus(), policies)
        val descriptor = ToolDescriptor("exec", "Execute", "{}", pluginId = "test.shell", capabilities = setOf("shell"))
        var executions = 0
        val call = ToolCall("one", "exec", "{\"command\":\"id\"}")
        registry.register(object : SolvitTool {
            override val descriptor = descriptor
            override suspend fun execute(argumentsJson: String): String {
                assertEquals(call, currentCoroutineContext()[ToolInvocationContext]?.call)
                executions++; return "ok"
            }
        })
        policies.set(descriptor, ToolPolicy.ALLOW)
        assertEquals("ok", registry.executeCall(call, DenyTools))
        assertFailsWith<ToolDeniedException> { registry.executeCall(call, DenyTools, AgentRole("empty", "Empty")) }
        policies.set(descriptor, ToolPolicy.DENY)
        assertFailsWith<ToolDeniedException> { registry.executeCall(call, object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }) }
        assertEquals(1, executions)
        assertEquals(ToolPolicy.ASK, policies.policy(descriptor.copy(pluginVersion = "2.0.0")))
    }
    @Test fun denialWhileApprovalIsPendingPreventsExecution() = runTest {
        val policies = Policies()
        val registry = ToolRegistry(EventBus(), policies)
        val descriptor = ToolDescriptor("exec", "Execute", "{}")
        registry.register(object : SolvitTool {
            override val descriptor = descriptor
            override suspend fun execute(argumentsJson: String): String = fail("Revoked tool must not execute")
        })
        val pending = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Boolean>()
        val job = async {
            assertFailsWith<ToolDeniedException> {
                registry.executeCall(ToolCall("one", "exec", "{}"), object : ToolAuthorization {
                    override suspend fun authorize(descriptor: ToolDescriptor): Boolean { pending.complete(Unit); return reply.await() }
                })
            }
        }
        pending.await(); policies.set(descriptor, ToolPolicy.DENY); reply.complete(true); job.await()
    }
}
