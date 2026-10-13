package dev.solvit.core

import kotlinx.coroutines.Job

/** Per-turn orchestration. Requests, credentials and native transcripts remain host-owned. */
fun interface AgentStepHost {
    val ownerJob: Job? get() = null
    suspend fun observe(): AgentObservation = error("Agent observations unavailable")
    suspend fun command(operation: String, index: Int): Int
}
/** Runtime supplies the entire producer job, including its native cleanup after driver.run. */
class OwnedAgentStepHost(override val ownerJob: Job, private val delegate: AgentStepHost) : AgentStepHost {
    override suspend fun command(operation: String, index: Int) = delegate.command(operation, index)
    override suspend fun observe() = delegate.observe()
}
fun interface AgentDriver {
    suspend fun run(maxRounds: Int, host: AgentStepHost)
}
val AgentDriverServiceKey = ServiceKey("agent.driver", AgentDriver::class)

/** Reference driver for JVM consumers. Android declares the JS driver as a dependency. */
object NativeAgentDriver : AgentDriver {
    override suspend fun run(maxRounds: Int, host: AgentStepHost) {
        for (round in 0 until maxRounds) {
            val calls = host.command("model", round)
            if (calls == 0) { host.command("finish", round); return }
            for (index in 0 until calls) host.command("tool", index)
            host.command("settle", round)
        }
    }
}
