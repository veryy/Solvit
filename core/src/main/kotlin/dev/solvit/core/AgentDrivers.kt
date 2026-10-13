package dev.solvit.core

import kotlinx.coroutines.*

/** Explicitly opted-in global strategies. A registration owns its in-flight turn scopes. */
class AgentDriverRegistry(private val default: AgentDriver) : AgentDriver {
    private class Entry(val id: String, val driver: AgentDriver, val enabled: suspend () -> Boolean) {
        val turns = mutableSetOf<Job>()
    }
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()

    fun register(id: String, driver: AgentDriver, enabled: suspend () -> Boolean): suspend () -> Unit {
        val entry = Entry(id, driver, enabled)
        synchronized(lock) {
            check(id !in entries && entries.size < 8) { "Agent 驱动重复或过多" }
            entries[id] = entry
        }
        return {
            withContext(NonCancellable) {
                val turns = synchronized(lock) {
                    if (entries[id] === entry) entries.remove(id)
                    entry.turns.toList()
                }
                turns.forEach { it.cancel(CancellationException("Agent 插件已停用")) }
                turns.joinAll()
            }
        }
    }

    override suspend fun run(maxRounds: Int, host: AgentStepHost): Unit = coroutineScope {
        val snapshot = synchronized(lock) { entries.values.toList() }
        val selected = snapshot.filter { it.enabled() }
        if (selected.size > 1) throw ModelException("多个 Agent 驱动同时启用，请在插件配置中仅保留一个")
        val entry = selected.singleOrNull()
        if (entry == null) {
            default.run(maxRounds, host)
        } else {
            val turn = currentCoroutineContext().job
            synchronized(lock) {
                if (entries[entry.id] !== entry) throw ModelException("Agent 插件已停用，请重新发送")
                entry.turns += turn
            }
            try { entry.driver.run(maxRounds, host) }
            finally { synchronized(lock) { entry.turns -= turn } }
        }
    }
}

val AgentDriversServiceKey = ServiceKey("agent.drivers", AgentDriverRegistry::class)
