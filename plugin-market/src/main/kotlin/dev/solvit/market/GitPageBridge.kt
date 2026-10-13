package dev.solvit.market

import dev.solvit.core.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Retains native review values. Scripts can select handles but cannot supply Git revisions. */
class GitPageBridge(service: WorkspaceGitService, chatId: String, activeChat: () -> String?) : PageGitAccess {
    private class Authority(val verify: suspend () -> Unit, val write: Boolean) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Authority>
    }
    private val mutex = Mutex()
    private var closed = false
    private var review: Pair<String, WorkspaceGitSession.Diff>? = null
    private var index: Pair<String, WorkspaceGitSession.Revision>? = null
    private val session = service.bind(chatId, activeChat, { verify(false) }, { verify(true) })

    private suspend fun verify(write: Boolean) {
        check(!closed) { "Git页面已关闭" }
        val authority = checkNotNull(currentCoroutineContext()[Authority]) { "缺少Git页面授权" }
        check(!write || authority.write) { "Git只读查询不能执行写入" }
        authority.verify()
        check(!closed) { "Git页面已关闭" }
    }
    private fun arguments(json: String): JsonObject {
        require(json.length <= 8192)
        return Json.parseToJsonElement(json).jsonObject
    }
    private fun JsonObject.text(key: String, max: Int = 1024): String = getValue(key).jsonPrimitive
        .also { require(it.isString && it.content.length in 1..max) }.content
    private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive
        .also { require(!it.isString) }.int
    private fun remember(revision: WorkspaceGitSession.Revision): JsonObject {
        val id = UUID.randomUUID().toString()
        index = id to revision
        return buildJsonObject { put("revisionId", id) }
    }
    private fun status(value: WorkspaceGitSession.Status) = buildJsonObject {
        put("branch", value.branch?.let(::JsonPrimitive) ?: JsonNull)
        put("head", value.head?.let(::JsonPrimitive) ?: JsonNull)
        put("total", value.total)
        put("entries", buildJsonArray { value.entries.forEach { entry -> add(buildJsonObject {
            put("path", entry.path); put("staged", entry.staged?.let(::JsonPrimitive) ?: JsonNull)
            put("working", entry.working?.let(::JsonPrimitive) ?: JsonNull); put("conflict", entry.conflict)
        }) } })
    }

    override suspend fun query(operation: String, argumentsJson: String, authorize: suspend () -> Unit): String =
        mutex.withLock { withContext(Authority(authorize, false)) {
            verify(false)
            val args = arguments(argumentsJson)
            val result = when (operation) {
                "status" -> {
                    require(args.keys == setOf("offset", "limit"))
                    status(session.status(args.number("offset"), args.number("limit")))
                }
                "diff" -> {
                    require(args.keys == setOf("path", "staged"))
                    val staged = args.getValue("staged").jsonPrimitive.also { require(!it.isString) }.boolean
                    val diff = session.diff(args.text("path"), staged)
                    require(diff.patch.length <= 256 * 1024)
                    val id = UUID.randomUUID().toString()
                    review = id to diff
                    buildJsonObject { put("reviewId", id); put("path", diff.path); put("staged", diff.staged); put("total", diff.patch.length) }
                }
                "diffChunk" -> {
                    require(args.keys == setOf("reviewId", "offset"))
                    session.reviewIndex() // Recheck the captured native binding even for cached text.
                    val retained = checkNotNull(review)
                    check(retained.first == args.text("reviewId")) { "差异审阅已变化，请重新读取" }
                    val patch = retained.second.patch
                    val offset = args.number("offset").also { require(it in 0..patch.length) }
                    require(offset == 0 || offset == patch.length || !patch[offset].isLowSurrogate())
                    var end = minOf(offset + 4096, patch.length)
                    if (end < patch.length && patch[end - 1].isHighSurrogate()) end--
                    buildJsonObject { put("text", patch.substring(offset, end)); put("offset", offset); put("nextOffset", end); put("total", patch.length) }
                }
                "reviewCommit" -> { require(args.isEmpty()); remember(session.reviewIndex()) }
                "history" -> {
                    require(args.keys == setOf("limit"))
                    buildJsonArray { session.history(args.number("limit")).forEach { commit -> add(buildJsonObject {
                        put("id", commit.id); put("message", commit.message); put("author", commit.author); put("timeSeconds", commit.timeSeconds)
                    }) } }
                }
                else -> error("未开放的Git页面查询")
            }
            verify(false)
            result.toString().also { require(it.length <= 16 * 1024) { "Git查询结果过大，请减少每页条数" } }
        } }

    override suspend fun perform(control: PageWorkspaceControl, authorize: suspend () -> Unit): String =
        mutex.withLock { withContext(Authority(authorize, true)) {
            verify(true)
            val args = arguments(control.argumentsJson)
            val result = when (control.operation) {
                "initialize" -> { require(args.isEmpty()); remember(session.initialize()) }
                "stage" -> {
                    require(args.keys == setOf("reviewId"))
                    val retained = checkNotNull(review)
                    check(retained.first == args.text("reviewId")) { "差异审阅已变化，请重新读取" }
                    remember(session.stage(retained.second)).also { review = null }
                }
                "unstage" -> {
                    require(args.keys == setOf("reviewId"))
                    val retained = checkNotNull(review)
                    check(retained.first == args.text("reviewId") && retained.second.staged)
                    remember(session.unstage(retained.second.path, retained.second.revision)).also { review = null }
                }
                "commit" -> {
                    require(args.keys == setOf("revisionId", "message", "author", "email"))
                    val retained = checkNotNull(index)
                    check(retained.first == args.text("revisionId")) { "暂存区审阅已变化，请重新确认" }
                    val commit = session.commit(retained.second, args.text("message", 4096), args.text("author", 80), args.text("email", 254))
                    index = null; review = null
                    buildJsonObject { put("id", commit.id); put("message", commit.message) }
                }
                else -> error("未开放的Git页面操作")
            }
            verify(true)
            result.toString()
        } }

    override suspend fun close() = withContext(NonCancellable) {
        mutex.withLock { closed = true; review = null; index = null; session.close() }
    }
}
