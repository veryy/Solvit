package dev.solvit.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.solvit.core.ShellStage
import dev.solvit.market.BuiltinWorkspaceManager
import dev.solvit.market.GitPageBridge
import dev.solvit.market.WorkspacePageBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class GitHtmlPageTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun actualHtmlCredentialControlSavesOnlyNativeEncryptedFieldsAndSupportsRemoveAndCancel() {
        val app = rule.activity.application as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
        val chat = "git-credential-html-${UUID.randomUUID()}"
        val workspace = runBlocking { app.workspaces.forChat(chat) }
        val root = File(runBlocking { app.workspaces.location(workspace.id) })
        val endpoint = dev.solvit.core.GitRemoteEndpoint.parse("https://github.com/fixture/${UUID.randomUUID()}.git")
        val probe = (app.workspaceGit as dev.solvit.core.WorkspaceRemoteGitService).bindRemote(chat, { chat }, {}, {}, {})
        val html = HtmlPageProbe(rule.activity)
        val manager = BuiltinWorkspaceManager(app, "dev.solvit.git")
        var visible by mutableStateOf(true)
        fun waitDom(expression: String) {
            try { rule.waitUntil(15_000) { runCatching { html.js(expression) == "true" }.getOrDefault(false) } }
            catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                val nodes = runCatching { html.js("Array.from(document.querySelectorAll('[data-node]')).map(e=>({id:e.dataset.node,disabled:e.disabled})).slice(0,30)") }.getOrDefault("unavailable")
                throw AssertionError("Git page condition: $expression; public node states: $nodes", timeout)
            }
        }
        fun click(id: String) {
            waitDom("!!document.querySelector('[data-node=$id]') && !document.querySelector('[data-node=$id]').disabled")
            html.js("document.querySelector('[data-node=$id]').click()")
        }
        fun openForm() {
            click("git_credentials"); click("git_credentials_confirm")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("git_credential_secret").fetchSemanticsNodes().isNotEmpty() }
        }
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
                if (visible) PluginPageScreen("Git", open = {
                    manager.openPage("zh", WorkspacePageBridge(app.workspaces, app.pluginDrafts, chat, false) { chat },
                        git = GitPageBridge(app.workspaceGit, chat) { chat })
                }, onDismiss = { visible = false })
            } } }
            click("git_remote")
            html.js("(()=>{const field=document.getElementById('field-git_remote_url');field.value=${kotlinx.serialization.json.JsonPrimitive(endpoint.url)};field.dispatchEvent(new Event('input'))})()")
            openForm()
            rule.onNodeWithTag("git_credential_endpoint").assertTextEquals(endpoint.url)
            rule.runOnIdle {
                assertTrue(android.view.inspector.WindowInspector.getGlobalWindowViews().any { view ->
                    (view.layoutParams as? android.view.WindowManager.LayoutParams)?.flags?.let {
                        it and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0
                    } == true
                })
            }
            rule.onNodeWithTag("git_credential_secret").performTextInput("solvit-ui-fixture-secret")
            assertEquals("false", html.js("document.body.innerHTML.includes('solvit-ui-fixture-secret')"))
            rule.onNodeWithTag("git_credential_save").performScrollTo().performClick()
            waitDom("!!document.querySelector('[data-node=git_credentials]') && !document.querySelector('[data-node=git_credentials]').disabled")
            assertTrue(runBlocking { probe.credentialsConfigured(endpoint) })
            assertFalse(app.getSharedPreferences("git_credentials", android.content.Context.MODE_PRIVATE)
                .all.values.any { it.toString().contains("solvit-ui-fixture-secret") })
            openForm()
            rule.onNodeWithTag("git_credential_secret").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            rule.onNodeWithTag("git_credential_remove").performScrollTo().performClick()
            waitDom("!!document.querySelector('[data-node=git_credentials]') && !document.querySelector('[data-node=git_credentials]').disabled")
            assertFalse(runBlocking { probe.credentialsConfigured(endpoint) })
            openForm()
            rule.onNodeWithTag("git_credential_secret").performTextInput("cancelled-ui-fixture")
            rule.onNodeWithTag("git_credential_cancel").performScrollTo().performClick()
            waitDom("!!document.querySelector('[data-node=git_credentials]') && !document.querySelector('[data-node=git_credentials]').disabled")
            assertFalse(runBlocking { probe.credentialsConfigured(endpoint) })
            assertEquals("false", html.js("document.body.innerHTML.includes('cancelled-ui-fixture')"))
        } finally {
            visible = false; rule.waitForIdle()
            runBlocking {
                probe.removeCredentials(endpoint); probe.close(); app.workspaces.unbind(chat)
                root.deleteRecursively()
            }
        }
    }

    @Test fun actualQuickJsGitToolsReadBoundRepositoryAndDenyUnapprovedOrReboundCalls() {
        val app = rule.activity.application as SolvitApplication
        runBlocking {
            withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() }
            val chat = "git-tools-${UUID.randomUUID()}"
            val workspace = app.workspaces.forChat(chat)
            val root = File(app.workspaces.location(workspace.id))
            val native = app.workspaceGit.bind(chat, { chat }, {}, {})
            val plugin = "dev.solvit.git"
            val wasEnabled = app.marketplace.installed.value.single { it.id == plugin }.enabled
            try {
                native.initialize()
                File(root, "read.txt").writeText("actual Git tool 中文 😀\n")
                native.commit(native.stage(native.diff("read.txt", false)), "Tool history", "Fixture", "test@example.invalid")
                File(root, "read.txt").writeText("modified tool result 😀\n")
                if (!wasEnabled) app.marketplace.enable(plugin, setOf("workspace", "git", "network"))
                val descriptor = app.marketplace.installed.value.single { it.id == plugin }.tools.single { it.name.endsWith("_status") }
                assertTrue(descriptor.chatVisible)
                val allow = object : dev.solvit.core.ToolAuthorization {
                    override suspend fun authorize(descriptor: dev.solvit.core.ToolDescriptor): Boolean {
                        assertEquals(plugin, descriptor.pluginId)
                        assertEquals(setOf("workspace", "git"), descriptor.capabilities)
                        return true
                    }
                }
                withContext(dev.solvit.core.ConversationContext(chat, workspace.id)) {
                    try { app.marketplace.runTool("dev_solvit_git_status", "{}", dev.solvit.core.DenyTools); fail("Expected denied tool") }
                    catch (_: dev.solvit.core.ToolDeniedException) { }
                    val status = org.json.JSONObject(app.marketplace.runTool("dev_solvit_git_status", "{}", allow))
                    assertEquals("modified", status.getJSONArray("entries").getJSONObject(0).getString("working"))
                    val diff = org.json.JSONObject(app.marketplace.runTool("dev_solvit_git_diff", """{"path":"read.txt","staged":false}""", allow))
                    assertTrue(diff.getString("patch").contains("+modified tool result 😀"))
                    assertEquals(diff.getInt("total"), diff.getInt("nextOffset"))
                    val history = org.json.JSONArray(app.marketplace.runTool("dev_solvit_git_history", "{}", allow))
                    assertEquals("Tool history", history.getJSONObject(0).getString("message"))
                    try { app.marketplace.runTool("dev_solvit_git_status", """{"workspaceId":"other"}""", allow); fail("Expected hidden-root rejection") }
                    catch (_: Exception) { }
                    app.workspaces.unbind(chat)
                    try { app.marketplace.runTool("dev_solvit_git_status", "{}", allow); fail("Expected rebound invocation rejection") }
                    catch (_: Exception) { }
                }
                assertEquals("modified tool result 😀\n", File(root, "read.txt").readText())
            } finally {
                if (!wasEnabled) app.marketplace.disable(plugin)
                native.close(); app.workspaces.unbind(chat); root.deleteRecursively()
            }
        }
    }
    @Test fun actualGitPageReviewsFullChangesCommitsOnlyStagedBytesAndPreviewsTheSameShellFiles() {
        val app = rule.activity.application as SolvitApplication
        val chat = "git-html-${UUID.randomUUID()}"
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
        val workspace = runBlocking { app.workspaces.forChat(chat) }
        val root = File(runBlocking { app.workspaces.location(workspace.id) })
        val original = "// 中文 😀\n" + "const value = 1;\n".repeat(1500) + "LAST_PATCH_LINE\n"
        File(root, "a.txt").writeText(original)
        File(root, "z.txt").writeText("keep local\n")
        val probe = app.workspaceGit.bind(chat, { chat }, {}, {})
        val html = HtmlPageProbe(rule.activity)
        val manager = BuiltinWorkspaceManager(app, "dev.solvit.git")
        var visible by mutableStateOf(true)
        fun waitDom(expression: String) { rule.waitUntil(15_000) { runCatching { html.js(expression) == "true" }.getOrDefault(false) } }
        fun click(id: String) {
            waitDom("!!document.querySelector('[data-node=$id]') && !document.querySelector('[data-node=$id]').disabled")
            html.js("document.querySelector('[data-node=$id]').click()")
        }
        fun field(id: String, value: String) {
            html.js("(()=>{const field=document.getElementById('field-$id');field.value=${kotlinx.serialization.json.JsonPrimitive(value)};field.dispatchEvent(new Event('input'))})()")
        }
        fun confirm(allowed: Boolean) {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            if (allowed) rule.onNodeWithTag("plugin_page_confirm").performClick() else rule.onNodeWithText("取消").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isEmpty() }
        }
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
                if (visible) PluginPageScreen("Git", { manager.openPage("zh",
                    access = WorkspacePageBridge(app.workspaces, app.pluginDrafts, chat),
                    git = GitPageBridge(app.workspaceGit, chat) { chat }) }) { visible = false }
            } } }
            click("git_initialize"); click("git_confirm"); confirm(false)
            assertFalse(File(root, ".git").exists())
            click("git_confirm"); confirm(true)
            click("git_working_0")
            waitDom("document.getElementById('git-patch')?.textContent.includes('LAST_PATCH_LINE')===true && !document.querySelector('[data-node=git_stage]').disabled")
            assertTrue(html.js("document.getElementById('git-patch').textContent.includes('中文 😀')") == "true")
            // DOM completeness alone did not catch a diff clipped to one line in the real screenshot.
            assertEquals("true", html.js("(()=>{const p=document.getElementById('git-patch');return p.clientHeight>=Math.min(300,innerHeight*.4)&&p.scrollHeight>p.clientHeight})()"))
            html.screenshot("git-workbench-complete-working-diff")
            html.js("(()=>{const p=document.getElementById('git-patch');p.scrollTop=p.scrollHeight;return true})()")
            assertEquals("true", html.js("(()=>{const p=document.getElementById('git-patch');return p.scrollTop+p.clientHeight>=p.scrollHeight-2})()"))
            html.screenshot("git-workbench-complete-diff-end")
            html.js("document.getElementById('git-patch').scrollTop=0")
            click("git_stage"); click("git_confirm"); confirm(false)
            assertNull(runBlocking { probe.status().entries.first { it.path == "a.txt" }.staged })
            click("git_confirm"); confirm(true)
            // Dialog dismissal happens before PluginPageScreen's async dispatch finishes.
            waitDom("!!document.querySelector('[data-node=git_files]') && !document.querySelector('[data-node=git_files]').disabled")
            assertEquals("added", runBlocking { probe.status().entries.first { it.path == "a.txt" }.staged })
            val working = "working after staging 😀\n"
            File(root, "a.txt").writeText(working)
            click("git_commit")
            waitDom("!!document.getElementById('field-git_message')")
            field("git_message", "Only reviewed a.txt"); field("git_author", "Fixture"); field("git_email", "test@example.invalid")
            click("git_review"); click("git_confirm"); confirm(true)
            waitDom("!!document.querySelector('[data-node=git_files]') && !document.querySelector('[data-node=git_files]').disabled")
            assertEquals("Only reviewed a.txt", runBlocking { probe.history().single().message })
            assertEquals("modified", runBlocking { probe.status().entries.first { it.path == "a.txt" }.working })
            assertEquals("untracked", runBlocking { probe.status().entries.first { it.path == "z.txt" }.working })
            assertEquals(working, File(root, "a.txt").readText())
            assertEquals("keep local\n", File(root, "z.txt").readText())
            click("git_files"); click("directory")
            waitDom("!!document.getElementById('tree-toggle') && !document.getElementById('tree-toggle').disabled")
            html.tapElement("#tree-toggle")
            waitDom("document.getElementById('file-tree').classList.contains('open')")
            waitDom("!!document.querySelector('[data-open=\"a.txt\"]')")
            html.tapElement("[data-open=\"a.txt\"]")
            waitDom("window.workspaceEditor?.state.doc.toString()==='working after staging 😀\\n'")
            html.screenshot("git-workbench-shared-file-preview")
            val shell = app.workspaces.state.value.shells.first { it.workspaceId == workspace.id }
            runBlocking { withTimeout(15_000) {
                val job = app.workspaces.run(workspace.id, shell.id, "cat a.txt")
                val done = app.workspaces.jobs.first { jobs -> jobs.any { it.id == job.id && it.stage in setOf(ShellStage.COMPLETED, ShellStage.FAILED) } }.first { it.id == job.id }
                assertEquals(ShellStage.COMPLETED, done.stage); assertEquals(0, done.exitCode)
                assertEquals(working, done.output.joinToString("") { it.text })
            } }
            click("backToList"); click("git_changes")
            waitDom("document.getElementById('file-tree').hidden && !!document.querySelector('[data-node=git_files]')")
            assertFalse(app.marketplace.installed.value.first { it.id == "dev.solvit.git" }.enabled)
        } finally {
            rule.runOnUiThread { visible = false }; rule.waitForIdle()
            runBlocking { probe.close(); app.workspaces.unbind(chat) }; root.deleteRecursively()
        }
    }
}
