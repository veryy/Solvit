package dev.solvit.plugins

import kotlinx.serialization.json.*

/** Validated page state and action declarations. HTML presentation loads separately from the package. */
class ScriptPageView private constructor(
    val title: String,
    val nodes: List<JsonObject>,
    val footer: List<JsonObject>,
    private val byId: Map<String, JsonObject>,
) {
    fun toJson(): String = buildJsonObject {
        put("title", title); put("nodes", JsonArray(nodes))
        if (footer.isNotEmpty()) put("footer", JsonArray(footer))
    }.toString()
    fun hostAction(nodeId: String): JsonObject? = byId[nodeId]?.get("hostAction")?.jsonObject
    fun node(nodeId: String): JsonObject? = byId[nodeId]
    /** Resolve the action from the displayed page, never from caller-supplied action text. */
    fun event(nodeId: String, value: String? = null, fields: Map<String, String> = emptyMap(), confirmed: Boolean = false): JsonObject {
        val node = byId[nodeId] ?: error("Page node no longer exists")
        val kind = node.getValue("kind").jsonPrimitive.content
        require(kind in setOf("button", "field", "choice", "graph"))
        require(node["enabled"]?.jsonPrimitive?.boolean != false)
        require(node["confirm"] == null || confirmed) { "Page action requires human confirmation" }
        require(node["hostAction"] == null || confirmed) { "Native task control requires human confirmation" }
        val action = node["action"]?.jsonPrimitive?.content ?: error("Node has no action")
        when (kind) {
            "button" -> require(value == null)
            "field" -> require(value != null && value.length <= MAX_FIELD)
            "choice" -> require(node.getValue("options").jsonArray.any { it.jsonObject["value"] == value?.let(::JsonPrimitive) })
            "graph" -> require(node.getValue("nodes").jsonArray.any { it.jsonObject["id"] == value?.let(::JsonPrimitive) })
        }
        require(fields.size <= MAX_NODES)
        fields.forEach { (id, text) ->
            val field = byId[id] ?: error("Unknown page field")
            require(field["kind"] == JsonPrimitive("field") && text.length <= MAX_FIELD)
        }
        return buildJsonObject {
            put("nodeId", nodeId); put("action", action)
            if (value != null) put("value", value)
            put("fields", JsonObject(fields.mapValues { JsonPrimitive(it.value) }))
        }
    }

    companion object {
        const val MAX_BYTES = 128 * 1024
        const val MAX_NODES = 300
        const val MAX_FIELD = 4096
        fun parse(json: String): ScriptPageView {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Plugin page too large" }
            val root = Json.parseToJsonElement(json).jsonObject
            require(root.keys.all { it in setOf("title", "nodes", "footer") })
            fun JsonObject.text(key: String, max: Int, nonEmpty: Boolean = false): String {
                val p = getValue(key).jsonPrimitive
                require(p.isString)
                return p.content.also { require(it.length <= max && (!nonEmpty || it.isNotBlank())) }
            }
            fun JsonObject.optionalBoolean(key: String) {
                if (key in this) getValue(key).jsonPrimitive.let { require(!it.isString && it.booleanOrNull != null) }
            }
            val title = root.text("title", 128, true)
            val indexed = linkedMapOf<String, JsonObject>()
            fun visit(node: JsonObject, depth: Int) {
                require(depth <= 12 && indexed.size < MAX_NODES) { "Plugin page hierarchy too large" }
                val id = node.text("id", 80, true)
                require(id !in indexed) { "Duplicate page node" }
                indexed[id] = node
                val kind = node.text("kind", 16, true)
                val allowed = when (kind) {
                    "column", "row", "card" -> setOf("id", "kind", "children")
                    "text" -> setOf("id", "kind", "text", "tone")
                    "button" -> setOf("id", "kind", "text", "action", "confirm", "enabled", "hostAction")
                    "field" -> setOf("id", "kind", "label", "value", "action", "multiline")
                    "choice" -> setOf("id", "kind", "label", "value", "action", "options")
                    "graph" -> setOf("id", "kind", "nodes", "edges", "action")
                    else -> error("Unknown page component")
                }
                require(node.keys.all { it in allowed })
                if ("action" in node) node.text("action", 80, true)
                when (kind) {
                    "column", "row", "card" -> node.getValue("children").jsonArray.forEach { visit(it.jsonObject, depth + 1) }
                    "text" -> {
                        node.text("text", 16384)
                        if ("tone" in node) require(node.text("tone", 16) in setOf("body", "title", "caption"))
                    }
                    "button" -> {
                        node.text("text", 128, true); node.text("action", 80, true)
                        if ("confirm" in node) node.text("confirm", 1000, true)
                        node.optionalBoolean("enabled")
                        node["hostAction"]?.let { ScriptPageHostAction.parse(it.jsonObject) }
                    }
                    "field" -> {
                        node.text("label", 128, true); node.text("value", MAX_FIELD)
                        node.optionalBoolean("multiline")
                    }
                    "choice" -> {
                        node.text("label", 128, true); node.text("action", 80, true)
                        val value = node.text("value", 128)
                        val options = node.getValue("options").jsonArray
                        require(options.size in 1..50)
                        val values = options.map { it.jsonObject.run {
                            require(keys == setOf("value", "label")); text("label", 128, true); text("value", 128)
                        } }
                        require(values.distinct().size == values.size && value in values)
                    }
                    "graph" -> {
                        val graphNodes = node.getValue("nodes").jsonArray
                        val edges = node.getValue("edges").jsonArray
                        // Matches the existing memory store's bounded fact/relation capacity.
                        require(graphNodes.size <= 256 && edges.size <= 512)
                        val ids = graphNodes.map { it.jsonObject.run {
                            require(keys == setOf("id", "label")); text("label", 256, true); text("id", 80, true)
                        } }
                        require(ids.distinct().size == ids.size)
                        edges.forEach { it.jsonObject.run {
                            require(keys == setOf("from", "to", "label"))
                            require(text("from", 80, true) in ids && text("to", 80, true) in ids)
                            text("label", 256)
                        } }
                    }
                }
            }
            val nodes = root.getValue("nodes").jsonArray.map { it.jsonObject }
            nodes.forEach { visit(it, 0) }
            val footer = root["footer"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            require(footer.size <= 6 && footer.all { it["kind"] == JsonPrimitive("button") })
            footer.forEach { visit(it, 0) }
            return ScriptPageView(title, nodes, footer, indexed)
        }
    }
}
