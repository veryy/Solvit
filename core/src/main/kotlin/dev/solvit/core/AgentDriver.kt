package dev.solvit.core

/** Per-turn orchestration. Requests, credentials and native transcripts remain host-owned. */
fun interface AgentStepHost {
    suspend fun command(operation: String, index: Int): Int
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
