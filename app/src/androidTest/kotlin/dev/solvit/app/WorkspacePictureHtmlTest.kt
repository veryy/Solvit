package dev.solvit.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
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
import dev.solvit.documents.AndroidFilePictureRenderer
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

class WorkspacePictureHtmlTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun selectedPngPaintsInsideTheActualHtmlPageBeforeOriginalFileImport() {
        val app=rule.activity.application as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
        val chat="picture-html-${UUID.randomUUID()}"
        val workspace=runBlocking { app.workspaces.create("Picture fixture").also { app.workspaces.bind(chat,it.id) } }
        val export=File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"updates").apply { check(mkdirs()||isDirectory) }
        val selected=File(export,"picture-${UUID.randomUUID()}.png")
        val bitmap=Bitmap.createBitmap(800,400,Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.RED);selected.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
        val bytes=selected.readBytes()
        val uri=FileProvider.getUriForFile(app,"${app.packageName}.updates",selected)
        val picker=object:ActivityResultRegistry() {
            override fun <I,O> onLaunch(code:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
                assertEquals(Intent.ACTION_OPEN_DOCUMENT,contract.createIntent(app,input).action)
                Handler(Looper.getMainLooper()).post { dispatchResult(code,Activity.RESULT_OK,Intent().setData(uri)) }
            }
        }
        val owner=object:ActivityResultRegistryOwner { override val activityResultRegistry=picker }
        val manager=BuiltinWorkspaceManager(app)
        val html=HtmlPageProbe(rule.activity)
        var visible by mutableStateOf(true)
        fun waitDom(expression:String) { rule.waitUntil(10_000) { runCatching { html.js(expression)=="true" }.getOrDefault(false) } }
        fun click(id:String) { waitDom("!!document.querySelector('[data-node=$id]') && !document.querySelector('[data-node=$id]').disabled");html.js("document.querySelector('[data-node=$id]').click()") }
        fun confirm() {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_page_confirm").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isEmpty() }
        }
        try {
            rule.runOnUiThread { rule.activity.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { MaterialTheme {
                if(visible)PluginPageScreen("工作区",{manager.openPage("zh",WorkspacePageBridge(app.workspaces,app.pluginDrafts,chat,pictureRenderer=AndroidFilePictureRenderer()))}) { visible=false }
            } } } }
            waitDom("window.pluginPageReady===true && !!document.querySelector('[data-node=selectImport]')")
            click("selectImport");confirm();click("importPicture")
            waitDom("(()=>{const img=document.querySelector('img[data-node=import_picture]');return !!img&&img.complete&&img.naturalWidth>0&&img.naturalWidth<=640&&img.naturalHeight<=640})()")
            html.js("document.querySelector('[data-node=import_picture]').scrollIntoView({block:'center'})")
            waitDom("(()=>{const r=document.querySelector('[data-node=import_picture]').getBoundingClientRect();return r.height>80&&r.top>=0&&r.bottom<innerHeight})()")
            val screenshot=html.screenshot("workspace-import-image")
            try {
                var red=0
                for(y in 0 until screenshot.height step 4)for(x in 0 until screenshot.width step 4){
                    val pixel=screenshot.getPixel(x,y)
                    if(Color.red(pixel)>200&&Color.green(pixel)<60&&Color.blue(pixel)<60)red++
                }
                assertTrue("Actual screen must paint the red image, not only an img DOM node",red>1000)
            } finally { screenshot.recycle() }
            assertTrue(runBlocking { app.workspaces.list(workspace.id) }.isEmpty())
            assertArrayEquals(bytes,selected.readBytes())
            click("prepareImport");click("confirm");confirm()
            waitDom("!!document.querySelector('[data-node=file_name_0]')")
            assertArrayEquals(bytes,File(runBlocking { app.workspaces.location(workspace.id) },selected.name).readBytes())
        } finally { rule.runOnUiThread { visible=false };selected.delete() }
    }
}
