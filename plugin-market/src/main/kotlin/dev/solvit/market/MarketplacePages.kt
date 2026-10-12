package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Page instances are bound to a package installation; updates never reuse old code. */
internal class MarketplacePages(
    private val manager: PluginManager,
    private val verify: (ScriptPackage) -> Unit,
    private val host: ScriptHost,
) {
    private val lock = Any()
    private val active = mutableSetOf<Lease>()
    fun open(pkg: ScriptPackage, pageId: String, locale: String): ExtensionPageSession {
        require(pkg.manifest.apiVersion >= 9 && pkg.manifest.pages.any { it.id == pageId })
        require(locale.matches(Regex("[a-zA-Z0-9_-]{1,20}")))
        verify(pkg)
        val lease = synchronized(lock) {
            check(active.size < 8) { "打开的插件页面过多" }
            Lease(pkg, pageId, locale).also { active += it }
        }
        lease.monitor()
        return lease
    }
    suspend fun closePlugin(id: String) {
        val leases = synchronized(lock) { active.filter { it.pluginId == id }.also { list -> list.forEach { it.revoke() } } }
        withContext(NonCancellable) { leases.forEach { it.close() } }
    }

    private inner class Lease(private val pkg: ScriptPackage, pageId: String, locale: String) : ExtensionPageSession {
        val pluginId = pkg.manifest.id
        private val mutableClosed = MutableStateFlow(false)
        override val closed = mutableClosed.asStateFlow()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val mutex = Mutex()
        private var revision = 0L
        private var view: ScriptPageView? = null
        private val engine = ScriptPageSession(pkg, pageId, locale, host) { authorize() }
        private fun authorize() { check(!mutableClosed.value) { "插件页面已关闭" }; verify(pkg) }
        fun revoke() { mutableClosed.value = true }
        fun monitor() { scope.launch {
            manager.states.collect {
                if (runCatching { authorize() }.isFailure) close()
            }
        } }
        override suspend fun open(): ExtensionPageSnapshot = mutex.withLock {
            try { snapshot(engine.open()) }
            catch (e: Throwable) { close(); throw e }
        }
        override suspend fun dispatch(revision: Long, nodeId: String, value: String?, fields: Map<String, String>, confirmed: Boolean): ExtensionPageSnapshot = mutex.withLock {
            authorize()
            check(revision == this.revision) { "页面已变化，请使用当前页面" }
            snapshot(engine.dispatch(checkNotNull(view), nodeId, value, fields, confirmed))
        }
        override suspend fun document(): ExtensionPageDocument? { authorize(); return engine.document() }
        override fun attachDocuments(access: PageDocumentAccess) { authorize(); engine.attachDocuments(access) }
        override fun attachWorkspace(access: PageWorkspaceAccess) { authorize(); engine.attachWorkspace(access) }
        override suspend fun resource(path: String): ExtensionPageResource { authorize(); return engine.resource(path) }
        private fun snapshot(next: ScriptPageView): ExtensionPageSnapshot {
            authorize(); view = next; revision++
            return ExtensionPageSnapshot(revision, next.toJson())
        }
        override suspend fun close() {
            revoke()
            withContext(NonCancellable) {
                scope.cancel(); engine.close()
                synchronized(lock) { active.remove(this@Lease) }
            }
        }
    }
}
