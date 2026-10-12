package dev.solvit.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.view.MotionEvent
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Evaluate the actual loaded document; callbacks and storage are never mocked. */
internal class HtmlPageProbe(private val activity: MainActivity) {
    private fun browser(view: View): WebView? = if (view is WebView) view else
        (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { browser(group.getChildAt(it)) } }
    private fun owned(web: WebView): Boolean {
        var context: android.content.Context = web.context
        while (context is android.content.ContextWrapper) {
            if (context === activity) return true
            val next = context.baseContext
            if (next === context) break
            context = next
        }
        return context === activity
    }
    private fun activeBrowser(): WebView? {
        val roots = android.view.inspector.WindowInspector.getGlobalWindowViews().asReversed()
        fun candidate(root: View) = browser(root)?.takeIf { it.isShown && owned(it) }
        return roots.filter { it.hasWindowFocus() }.firstNotNullOfOrNull(::candidate)
            ?: roots.firstNotNullOfOrNull(::candidate) // A native confirmation temporarily owns focus.
    }
    fun js(code: String): String = runBlocking {
        val result = CompletableDeferred<String>()
        activity.runOnUiThread {
            val web = activeBrowser()
            if (web == null) result.completeExceptionally(IllegalStateException("No active HTML page"))
            else web.evaluateJavascript(code) { result.complete(it) }
        }
        withTimeout(10_000) { result.await() }
    }
    fun tapMemoryNode(id: String) {
        val point = Json.parseToJsonElement(js("(()=>{const node=document.querySelector('[data-memory-id='+${JsonPrimitive(id)}+']');return [Number(node.dataset.x),Number(node.dataset.y),innerWidth]})()")) .jsonArray
        var x = 0f; var y = 0f
        val ready = CompletableDeferred<Unit>()
        activity.runOnUiThread {
            val web = checkNotNull(activeBrowser()); val location = IntArray(2)
            web.getLocationOnScreen(location)
            val scale = web.width / point[2].jsonPrimitive.float
            x = location[0] + point[0].jsonPrimitive.float * scale
            y = location[1] + point[1].jsonPrimitive.float * scale
            ready.complete(Unit)
        }
        runBlocking { withTimeout(10_000) { ready.await() } }
        val instrument = InstrumentationRegistry.getInstrumentation()
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).let {
            it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; instrument.sendPointerSync(it); it.recycle()
        }
        MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).let {
            it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; instrument.sendPointerSync(it); it.recycle()
        }
    }
    fun screenshot(name: String) {
        require(name.matches(Regex("[a-z-]+")))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/solvit-ui")).use { it.readBytes() }
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /data/local/tmp/solvit-ui/$name.png")).use { it.readBytes() }
    }
    fun secureWindow(): Boolean = runBlocking {
        val result = CompletableDeferred<Boolean>()
        activity.runOnUiThread {
            val root = android.view.inspector.WindowInspector.getGlobalWindowViews().asReversed().firstOrNull {
                it.hasWindowFocus() && browser(it)?.let { web -> web.isShown && owned(web) } == true
            }
            val flags = (root?.layoutParams as? android.view.WindowManager.LayoutParams)?.flags ?: 0
            result.complete(flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        withTimeout(10_000) { result.await() }
    }
}
