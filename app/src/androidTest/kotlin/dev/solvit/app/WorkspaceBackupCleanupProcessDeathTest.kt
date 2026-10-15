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
import java.nio.file.Files
import java.security.MessageDigest

/** Invoked exclusively by the separate adb force-stop script. Preparation deliberately
 * cannot end normally; verification runs in a newly started process and actual HTML UI. */
class WorkspaceBackupCleanupProcessDeathTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as SolvitApplication
    private val fixture get()=File(app.noBackupFilesDir,"backup-cleanup-fixture.json")
    private val marker get()=File(app.noBackupFilesDir,"backup-cleanup-ready")
    private val original="retained original before cleanup kill"
    private val target="current target survives every cleanup"
    private val concurrent="concurrent name must never be deleted"
    private fun sha(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun sync(file:File,text:String){FileOutputStream(file).use { it.write(text.toByteArray());it.fd.sync() }}
    private suspend fun ready(){withTimeout(30_000){app.ready.filterNotNull().first().getOrThrow()}}

    @Test fun prepareActualCleanupAndWaitForExternalKill()=runBlocking<Unit> {
        ready();val scenario=InstrumentationRegistry.getArguments().getString("cleanupScenario")!!
        require(scenario in setOf("PREPARED","HELD","CONFLICT","DELETING"))
        check(!fixture.exists()) { "Previous cleanup fixture was not verified" }
        val chat=app.chatStore.createSession().id
        val workspace=app.workspaces.create("Cleanup kill $scenario").also { app.workspaces.bind(chat,it.id) }
        val root=File(app.workspaces.location(workspace.id));File(root,"data.txt").writeText(original)
        val imported=app.workspaces.replaceImportedFile(workspace.id,"data.txt",target.toByteArray(),sha(target),sha(original))
        val record=app.workspaces.importBackups(workspace.id).backups.single()
        val backup=File(root,checkNotNull(imported.backupPath))
        val directory=File(app.noBackupFilesDir,"workspace-import-staging/${workspace.id}/backup-cleanup")
        val intent=File(directory,"${record.id}.intent");val held=File(directory,"${record.id}.held")
        sync(fixture,buildJsonObject { put("scenario",scenario);put("chat",chat);put("workspace",workspace.id);put("backupId",record.id);put("backupPath",record.backupPath) }.toString())
        withContext(WorkspacePageAuthority { _,_ ->
            val phase=if(intent.isFile)intent.readLines().last() else ""
            val reached=when(scenario) {
                "PREPARED" -> phase=="PREPARED" && backup.exists() && !held.exists()
                "HELD" -> phase=="PREPARED" && held.exists() && !backup.exists()
                "CONFLICT" -> phase=="CLAIMED" && held.exists() && !backup.exists()
                else -> phase=="DELETING" && !held.exists() && !backup.exists()
            }
            if(reached) {
                if(scenario=="CONFLICT")backup.writeText(concurrent)
                sync(marker,scenario);awaitCancellation()
            }
        }) { app.workspaces.cleanupImportBackup(workspace.id,record.id,sha(original)) }
        error("External force-stop did not interrupt cleanup")
    }

    @Test fun verifyRestartedCleanupFactsAndActualHtmlRecoveryWithoutDeletionReplay() {
        runBlocking { ready() }
        val saved=Json.parseToJsonElement(fixture.readText()).jsonObject
        fun text(key:String)=saved.getValue(key).jsonPrimitive.content
        val scenario=text("scenario");val workspace=text("workspace");val id=text("backupId")
        val root=File(runBlocking { app.workspaces.location(workspace) });val backup=File(root,text("backupPath"))
        val held=File(app.noBackupFilesDir,"workspace-import-staging/$workspace/backup-cleanup/$id.held")
        val expected=when(scenario){"PREPARED"->"SOURCE_AVAILABLE";"HELD"->"HELD";"CONFLICT"->"SOURCE_AND_HELD";else->"UNCONFIRMED"}
        val pending=runBlocking { app.workspaces.importBackupCleanups(workspace) }.cleanups.single()
        assertEquals(expected,pending.status);assertEquals(id,pending.id)
        assertEquals(target,File(root,"data.txt").readText())
        if(scenario=="DELETING") {
            assertFalse(held.exists());assertFalse(backup.exists())
            backup.writeText(concurrent)
        }else {
            if(scenario!="PREPARED")assertEquals(original,held.readText())
            if(scenario=="CONFLICT")assertEquals(concurrent,backup.readText())
            if(scenario=="PREPARED")assertEquals(original,backup.readText())
        }
        val bridge=WorkspacePageBridge(app.workspaces,null,text("chat"))
        val manager=BuiltinWorkspaceManager(app);val html=HtmlPageProbe(rule.activity)
        fun waitDom(code:String){rule.waitUntil(10_000){runCatching { html.js(code)=="true" }.getOrDefault(false)}}
        fun click(id:String){waitDom("!!document.querySelector('[data-node=$id]')&&!document.querySelector('[data-node=$id]').disabled");html.js("document.querySelector('[data-node=$id]').click()")}
        fun confirm(){rule.waitUntil(10_000){rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty()};rule.onNodeWithTag("plugin_page_confirm").performClick();rule.waitUntil(10_000){rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isEmpty()}}
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme { PluginPageScreen("工作区",{manager.openPage("zh",bridge)}){} } } }
            waitDom("window.pluginPageReady===true&&!!document.querySelector('[data-node=backupOpen]')")
            click("backupOpen");click("cleanupOpen")
            waitDom("document.querySelector('[data-node=cleanup_path_0]')?.textContent==='data.txt'")
            if(scenario=="DELETING") {
                waitDom("document.querySelector('[data-node=cleanup_status_0]')?.textContent==='结果未确认'")
                assertEquals("false",html.js("!!document.querySelector('[data-node=cleanup_restore_0]')"))
                click("cleanup_dismiss_0");confirm()
                assertEquals(concurrent,backup.readText())
            }else {
                if(scenario=="CONFLICT") {
                    html.screenshot("workspace-backup-cleanup-conflict").recycle()
                    click("cleanup_restore_0");confirm()
                    waitDom("document.querySelector('[data-node=message]')?.textContent.includes('同名文件')===true")
                    assertEquals(original,held.readText());assertEquals(concurrent,backup.readText())
                    Files.move(backup.toPath(),File(root,"kept-concurrent.txt").toPath())
                }
                click("cleanup_restore_0");confirm()
                waitDom("!!document.querySelector('[data-node=cleanup_empty]')")
                assertEquals(original,backup.readText());assertFalse(held.exists())
                if(scenario=="CONFLICT")assertEquals(concurrent,File(root,"kept-concurrent.txt").readText())
            }
            waitDom("!!document.querySelector('[data-node=cleanup_empty]')")
            assertTrue(runBlocking { app.workspaces.importBackupCleanups(workspace) }.cleanups.isEmpty())
            assertEquals(target,File(root,"data.txt").readText())
            val provenance=runBlocking { app.workspaces.importBackups(workspace) }.backups
            if(scenario=="DELETING") {
                // Closing an unconfirmed result retires ownership too: a later inode
                // number reuse must never auto-adopt a new file at this backup name.
                assertTrue(provenance.isEmpty());assertEquals(concurrent,backup.readText())
            }else {assertFalse(provenance.single().pending);assertEquals(WorkspaceBackupStatus.PRESENT,provenance.single().status)}
            assertTrue(fixture.delete());marker.delete()
        }finally { runBlocking { bridge.close() } }
    }
}
