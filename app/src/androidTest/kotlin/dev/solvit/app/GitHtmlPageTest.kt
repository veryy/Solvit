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
            html.screenshot("git-workbench-complete-working-diff")
            click("git_stage"); click("git_confirm"); confirm(false)
            assertNull(runBlocking { probe.status().entries.first { it.path == "a.txt" }.staged })
            click("git_confirm"); confirm(true)
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
            waitDom("!!document.querySelector('[data-open=\"a.txt\"]')")
            html.js("document.querySelector('[data-open=\"a.txt\"]').click()")
            waitDom("window.workspaceEditor?.state.doc.toString()==='working after staging 😀\\n'")
            html.screenshot("git-workbench-shared-file-preview")
            val shell = app.workspaces.state.value.shells.first { it.workspaceId == workspace.id }
            runBlocking { withTimeout(15_000) {
                val job = app.workspaces.run(workspace.id, shell.id, "cat a.txt")
                val done = app.workspaces.jobs.first { jobs -> jobs.any { it.id == job.id && it.stage in setOf(ShellStage.COMPLETED, ShellStage.FAILED) } }.first { it.id == job.id }
                assertEquals(ShellStage.COMPLETED, done.stage); assertEquals(working, done.output)
            } }
            assertFalse(app.marketplace.installed.value.first { it.id == "dev.solvit.git" }.enabled)
        } finally {
            rule.runOnUiThread { visible = false }; rule.waitForIdle()
            runBlocking { probe.close(); app.workspaces.unbind(chat) }; root.deleteRecursively()
        }
    }
}
