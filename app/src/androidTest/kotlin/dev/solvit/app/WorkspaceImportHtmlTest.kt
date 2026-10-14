package dev.solvit.app

import android.app.Activity
import android.content.Intent
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.solvit.core.ShellStage
import dev.solvit.market.BuiltinWorkspaceManager
import dev.solvit.market.WorkspacePageBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Actual HTML/QuickJS, Android resolver, native no-clobber import, Git and Shell.
 * ActivityResultRegistry supplies a real content URI; this does not tap DocumentsUI. */
class WorkspaceImportHtmlTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun selectReviewCancelConflictRenameAndReopenKeepOriginalBinaryInGitAndShell() {
        val app=rule.activity.application as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
        val chat="import-html-${UUID.randomUUID()}"
        val workspace=runBlocking { app.workspaces.create("Import fixture").also { app.workspaces.bind(chat,it.id) } }
        val shell=runBlocking { app.workspaces.createShell(workspace.id,"Import Shell") }
        val git=app.workspaceGit.bind(chat,{chat},{},{})
        runBlocking { git.initialize();app.workspaces.write(workspace.id,"existing.bin","keep original") }
        val bytes=ByteArray(300_000) { it.toByte() }
        val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val export=File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"updates").apply { check(mkdirs()||isDirectory) }
        val selected=File(export,"import-${UUID.randomUUID()}.bin").apply { writeBytes(bytes) }
        val uri=FileProvider.getUriForFile(app,"${app.packageName}.updates",selected)
        var pickerLaunches=0
        val picker=object:ActivityResultRegistry() {
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
                val intent=contract.createIntent(app,input)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.action)
                assertEquals("*/*",intent.type)
                pickerLaunches++
                Handler(Looper.getMainLooper()).post { dispatchResult(requestCode,Activity.RESULT_OK,Intent().setData(uri)) }
            }
        }
        val owner=object:ActivityResultRegistryOwner { override val activityResultRegistry=picker }
        val manager=BuiltinWorkspaceManager(app)
        val html=HtmlPageProbe(rule.activity)
        var visible by mutableStateOf(true)
        fun waitDom(expression:String) { rule.waitUntil(10_000) { runCatching { html.js(expression)=="true" }.getOrDefault(false) } }
        fun click(id:String) {
            waitDom("!!document.querySelector('[data-node=$id]') && !document.querySelector('[data-node=$id]').disabled")
            html.js("document.querySelector('[data-node=$id]').click()")
        }
        fun confirm(accept:Boolean) {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            if(accept)rule.onNodeWithTag("plugin_page_confirm").performClick() else rule.onNodeWithText("取消").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isEmpty() }
        }
        fun target(path:String) {
            html.js("(()=>{const field=document.getElementById('field-importPath');field.value=${JsonPrimitive(path)};field.dispatchEvent(new Event('input'))})()")
        }
        try {
            rule.runOnUiThread { rule.activity.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme { if(visible)PluginPageScreen("工作区",{manager.openPage("zh",WorkspacePageBridge(app.workspaces,app.pluginDrafts,chat))}) { visible=false } }
            } } }
            waitDom("window.pluginPageReady===true && !!document.querySelector('[data-node=selectImport]')")
            click("selectImport");confirm(false);assertEquals(0,pickerLaunches)
            click("selectImport");confirm(true)
            waitDom("document.querySelector('[data-node=import_type]')?.textContent.includes('300000 B')===true")
            assertEquals(1,pickerLaunches)
            waitDom("document.querySelector('[data-node=import_sha]')?.textContent==='$sha'")
            assertEquals(listOf("existing.bin"),runBlocking { app.workspaces.list(workspace.id) }.filter { !it.name.startsWith(".") }.map { it.name })
            html.screenshot("workspace-import-selected")
            target("existing.bin");click("prepareImport");click("confirm");confirm(false)
            assertEquals("keep original",runBlocking { app.workspaces.read(workspace.id,"existing.bin") })
            click("confirm");confirm(true)
            waitDom("document.querySelector('[data-node=message]')?.textContent.length>0")
            assertEquals("keep original",runBlocking { app.workspaces.read(workspace.id,"existing.bin") })
            click("cancel");waitDom("!!document.getElementById('field-importPath')")
            target("imported.bin");click("prepareImport");click("confirm");confirm(true)
            waitDom("Array.from(document.querySelectorAll('[data-node^=file_name_]')).some(n=>n.textContent==='imported.bin')")
            val status=runBlocking { git.status() }
            assertEquals("untracked",status.entries.first { it.path=="imported.bin" }.working)
            val result=runBlocking {
                val job=app.workspaces.run(workspace.id,shell.id,"/system/bin/toybox sha256sum imported.bin",10_000)
                withTimeout(15_000) { app.workspaces.jobs.first { jobs->jobs.any { j->j.id==job.id&&j.stage==ShellStage.COMPLETED } }.first { it.id==job.id } }
            }
            assertEquals(0,result.exitCode);assertTrue(result.output.joinToString("") { it.text }.contains(sha))
            html.screenshot("workspace-import-directory")
            rule.onNodeWithTag("plugin_page_close").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_web_page").fetchSemanticsNodes().isEmpty() }
            rule.runOnUiThread { visible=true }
            waitDom("window.pluginPageReady===true && !!document.querySelector('[data-node=directory]')")
            click("directory")
            waitDom("Array.from(document.querySelectorAll('[data-node^=file_name_]')).some(n=>n.textContent==='imported.bin')")
            assertEquals(1,pickerLaunches)
        } finally {
            rule.runOnUiThread { visible=false }
            runBlocking { git.close() }
            selected.delete()
        }
    }
}
