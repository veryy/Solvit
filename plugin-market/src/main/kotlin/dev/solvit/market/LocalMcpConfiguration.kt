package dev.solvit.market

import kotlinx.serialization.json.*
import dev.solvit.core.ToolExecutionException
import dev.solvit.core.ToolFailureCode
import java.security.MessageDigest

/** Private execution configuration, not a model argument or a logged data class. */
class LocalMcpConfiguration private constructor(val name: String, val command: String, val arguments: List<String>,
    val environment: Map<String, String>, val cwd: String, val fingerprint: String) {
    override fun toString() = "LocalMcpConfiguration(<private>)"
    companion object {
        fun select(encoded: String, requested: String?): LocalMcpConfiguration {
            try { return parse(encoded, requested) }
            catch (_: Exception) { throw ToolExecutionException(ToolFailureCode.LOCAL_MCP_CONFIGURATION) }
        }
        private fun parse(encoded: String, requested: String?): LocalMcpConfiguration {
            require(encoded.length <= 4096)
            val servers = Json.parseToJsonElement(encoded).jsonArray
            require(servers.size <= 8)
            val names = mutableSetOf<String>()
            val enabled = servers.map { it.jsonObject }.filter { raw ->
                val name = raw.getValue("name").jsonPrimitive.also { require(it.isString) }.content
                require(name.isNotBlank() && name == name.trim() && name.length <= 40 &&
                    name.none { it.code < 32 || it.code == 127 } && name !in setOf("__proto__", "prototype", "constructor") && names.add(name))
                raw["disabled"]?.jsonPrimitive?.also { require(!it.isString && it.booleanOrNull != null) }?.boolean != true
            }
            val profile = if (requested == null) enabled.singleOrNull() else enabled.singleOrNull { it["name"]?.jsonPrimitive?.content == requested }
            requireNotNull(profile) { "请明确选择已启用的本地MCP服务器" }
            require(profile["type"] == JsonPrimitive("stdio")) { "此连接不是本地stdio服务" }
            require(profile.keys.all { it in setOf("name", "type", "command", "args", "env", "cwd", "disabled") })
            fun text(key: String, default: String? = null) = profile[key]?.jsonPrimitive?.also { require(it.isString) }?.content
                ?: checkNotNull(default) { "本地MCP配置不完整" }
            val command = text("command")
            require(command.isNotBlank() && command.length <= 1024 && !command.startsWith('-') && '=' !in command && '\u0000' !in command)
            val args = profile["args"]?.jsonArray.orEmpty().map { it.jsonPrimitive.also { p -> require(p.isString) }.content }
            require(args.size <= 64 && args.all { it.length <= 16_000 && '\u0000' !in it })
            val env = profile["env"]?.jsonObject.orEmpty().mapValues { (_, value) -> value.jsonPrimitive.also { require(it.isString) }.content }
            require(env.size <= 32 && env.keys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) } && env.values.all { '\u0000' !in it && it.length <= 16_000 })
            val cwd = text("cwd", "")
            require(cwd.length <= 1024 && !cwd.startsWith('/') && '\u0000' !in cwd && '\\' !in cwd &&
                (cwd.isEmpty() || cwd.split('/').all { it.isNotEmpty() && it != "." && it != ".." })) { "MCP工作目录需在当前工作区内" }
            return LocalMcpConfiguration(text("name"), command, args, env, cwd, privateDigest(profile.toString()))
        }
    }
}
internal fun privateDigest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 255) }
