package dev.solvit.market

import android.content.Context
import dev.solvit.core.*
import dev.solvit.plugins.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Human management uses immutable APK page code; it never enables AI tools or downloaded code. */
class BuiltinWorkspaceManager(context: Context, private val pluginId: String = "dev.solvit.workspace") {
    private val app = context.applicationContext
    private val pkg by lazy {
        ScriptPackage.parse(app.assets.open("builtin-plugins/$pluginId.solvit.json").use { it.readBytes() })
            .also { check(it.manifest.id == pluginId && (pluginId == "dev.solvit.workspace" && it.manifest.apiVersion == 17 || pluginId == "dev.solvit.terminal" && it.manifest.apiVersion == 13)) }
    }
    fun openPage(locale: String, access: PageWorkspaceAccess? = null, terminal: PageTerminalAccess? = null, pageId: String = "main"): ExtensionPageSession {
        require(locale.matches(Regex("[a-zA-Z0-9_-]{1,20}")))
        require(pageId == "main" || pageId == "changes" && pluginId == "dev.solvit.workspace" && access is ExecutionChangesPageBridge && terminal == null)
        return object : ExtensionPageSession {
            private val mutableClosed = MutableStateFlow(false)
            override val closed = mutableClosed.asStateFlow()
            private val mutex = Mutex()
            private var revision = 0L
            private var view: ScriptPageView? = null
            private val engine = ScriptPageSession(pkg, pageId, locale, ScriptHost { _, _, _ ->
                error("人工工作区页面未开放此能力")
            }) { check(!mutableClosed.value) { "Workspace page closed" } }.also { access?.let(it::attachWorkspace); terminal?.let(it::attachTerminal) }
            private fun snapshot(next: ScriptPageView): ExtensionPageSnapshot {
                check(!mutableClosed.value); view = next
                return ExtensionPageSnapshot(++revision, next.toJson())
            }
            override suspend fun open(): ExtensionPageSnapshot = mutex.withLock {
                try { snapshot(engine.open()) } catch (error: Throwable) { close(); throw error }
            }
            override suspend fun dispatch(revision: Long, nodeId: String, value: String?, fields: Map<String, String>, confirmed: Boolean): ExtensionPageSnapshot = mutex.withLock {
                check(!mutableClosed.value && revision == this.revision)
                snapshot(engine.dispatch(checkNotNull(view), nodeId, value, fields, confirmed))
            }
            override suspend fun document() = engine.document()
            override suspend fun terminalAccess() = engine.terminalAccess()
            override suspend fun resource(path: String) = engine.resource(path)
            override suspend fun close() { mutableClosed.value = true; withContext(NonCancellable) { engine.close() } }
        }
    }
}
