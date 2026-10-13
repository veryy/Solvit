package dev.solvit.plugins

import kotlinx.serialization.json.*
import java.security.MessageDigest
import dev.solvit.core.ExtensionSetting

data class ScriptTool(val name: String, val description: String, val inputSchema: JsonObject, val chatVisible: Boolean = true)
data class ScriptPage(val id: String, val title: String, val entry: String? = null)
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
    val afterTurnEnabledSetting: String? = null,
    val pages: List<ScriptPage> = emptyList(),
    val nativeServices: Set<String> = emptySet(),
)
data class ScriptPackage(val manifest: ScriptManifest, val source: String, val payload: ByteArray,
    val resources: Map<String, String> = emptyMap()) {
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
            val manifest = parseManifest(root.getValue("manifest").jsonObject)
            val resources = root["resources"]?.let { value ->
                require(manifest.apiVersion >= 10) { "HTML 页面资源需要 API 10" }
                ScriptWebResources.parse(value.jsonObject)
            }.orEmpty()
            manifest.pages.mapNotNull { it.entry }.forEach { entry ->
                require(entry in resources) { "HTML 页面入口资源缺失" }
            }
            val source = root.getValue("source").jsonPrimitive.content
            require(source.isNotBlank()) { "插件缺少 JavaScript 代码" }
            return ScriptPackage(manifest, source, bytes.copyOf(), resources)
        }

        /** Catalog metadata validates declarations; only parse accepts executable packages. */
        fun parseManifest(m: JsonObject): ScriptManifest {
            require(m.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val apiVersion = m.getValue("apiVersion").jsonPrimitive.int
            require(apiVersion in 1..13) { "插件需要其他版本的宿主 API" }
            fun string(key: String) = m.getValue(key).jsonPrimitive.content.also { require(it.isNotBlank()) { "插件字段 $key 为空" } }
            fun strings(key: String) = m.getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()
            val id = string("id")
            require(!id.startsWith("builtin.")) { "插件不能使用宿主保留 ID" }
            require(id.length <= 40 && id.matches(Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+"))) { "插件 ID 无效" }
            val version = string("version")
            require(version.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) { "插件版本无效" }
            val permissions = strings("permissions")
            require(permissions.all { it in setOf("storage", "network") || apiVersion >= 4 && it in setOf("workspace", "shell", "plugins") || apiVersion >= 8 && it == "model" }) { "插件声明了尚未支持的权限" }
            val dependencies = strings("dependencies")
            require(id !in dependencies) { "插件不能依赖自身" }
            val nativeServices = m["nativeServices"]?.jsonArray?.map { it.jsonPrimitive.also { p -> require(p.isString) }.content }?.toSet().orEmpty()
            require("nativeServices" !in m || apiVersion >= 12) { "原生服务声明需要 API12" }
            require(nativeServices.all { it == "terminal.pty" && "shell" in permissions || it == "web.pages" && "network" in permissions }) { "原生服务声明无效或缺少权限" }
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
                require(key.matches(Regex("[a-z][a-zA-Z0-9_]{0,39}")) && (kind in setOf("text", "secret", "url") || apiVersion >= 7 && kind == "boolean")) { "插件配置字段无效" }
                val label = field.getValue("label").jsonPrimitive.content
                require(label.isNotBlank() && label.length <= 100)
                val defaultValue = field["defaultValue"]?.jsonPrimitive?.content
                require(defaultValue == null || apiVersion >= 7 && kind == "boolean" && defaultValue in setOf("true", "false")) { "插件配置默认值无效" }
                val pageManaged = field["pageManaged"]?.jsonPrimitive?.boolean ?: false
                require(!pageManaged || apiVersion >= 10) { "插件页面管理配置需要 API 10" }
                ExtensionSetting(key, label, kind, field["required"]?.jsonPrimitive?.boolean ?: false, defaultValue, pageManaged)
            }.orEmpty()
            require(settings.size <= 16 && settings.map { it.key }.toSet().size == settings.size && (settings.isEmpty() || apiVersion >= 2))
            val hooks = m["hooks"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            require(hooks.all { it == "beforeModel" && apiVersion >= 3 || it == "afterTurn" && apiVersion >= 8 }) { "插件 Hook 声明无效" }
            val afterTurnSetting = m["afterTurnEnabledSetting"]?.jsonPrimitive?.content
            if ("afterTurn" in hooks) {
                require(afterTurnSetting != null && settings.any { it.key == afterTurnSetting && it.kind == "boolean" && it.defaultValue != "true" }) { "后台 Hook 必须声明默认关闭的开关" }
            } else require(afterTurnSetting == null && "model" !in permissions) { "后台模型权限需要 afterTurn Hook" }
            val pages = m["pages"]?.jsonArray?.map { element ->
                val page = element.jsonObject
                require(page.keys == setOf("id", "title") ||
                    apiVersion >= 10 && page.keys == setOf("id", "title", "entry")) { "插件页面声明无效" }
                val pageId = page.getValue("id").jsonPrimitive.also { require(it.isString) }.content
                val title = page.getValue("title").jsonPrimitive.also { require(it.isString) }.content
                require(pageId.matches(Regex("[a-z][a-zA-Z0-9_-]{0,39}")) && title.isNotBlank() && title.length <= 128)
                val entry = page["entry"]?.jsonPrimitive?.also { require(it.isString) }?.content
                if (entry != null) {
                    require(ScriptWebResources.mimeType(entry) == "text/html") { "HTML 页面入口缺失或无效" }
                }
                ScriptPage(pageId, title, entry)
            }.orEmpty()
            require(pages.size <= 8 && pages.map { it.id }.distinct().size == pages.size && (pages.isEmpty() || apiVersion >= 9)) { "插件页面需要 API 9 且 ID 不得重复" }
            return ScriptManifest(id, string("name"), string("description"), version,
                permissions, dependencies, tools, apiVersion, settings, hooks, afterTurnSetting, pages, nativeServices)
        }
    }
}
