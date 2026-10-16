package dev.solvit.app

import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The real workspace form must remain usable while the service is pending or fails. */
class WorkspaceFormDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/solvit-ui")).use { it.readBytes() }
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /data/local/tmp/solvit-ui/$name.png")).use { it.readBytes() }
    }
    private fun app(): SolvitApplication = (rule.activity.application as SolvitApplication).also { app ->
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
    }
    @Test fun failedCreationKeepsNameAndAllowsRetryAfterShowingPendingState() {
        val app = app()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val service = object : WorkspaceService by app.workspaces {
            override suspend fun create(name: String): Workspace {
                calls.incrementAndGet(); release.await(); error("Fixture creation unavailable")
            }
        }
        val vm = WorkspaceViewModel(service, app.pluginDrafts, app.terminals)
        try {
            rule.runOnUiThread {
                vm.selectChat("workspace-create-form-fixture")
                rule.activity.setContent { MaterialTheme { WorkspaceDialog(vm, app.systemExecution, createOnOpen = true) {} } }
            }
            rule.onNodeWithTag("workspace_create_name").performTextReplacement("Project to keep")
            rule.onNodeWithText("创建并绑定").performClick()
            rule.waitUntil(10_000) { calls.get() == 1 }
            rule.onNodeWithTag("workspace_create_name").assertIsDisplayed().assert(hasText("Project to keep"))
            rule.onNodeWithText("创建并绑定").assertIsNotEnabled()
            screenshot("workspace-create-pending")
            release.complete(Unit)
            rule.waitUntil(10_000) { !vm.busy.value && vm.error.value != null }
            rule.onNodeWithTag("workspace_create_name").assertIsDisplayed().assert(hasText("Project to keep"))
            rule.onNodeWithText("Fixture creation unavailable").assertIsDisplayed()
            rule.onNodeWithText("创建并绑定").assertIsEnabled().performClick()
            rule.waitUntil(10_000) { calls.get() == 2 && !vm.busy.value }
            assertNull(vm.workspaceId.value)
            screenshot("workspace-create-failure")
        } catch (failure: Throwable) {
            runCatching { screenshot("workspace-create-form-failure") }; throw failure
        } finally { release.complete(Unit); rule.runOnUiThread { vm.viewModelScope.cancel() } }
    }
    @Test fun failedBindingKeepsChooserAndReenablesTheSelectedWorkspace() {
        val app = app()
        val workspace = Workspace("workspace-bind-form-fixture", "Fixture directory")
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val service = object : WorkspaceService by app.workspaces {
            override val state = MutableStateFlow(WorkspaceState(workspaces = listOf(workspace)))
            override suspend fun bind(sessionId: String, workspaceId: String) {
                calls.incrementAndGet(); release.await(); error("Fixture binding unavailable")
            }
        }
        val vm = WorkspaceViewModel(service, app.pluginDrafts, app.terminals)
        try {
            rule.runOnUiThread {
                vm.selectChat("workspace-bind-form-chat")
                rule.activity.setContent { MaterialTheme { WorkspaceDialog(vm, app.systemExecution) {} } }
            }
            rule.onNodeWithText("绑定已有工作区").performClick()
            rule.onNodeWithText(workspace.name).performClick()
            rule.waitUntil(10_000) { calls.get() == 1 }
            rule.onNodeWithText(workspace.name).assertIsDisplayed().assertIsNotEnabled()
            release.complete(Unit)
            rule.waitUntil(10_000) { !vm.busy.value && vm.error.value != null }
            rule.onNodeWithText(workspace.name).assertIsDisplayed().assertIsEnabled()
            rule.onNodeWithText("Fixture binding unavailable").assertIsDisplayed()
            assertNull(vm.workspaceId.value)
            screenshot("workspace-bind-failure")
        } catch (failure: Throwable) {
            runCatching { screenshot("workspace-bind-form-failure") }; throw failure
        } finally { release.complete(Unit); rule.runOnUiThread { vm.viewModelScope.cancel() } }
    }
    @Test fun successfulBindingClosesChooserWhenOnlyDirectoryReadFails() {
        val app = app()
        val workspace = Workspace("workspace-list-failure-fixture", "Bound directory")
        val calls = AtomicInteger()
        val service = object : WorkspaceService by app.workspaces {
            override val state = MutableStateFlow(WorkspaceState(workspaces = listOf(workspace)))
            override suspend fun bind(sessionId: String, workspaceId: String) {
                calls.incrementAndGet()
                state.value = state.value.copy(bindings = mapOf(sessionId to workspaceId))
            }
            override suspend fun location(workspaceId: String): String = error("Fixture directory unavailable")
        }
        val vm = WorkspaceViewModel(service, app.pluginDrafts, app.terminals)
        try {
            rule.runOnUiThread {
                vm.selectChat("workspace-list-failure-chat")
                rule.activity.setContent { MaterialTheme { WorkspaceDialog(vm, app.systemExecution) {} } }
            }
            rule.onNodeWithText("绑定已有工作区").performClick()
            rule.onNodeWithText(workspace.name).performClick()
            rule.waitUntil(10_000) { !vm.busy.value && vm.workspaceId.value == workspace.id && vm.error.value != null }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("绑定工作区").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag("workspace_switcher").assertIsDisplayed()
            assertEquals(workspace.id, service.state.value.bindings["workspace-list-failure-chat"])
            assertEquals(1, calls.get())
            assertTrue(rule.onAllNodes(isDialog()).fetchSemanticsNodes().size <= 1)
            screenshot("workspace-bound-directory-failure")
        } catch (failure: Throwable) {
            runCatching { screenshot("workspace-bound-result-failure") }; throw failure
        } finally { rule.runOnUiThread { vm.viewModelScope.cancel() } }
    }

    @Test fun closingPendingFormShowsBackgroundProgressAndKeepsDraftAfterFailure() {
        val app = app()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val service = object : WorkspaceService by app.workspaces {
            override suspend fun create(name: String): Workspace {
                calls.incrementAndGet(); release.await(); error("Fixture background failure")
            }
        }
        val vm = WorkspaceViewModel(service, app.pluginDrafts, app.terminals)
        try {
            rule.runOnUiThread {
                vm.selectChat("workspace-background-form-chat")
                rule.activity.setContent { MaterialTheme { WorkspaceDialog(vm, app.systemExecution, createOnOpen = true) {} } }
            }
            rule.onNodeWithTag("workspace_create_name").performTextReplacement("Background draft")
            rule.onNodeWithText("创建并绑定").performClick()
            rule.waitUntil(10_000) { calls.get() == 1 }
            // Closing a form is not a claim that already-started disk work was cancelled.
            rule.onNodeWithText("关闭").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("workspace_create_name").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag("workspace_background_progress").assertIsDisplayed()
            rule.onNodeWithText("新建工作区").assertIsNotEnabled()
            assertEquals(1, calls.get())
            release.complete(Unit)
            rule.waitUntil(10_000) { !vm.busy.value && vm.error.value != null }
            rule.onNodeWithText("Fixture background failure").assertIsDisplayed()
            rule.onNodeWithText("新建工作区").performClick()
            rule.onNodeWithTag("workspace_create_name").assert(hasText("Background draft"))
            assertEquals(1, calls.get())
            screenshot("workspace-close-pending-form")
        } catch (failure: Throwable) {
            runCatching { screenshot("workspace-close-pending-form-failure") }; throw failure
        } finally { release.complete(Unit); rule.runOnUiThread { vm.viewModelScope.cancel() } }
    }

}
