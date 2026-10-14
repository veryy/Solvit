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
import java.io.ByteArrayInputStream
import java.io.File

/** Saved SQLite message -> original attachment -> QuickJS/HTML -> native import -> Git/Shell. */
class ChatAttachmentWorkspaceHtmlTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun savedPdfPreviewsAndImportsIntoTheSameGitAndShellDirectoryWithoutAFilePicker() {
        val app=rule.activity.application as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
        val session=runBlocking { app.chatStore.createSession() }
        val bytes=workspacePdfFixture(app)
        val attachment=runBlocking {
            app.attachments.import(session.id,"saved.pdf","application/pdf",ByteArrayInputStream(bytes))
                .copy(documentSelection=DocumentSelection(2,2))
        }
        val message="saved-pdf-source-${java.util.UUID.randomUUID()}"
        runBlocking { app.chatStore.append(ChatMessage(message,session.id,Role.USER,"private message body",attachments=listOf(attachment))) }
        val workspace=runBlocking { app.workspaces.create("Saved attachment fixture").also { app.workspaces.bind(session.id,it.id) } }
        val shell=runBlocking { app.workspaces.createShell(workspace.id,"Saved attachment Shell") }
        val git=app.workspaceGit.bind(session.id,{session.id},{},{})
        runBlocking { git.initialize() }
        val manager=BuiltinWorkspaceManager(app)
        val html=HtmlPageProbe(rule.activity)
        var visible by mutableStateOf(true)
        fun waitDom(code:String) { rule.waitUntil(10_000) { runCatching { html.js(code)=="true" }.getOrDefault(false) } }
        fun click(id:String) {
            waitDom("!!document.querySelector('[data-node=$id]') && !document.querySelector('[data-node=$id]').disabled")
            html.js("document.querySelector('[data-node=$id]').click()")
        }
        fun confirm(accept:Boolean=true) {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            if(accept)rule.onNodeWithTag("plugin_page_confirm").performClick() else rule.onNodeWithText("取消").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isEmpty() }
        }
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
                if(visible)PluginPageScreen("工作区",{manager.openPage("zh",WorkspacePageBridge(
                    app.workspaces,app.pluginDrafts,session.id,pdfRenderer=app.workspacePdfPreviews,
                    chatAttachments=SavedChatAttachmentSource(app.chatStore,app.attachments,session.id) { session.id }))}) { visible=false }
            } } }
            waitDom("window.pluginPageReady===true && !!document.querySelector('[data-node=chatFiles]')")
            click("chatFiles")
            waitDom("document.querySelector('[data-node=chat_file_name_0]')?.textContent==='saved.pdf'")
            waitDom("document.querySelector('[data-node=chat_file_source_0]')?.textContent.includes('$message')===true")
            assertEquals("false",html.js("document.body.textContent.includes('private message body')"))
            click("chat_file_select_0");confirm(false)
            assertTrue(runBlocking { app.workspaces.list(workspace.id) }.none { it.name=="saved.pdf" })
            waitDom("!!document.querySelector('[data-node=chat_file_select_0]')")
            click("chat_file_select_0");confirm()
            waitDom("document.querySelector('[data-node=import_source]')?.textContent.includes('$message')===true")
            waitDom("document.querySelector('[data-node=import_sha]')?.textContent==='${attachment.sha256}'")
            click("importPdf")
            waitDom("document.querySelector('[data-node=pdf_page_text]')?.textContent.includes('PAGE 1')===true")
            click("pdfNext")
            waitDom("document.querySelector('[data-node=pdf_page_text]')?.textContent.includes('PAGE 2')===true")
            waitDom("(()=>{const n=document.querySelector('img[data-node=import_pdf_picture]');return !!n&&n.complete&&n.naturalWidth>0})()")
            html.js("document.querySelector('[data-node=import_pdf_picture]').scrollIntoView({block:'center'})")
            waitDom("(()=>{const r=document.querySelector('[data-node=import_pdf_picture]').getBoundingClientRect();return r.height>80&&r.top>=0&&r.bottom<innerHeight})()")
            val screenshot=html.screenshot("chat-attachment-workspace-pdf")
            try {
                var blue=0
                for(y in 0 until screenshot.height step 4)for(x in 0 until screenshot.width step 4){
                    val p=screenshot.getPixel(x,y)
                    if(android.graphics.Color.blue(p)>180&&android.graphics.Color.red(p)<70&&android.graphics.Color.green(p)<70)blue++
                }
                assertTrue("The saved attachment's PDF must actually paint",blue>100)
            }finally{screenshot.recycle()}
            click("prepareImport");click("confirm");confirm(false)
            assertTrue(runBlocking { app.workspaces.list(workspace.id) }.none { it.name=="saved.pdf" })
            click("confirm");confirm()
            waitDom("Array.from(document.querySelectorAll('[data-node^=file_name_]')).some(n=>n.textContent==='saved.pdf')")
            assertArrayEquals(bytes,File(runBlocking { app.workspaces.location(workspace.id) },"saved.pdf").readBytes())
            assertArrayEquals(bytes,runBlocking { app.attachments.read(session.id,attachment) })
            assertEquals(attachment,runBlocking { app.chatStore.history(session.id) }.single().attachments.single())
            assertEquals("untracked",runBlocking { git.status() }.entries.single { it.path=="saved.pdf" }.working)
            val result=runBlocking {
                val job=app.workspaces.run(workspace.id,shell.id,"/system/bin/toybox sha256sum saved.pdf",10_000)
                withTimeout(15_000) { app.workspaces.jobs.first { jobs->jobs.any { it.id==job.id&&it.stage==ShellStage.COMPLETED } }.first { it.id==job.id } }
            }
            assertEquals(0,result.exitCode);assertTrue(result.output.joinToString("") { it.text }.contains(attachment.sha256))

            // Same persisted source, now with an existing destination: inspect, cancel, then
            // let the real Shell change it after review. A stale confirmation must not write.
            click("home");click("chatFiles");click("chat_file_select_0");confirm()
            click("prepareImport")
            waitDom("document.querySelector('[data-node=replace_sha]')?.textContent==='${attachment.sha256}'")
            click("cancel")
            val root=File(runBlocking { app.workspaces.location(workspace.id) })
            assertArrayEquals(bytes,File(root,"saved.pdf").readBytes())
            assertTrue(root.listFiles()!!.none { it.name.startsWith("solvit-backup-") })
            click("prepareImport")
            val external=runBlocking {
                val job=app.workspaces.run(workspace.id,shell.id,"printf 'Shell modified after review' > saved.pdf",10_000)
                withTimeout(15_000) { app.workspaces.jobs.first { jobs->jobs.any { it.id==job.id&&it.stage==ShellStage.COMPLETED } }.first { it.id==job.id } }
            }
            assertEquals(0,external.exitCode)
            click("confirm");confirm()
            waitDom("!!document.getElementById('field-importPath') && document.querySelector('[data-node=message]')?.textContent.includes('重新审阅')===true")
            assertEquals("Shell modified after review",File(root,"saved.pdf").readText())
            assertTrue(root.listFiles()!!.none { it.name.startsWith("solvit-backup-") })
            click("prepareImport")
            waitDom("!!document.querySelector('[data-node=replace_warning]') && document.querySelector('[data-node=confirm]')?.textContent.includes('保留原文件')===true")
            html.screenshot("workspace-import-replacement-review").recycle()
            click("confirm");confirm()
            waitDom("document.querySelector('[data-node=message]')?.textContent.includes('solvit-backup-')===true")
            assertArrayEquals(bytes,File(root,"saved.pdf").readBytes())
            val backup=root.listFiles()!!.single { it.name.startsWith("solvit-backup-") }
            assertEquals("Shell modified after review",backup.readText())
            val status=runBlocking { git.status() }
            assertEquals("untracked",status.entries.single { it.path=="saved.pdf" }.working)
            assertEquals("untracked",status.entries.single { it.path==backup.name }.working)
            assertArrayEquals(bytes,runBlocking { app.attachments.read(session.id,attachment) })
        }finally{rule.runOnUiThread { visible=false };runBlocking { git.close() }}
    }
}
