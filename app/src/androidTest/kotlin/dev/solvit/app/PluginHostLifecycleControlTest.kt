package dev.solvit.app

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Real production page host and normal dispatcher, with only a minimal offline document.
 * No Compose test rule, terminal script, workspace service or page authorization fixture.
 */
@RunWith(AndroidJUnit4::class)
class PluginHostLifecycleControlTest {
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Plugin host control: $label")
            SystemClock.sleep(50)
        }
    }

    @Test fun minimalDocumentUsesProductionHostAndReleasesAfterRecreation() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
        val repetitions = InstrumentationRegistry.getArguments().getString("workspaceRecreations")?.toInt() ?: 3
        require(repetitions in 3..30)
        val document = ExtensionPageDocument("ui/control.html")
        val pageView = ScriptPageView.parse("""{"title":"Lifecycle control","nodes":[]}""")
        val resource = ExtensionPageResource("text/html", """
            <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
            <div id="state">host control</div><script>
            window.solvitPageUpdate = state => { document.getElementById('state').textContent = state.view.title; };
            solvitHost.postMessage(JSON.stringify({type:'ready'}));
            </script>
        """.trimIndent())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(repetitions) { index ->
                scenario.recreate()
                val visible = mutableStateOf(true)
                lateinit var activity: MainActivity
                scenario.onActivity {
                    activity = it
                    it.setContent { MaterialTheme {
                        if (visible.value) PluginWebPage(pageView, document,
                            { path -> check(path == document.entry); resource }, busy = false) {}
                    } }
                }
                val probe = HtmlPageProbe(activity)
                try {
                    await("actual browser attached") { probe.hasAttachedBrowser() }
                    // Like the failing workspace path, release immediately after
                    // attachment, without waiting for DOM-ready or bridge completion.
                    scenario.onActivity { visible.value = false }
                    await("actual browser detached") { !probe.hasAttachedBrowser() }
                    instrument.waitForIdleSync()
                    assertFalse(probe.hasAttachedBrowser())
                    android.util.Log.i("WORKSPACE_PAGE_DIAG", "PLUGIN_HOST_CONTROL_COMPLETED|${index + 1}|$repetitions")
                } finally {
                    scenario.onActivity { visible.value = false }
                }
            }
        }
    }
}
