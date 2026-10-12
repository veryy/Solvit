package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.plugins.ScriptPageExecutionContext
import kotlinx.serialization.json.*

/** No turn contents, credentials, unrelated targets, or arbitrary job mutation arguments. */
internal class PostTurnPageBridge(private val service: PostTurnTaskService?) {
    suspend fun call(owner: String, operation: String, arguments: JsonObject,
        page: ScriptPageExecutionContext, authorize: suspend () -> Unit,
        afterControl: suspend (PostTurnTaskControl) -> Int = { 0 }): JsonObject {
        check(page.pluginId == owner)
        authorize()
        when (operation) {
            "jobs.list" -> {
                require(arguments.keys.all { it == "cursor" })
                val cursor = arguments["cursor"]?.takeUnless { it == JsonNull }?.jsonObject?.let {
                    require(it.keys == setOf("updatedAt", "id"))
                    val time = it.getValue("updatedAt").jsonPrimitive
                    val id = it.getValue("id").jsonPrimitive
                    require(!time.isString && id.isString)
                    PostTurnJobCursor(requireNotNull(time.longOrNull), id.content)
                }
                if (service == null) return buildJsonObject {
                    put("available", false); put("jobs", JsonArray(emptyList())); put("activeJobId", JsonNull); put("nextCursor", JsonNull)
                }
                val result = service.list(owner, cursor, 20, authorize)
                check(result.jobs.size <= 20 && result.jobs.all { it.target.pluginId == owner })
                authorize()
                return buildJsonObject {
                    put("available", true)
                    put("jobs", JsonArray(result.jobs.map { job -> buildJsonObject {
                        put("id", job.id); put("status", job.status.name); put("attempt", job.attempt); put("updatedAt", job.updatedAt)
                        put("failure", job.failure?.name?.let(::JsonPrimitive) ?: JsonNull)
                        put("roleName", job.turn.roleName?.let(::JsonPrimitive) ?: JsonNull)
                        put("model", job.turn.requestedModel); put("sessionId", job.turn.sessionId)
                        put("userMessageId", job.turn.userMessageId); put("assistantMessageId", job.turn.assistantMessageId)
                    } }))
                    put("activeJobId", result.activeJobId?.let(::JsonPrimitive) ?: JsonNull)
                    put("nextCursor", if (result.jobs.size == 20) result.jobs.last().let { job -> buildJsonObject {
                        put("updatedAt", job.updatedAt); put("id", job.id)
                    } } else JsonNull)
                }
            }
            "jobs.perform" -> {
                require(arguments.isEmpty())
                val tasks = checkNotNull(service) { "Task service unavailable" }
                val control = page.consumeTaskControl()
                val changed = tasks.control(owner, control, authorize)
                check(changed in 0..(if (control == PostTurnTaskControl.Cleanup) 100 else 1))
                authorize()
                val receipts = if (control is PostTurnTaskControl.Delete || control == PostTurnTaskControl.Cleanup) afterControl(control) else 0
                return buildJsonObject { put("changed", changed); put("receipts", receipts) }
            }
            else -> error("Unsupported task operation")
        }
    }
}
