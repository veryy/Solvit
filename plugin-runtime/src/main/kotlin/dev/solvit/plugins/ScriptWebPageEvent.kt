package dev.solvit.plugins

import kotlinx.serialization.json.*

/** Browser requests name current declared nodes; they never supply an action or approval. */
data class ScriptWebPageEvent(val node: JsonObject, val value: String?, val fields: Map<String, String>) {
    companion object {
        fun parse(view: ScriptPageView, token: String, json: String): ScriptWebPageEvent {
            require(json.toByteArray(Charsets.UTF_8).size <= ScriptPageView.MAX_BYTES)
            val input = Json.parseToJsonElement(json).jsonObject
            require(input.keys.all { it in setOf("type", "token", "nodeId", "value", "fields") })
            fun text(key: String) = input.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            require(text("type") == "event" && text("token") == token) { "Plugin page changed" }
            val nodeId = text("nodeId")
            val value = input["value"]?.let { if (it == JsonNull) null else it.jsonPrimitive.also { p -> require(p.isString) }.content }
            val fields = input.getValue("fields").jsonObject.mapValues { (_, v) ->
                v.jsonPrimitive.also { require(it.isString) }.content
            }
            // Validate arguments, not approval. The UI must still show the host dialog;
            // the session revalidates confirmed only after that actual human decision.
            view.event(nodeId, value, fields, confirmed = true)
            return ScriptWebPageEvent(checkNotNull(view.node(nodeId)), value, fields)
        }
    }
}
