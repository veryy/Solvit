package dev.solvit.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.reflect.KClass

data class ServiceKey<T : Any>(val name: String, val type: KClass<T>)

/** Typed, single-provider services. Registrations belong to a plugin activation. */
class PluginRegistry {
    private data class Entry(val owner: String, val value: Any)
    private val entries = mutableMapOf<ServiceKey<*>, Entry>()

    @Synchronized fun contains(key: ServiceKey<*>) = key in entries
    @Synchronized fun owner(key: ServiceKey<*>) = entries[key]?.owner
    @Synchronized fun <T : Any> require(key: ServiceKey<T>): T {
        val value = entries[key]?.value ?: error("Missing service: ${key.name}")
        check(key.type.isInstance(value))
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    @Synchronized internal fun <T : Any> register(owner: String, key: ServiceKey<T>, value: T): () -> Unit {
        check(key !in entries) { "Duplicate service: ${key.name}" }
        check(key.type.isInstance(value)) { "Invalid service: ${key.name}" }
        val entry = Entry(owner, value)
        entries[key] = entry
        return { synchronized(this) { if (entries[key] === entry) entries.remove(key); Unit } }
    }
}

sealed interface CoreEvent {
    data class PluginChanged(val id: String, val state: PluginState) : CoreEvent
    data class ToolsChanged(val names: List<String>) : CoreEvent
    data class MessageStored(val sessionId: String, val messageId: String) : CoreEvent
}

/** Transient observation only; durable chat facts live in ChatStore. */
class EventBus {
    private val mutable = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<CoreEvent> = mutable.asSharedFlow()
    fun publish(event: CoreEvent) { mutable.tryEmit(event) }
}

enum class PluginState { LOADED, INITIALIZED, ENABLED, DISABLED, UNLOADED }

interface SolvitPlugin {
    val id: String
    val dependencies: Set<String> get() = emptySet()
    val requires: Set<ServiceKey<*>> get() = emptySet()
    val provides: Set<ServiceKey<*>> get() = emptySet()
    /** Prepare metadata only; acquire resources in enable and own them in context. */
    suspend fun initialize() {}
    suspend fun enable(context: PluginContext)
    suspend fun disable() {}
    suspend fun unload() {}
}

class PluginContext internal constructor(
    val plugin: SolvitPlugin,
    private val registry: PluginRegistry,
    val events: EventBus,
    parentScope: CoroutineScope,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val effects = mutableListOf<suspend () -> Unit>()
    private var disposed = false

    fun <T : Any> require(key: ServiceKey<T>): T {
        check(key in plugin.requires || key in plugin.provides) { "Undeclared dependency: ${key.name}" }
        return registry.require(key)
    }

    fun <T : Any> register(key: ServiceKey<T>, value: T) {
        check(key in plugin.provides) { "Undeclared service: ${key.name}" }
        val dispose = registry.register(plugin.id, key, value)
        own { dispose() }
    }

    /** Own listeners, tool registrations, connections, etc.; unwind in reverse order. */
    fun own(dispose: suspend () -> Unit) { check(!disposed); effects += dispose }

    internal suspend fun dispose() = withContext(NonCancellable) {
        if (disposed) return@withContext
        disposed = true
        job.cancelAndJoin()
        var failure: Throwable? = null
        effects.asReversed().forEach { effect ->
            try { effect() } catch (e: Throwable) {
                if (failure == null) failure = e else failure!!.addSuppressed(e)
            }
        }
        effects.clear()
        failure?.let { throw it }
    }
}

/** Serialized lifecycle, dependency ordering, rollback, and reverse shutdown. */
class PluginManager(
    val registry: PluginRegistry = PluginRegistry(),
    val events: EventBus = EventBus(),
    private val scope: CoroutineScope,
) {
    private data class Loaded(val plugin: SolvitPlugin, var context: PluginContext? = null)
    private val plugins = linkedMapOf<String, Loaded>()
    private val mutex = Mutex()
    private val mutableStates = MutableStateFlow<Map<String, PluginState>>(emptyMap())
    val states = mutableStates.asStateFlow()

    private fun state(id: String, state: PluginState) {
        mutableStates.value = mutableStates.value + (id to state)
        events.publish(CoreEvent.PluginChanged(id, state))
    }

    suspend fun load(plugin: SolvitPlugin) = mutex.withLock {
        require(plugin.id.isNotBlank())
        check(plugin.id !in plugins) { "Duplicate plugin: ${plugin.id}" }
        plugins[plugin.id] = Loaded(plugin)
        state(plugin.id, PluginState.LOADED)
    }

    suspend fun enableAll() = mutex.withLock {
        val started = mutableListOf<Loaded>()
        try {
            val pending = plugins.values.filter { it.context == null }.toMutableList()
            while (pending.isNotEmpty()) {
                val ready = pending.firstOrNull { row -> row.plugin.requires.all(registry::contains) &&
                    row.plugin.dependencies.all { plugins[it]?.context != null } }
                    ?: error("Missing or cyclic dependencies: ${pending.joinToString { it.plugin.id }}")
                activate(ready)
                started += ready
                pending.remove(ready)
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                started.asReversed().forEach { row ->
                    try { deactivate(row) } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
                }
            }
            throw e
        }
    }

    private suspend fun activate(row: Loaded) {
        if (mutableStates.value[row.plugin.id] == PluginState.LOADED) {
            row.plugin.initialize()
            state(row.plugin.id, PluginState.INITIALIZED)
        }
        val context = PluginContext(row.plugin, registry, events, scope)
        try {
            row.plugin.enable(context)
            check(row.plugin.provides.all { registry.owner(it) == row.plugin.id }) {
                "Plugin ${row.plugin.id} did not register declared services"
            }
            row.context = context
            state(row.plugin.id, PluginState.ENABLED)
        } catch (e: Throwable) {
            try { context.dispose() } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
            state(row.plugin.id, PluginState.DISABLED)
            throw e
        }
    }

    private suspend fun deactivate(row: Loaded) = withContext(NonCancellable) {
        val context = row.context ?: return@withContext
        try { row.plugin.disable() } finally {
            try { context.dispose() } finally {
                row.context = null
                state(row.plugin.id, PluginState.DISABLED)
            }
        }
    }

    private fun requireNoConsumers(row: Loaded) {
        val consumers = plugins.values.filter { it !== row && it.context != null &&
            (row.plugin.id in it.plugin.dependencies || it.plugin.requires.any { key -> key in row.plugin.provides }) }
        check(consumers.isEmpty()) { "Enabled dependents: ${consumers.joinToString { it.plugin.id }}" }
    }

    suspend fun disable(id: String) = mutex.withLock {
        val row = plugins.getValue(id)
        requireNoConsumers(row)
        deactivate(row)
    }

    suspend fun unload(id: String) = mutex.withLock {
        val row = plugins.getValue(id)
        requireNoConsumers(row)
        deactivate(row)
        row.plugin.unload()
        plugins.remove(id)
        state(id, PluginState.UNLOADED)
    }

    suspend fun shutdown() = mutex.withLock {
        withContext(NonCancellable) {
            var failure: Throwable? = null
            // Activation order can differ from load order: remove consumers first.
            while (plugins.isNotEmpty()) {
                val row = plugins.values.first { candidate -> plugins.values.none {
                    it !== candidate && it.context != null &&
                        (candidate.plugin.id in it.plugin.dependencies ||
                            it.plugin.requires.any { key -> key in candidate.plugin.provides })
                } }
                try { deactivate(row) } catch (e: Throwable) {
                    if (failure == null) failure = e else failure!!.addSuppressed(e)
                }
                try { row.plugin.unload() } catch (e: Throwable) {
                    if (failure == null) failure = e else failure!!.addSuppressed(e)
                }
                plugins.remove(row.plugin.id)
                state(row.plugin.id, PluginState.UNLOADED)
            }
            failure?.let { throw it }
        }
    }
}
