package dev.solvit.plugins

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import dev.solvit.core.*
import kotlinx.coroutines.*

/** Dedicated engine per turn; async host work is explicitly parented to that turn. */
class ScriptAgentDriver(private val source: String) : AgentDriver {
    override suspend fun run(maxRounds: Int, host: AgentStepHost) = coroutineScope {
        val parent = currentCoroutineContext()
        val js = QuickJs.create(Dispatchers.IO)
        var hostFailure: Throwable? = null
        try {
            js.memoryLimit = 8L * 1024 * 1024
            js.maxStackSize = 256L * 1024
            // Trusted bundled source only. User stop interrupts the entire evaluation.
            js.asyncFunction("__solvitAgentCommand") { args ->
                require(args.size == 2)
                val operation = args[0] as String
                val number = args[1] as Number
                val index = number.toInt()
                require(number.toDouble() == index.toDouble() && index >= 0)
                withContext(parent) {
                    try { host.command(operation, index) }
                    catch (failure: Throwable) { hostFailure = failure; throw failure }
                }
            }
            js.evaluate<Any?>(source)
            js.evaluate<Any?>("await solvitAgent.run($maxRounds)")
            hostFailure?.let { throw it }
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            throw hostFailure ?: ModelException("JS Agent 执行失败")
        } finally { js.close() }
    }
}

class ScriptAgentDriverPlugin(private val source: String) : SolvitPlugin {
    override val id = "builtin.agent.javascript"
    override val provides = setOf(AgentDriverServiceKey)
    override suspend fun enable(context: PluginContext) {
        require(source.isNotBlank() && source.length <= 64 * 1024)
        context.register(AgentDriverServiceKey, ScriptAgentDriver(source))
    }
}
