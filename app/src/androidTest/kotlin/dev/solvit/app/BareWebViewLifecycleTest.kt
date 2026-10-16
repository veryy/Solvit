package dev.solvit.app

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native browser control: no plugin document, message bridge or Compose AndroidView.
 * MainActivity and the application's services remain; this is not a standalone app.
 */
@RunWith(AndroidJUnit4::class)
class BareWebViewLifecycleTest {
    @SuppressLint("SetJavaScriptEnabled")
    @Test fun nativeBrowserRecreationAndImmediateRelease() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
        val repetitions = InstrumentationRegistry.getArguments().getString("workspaceRecreations")?.toInt() ?: 3
        require(repetitions in 3..30)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(repetitions) { index ->
                scenario.recreate()
                lateinit var container: FrameLayout
                lateinit var browser: WebView
                var destroyed = false
                var created = false
                try {
                    scenario.onActivity { activity ->
                        container = FrameLayout(activity)
                        // Remove the normal Compose content; mount only a native view.
                        activity.setContentView(container)
                        browser = WebView(activity)
                        created = true
                        browser.apply {
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.blockNetworkLoads = true
                        }
                        container.addView(browser, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        browser.loadDataWithBaseURL("https://solvit-control.invalid/",
                            "<html><body><div id='state'>native control</div><script>window.update=function(){document.getElementById('state').textContent='updated'}</script></body></html>",
                            "text/html", "UTF-8", null)
                    }
                    instrument.waitForIdleSync()
                    scenario.onActivity {
                        assertTrue("The native browser must actually attach", browser.isAttachedToWindow)
                        assertSame(container, browser.parent)
                        browser.evaluateJavascript("window.update&&window.update()", null)
                        // Follow the product's detach/stop/remove/destroy order; no
                        // DOM-ready wait, delayed destruction or exception suppression.
                        container.removeView(browser)
                        assertNull(browser.parent)
                        browser.stopLoading()
                        browser.removeAllViews()
                        browser.destroy()
                        destroyed = true
                        assertEquals(0, container.childCount)
                    }
                    instrument.waitForIdleSync()
                    android.util.Log.i("WORKSPACE_PAGE_DIAG", "BARE_LIFECYCLE_COMPLETED|${index + 1}|$repetitions")
                } finally {
                    if (!destroyed && created) {
                        scenario.onActivity {
                            (browser.parent as? ViewGroup)?.removeView(browser)
                            browser.stopLoading()
                            browser.removeAllViews()
                            browser.destroy()
                        }
                    }
                }
            }
        }
    }
}
