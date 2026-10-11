package dev.solvit.plugins

import kotlinx.serialization.json.*
import java.security.MessageDigest
import dev.solvit.core.ExtensionSetting

data class ScriptTool(val name: String, val description: String, val inputSchema: JsonObject, val chatVisible: Boolean = true)
data class ScriptManifest(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val permissions: Set<String>,
    val dependencies: Set<String>,
    val tools: List<ScriptTool>,
    val apiVersion: Int = 1,
    val settings: List<ExtensionSetting> = emptyList(),
    val hooks: Set<String> = emptySet(),
)
data class ScriptPackage(val manifest: ScriptManifest, val source: String, val payload: ByteArray) {
    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        fun parse(bytes: ByteArray, expectedSha256: String? = null): ScriptPackage {
            require(bytes.size in 1..MAX_BYTES) { "插件包大小超出限制" }
            if (expectedSha256 != null) {
                require(expectedSha256.matches(Regex("[a-f0-9]{64}"))) { "插件校验信息无效" }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                require(hash == expectedSha256) { "插件下载校验失败" }
            }
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            val m = root.getValue("manifest").jsonObject
            val apiVersion = m.getValue("apiVersion").jsonPrimitive.int
            require(apiVersion in 1..4) { "插件需要其他版本的宿主 API" }
            fun string(key: String) = m.getValue(key).jsonPrimitive.content.also { require(it.isNotBlank()) { "插件字段 $key 为空" } }
            fun strings(key: String) = m.getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()
            val id = string("id")
            require(!id.startsWith("builtin.")) { "插件不能使用宿主保留 ID" }
            require(id.length <= 40 && id.matches(Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+"))) { "插件 ID 无效" }
            val version = string("version")
            require(version.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) { "插件版本无效" }
            val permissions = strings("permissions")
            require(permissions.all { it in setOf("storage", "network") || apiVersion >= 4 && it in setOf("workspace", "shell", "plugins") }) { "插件声明了尚未支持的权限" }
            val dependencies = strings("dependencies")
            require(id !in dependencies) { "插件不能依赖自身" }
            val tools = m.getValue("tools").jsonArray.map { element ->
                val tool = element.jsonObject
                val name = tool.getValue("name").jsonPrimitive.content
                require(name.length <= 20 && name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*"))) { "工具名无效" }
                val schema = tool.getValue("inputSchema").jsonObject
                require(schema["type"]?.jsonPrimitive?.content == "object") { "工具输入必须为对象" }
                val exposure = tool["exposure"]?.jsonPrimitive?.content ?: "chat"
                require(exposure in setOf("chat", "management") && (exposure == "chat" || apiVersion >= 3))
                ScriptTool(name, tool.getValue("description").jsonPrimitive.content, schema, exposure == "chat")
            }
            require(tools.map { it.name }.toSet().size == tools.size) { "工具名重复" }
            require(tools.size <= 64) { "插件工具过多" }
            val settings = m["settings"]?.jsonArray?.map { element ->
                val field = element.jsonObject
                val key = field.getValue("key").jsonPrimitive.content
                val kind = field.getValue("kind").jsonPrimitive.content
                require(key.matches(Regex("[a-z][a-zA-Z0-9_]{0,39}")) && kind in setOf("text", "secret", "url")) { "插件配置字段无效" }
                val label = field.getValue("label").jsonPrimitive.content
                require(label.isNotBlank() && label.length <= 100)
                ExtensionSetting(key, label, kind, field["required"]?.jsonPrimitive?.boolean ?: false)
            }.orEmpty()
            require(settings.size <= 16 && settings.map { it.key }.toSet().size == settings.size && (settings.isEmpty() || apiVersion >= 2))
            val source = root.getValue("source").jsonPrimitive.content
            val hooks = m["hooks"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            require(hooks.all { it == "beforeModel" } && (hooks.isEmpty() || apiVersion >= 3)) { "插件 Hook 声明无效" }
            require(source.isNotBlank()) { "插件缺少 JavaScript 代码" }
            return ScriptPackage(ScriptManifest(id, string("name"), string("description"), version,
                permissions, dependencies, tools, apiVersion, settings, hooks), source, bytes.copyOf())
        }
    }
}
