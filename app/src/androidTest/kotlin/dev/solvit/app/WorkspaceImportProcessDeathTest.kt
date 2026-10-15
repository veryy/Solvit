package dev.solvit.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.*
import dev.solvit.market.BuiltinWorkspaceManager
import dev.solvit.market.WorkspacePageBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Only scripts/verify-workspace-import-process-death.sh invokes this class. The prepare
 * method deliberately never completes: an EXTERNAL adb process kills the actual app at
 * a production publication checkpoint, then launches verify in a fresh process. */
class WorkspaceImportProcessDeathTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as SolvitApplication
    private val fixture get()=File(app.noBackupFilesDir,"import-recovery-fixture.json")
    private val marker get()=File(app.noBackupFilesDir,"import-recovery-ready")
    private val original="original before actual process death"
    private val imported="new bytes from actual native publication"
    private val concurrent="concurrent Shell file must survive"
    private fun sha(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun sync(file:File,text:String) { FileOutputStream(file).use { it.write(text.toByteArray());it.fd.sync() } }
    private suspend fun ready() { withTimeout(30_000){app.ready.filterNotNull().first().getOrThrow()} }

    @Test fun prepareActualReplacementAndWaitForExternalKill()=runBlocking<Unit> {
        ready()
        val scenario=InstrumentationRegistry.getArguments().getString("recoveryScenario")!!
        require(scenario in setOf("PREPARING","PUBLISHING","COMMITTED","CONFLICT","DELETED"))
        check(!fixture.exists()) { "Previous process-death fixture was not verified" }
        val chat=app.chatStore.createSession().id
        val workspace=app.workspaces.create("Process death $scenario").also { app.workspaces.bind(chat,it.id) }
        val root=File(app.workspaces.location(workspace.id));File(root,"data.txt").writeText(original)
        sync(fixture,buildJsonObject { put("scenario",scenario);put("chat",chat);put("workspace",workspace.id) }.toString())
        val checkpoint=when(scenario){"CONFLICT"->"PREPARING";"DELETED"->"COMMITTED";else->scenario}
        withContext(WorkspaceImportMonitor { progress ->
            if(progress.stage==checkpoint) {
                if(scenario=="CONFLICT")File(root,"data.txt").writeText(concurrent)
                if(scenario=="DELETED")check(File(root,"data.txt").delete())
                // The marker is written only after the production journal / move above.
                sync(marker,scenario)
                awaitCancellation()
            }
        }) { app.workspaces.replaceImportedFile(workspace.id,"data.txt",imported.toByteArray(),sha(imported),sha(original)) }
        error("External force-stop did not interrupt the paused import")
    }

    @Test fun verifyRestartedFilesShellAndActualPluginRecoveryPage() {
        runBlocking { ready() }
        val saved=Json.parseToJsonElement(fixture.readText()).jsonObject
        val scenario=saved.getValue("scenario").jsonPrimitive.content
        val chat=saved.getValue("chat").jsonPrimitive.content
        val workspace=saved.getValue("workspace").jsonPrimitive.content
        val root=File(runBlocking { app.workspaces.location(workspace) })
        val reports=runBlocking { app.workspaces.importRecoveries(workspace) }
        assertEquals(1,reports.size)
        val report=reports.single()
        val expected=when(scenario){"PREPARING","PUBLISHING"->"ORIGINAL_AVAILABLE";"CONFLICT"->"CONFLICT";else->"PUBLISHED"}
        assertEquals(expected,report.status);assertEquals("data.txt",report.path)
        fun assertFiles() {
            val target=File(root,"data.txt")
            if(scenario=="DELETED")assertFalse(target.exists())
            else assertEquals(when(scenario){"PREPARING","PUBLISHING"->original;"CONFLICT"->concurrent;else->imported},target.readText())
            val backups=root.listFiles()!!.filter { it.name.startsWith("solvit-backup-") }
            if(expected=="ORIGINAL_AVAILABLE"){assertTrue(backups.isEmpty());assertNull(report.backupPath)}
            else {assertEquals(1,backups.size);assertEquals(original,backups.single().readText());assertEquals(backups.single().name,report.backupPath)}
            assertTrue(root.listFiles()!!.none { it.name.startsWith(".solvit-import-") })
        }
        assertFiles()
        val catalogue=runBlocking { app.workspaces.importBackups(workspace) }
        if(expected=="ORIGINAL_AVAILABLE") {
            assertTrue(catalogue.backups.isEmpty());assertEquals(0L,catalogue.logicalBytes)
        } else {
            val retained=catalogue.backups.single()
            assertEquals("data.txt",retained.path);assertEquals(report.backupPath,retained.backupPath)
            assertEquals(sha(original),retained.originalSha256)
            assertEquals(original.toByteArray().size.toLong(),retained.originalBytes)
            assertEquals(WorkspaceBackupStatus.PRESENT,retained.status);assertFalse(retained.pending)
            assertEquals(original.toByteArray().size.toLong(),catalogue.logicalBytes)
        }
        val shell=runBlocking { app.workspaces.createShell(workspace,"Recovery verification") }
        val shellResult=runBlocking {
            val command=if(scenario=="DELETED")"/system/bin/toybox test ! -e data.txt" else "/system/bin/toybox sha256sum data.txt"
            val job=app.workspaces.run(workspace,shell.id,command,10_000)
            withTimeout(15_000){app.workspaces.jobs.first { jobs->jobs.any { it.id==job.id&&it.stage==ShellStage.COMPLETED } }.first { it.id==job.id }}
        }
        assertEquals(0,shellResult.exitCode)
        if(scenario!="DELETED")assertTrue(shellResult.output.joinToString(""){it.text}.contains(sha(File(root,"data.txt").readText())))
        val bridge=WorkspacePageBridge(app.workspaces,null,chat)
        val manager=BuiltinWorkspaceManager(app);val html=HtmlPageProbe(rule.activity)
        fun waitDom(code:String){rule.waitUntil(10_000){runCatching { html.js(code)=="true" }.getOrDefault(false)}}
        fun click(id:String){waitDom("!!document.querySelector('[data-node=$id]')&&!document.querySelector('[data-node=$id]').disabled");html.js("document.querySelector('[data-node=$id]').click()")}
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme { PluginPageScreen("工作区",{manager.openPage("zh",bridge)}){} } } }
            waitDom("window.pluginPageReady===true&&!!document.querySelector('[data-node=recovery_summary]')")
            click("recoveryOpen")
            waitDom("document.querySelector('[data-node=recovery_path_0]')?.textContent==='data.txt'")
            waitDom("!!document.querySelector('[data-node=recovery_status_0]')&&document.querySelector('[data-node=recovery_status_0]').getBoundingClientRect().height>0")
            if(scenario=="CONFLICT")html.screenshot("workspace-import-process-death-recovery").recycle()
            click("recovery_dismiss_0")
            rule.waitUntil(10_000){rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithText("仅关闭这条恢复提示，不删除工作区文件或原文件备份。").assertIsDisplayed()
            rule.onNodeWithTag("plugin_page_confirm").performClick()
            waitDom("!!document.querySelector('[data-node=recovery_empty]')")
            assertTrue(runBlocking { app.workspaces.importRecoveries(workspace) }.isEmpty())
            assertFiles()
            val afterDismiss=runBlocking { app.workspaces.importBackups(workspace) }
            assertEquals(catalogue.backups,afterDismiss.backups)
            assertEquals(catalogue.logicalBytes,afterDismiss.logicalBytes)
            marker.delete();assertTrue(fixture.delete())
        }finally{runBlocking { bridge.close() }}
    }
}
