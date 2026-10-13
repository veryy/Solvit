package dev.solvit.market

import dev.solvit.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Immutable observations of one host-selected run. Scripts cannot select a chat, run or path. */
class ExecutionChangesPageBridge(private val chat: ChatService, private val run: ExecutionRun,
    private val activeChat: () -> String? = { run.sessionId }) : PageWorkspaceAccess {
    init { run.validate() }
    private val mutex = Mutex()
    private data class Review(val before: RecordedExecutionEvent?, val after: RecordedExecutionEvent?,
        val changes: List<WorkspaceChange>, val complete: Boolean, val pending: Boolean)
    private var review: Review? = null
    private suspend fun verify(authorize: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        authorize()
        check(activeChat() == run.sessionId) { "对话已变化，请重新打开文件变更。" }
    }
    private suspend fun load(authorize: suspend () -> Unit): Review {
        review?.let { return it }
        val events = chat.executionEvents(run.sessionId, run.id)
        verify(authorize)
        val before = events.firstOrNull { it.event.kind == ExecutionKind.WORKSPACE_BASELINE }
        val after = events.lastOrNull { it.event.kind == ExecutionKind.WORKSPACE_OBSERVED }
        val comparison = if (before != null && after != null)
            compareWorkspaceSnapshots(checkNotNull(before.event.workspace), checkNotNull(after.event.workspace)) else null
        return Review(before, after, comparison?.changes.orEmpty(), comparison?.complete == true,
            after == null || events.any { it.sequence > after.sequence && it.event.kind == ExecutionKind.TOOL_STARTED })
            .also { review = it }
    }
    private fun observation(value: FileObservation) = buildJsonObject {
        put("kind", value.kind.name)
        put("bytes", value.bytes?.let(::JsonPrimitive) ?: JsonNull)
        put("sha256", value.sha256?.let(::JsonPrimitive) ?: JsonNull)
        put("captured", value.captureSha256 != null)
    }
    override suspend fun query(operation: String, argumentsJson: String, authorize: suspend () -> Unit): String = mutex.withLock {
        verify(authorize)
        val args = Json.parseToJsonElement(argumentsJson).jsonObject
        val source = load(authorize)
        val result = when (operation) {
            "changes" -> {
                require(args.isEmpty())
                buildJsonObject {
                    put("complete", source.complete); put("pending", source.pending)
                    put("beforeAt", source.before?.recordedAt?.let(::JsonPrimitive) ?: JsonNull)
                    put("afterAt", source.after?.recordedAt?.let(::JsonPrimitive) ?: JsonNull)
                    put("changes", buildJsonArray { source.changes.forEachIndexed { index, value -> add(buildJsonObject {
                        put("index", index); put("path", value.path); put("kind", value.kind.name)
                        put("before", observation(value.before)); put("after", observation(value.after))
                    }) } })
                }
            }
            "changeContent" -> {
                require(args.keys == setOf("index", "side", "offset"))
                fun integer(key: String) = args.getValue(key).jsonPrimitive.also { require(!it.isString) }.int
                val index = integer("index"); require(index in source.changes.indices)
                val side = args.getValue("side").jsonPrimitive.also { require(it.isString) }.content
                require(side in setOf("before", "after"))
                val offset = integer("offset"); require(offset in 0..MAX_FILE_CAPTURE_BYTES)
                val change = source.changes[index]
                val value = if (side == "before") change.before else change.after
                val sequence = if (side == "before") source.before?.sequence else source.after?.sequence
                val text = when {
                    value.kind == FileObservationKind.ABSENT -> ""
                    value.captureSha256 == null || sequence == null -> null
                    else -> chat.executionFileContent(run.sessionId, run.id, sequence, change.path) { verify(authorize) }
                }
                if (text != null) {
                    require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_CAPTURE_BYTES)
                    if (value.kind != FileObservationKind.ABSENT) {
                        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                        check(hash == value.captureSha256 && hash == value.sha256) { "历史副本摘要不匹配" }
                    }
                    require(offset <= text.length)
                    require(offset == 0 || offset == text.length || !text[offset].isLowSurrogate() || !text[offset - 1].isHighSurrogate())
                }
                var end = text?.let { minOf(it.length, offset + 4096) } ?: 0
                if (text != null && end < text.length && end > offset && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                buildJsonObject {
                    put("available", text != null); put("kind", value.kind.name)
                    put("sha256", value.sha256?.let(::JsonPrimitive) ?: JsonNull)
                    put("text", text?.substring(offset, end)?.let(::JsonPrimitive) ?: JsonNull)
                    put("offset", offset); put("nextOffset", end); put("total", text?.length?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
            else -> error("未开放的执行记录查询")
        }
        verify(authorize)
        result.toString()
    }
    override suspend fun perform(control: PageWorkspaceControl, authorize: suspend () -> Unit): String {
        verify(authorize)
        error("文件变更页面只读，不能重放或写入操作")
    }
}
