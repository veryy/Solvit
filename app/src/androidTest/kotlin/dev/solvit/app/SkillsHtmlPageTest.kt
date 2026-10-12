package dev.solvit.app

import android.content.ContentValues
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.graphics.Rect
import android.view.MotionEvent
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Actual settings tab -> plugin HTML -> system SAF picker -> QuickJS -> durable data -> export/reimport. */
class SkillsHtmlPageTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        if (!root.packageName.toString().contains("documentsui")) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        var examined = 0
        while (queue.isNotEmpty() && examined++ < 1000) {
            val current = queue.removeFirst()
            if (current.isVisibleToUser && predicate(current)) return current
            for (index in 0 until current.childCount) current.getChild(index)?.let(queue::add)
        }
        return null
    }
    private fun clickSystem(predicate: (AccessibilityNodeInfo) -> Boolean) {
        var target: AccessibilityNodeInfo? = null
        rule.waitUntil(10_000) { node(predicate)?.also { target = it } != null }
        var clickable = checkNotNull(target)
        repeat(8) { if (!clickable.isClickable) clickable.parent?.let { clickable = it } }
        // DocumentsUI's drawer ListView rows do not consistently expose ACTION_CLICK.
        // Tap the bounds of the observed, visible control rather than simulating a picker result.
        val bounds = Rect().also(clickable::getBoundsInScreen)
        assertFalse("System document control must have visible bounds", bounds.isEmpty)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { assertTrue("System document control must receive actual touch input", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
    }
    private fun selectFile(name: String) {
        if (node { it.text?.toString() == name } == null) {
            clickSystem { it.contentDescription?.toString()?.contains("Show roots", ignoreCase = true) == true ||
                it.viewIdResourceName?.endsWith("/drawer_icon") == true || it.viewIdResourceName == "android:id/home" }
            clickSystem { it.text?.toString() == "Downloads" }
        }
        clickSystem { it.text?.toString() == name }
    }
    @Test fun importsTextZipThroughSystemPickerExportsReimportsAndUsesActualDeleteConfirmation() {
        val app = rule.activity.application as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() }; app.marketplace.enable("dev.solvit.skills", setOf("storage")) }
        val name = "page-documents-writing"
        val vm = AgentLibraryViewModel(app.roles, app.skills, app.marketplace)
        vm.setSession("skills-html-settings-fixture")
        val doc = "---\nname: $name\ndescription: Native file import fixture\n---\nWrite concrete sentences."
        val script = "throw Error('must not execute')"
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                mapOf("$name/SKILL.md" to doc, "$name/references/style.md" to "Use clear examples.", "$name/scripts/example.js" to script)
                    .forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
            }
        }.toByteArray()
        val filename = "solvit-skills-fixture-${System.currentTimeMillis()}.zip"
        val uri = checkNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename); put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS); put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        app.contentResolver.openOutputStream(uri)!!.use { it.write(archive) }
        app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        val html = HtmlPageProbe(rule.activity)
        fun waitDom(expression: String) = rule.waitUntil(15_000) { runCatching { html.js(expression) == "true" }.getOrDefault(false) }
        fun click(id: String) { html.js("document.querySelector('[data-node='+${JsonPrimitive(id)}+']').click()") }
        fun confirm() {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_page_confirm").performClick()
        }
        fun openSkill() {
            html.js("(()=>{const name=Array.from(document.querySelectorAll('p')).find(n=>n.dataset.node?.startsWith('name_')&&n.textContent===${JsonPrimitive(name)});name.closest('.card').querySelector('button').click()})()")
            waitDom("!!document.querySelector('[data-node=export]')")
        }
        try {
            runBlocking { if (app.skills.list().any { it.name == name }) app.skills.remove(name) }
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme(colorScheme = darkColorScheme()) { AgentLibraryDialog(vm) {} } } }
            rule.onNodeWithTag("skills_library_tab").performClick()
            waitDom("window.pluginPageReady===true && !!document.querySelector('[data-node=import]')")
            click("import"); confirm()
            rule.waitUntil(10_000) { node { true } != null }
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
            waitDom("document.querySelector('[data-node=message]')?.textContent==='已取消'")
            assertFalse(runBlocking { app.skills.list().any { it.name == name } })
            click("import"); confirm(); selectFile(filename)
            waitDom("document.querySelector('[data-node=preview]')?.textContent==='导入预览'")
            assertFalse(runBlocking { app.skills.list().any { it.name == name } })
            html.screenshot("skills-html-import-preview").recycle()
            click("confirm_import"); confirm()
            waitDom("!document.querySelector('[data-node=preview]') && document.body.textContent.includes(${JsonPrimitive(name)})")
            assertEquals("Write concrete sentences.", runBlocking { app.skills.load(name).instructions })
            rule.onNodeWithTag("plugin_page_close").performClick()
            rule.onNodeWithTag("skills_library_tab").performClick()
            waitDom("window.pluginPageReady===true && document.body.textContent.includes(${JsonPrimitive(name)})")
            html.screenshot("skills-html-library").recycle()
            openSkill(); click("export"); confirm()
            clickSystem { it.isEnabled && it.text?.toString()?.equals("Save", ignoreCase = true) == true }
            waitDom("document.querySelector('[data-node=message]')?.textContent==='已导出'")
            click("back"); waitDom("!!document.querySelector('[data-node=import]')")
            click("import"); confirm(); selectFile("$name.skill.json")
            waitDom("!!document.getElementById('field-replacement')")
            assertEquals("true", html.js("document.querySelector('[data-node=confirm_import]').disabled"))
            html.js("const field=document.getElementById('field-resource');field.value='scripts/example.js';field.dispatchEvent(new Event('change'))")
            waitDom("document.body.textContent.includes(${JsonPrimitive(script)})")
            assertEquals("4", html.js("document.getElementById('field-resource').options.length"))
            click("back"); waitDom("!!document.querySelector('[data-node=import]')")
            openSkill(); click("delete")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("取消").performClick()
            assertTrue(runBlocking { app.skills.list().any { it.name == name } })
            click("delete"); confirm(); waitDom("document.querySelector('[data-node=message]')?.textContent==='已删除'")
            assertFalse(runBlocking { app.skills.list().any { it.name == name } })
        } finally {
            vm.viewModelScope.cancel()
            runBlocking { if (app.skills.list().any { it.name == name }) app.skills.remove(name); app.marketplace.disable("dev.solvit.skills") }
            app.contentResolver.delete(uri, null, null)
        }
    }
}
