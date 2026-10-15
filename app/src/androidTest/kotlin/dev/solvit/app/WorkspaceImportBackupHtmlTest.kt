package dev.solvit.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.solvit.core.*
import dev.solvit.market.BuiltinWorkspaceManager
import dev.solvit.market.WorkspacePageBridge
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Actual generated JS/HTML, native confirmations, retained inodes and production JNI publisher. */
class WorkspaceImportBackupHtmlTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    @Test fun staleBackupReviewIsRejectedThenFreshCopyRestoresWithSeparateDestinationApproval() {
        val app=rule.activity.application as SolvitApplication
        runBlocking { withTimeout(30_000){app.ready.filterNotNull().first().getOrThrow()} }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val native=AndroidWorkspaces(app,scope)
        val chat=runBlocking { app.chatStore.createSession().id }
        val workspace=runBlocking { native.create("Backup restore").also { native.bind(chat,it.id) } }
        val root=File(runBlocking { native.location(workspace.id) })
        val original="original source".toByteArray();val replacement="replacement remains until explicit restore".toByteArray()
        File(root,"data.txt").writeBytes(original)
        val imported=runBlocking { native.replaceImportedFile(workspace.id,"data.txt",replacement,sha(replacement),sha(original)) }
        val backup=File(root,checkNotNull(imported.backupPath))
        val bridge=WorkspacePageBridge(native,null,chat)
        val manager=BuiltinWorkspaceManager(app);val html=HtmlPageProbe(rule.activity)
        var visible by mutableStateOf(true)
        fun waitDom(code:String){rule.waitUntil(10_000){runCatching { html.js(code)=="true" }.getOrDefault(false)}}
        fun click(id:String){waitDom("!!document.querySelector('[data-node=$id]')&&!document.querySelector('[data-node=$id]').disabled");html.js("document.querySelector('[data-node=$id]').click()")}
        fun confirm(){rule.waitUntil(10_000){rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty()};rule.onNodeWithTag("plugin_page_confirm").performClick();rule.waitUntil(10_000){rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isEmpty()}}
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
                if(visible)PluginPageScreen("工作区",{manager.openPage("zh",bridge)}){visible=false}
            } } }
            waitDom("window.pluginPageReady===true&&!!document.querySelector('[data-node=backupOpen]')")
            click("backupOpen");click("backup_view_0");click("backupReview")
            waitDom("document.querySelector('[data-node=backup_current_sha]')?.textContent==='${sha(original)}'")
            // An ordinary Shell-style write through the retained inode invalidates approval.
            val changed="updated retained backup".toByteArray();backup.writeBytes(changed)
            click("backupSelect");confirm()
            waitDom("document.querySelector('[data-node=message]')?.textContent.includes('重新审阅')===true")
            assertArrayEquals(replacement,File(root,"data.txt").readBytes())
            assertEquals("null",runBlocking { bridge.query("importSelection","{}"){} })
            click("backupReview")
            waitDom("document.querySelector('[data-node=backup_current_sha]')?.textContent==='${sha(changed)}'")
            waitDom("document.querySelector('[data-node=backup_original_sha]')?.textContent==='${sha(original)}'")
            html.screenshot("workspace-backup-current-review").recycle()
            click("backupSelect");confirm()
            waitDom("document.getElementById('field-importPath')?.value==='data.txt'")
            assertArrayEquals(replacement,File(root,"data.txt").readBytes())
            click("importPreview")
            waitDom("document.querySelector('[data-node=import_content]')?.textContent==='updated retained backup'")
            click("prepareImport")
            waitDom("document.querySelector('[data-node=replace_sha]')?.textContent==='${sha(replacement)}'")
            click("cancel")
            assertArrayEquals(replacement,File(root,"data.txt").readBytes())
            click("prepareImport");click("confirm");confirm()
            waitDom("document.querySelector('[data-node=message]')?.textContent.includes('solvit-backup-')===true")
            assertArrayEquals(changed,File(root,"data.txt").readBytes())
            assertArrayEquals(changed,backup.readBytes())
            val records=runBlocking { native.importBackups(workspace.id) }
            assertEquals(2,records.total)
            assertTrue(records.backups.all { it.status==WorkspaceBackupStatus.PRESENT&&!it.pending })
            val displaced=records.backups.single { it.backupPath!=imported.backupPath }
            assertArrayEquals(replacement,File(root,displaced.backupPath).readBytes())
            val shell=runBlocking { native.createShell(workspace.id,"Restored file Shell") }
            val result=runBlocking {
                val job=native.run(workspace.id,shell.id,"/system/bin/toybox sha256sum data.txt",10_000)
                withTimeout(15_000){native.jobs.first { jobs->jobs.any { it.id==job.id&&it.stage==ShellStage.COMPLETED } }.first { it.id==job.id }}
            }
            assertEquals(0,result.exitCode);assertTrue(result.output.joinToString(""){it.text}.contains(sha(changed)))
            click("backupOpen")
            waitDom("document.querySelector('[data-node=backup_count]')?.textContent.includes('2')===true")
            html.screenshot("workspace-backup-restored-copies").recycle()
            click("backup_view_0");click("backupReview")
            waitDom("document.querySelector('[data-node=backup_current_sha]')?.textContent==='${sha(replacement)}'")
            waitDom("(()=>{const b=document.querySelector('[data-node=backupClean]');const r=b?.getBoundingClientRect();return !!r&&r.height>0&&r.top>=0&&r.bottom<=innerHeight&&document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)?.closest('[data-node]')?.dataset.node==='backupClean'})()")
            html.tapElement("[data-node=backupClean]")
            rule.waitUntil(10_000){rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithText("删除刚审阅的备份，当前目标文件会保留。此操作无法撤销。").assertIsDisplayed()
            rule.onNodeWithText("取消").performClick()
            rule.waitUntil(10_000){rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isEmpty()}
            assertTrue(File(root,displaced.backupPath).exists())
            html.tapElement("[data-node=backupClean]");confirm()
            waitDom("document.querySelector('[data-node=backup_count]')?.textContent.includes('1')===true")
            assertFalse(File(root,displaced.backupPath).exists())
            assertArrayEquals(changed,File(root,"data.txt").readBytes());assertArrayEquals(changed,backup.readBytes())
            assertEquals(1,runBlocking { native.importBackups(workspace.id) }.total)
            click("cleanupOpen");waitDom("!!document.querySelector('[data-node=cleanup_empty]')")
            html.screenshot("workspace-backup-cleanup-complete").recycle()

        }finally {
            rule.runOnUiThread { visible=false }
            runBlocking { bridge.close();native.close() };scope.cancel()
        }
    }
}
