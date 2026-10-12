package dev.solvit.plugins

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One independent QuickJS instance per open page, owned and closed by the host UI. */
class ScriptPageSession(
    pkg: ScriptPackage,
    private val pageId: String,
    private val locale: String,
    host: ScriptHost,
    private val ensureAuthorized: suspend () -> Unit,
) {
    init { require(pkg.manifest.apiVersion >= 9 && pkg.manifest.pages.any { it.id == pageId }) }
    private val mutex = Mutex()
    private val executor = ScriptExecutor(pkg, ScriptHost { manifest, operation, args ->
        ensureAuthorized()
        host.call(manifest, operation, args)
    }, invokeLifecycle = false)
    @Volatile private var closed = false
    private var view: ScriptPageView? = null
    private var opened = false

    suspend fun open(): ScriptPageView = mutex.withLock {
        check(!closed && !opened)
        ensureAuthorized()
        try {
            executor.start(); opened = true
            render(null)
        } catch (e: Throwable) {
            closed = true
            withContext(NonCancellable) { executor.stop() }
            throw e
        }
    }

    suspend fun dispatch(nodeId: String, value: String? = null, fields: Map<String, String> = emptyMap(), confirmed: Boolean = false): ScriptPageView = mutex.withLock {
        check(!closed && opened)
        val event = checkNotNull(view).event(nodeId, value, fields, confirmed)
        render(event)
    }

    private suspend fun render(event: kotlinx.serialization.json.JsonObject?): ScriptPageView {
        check(!closed)
        ensureAuthorized()
        val next = executor.page(pageId, locale, event)
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
