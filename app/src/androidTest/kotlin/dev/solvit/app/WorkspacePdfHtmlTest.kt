package dev.solvit.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import dev.solvit.market.BuiltinWorkspaceManager
import dev.solvit.market.WorkspacePageBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real QuickJS/HTML, PDFBox and PdfRenderer. Supplies a real URI without tapping DocumentsUI. */
class WorkspacePdfHtmlTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun actualPdfPagesPaintAndNavigateBeforeImportingTheUnchangedOriginal() {
        val app = rule.activity.application as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
        val chat = "pdf-html-${UUID.randomUUID()}"
        val workspace = runBlocking { app.workspaces.create("PDF fixture").also { app.workspaces.bind(chat, it.id) } }
        val export = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { check(mkdirs() || isDirectory) }
        val bytes = workspacePdfFixture(app)
        val selected = File(export, "pages-${UUID.randomUUID()}.pdf").apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", selected)
        var cancelPicker = false
        val picker = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, contract.createIntent(app, input).action)
                Handler(Looper.getMainLooper()).post {
                    dispatchResult(code, if (cancelPicker) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                        if (cancelPicker) null else Intent().setData(uri))
                }
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        val html = HtmlPageProbe(rule.activity)
        val manager = BuiltinWorkspaceManager(app)
        var visible by mutableStateOf(true)
        fun waitDom(code: String) { rule.waitUntil(10_000) { runCatching { html.js(code) == "true" }.getOrDefault(false) } }
        fun click(id: String) {
            waitDom("!!document.querySelector('[data-node=$id]') && !document.querySelector('[data-node=$id]').disabled")
            html.js("document.querySelector('[data-node=$id]').click()")
        }
        fun confirm(accept: Boolean = true) {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            if (accept) rule.onNodeWithTag("plugin_page_confirm").performClick() else rule.onNodeWithText("取消").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isEmpty() }
        }
        fun field(id: String, value: String) {
            html.js("(()=>{const n=document.getElementById('field-$id');n.value='$value';n.dispatchEvent(new Event('input'))})()")
        }
        fun page(number: Int) {
            waitDom("document.querySelector('[data-node=pdf_page_text]')?.textContent.includes('PAGE $number')===true")
            waitDom("(()=>{const n=document.querySelector('img[data-node=import_pdf_picture]');return !!n&&n.complete&&n.naturalWidth>0&&n.naturalWidth<=640})()")
        }
        try {
            rule.runOnUiThread { rule.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { MaterialTheme {
                    if (visible) PluginPageScreen("工作区", { manager.openPage("zh", WorkspacePageBridge(
                        app.workspaces, app.pluginDrafts, chat, pdfRenderer = app.workspacePdfPreviews)) }) { visible = false }
                } }
            } }
            waitDom("window.pluginPageReady===true && !!document.querySelector('[data-node=selectImport]')")
            click("selectImport"); confirm(); click("importPdf"); page(1)
            waitDom("document.querySelector('[data-node=pdfPrev]').disabled===true")
            field("importPath", "original.pdf")
            click("pdfNext"); page(2)
            waitDom("document.querySelector('[data-node=pdfNext]').disabled===true && document.getElementById('field-importPath').value==='original.pdf'")
            html.js("document.querySelector('[data-node=import_pdf_picture]').scrollIntoView({block:'center'})")
            waitDom("(()=>{const r=document.querySelector('[data-node=import_pdf_picture]').getBoundingClientRect();return r.height>80&&r.top>=0&&r.bottom<innerHeight})()")
            val screenshot = html.screenshot("workspace-import-pdf")
            try {
                var blue = 0
                for (y in 0 until screenshot.height step 4) for (x in 0 until screenshot.width step 4) {
                    val p = screenshot.getPixel(x, y)
                    if (Color.blue(p) > 180 && Color.red(p) < 70 && Color.green(p) < 70) blue++
                }
                assertTrue("Actual display must paint the second PDF page", blue > 100)
            } finally { screenshot.recycle() }
            assertTrue(runBlocking { app.workspaces.list(workspace.id) }.isEmpty())
            assertArrayEquals(bytes, selected.readBytes())
            field("pdfPageNumber", "3"); click("pdfGo")
            waitDom("document.querySelector('[data-node=message]')?.textContent.includes('Invalid PDF page')===true")
            page(2); click("pdfPrev"); page(1)
            click("prepareImport"); click("cancel"); page(1)
            cancelPicker = true; click("selectImport"); confirm(); page(1)
            click("prepareImport"); click("confirm"); confirm(false)
            assertTrue(runBlocking { app.workspaces.list(workspace.id) }.isEmpty())
            click("confirm"); confirm()
            waitDom("Array.from(document.querySelectorAll('[data-node^=file_name_]')).some(n=>n.textContent==='original.pdf')")
            assertArrayEquals(bytes, File(runBlocking { app.workspaces.location(workspace.id) }, "original.pdf").readBytes())
        } finally { rule.runOnUiThread { visible = false }; selected.delete() }
    }
}
