package dev.solvit.plugins

import kotlinx.serialization.json.*

/** Offline, plugin-owned resources. Paths never resolve to Android files or remote URLs. */
object ScriptWebResources {
    const val MAX_FILES = 32
    const val MAX_BYTES = 1024 * 1024
    private val segment = Regex("[a-zA-Z0-9_-][a-zA-Z0-9._-]*")
    private val types = mapOf("html" to "text/html", "js" to "application/javascript",
        "css" to "text/css", "svg" to "image/svg+xml", "json" to "application/json", "txt" to "text/plain")

    fun mimeType(path: String): String {
        require(path.length in 1..160 && path.split('/').all { it.matches(segment) }) { "插件页面资源路径无效" }
        return types[path.substringAfterLast('.', "")] ?: error("插件页面资源类型不受支持")
    }

    fun parse(value: JsonObject): Map<String, String> {
        require(value.size <= MAX_FILES) { "插件页面资源过多" }
        var total = 0L
        return value.mapValues { (path, content) ->
            mimeType(path)
            val text = content.jsonPrimitive.also { require(it.isString) }.content
            total += text.toByteArray(Charsets.UTF_8).size
            require(total <= MAX_BYTES) { "插件页面资源过大" }
            text
        }.toMap()
    }
}
