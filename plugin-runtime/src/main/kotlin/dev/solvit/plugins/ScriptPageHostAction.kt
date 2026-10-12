package dev.solvit.plugins

import dev.solvit.core.*
import kotlinx.serialization.json.*

/** Native metadata is read from the displayed button, never supplied by a JS host call. */
object ScriptPageHostAction {
    fun parse(value: JsonObject): PostTurnTaskControl {
        fun text(key: String, max: Int): String = value.getValue(key).jsonPrimitive.let {
            require(it.isString && it.content.isNotBlank() && it.content.length <= max)
            it.content
        }
        fun attempt(): Int = value.getValue("attempt").jsonPrimitive.let {
            require(!it.isString); requireNotNull(it.intOrNull).also { count -> require(count in 0..3) }
        }
        return when (text("kind", 32)) {
            "postTurn.retry" -> {
                require(value.keys == setOf("kind", "jobId", "attempt"))
                PostTurnTaskControl.Retry(text("jobId", 256), attempt().also { require(it in 1..2) })
            }
            "postTurn.delete" -> {
                require(value.keys == setOf("kind", "jobId", "attempt", "status"))
                val status = PostTurnJobStatus.valueOf(text("status", 16))
                require(status != PostTurnJobStatus.RUNNING)
                PostTurnTaskControl.Delete(text("jobId", 256), attempt(), status)
            }
            "postTurn.stop" -> {
                require(value.keys == setOf("kind", "jobId"))
                PostTurnTaskControl.Stop(text("jobId", 256))
            }
            "postTurn.cleanup" -> {
                require(value.keys == setOf("kind")); PostTurnTaskControl.Cleanup
            }
            else -> error("Unsupported page host action")
        }
    }
}
