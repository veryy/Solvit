package dev.solvit.app

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.ModelTask
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production Activity/dispatcher, system accessibility discovery and real pointer injection.
 * Deliberately has no Compose test rule, replacement frame clock or setContent fixture.
 */
@RunWith(AndroidJUnit4::class)
class WebViewUserLifecycleTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrument.uiAutomation

    private fun nodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(root)
        for (index in 0 until root.childCount) {
            val child = root.getChild(index)
            if (child != null) yieldAll(nodes(child))
        }
    }

    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        val screen = instrument.targetContext.resources.displayMetrics
        return nodes(root).firstOrNull { candidate ->
            val bounds = Rect().also(candidate::getBoundsInScreen)
            candidate.isVisibleToUser && candidate.isEnabled && bounds.width() > 0 && bounds.height() > 0 &&
                bounds.right > 0 && bounds.bottom > 0 && bounds.left < screen.widthPixels &&
                bounds.top < screen.heightPixels && predicate(candidate)
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("User lifecycle: $label")
            SystemClock.sleep(50) // Poll actual observable state; never advance a test clock.
        }
    }

    private fun tap(label: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
        await("$label must be visible") { node(predicate) != null }
        val bounds = Rect().also(checkNotNull(node(predicate))::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                instrument.sendPointerSync(event)
            } finally { event.recycle() }
        }
    }

    private fun text(value: String): (AccessibilityNodeInfo) -> Boolean = { it.text?.toString() == value }
    private fun description(value: String): (AccessibilityNodeInfo) -> Boolean = { it.contentDescription?.toString() == value }

    @Test fun workspaceRecreationUsesProductionDispatcherAndReleasesTheActualBrowser() {
        val app = instrument.targetContext.applicationContext as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
        val previous = app.modelProfiles.state.value
        val previousLanguage = app.language.language.value
        val recreations = InstrumentationRegistry.getArguments().getString("workspaceRecreations")?.toInt() ?: 3
        require(recreations in 3..30)
        try {
            app.language.set("zh")
            runBlocking { app.modelProfiles.save(checkNotNull(previous.selected(ModelTask.CHAT))) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                repeat(recreations) { index ->
                    scenario.recreate()
                    tap("history", description("对话历史"))
                    tap("new chat", text("新对话"))
                    await("drawer closes") { node(text("新对话")) == null }
                    await("unbound new chat") { node(text("新建工作区")) != null }
                    assertNull(node(text("配置你的模型")))
                    assertNull(node { it.text?.startsWith("工作区：") == true })
                    tap("create workspace", text("新建工作区"))
                    await("workspace name field") { node { it.isEditable } != null }
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "UI workspace ${index + 1}")
                    }
                    assertTrue("System text action must update the real name field",
                        checkNotNull(node { it.isEditable }).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
                    tap("confirm creation", text("创建并绑定"))
                    await("bound workspace") { node(description("关闭工作区")) != null && node(text("命令任务")) != null }
                    lateinit var activity: MainActivity
                    scenario.onActivity { activity = it }
                    val page = HtmlPageProbe(activity)
                    await("interactive browser attached") { page.hasAttachedBrowser() }
                    // Match the failing Compose scenario: switch away while the new
                    // browser may still be loading; do not add a DOM-ready delay.
                    tap("command jobs", text("命令任务"))
                    await("browser released") { !page.hasAttachedBrowser() }
                    await("command input") { node { it.isEditable } != null }
                    tap("close workspace", description("关闭工作区"))
                    await("chat retains workspace") { node { it.text?.startsWith("工作区：") == true } != null }
                    assertFalse(page.hasAttachedBrowser())
                    android.util.Log.i("WORKSPACE_PAGE_DIAG", "USER_LIFECYCLE_COMPLETED|${index + 1}|$recreations")
                }
            }
        } finally {
            runBlocking { app.modelProfiles.replace(previous) }
            app.language.set(previousLanguage)
        }
    }
}
