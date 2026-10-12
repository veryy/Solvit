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
    private fun activeBrowser(): WebView? = android.view.inspector.WindowInspector.getGlobalWindowViews()
        .firstNotNullOfOrNull { browser(it) }
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
        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).let { instrument.sendPointerSync(it); it.recycle() }
        MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0).let { instrument.sendPointerSync(it); it.recycle() }
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
            val root = android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull { browser(it) != null }
            val flags = (root?.layoutParams as? android.view.WindowManager.LayoutParams)?.flags ?: 0
            result.complete(flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        withTimeout(10_000) { result.await() }
    }
}
