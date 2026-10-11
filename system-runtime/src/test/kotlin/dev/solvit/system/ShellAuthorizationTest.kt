package dev.solvit.system

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ShellAuthorizationTest {
    private val descriptor = ToolDescriptor("run", "Run", "{}", pluginId = "test.shell", capabilities = setOf("shell"), executionMode = ExecutionMode.APP)
    private val call = ToolCall("one", "run", """{"command":"printf safe","cwd":"folder","timeoutMs":5000}""")
    @Test fun nativeStartRequiresApprovedExactCommandAndCanOnlyStartOnce() = runBlocking<Unit> {
        assertFailsWith<IllegalStateException> { ShellAuthorization.start("test.shell", ShellRequest("printf safe", "folder", 5000), ExecutionMode.APP) }
        withContext(ToolInvocationContext(descriptor, call) + AgentRoleContext(null)) {
            assertFailsWith<IllegalStateException> { ShellAuthorization.start("test.shell", ShellRequest("printf changed", "folder", 5000), ExecutionMode.APP) }
            assertFailsWith<IllegalStateException> { ShellAuthorization.start("test.shell", ShellRequest("printf safe", "other", 5000), ExecutionMode.APP) }
            assertFailsWith<IllegalStateException> { ShellAuthorization.start("test.shell", ShellRequest("printf safe", "folder", 6000), ExecutionMode.APP) }
            assertFailsWith<IllegalStateException> { ShellAuthorization.start("test.shell", ShellRequest("printf safe", "folder", 5000), ExecutionMode.ROOT) }
            ShellAuthorization.start("test.shell", ShellRequest("printf safe", "folder", 5000), ExecutionMode.APP)
            assertFailsWith<IllegalStateException> { ShellAuthorization.start("test.shell", ShellRequest("printf safe", "folder", 5000), ExecutionMode.APP) }
        }
    }
    @Test fun pluginAndRoleCannotAccessAnotherApprovedTask() = runBlocking<Unit> {
        withContext(ToolInvocationContext(descriptor, ToolCall("read", "run", """{"id":"job","after":4}""")) + AgentRoleContext(null)) {
            ShellAuthorization.job("test.shell", "job", 4, ExecutionMode.APP)
            assertFailsWith<IllegalStateException> { ShellAuthorization.job("other.shell", "job", 4, ExecutionMode.APP) }
            assertFailsWith<IllegalStateException> { ShellAuthorization.job("test.shell", "other-job", 4, ExecutionMode.APP) }
            assertFailsWith<IllegalStateException> { ShellAuthorization.job("test.shell", "job", 5, ExecutionMode.APP) }
        }
        withContext(ToolInvocationContext(descriptor, call) + AgentRoleContext(AgentRole("none", "No plugins"))) {
            assertFailsWith<IllegalStateException> { ShellAuthorization.start("test.shell", ShellRequest("printf safe", "folder", 5000), ExecutionMode.APP) }
        }
    }
    @Test fun invalidTimeoutAndUnknownFieldsCannotReachLauncher() {
        for (text in listOf("""{"command":"id","mode":"root"}""", """{"command":"id","timeoutMs":"60000"}""", """{"command":"id","timeoutMs":0}""")) {
            assertFailsWith<IllegalArgumentException> { ShellAuthorization.parse(Json.parseToJsonElement(text).jsonObject) }
        }
    }
}
