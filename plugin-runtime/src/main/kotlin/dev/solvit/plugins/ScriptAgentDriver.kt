package dev.solvit.plugins

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import dev.solvit.core.*
import kotlinx.coroutines.*

/** Dedicated engine per turn; async host work is explicitly parented to that turn. */
class ScriptAgentDriver(private val source: String, private val timeoutMs: Long? = null) : AgentDriver {
    override suspend fun run(maxRounds: Int, host: AgentStepHost): Unit = coroutineScope {
        val parent = currentCoroutineContext()
        val js = QuickJs.create(Dispatchers.IO)
        var hostFailure: Throwable? = null
        try {
            js.memoryLimit = 8L * 1024 * 1024
            js.maxStackSize = 256L * 1024
            if (timeoutMs != null) js.evaluationTimeoutMillis = timeoutMs
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
            Unit
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            throw hostFailure ?: ModelException("JS Agent 执行失败")
        } finally { js.close() }
    }
}

class ScriptAgentDriverPlugin(private val source: String) : SolvitPlugin {
    override val id = "builtin.agent.javascript"
    override val provides = setOf(AgentDriverServiceKey, AgentDriversServiceKey)
    override suspend fun enable(context: PluginContext) {
        require(source.isNotBlank() && source.length <= 64 * 1024)
        val drivers = AgentDriverRegistry(ScriptAgentDriver(source))
        context.register(AgentDriversServiceKey, drivers)
        context.register(AgentDriverServiceKey, drivers)
    }
}

/** Cold strategy engine. No tool bridge, credentials, messages or native transcript cross into it. */
fun scriptPluginAgent(pkg: ScriptPackage): AgentDriver = ScriptAgentDriver(pkg.source + """
    ;if (typeof solvitPlugin.agent !== 'function') throw new Error('Missing agent strategy');
    globalThis.solvitAgent = Object.freeze({run: async maxRounds => {
      const context = Object.freeze({
        model: round => __solvitAgentCommand('model', round),
        tool: index => __solvitAgentCommand('tool', index),
        settle: round => __solvitAgentCommand('settle', round),
        finish: round => __solvitAgentCommand('finish', round)
      });
      await solvitPlugin.agent(Object.freeze({maxRounds}), context);
    }});
""".trimIndent(), timeoutMs = 600_000)
