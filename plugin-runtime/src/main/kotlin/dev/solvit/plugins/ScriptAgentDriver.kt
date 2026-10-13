package dev.solvit.plugins

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Dedicated engine per turn; async host work is explicitly parented to that turn. */
class ScriptAgentDriver(private val source: String, private val timeoutMs: Long? = null, private val observations: Boolean = false) : AgentDriver {
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
                require(operation in setOf("model", "tool", "settle", "finish") || observations && operation == "decline")
                val number = args[1] as Number
                val index = number.toInt()
                require(number.toDouble() == index.toDouble() && index >= 0)
                withContext(parent) {
                    try { host.command(operation, index) }
                    catch (failure: Throwable) { hostFailure = failure; throw failure }
                }
            }
            if (observations) js.asyncFunction("__solvitAgentObserve") { args ->
                require(args.isEmpty())
                withContext(parent) {
                    try { observationJson(host.observe()) }
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

/** Cold strategy engine. Read access is explicitly declared; credentials/native transcripts stay host-owned. */
fun scriptPluginAgent(pkg: ScriptPackage): AgentDriver {
    val canObserve = pkg.manifest.apiVersion >= 15 && "agent.read" in pkg.manifest.permissions
    val extra = if (canObserve) ", observe: async () => JSON.parse(await __solvitAgentObserve()), decline: index => __solvitAgentCommand('decline', index)" else ""
    return ScriptAgentDriver(pkg.source + """
    ;if (typeof solvitPlugin.agent !== 'function') throw new Error('Missing agent strategy');
    globalThis.solvitAgent = Object.freeze({run: async maxRounds => {
      const context = Object.freeze({
        model: round => __solvitAgentCommand('model', round),
        tool: index => __solvitAgentCommand('tool', index),
        settle: round => __solvitAgentCommand('settle', round),
        finish: round => __solvitAgentCommand('finish', round)$extra
      });
      await solvitPlugin.agent(Object.freeze({maxRounds}), context);
    }});
""".trimIndent(), timeoutMs = 600_000, observations = canObserve)
}

private fun observationJson(value: AgentObservation): String {
    fun text(value: AgentText) = buildJsonObject { put("text", value.text); put("truncated", value.truncated) }
    return buildJsonObject {
        put("step", value.step); put("nextModelRound", value.nextModelRound); put("phase", value.phase)
        put("userText", text(value.userText)); put("roleName", value.roleName?.let(::text) ?: JsonNull)
        put("assistantText", text(value.assistantText)); put("reasoning", text(value.reasoning))
        put("toolsTruncated", value.toolsTruncated)
        putJsonArray("tools") { value.tools.forEach { tool -> add(buildJsonObject {
            put("index", tool.index); put("id", text(tool.id)); put("name", tool.name); put("arguments", text(tool.arguments))
            put("stage", tool.stage?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("result", tool.result?.let(::text) ?: JsonNull)
        }) } }
    }.toString()
}
