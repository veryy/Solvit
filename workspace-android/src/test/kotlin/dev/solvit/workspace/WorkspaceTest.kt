package dev.solvit.workspace

import dev.solvit.core.*
import dev.solvit.system.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkspaceTest {
    @Test fun conversationHasOnePersistentBindingAndWorkspaceHasManyShells() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = AndroidWorkspaces(RuntimeEnvironment.getApplication(), scope)
        try {
            val first = service.forChat("chat-a")
            assertEquals(first, service.forChat("chat-a"))
            service.bind("chat-b", first.id)
            val second = service.create("Project B")
            service.bind("chat-a", second.id)
            service.createShell(first.id, "Shell 2")
            service.write(first.id, "src/example.js", "hello")
            val restored = AndroidWorkspaces(RuntimeEnvironment.getApplication(), scope)
            try {
                assertEquals(second, restored.forChat("chat-a"))
                assertEquals(first, restored.forChat("chat-b"))
                assertEquals(2, restored.state.value.shells.count { it.workspaceId == first.id })
                assertEquals("hello", restored.read(first.id, "src/example.js"))
                restored.unbind("chat-b")
                assertEquals("hello", restored.read(first.id, "src/example.js"))
            } finally { restored.close() }
        } finally { service.close(); scope.cancel() }
    }
    @Test fun filesCannotEscapeWorkspaceOrReadAnotherWorkspace() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = AndroidWorkspaces(RuntimeEnvironment.getApplication(), scope)
        try {
            val a = service.forChat("a"); val b = service.forChat("b")
            service.write(a.id, "private.txt", "a-data")
            assertFailsWith<IllegalArgumentException> { service.read(b.id, "../${a.id}/private.txt") }
            assertFailsWith<IllegalArgumentException> { service.write(a.id, "../../settings.xml", "bad") }
            assertFailsWith<IllegalArgumentException> { service.read(a.id, "/etc/passwd") }
            assertFailsWith<IllegalArgumentException> { service.write(a.id, "large", "x".repeat(256 * 1024 + 1)) }
        } finally { service.close(); scope.cancel() }
    }
    @Test fun realProcessesBelongToTheirWorkspaceAndNamedShell() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = AndroidWorkspaces(RuntimeEnvironment.getApplication(), scope, ShellLauncher(ShellPaths("/bin/sh", "/usr/bin/setsid")))
        try {
            val a = service.forChat("a"); val b = service.forChat("b")
            val shells = service.state.value.shells.filter { it.workspaceId == a.id }
            val shell2 = service.createShell(a.id, "Shell 2")
            assertFailsWith<IllegalStateException> { service.run(b.id, shell2.id, "echo bad") }
            val one = service.run(a.id, shells.single().id, "printf one")
            val two = service.run(a.id, shell2.id, "printf two")
            val jobs = withTimeout(5000) { service.jobs.first { list -> list.count { it.stage == ShellStage.COMPLETED } == 2 } }
            assertEquals(a.id, jobs.first { it.id == one.id }.workspaceId)
            assertEquals(shell2.id, jobs.first { it.id == two.id }.shellSessionId)
            assertEquals("one", jobs.first { it.id == one.id }.output.joinToString("") { it.text })
            assertEquals("two", jobs.first { it.id == two.id }.output.joinToString("") { it.text })
        } finally { service.close(); scope.cancel() }
    }
}
