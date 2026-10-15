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
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Inject only the disk availability observation, never the actual import/publisher/outcome.
 * Deliberately filling a shared emulator disk would corrupt unrelated acceptance fixtures. */
class WorkspaceImportCapacityHtmlTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun lowSpaceIsVisibleBeforeMutationAndSameSelectedAttachmentRetriesThroughRealNativeImport() {
        val app=rule.activity.application as SolvitApplication
        runBlocking { withTimeout(30_000){app.ready.filterNotNull().first().getOrThrow()} }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val available=AtomicLong(0)
        val native=AndroidWorkspaces(app,scope,importAvailableBytes={available.get()})
        val chat=runBlocking { app.chatStore.createSession().id }
        val workspace=runBlocking { native.create("Low-space import").also { native.bind(chat,it.id) } }
        val root=File(runBlocking { native.location(workspace.id) })
        val original="original survives known low-space rejection".toByteArray()
        File(root,"data.txt").writeBytes(original)
        val bytes="new imported bytes after capacity retry".toByteArray()
        val attachment=runBlocking { app.attachments.import(chat,"data.txt","text/plain",ByteArrayInputStream(bytes)) }
        runBlocking { app.chatStore.append(ChatMessage("capacity-source-${java.util.UUID.randomUUID()}",chat,Role.USER,"file",attachments=listOf(attachment))) }
        val bridge=WorkspacePageBridge(native,null,chat,
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
            click("chatFiles");click("chat_file_select_0");confirm()
            click("prepareImport");click("confirm");confirm()
            waitDom("!!document.getElementById('field-importPath')&&document.querySelector('[data-node=message]')?.textContent.includes('存储空间不足')===true")
            waitDom("document.querySelector('[data-node=message]')?.textContent.includes('可用 0 B')===true&&document.querySelector('[data-node=message]')?.textContent.includes('尚未开始导入')===true")
            assertArrayEquals(original,File(root,"data.txt").readBytes())
            assertEquals(listOf("data.txt"),root.listFiles()!!.map { it.name })
            assertTrue(runBlocking { native.importRecoveries(workspace.id) }.isEmpty())
            html.screenshot("workspace-import-insufficient-space").recycle()
            // Source bytes and the existing selection survive; this retry reviews the target
            // again and goes through a NEW native confirmation, then the production JNI move.
            assertArrayEquals(bytes,runBlocking { app.attachments.read(chat,attachment) })
            available.set(root.usableSpace)
            click("prepareImport");click("confirm");confirm()
            waitDom("document.querySelector('[data-node=message]')?.textContent.includes('solvit-backup-')===true")
            assertArrayEquals(bytes,File(root,"data.txt").readBytes())
            assertArrayEquals(original,root.listFiles()!!.single { it.name.startsWith("solvit-backup-") }.readBytes())
            val shell=runBlocking { native.createShell(workspace.id,"Capacity retry Shell") }
            val result=runBlocking {
                val job=native.run(workspace.id,shell.id,"/system/bin/toybox sha256sum data.txt",10_000)
                withTimeout(15_000){native.jobs.first { jobs->jobs.any { it.id==job.id&&it.stage==ShellStage.COMPLETED } }.first { it.id==job.id }}
            }
            assertEquals(0,result.exitCode);assertTrue(result.output.joinToString(""){it.text}.contains(attachment.sha256))
            assertTrue(runBlocking { native.importRecoveries(workspace.id) }.isEmpty())
        }finally {
            rule.runOnUiThread { visible=false }
            runBlocking { bridge.close();native.close() };scope.cancel()
        }
    }
}
