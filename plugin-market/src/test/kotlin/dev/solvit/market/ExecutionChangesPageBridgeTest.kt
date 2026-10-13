package dev.solvit.market

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import kotlin.test.*

class ExecutionChangesPageBridgeTest {
    private val run = ExecutionRun("run", "chat", "user", null, "workspace", 1)
    private val before = "a".repeat(4095) + "🙂before\n".repeat(800)
    private val after = before + "AFTER-TAIL"
    private fun value(text: String, captured: Boolean = true): FileObservation {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return FileObservation(FileObservationKind.TEXT, bytes.size.toLong(), hash, "preview", true, null,
            if (captured) hash else null)
    }
    private class Source(var records: List<RecordedExecutionEvent>, val copies: Map<Pair<Int, String>, String>) : ChatService {
        val reads = mutableListOf<List<Any>>()
        var afterRead: suspend () -> Unit = {}
        override suspend fun executionEvents(sessionId: String, runId: String): List<RecordedExecutionEvent> {
            assertEquals("chat", sessionId); assertEquals("run", runId); return records
        }
        override suspend fun executionFileContent(sessionId: String, runId: String, sequence: Int, path: String,
            authorize: suspend () -> Unit): String? {
            authorize(); reads.add(listOf(sessionId, runId, sequence, path)); afterRead(); authorize()
            return copies[sequence to path]
        }
        override fun sessions(): Flow<List<ChatSession>> = emptyFlow()
        override fun messages(sessionId: String): Flow<List<ChatMessage>> = emptyFlow()
        override suspend fun createSession(): ChatSession = error("not used")
        override suspend fun deleteSession(sessionId: String) = error("not used")
        override fun send(sessionId: String, text: String, config: ModelConfig, authorization: ToolAuthorization): Flow<ModelChunk> = error("must not execute")
    }
    private fun source(): Source {
        val old = WorkspaceSnapshot("workspace", mapOf("code.txt" to value(before), "deleted" to value("gone")), true, 2)
        val new = WorkspaceSnapshot("workspace", mapOf("code.txt" to value(after), "created" to value("new")), true, 5)
        return Source(listOf(RecordedExecutionEvent(2, 2, ExecutionEvent(1, ExecutionKind.WORKSPACE_BASELINE, workspace = old)),
            RecordedExecutionEvent(5, 5, ExecutionEvent(1, ExecutionKind.WORKSPACE_OBSERVED, workspace = new))),
            mapOf((2 to "code.txt") to before, (5 to "code.txt") to after, (2 to "deleted") to "gone", (5 to "created") to "new"))
    }
    private suspend fun query(bridge: ExecutionChangesPageBridge, operation: String, args: String = "{}") =
        Json.parseToJsonElement(bridge.query(operation, args) {}).jsonObject
    private suspend fun content(bridge: ExecutionChangesPageBridge, index: Int, side: String): String {
        var offset = 0; val result = StringBuilder()
        do {
            val chunk = query(bridge, "changeContent", """{"index":$index,"side":"$side","offset":$offset}""")
            assertTrue(chunk.getValue("available").jsonPrimitive.boolean)
            val text = chunk.getValue("text").jsonPrimitive.content
            assertTrue(text.length <= 4096)
            assertFalse(text.lastOrNull()?.isHighSurrogate() == true)
            result.append(text); offset = chunk.getValue("nextOffset").jsonPrimitive.int
        } while (offset < chunk.getValue("total").jsonPrimitive.int)
        return result.toString()
    }
    @Test fun chunksPreserveUnicodeAndCapturedEventIdentityEvenWhenNewObservationsArrive() = runBlocking<Unit> {
        val source = source(); val bridge = ExecutionChangesPageBridge(source, run)
        val index = query(bridge, "changes").getValue("changes").jsonArray.indexOfFirst { it.jsonObject["path"] == JsonPrimitive("code.txt") }
        source.records = source.records + RecordedExecutionEvent(8, 8, ExecutionEvent(1, ExecutionKind.WORKSPACE_OBSERVED,
            workspace = WorkspaceSnapshot("workspace", mapOf("code.txt" to value("later overwrite")), true, 8)))
        assertEquals(before, content(bridge, index, "before")); assertEquals(after, content(bridge, index, "after"))
        assertTrue(source.reads.all { it == listOf("chat", "run", 2, "code.txt") || it == listOf("chat", "run", 5, "code.txt") })
    }
    @Test fun absentIsAnEmptyFileButMissingLegacyCopiesAreExplicitlyUnavailable() = runBlocking<Unit> {
        val source = source(); val bridge = ExecutionChangesPageBridge(source, run)
        val changes = query(bridge, "changes").getValue("changes").jsonArray
        val created = changes.indexOfFirst { it.jsonObject["path"] == JsonPrimitive("created") }
        assertEquals("", content(bridge, created, "before")); assertEquals("new", content(bridge, created, "after"))
        val legacy = source.records.first().copy(event = ExecutionEvent(1, ExecutionKind.WORKSPACE_BASELINE,
            workspace = WorkspaceSnapshot("workspace", mapOf("code.txt" to value(before, false)), true, 2)))
        source.records = listOf(legacy, source.records.last())
        val oldBridge = ExecutionChangesPageBridge(source, run)
        val index = query(oldBridge, "changes").getValue("changes").jsonArray.indexOfFirst { it.jsonObject["path"] == JsonPrimitive("code.txt") }
        val missing = query(oldBridge, "changeContent", """{"index":$index,"side":"before","offset":0}""")
        assertFalse(missing.getValue("available").jsonPrimitive.boolean); assertEquals(JsonNull, missing["text"])
    }
    @Test fun scriptCannotChooseAnotherConversationRunPathOrMutation() = runBlocking<Unit> {
        val source = source(); val bridge = ExecutionChangesPageBridge(source, run)
        assertFails { query(bridge, "changes", """{"runId":"foreign"}""") }
        for (args in listOf("""{"index":0,"side":"before","offset":0,"path":"secret"}""",
            """{"index":999,"side":"before","offset":0}""", """{"index":0,"side":"other","offset":0}""",
            """{"index":0,"side":"before","offset":-1}""", """{"index":0,"side":"before","offset":4096}"""))
            assertFails { query(bridge, "changeContent", args) }
        assertFails { query(bridge, "read", """{"path":"code.txt"}""") }
        assertFails { bridge.perform(PageWorkspaceControl("write", "{}")) {} }
    }
    @Test fun selectedChatRevocationAndCancellationAreCheckedAfterReading() = runBlocking<Unit> {
        val source = source(); var selected = "chat"
        val bridge = ExecutionChangesPageBridge(source, run) { selected }
        query(bridge, "changes"); source.afterRead = { selected = "foreign" }
        assertFails { query(bridge, "changeContent", """{"index":0,"side":"before","offset":0}""") }
        selected = "chat"; source.afterRead = {}; var allowed = true
        source.afterRead = { allowed = false }
        assertFails { bridge.query("changeContent", """{"index":0,"side":"before","offset":0}""") { check(allowed) } }
        source.afterRead = { currentCoroutineContext().cancel() }
        val task = async { query(bridge, "changeContent", """{"index":0,"side":"before","offset":0}""") }
        assertFailsWith<CancellationException> { task.await() }
    }
    @Test fun wrongContentCannotPretendToBeTheStoredFile() = runBlocking<Unit> {
        val valid = source()
        val bad = Source(valid.records, valid.copies + ((2 to "code.txt") to "wrong historical body"))
        assertFails { query(ExecutionChangesPageBridge(bad, run), "changeContent", """{"index":0,"side":"before","offset":0}""") }
    }
}
