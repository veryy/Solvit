package dev.solvit.plugins

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.solvit.core.ExtensionPageDocument
import dev.solvit.core.ExtensionPageResource

/** One independent QuickJS instance per open page, owned and closed by the host UI. */
class ScriptPageSession(
    private val pkg: ScriptPackage,
    private val pageId: String,
    private val locale: String,
    host: ScriptHost,
    private val ensureAuthorized: suspend () -> Unit,
) {
    init { require(pkg.manifest.apiVersion >= 9 && pkg.manifest.pages.any { it.id == pageId }) }
    private val mutex = Mutex()
    private val authority = ScriptPageExecutionContext(pkg.manifest.id, ensureAuthorized)
    private val executor = ScriptExecutor(pkg, ScriptHost { manifest, operation, args ->
        ensureAuthorized()
        host.call(manifest, operation, args)
    }, invokeLifecycle = false)
    @Volatile private var closed = false
    private var view: ScriptPageView? = null
    @Volatile private var opened = false

    suspend fun open(): ScriptPageView = mutex.withLock {
        check(!closed && !opened)
        ensureAuthorized()
        try {
            withContext(authority) { executor.start() }; opened = true
            render(null)
        } catch (e: Throwable) {
            closed = true
            withContext(NonCancellable) { executor.stop() }
            throw e
        }
    }

    suspend fun dispatch(expectedView: ScriptPageView, nodeId: String, value: String? = null, fields: Map<String, String> = emptyMap(), confirmed: Boolean = false): ScriptPageView = mutex.withLock {
        check(!closed && opened)
        check(view === expectedView) { "Page has changed; use the current view" }
        val event = checkNotNull(view).event(nodeId, value, fields, confirmed)
        render(event, view!!.hostAction(nodeId))
    }

    suspend fun document(): ExtensionPageDocument? {
        check(!closed && opened)
        ensureAuthorized()
        return pkg.manifest.pages.single { it.id == pageId }.entry?.let { entry ->
            ExtensionPageDocument(entry, pkg.manifest.settings.any { it.pageManaged && it.kind == "secret" })
        }
    }

    suspend fun resource(path: String): ExtensionPageResource {
        check(!closed && opened)
        ensureAuthorized()
        check(pkg.manifest.pages.single { it.id == pageId }.entry != null)
        val mime = ScriptWebResources.mimeType(path)
        val content = pkg.resources[path] ?: error("插件页面资源不存在")
        ensureAuthorized()
        check(!closed)
        return ExtensionPageResource(mime, content)
    }

    private suspend fun render(event: kotlinx.serialization.json.JsonObject?, approvedAction: kotlinx.serialization.json.JsonObject? = null): ScriptPageView {
        check(!closed)
        ensureAuthorized()
        val invocation = ScriptPageExecutionContext(authority.pluginId, ensureAuthorized, approvedAction)
        val next = withContext(invocation) { executor.page(pageId, locale, event) }
        check(!closed)
        ensureAuthorized()
        view = next
        return next
    }

    suspend fun close() {
        closed = true
        // Interrupt work before waiting for the page mutex; repeat after a racing start.
        withContext(NonCancellable) {
            executor.stop()
            mutex.withLock { executor.stop(); view = null }
        }
    }
}
