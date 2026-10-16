package dev.solvit.app

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.ExtensionPageDocument
import dev.solvit.core.ExtensionPageResource
import dev.solvit.plugins.ScriptPageView
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Actual delayed page handshake and live host state; no Compose test dispatcher. */
@RunWith(AndroidJUnit4::class)
class PluginPageReadyStateTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Page ready state: $label")
            SystemClock.sleep(50)
        }
    }
    private fun hasText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser && node.text?.toString() == text) return true
        return (0 until node.childCount).any { hasText(node.getChild(it), text) }
    }
    private fun awaitHostTitle(title: String) {
        await("host has composed $title") { hasText(instrument.uiAutomation.rootInActiveWindow, title) }
        instrument.waitForIdleSync()
    }

    @Test fun delayedReadyReceivesLatestStateWithoutEarlierScriptDelivery() {
        val app = instrument.targetContext.applicationContext as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
        val title = mutableStateOf("Initial")
        val document = ExtensionPageDocument("ui/ready.html")
        val resource = ExtensionPageResource("text/html", """
            <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
            <script>
            window.received = [];
            window.solvitPageUpdate = state => { window.received.push(state.view.title); };
            window.sendReady = () => solvitHost.postMessage(JSON.stringify({type:'ready'}));
            </script>
            <p>Waiting for the test to send the actual ready message.</p>
        """.trimIndent())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity {
                activity = it
                it.setContent { MaterialTheme { Column(Modifier.fillMaxSize()) {
                    Text(title.value)
                    PluginWebPage(ScriptPageView.parse("""{"title":"${title.value}","nodes":[]}"""), document,
                        { path -> check(path == document.entry); resource }, busy = false,
                        modifier = Modifier.weight(1f)) {}
                } } }
            }
            val probe = HtmlPageProbe(activity)
            await("real document defines its receiver") {
                runCatching { probe.js("typeof window.sendReady") == "\"function\"" }.getOrDefault(false)
            }
            scenario.onActivity { title.value = "Latest" }
            awaitHostTitle("Latest")
            assertEquals("The host must buffer changes until the page explicitly sends ready",
                "0", probe.js("window.received.length"))
            probe.js("window.sendReady()")
            await("ready delivers the latest state") { probe.js("window.received[0]") == "\"Latest\"" }
            scenario.onActivity { title.value = "After ready" }
            awaitHostTitle("After ready")
            await("later host state still reaches the live page") {
                probe.js("window.received[window.received.length-1]") == "\"After ready\""
            }
            assertEquals("false", probe.js("window.received.includes('Initial')"))
        }
    }
}
