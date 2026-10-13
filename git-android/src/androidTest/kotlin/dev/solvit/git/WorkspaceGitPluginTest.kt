package dev.solvit.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.*
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceGitPluginTest {
    @Test fun registryLifecycleRevokesRetainedSessionsAndReenableUsesTheSameFiles() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val native = AndroidWorkspaces(InstrumentationRegistry.getInstrumentation().targetContext, scope)
        val manager = PluginManager(scope = scope)
        val chat = "git-plugin-${UUID.randomUUID()}"
        val workspace = native.forChat(chat)
        val root = File(native.location(workspace.id))
        suspend fun rejected(block: suspend () -> Unit) {
            try { block(); fail("Expected revoked Git capability") } catch (_: IllegalStateException) { }
        }
        try {
            manager.load(WorkspaceGitPlugin()) // Dependency order is resolved by the manager.
            manager.load(object : SolvitPlugin {
                override val id = "fixture.workspace"
                override val provides = setOf(WorkspaceServiceKey)
                override suspend fun enable(context: PluginContext) {
                    context.register(WorkspaceServiceKey, native)
                }
            })
            manager.enableAll()
            val service = manager.registry.require(WorkspaceGitServiceKey)
            assertEquals("builtin.workspace-git", manager.registry.owner(WorkspaceGitServiceKey))
            var writable = false
            var readable = true
            val session = service.bind(chat, { chat }, { check(readable) }, { check(writable) })
            File(root, "selected.txt").writeText("shared service 中文\n")
            rejected { session.initialize() }
            assertFalse(File(root, ".git").exists())
            writable = true
            session.initialize()
            readable = false
            rejected { session.status() }
            readable = true
            val revision = session.stage(session.diff("selected.txt", false))
            val commit = session.commit(revision, "Plugin commit", "Fixture", "test@example.invalid")
            session.close()
            rejected { session.history() }
            val retained = service.bind(chat, { chat }, {}, {})
            assertEquals(commit.id, retained.history().single().id)
            manager.disable("builtin.workspace-git")
            assertFalse(manager.registry.contains(WorkspaceGitServiceKey))
            rejected { retained.status() }
            rejected { service.bind(chat, { chat }, {}, {}) }
            manager.enableAll()
            val freshService = manager.registry.require(WorkspaceGitServiceKey)
            assertNotSame(service, freshService)
            val fresh = freshService.bind(chat, { chat }, {}, {})
            assertEquals(commit.id, fresh.history().single().id)
            assertEquals("shared service 中文\n", File(root, "selected.txt").readText())
            manager.shutdown()
            rejected { fresh.status() }
            rejected { freshService.bind(chat, { chat }, {}, {}) }
        } finally {
            manager.shutdown(); native.unbind(chat); native.close(); scope.cancel(); root.deleteRecursively()
        }
    }
}
