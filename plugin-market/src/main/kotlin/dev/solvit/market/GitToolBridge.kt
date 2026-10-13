package dev.solvit.market

import dev.solvit.core.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** One authorized tool invocation; no retained session, workspace selector or shell command. */
class GitToolBridge(private val service: WorkspaceGitService) {
    suspend fun query(operation: String, args: JsonObject, conversation: ConversationContext,
        authorize: suspend () -> Unit): JsonElement {
        val session = service.bind(conversation.sessionId, { conversation.sessionId }, authorize,
            { error("Git查询工具没有写授权") })
        try {
            authorize()
            fun number(key: String, default: Int): Int = args[key]?.jsonPrimitive?.also { require(!it.isString) }?.int ?: default
            fun text(key: String): String = args.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            val result = when (operation) {
                "git.status" -> {
                    require(args.keys.all { it in setOf("offset", "limit") })
                    val status = session.status(number("offset", 0), number("limit", 10))
                    buildJsonObject {
                        put("branch", status.branch?.let(::JsonPrimitive) ?: JsonNull)
                        put("head", status.head?.let(::JsonPrimitive) ?: JsonNull); put("total", status.total)
                        put("entries", buildJsonArray { status.entries.forEach { entry -> add(buildJsonObject {
                            put("path", entry.path); put("staged", entry.staged?.let(::JsonPrimitive) ?: JsonNull)
                            put("working", entry.working?.let(::JsonPrimitive) ?: JsonNull); put("conflict", entry.conflict)
                        }) } })
                    }
                }
                "git.diff" -> {
                    require(args.keys.all { it in setOf("path", "staged", "offset", "expectedPatchSha256") })
                    val staged = args["staged"]?.jsonPrimitive?.also { require(!it.isString) }?.boolean ?: false
                    val diff = session.diff(text("path"), staged)
                    val patch = diff.patch
                    val digest = MessageDigest.getInstance("SHA-256").digest(patch.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    val offset = number("offset", 0).also { require(it in 0..patch.length) }
                    val expected = args["expectedPatchSha256"]?.takeUnless { it == JsonNull }?.jsonPrimitive
                        ?.also { require(it.isString && it.content.matches(Regex("[a-f0-9]{64}"))) }?.content
                    check(offset == 0 || expected != null) { "继续读取差异必须传入前一块的patchSha256" }
                    check(expected == null || expected == digest) { "Git差异已变化，请从第一块重新读取" }
                    require(offset == 0 || offset == patch.length || !patch[offset].isLowSurrogate())
                    var end = minOf(offset + 4096, patch.length)
                    if (end < patch.length && patch[end - 1].isHighSurrogate()) end--
                    buildJsonObject {
                        put("path", diff.path); put("staged", staged); put("patch", patch.substring(offset, end))
                        put("offset", offset); put("nextOffset", end); put("total", patch.length); put("patchSha256", digest)
                        put("head", diff.revision.head?.let(::JsonPrimitive) ?: JsonNull)
                    }
                }
                "git.history" -> {
                    require(args.keys.all { it == "limit" })
                    buildJsonArray { session.history(number("limit", 5)).forEach { commit -> add(buildJsonObject {
                        put("id", commit.id); put("message", commit.message); put("author", commit.author); put("timeSeconds", commit.timeSeconds)
                    }) } }
                }
                else -> error("未开放的Git工具操作")
            }
            authorize()
            require(result.toString().length <= 16 * 1024) { "Git工具结果过大，请减少每页条数" }
            return result
        } finally { withContext(NonCancellable) { session.close() } }
    }
}
