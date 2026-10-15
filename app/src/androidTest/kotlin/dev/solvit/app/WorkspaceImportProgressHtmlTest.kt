package dev.solvit.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.solvit.core.*
import dev.solvit.market.BuiltinWorkspaceManager
import dev.solvit.market.WorkspacePageBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream

/** Real QuickJS/HTML and JNI publication. Only a native progress checkpoint is paused so a
 * fast local copy still allows deterministic user cancellation; no fake files or outcomes. */
class WorkspaceImportProgressHtmlTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun actualImportProgressCancelsRestoresOriginalAndRetriesThroughTheSamePage() {
        val app=rule.activity.application as SolvitApplication
        runBlocking { withTimeout(30_000){app.ready.filterNotNull().first().getOrThrow()} }
        val chat=runBlocking { app.chatStore.createSession().id }
        val workspace=runBlocking { app.workspaces.create("Import progress").also { app.workspaces.bind(chat,it.id) } }
        val shell=runBlocking { app.workspaces.createShell(workspace.id,"Import progress Shell") }
        val root=File(runBlocking { app.workspaces.location(workspace.id) })
        val original="original file before cancellation".toByteArray()
        File(root,"data.bin").writeBytes(original)
        val bytes=ByteArray(200_000) { (65+it%26).toByte() }
        val attachment=runBlocking { app.attachments.import(chat,"data.bin","text/plain",ByteArrayInputStream(bytes)) }
        runBlocking { app.chatStore.append(ChatMessage("source-${java.util.UUID.randomUUID()}",chat,Role.USER,"file",attachments=listOf(attachment))) }
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val service=object:WorkspaceService by app.workspaces {
            override suspend fun replaceImportedFile(workspaceId:String,path:String,bytes:ByteArray,sourceSha256:String,targetSha256:String):WorkspaceImportedFile {
                val monitor=currentCoroutineContext()[WorkspaceImportMonitor]
                return withContext(WorkspaceImportMonitor { progress ->
                    monitor?.update?.invoke(progress)
                    if(progress.stage=="WRITING"&&!entered.isCompleted){entered.complete(Unit);release.await()}
                }) { app.workspaces.replaceImportedFile(workspaceId,path,bytes,sourceSha256,targetSha256) }
            }
        }
        val bridge=WorkspacePageBridge(service,null,chat,
            chatAttachments=SavedChatAttachmentSource(app.chatStore,app.attachments,chat){chat})
        val manager=BuiltinWorkspaceManager(app);val html=HtmlPageProbe(rule.activity)
        var visible by mutableStateOf(true)
        fun waitDom(code:String){rule.waitUntil(10_000){runCatching { html.js(code)=="true" }.getOrDefault(false)}}
        fun click(id:String){waitDom("!!document.querySelector('[data-node=$id]')&&!document.querySelector('[data-node=$id]').disabled");html.js("document.querySelector('[data-node=$id]').click()")}
        fun confirm(){rule.waitUntil(10_000){rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty()};rule.onNodeWithTag("plugin_page_confirm").performClick();rule.waitUntil(10_000){rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isEmpty()}}
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
                if(visible)PluginPageScreen("工作区",{manager.openPage("zh",bridge)}){visible=false}
            } } }
            waitDom("window.pluginPageReady===true&&!!document.querySelector('[data-node=chatFiles]')")
            click("chatFiles");click("chat_file_select_0");confirm();click("prepareImport");click("confirm");confirm()
            rule.waitUntil(10_000){entered.isCompleted}
            waitDom("(()=>{const p=document.getElementById('workspace-import-progress');return !!p&&p.value===65536&&p.max===200000&&p.getBoundingClientRect().height>0})()")
            waitDom("document.querySelector('[data-node=transfer_bytes]')?.textContent==='65536 / 200000 B'")
            html.screenshot("workspace-import-progress").recycle()
            click("cancelImport");confirm()
            waitDom("!!document.querySelector('[data-node=transfer_cancel_wait]')")
            release.complete(Unit)
            waitDom("!!document.getElementById('field-importPath')&&document.querySelector('[data-node=message]')?.textContent.includes('已取消')===true")
            assertArrayEquals(original,File(root,"data.bin").readBytes())
            assertTrue(root.listFiles()!!.none { it.name.startsWith(".solvit-import-")||it.name.startsWith("solvit-backup-") })
            click("prepareImport");click("confirm");confirm()
            waitDom("document.querySelector('[data-node=message]')?.textContent.includes('solvit-backup-')===true")
            assertArrayEquals(bytes,File(root,"data.bin").readBytes())
            assertArrayEquals(original,root.listFiles()!!.single { it.name.startsWith("solvit-backup-") }.readBytes())
            val result=runBlocking {
                val job=app.workspaces.run(workspace.id,shell.id,"/system/bin/toybox sha256sum data.bin",10_000)
                withTimeout(15_000){app.workspaces.jobs.first { jobs->jobs.any { it.id==job.id&&it.stage==ShellStage.COMPLETED } }.first { it.id==job.id }}
            }
            assertEquals(0,result.exitCode);assertTrue(result.output.joinToString(""){it.text}.contains(attachment.sha256))
            html.screenshot("workspace-import-progress-completed").recycle()
            // Recreate the native service with a crash-shaped reserved orphan. This verifies
            // Android startup cleanup, not an actual OS kill of the running test process.
            val orphan=File(root,"nested/.solvit-import-crash.partial").apply { parentFile!!.mkdir();writeText("orphan") }
            // Persist the same path + fileKey record that production writes before copying.
            val ledger=File(app.noBackupFilesDir,"workspace-import-staging/${workspace.id}").apply { mkdirs() }
            val record=File(ledger,"${java.util.UUID.randomUUID()}.record")
            val key=java.nio.file.Files.readAttributes(orphan.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey().toString()
            val encode=java.util.Base64.getEncoder()
            record.writeText(encode.encodeToString("nested/${orphan.name}".toByteArray())+"\n"+encode.encodeToString(key.toByteArray())+"\n")
            val unknown=File(root,".solvit-import-user.partial").apply { writeText("user file") }
            val ordinary=File(root,"nested/user.partial").apply { writeText("keep") }
            val cleanupScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val reopened=dev.solvit.workspace.AndroidWorkspaces(app,cleanupScope)
            try {
                rule.waitUntil(10_000){!orphan.exists()}
                assertEquals("keep",ordinary.readText());assertEquals("user file",unknown.readText());assertFalse(record.exists())
                assertArrayEquals(bytes,File(root,"data.bin").readBytes())
                assertArrayEquals(original,root.listFiles()!!.single { it.name.startsWith("solvit-backup-") }.readBytes())
            }finally{runBlocking { reopened.close() };cleanupScope.cancel()}
        }finally{release.complete(Unit);rule.runOnUiThread { visible=false };runBlocking { bridge.close() }}
    }
}
