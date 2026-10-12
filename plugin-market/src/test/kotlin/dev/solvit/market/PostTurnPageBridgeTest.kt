package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.plugins.ScriptPageExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class PostTurnPageBridgeTest {
    private val owner = "dev.solvit.memory"
    private val target = PostTurnTarget(owner, "1.3.0", "a".repeat(64))
    private val turn = CompletedChatTurn("answer", "chat", "user", "answer", null, null, null,
        "private-profile", "provider", "model", 1, listOf(target))
    private val job = PostTurnJob("answer:$owner", turn, target, PostTurnJobStatus.FAILED, 1, 2, PostTurnFailure.MODEL)
    private class Tasks(var row: PostTurnJob) : PostTurnTaskService {
        var controls = mutableListOf<Pair<String, PostTurnTaskControl>>()
        override suspend fun list(pluginId: String, before: PostTurnJobCursor?, limit: Int, authorize: suspend () -> Unit): PostTurnTaskPage {
            assertEquals(20, limit); authorize(); return PostTurnTaskPage(listOf(row), null)
        }
        override suspend fun control(pluginId: String, action: PostTurnTaskControl, authorize: suspend () -> Unit): Int {
            authorize(); controls += pluginId to action; return 1
        }
        override suspend fun retired(pluginId: String, turnId: String, authorize: suspend () -> Unit): Boolean {
            authorize(); return false
        }
    }
    @Test fun listingProjectsOnlySafeOwnerMetadataAndRejectsForeignServiceRows() = runBlocking<Unit> {
        val tasks = Tasks(job); val page = ScriptPageExecutionContext(owner, {})
        val bridge = PostTurnPageBridge(tasks)
        val listed = bridge.call(owner, "jobs.list", buildJsonObject {}, page, {})
        assertEquals(JsonPrimitive(true), listed["available"])
        val row = listed.getValue("jobs").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("user"), row["userMessageId"])
        assertEquals(JsonPrimitive("MODEL"), row["failure"])
        assertFalse(listed.toString().contains("private-profile"))
        assertFalse(listed.toString().contains(target.digest))
        tasks.row = job.copy(target = target.copy(pluginId = "other.memory"))
        assertFailsWith<IllegalStateException> { bridge.call(owner, "jobs.list", buildJsonObject {}, page, {}) }
        assertFailsWith<IllegalArgumentException> { bridge.call(owner, "jobs.list", buildJsonObject { put("pluginId", "other.memory") }, page, {}) }
    }
    @Test fun mutationConsumesNativeProofOnceAndCannotAcceptSubstitutedJobArguments() = runBlocking<Unit> {
        val tasks = Tasks(job); var allowed = true
        val proof = buildJsonObject { put("kind", "postTurn.retry"); put("jobId", job.id); put("attempt", 1) }
        val page = ScriptPageExecutionContext(owner, { check(allowed) }, proof)
        val bridge = PostTurnPageBridge(tasks)
        assertFailsWith<IllegalArgumentException> {
            bridge.call(owner, "jobs.perform", buildJsonObject { put("jobId", "other-job") }, page, { check(allowed) })
        }
        assertTrue(tasks.controls.isEmpty())
        assertEquals(JsonPrimitive(1), bridge.call(owner, "jobs.perform", buildJsonObject {}, page, { check(allowed) })["changed"])
        assertEquals(listOf(owner to PostTurnTaskControl.Retry(job.id, 1)), tasks.controls)
        assertFailsWith<IllegalStateException> { bridge.call(owner, "jobs.perform", buildJsonObject {}, page, { check(allowed) }) }
        assertFailsWith<IllegalStateException> {
            bridge.call(owner, "jobs.perform", buildJsonObject {}, ScriptPageExecutionContext(owner, {}), {})
        }
        allowed = false
        assertFailsWith<IllegalStateException> { bridge.call(owner, "jobs.list", buildJsonObject {}, page, { check(allowed) }) }
        assertEquals(1, tasks.controls.size)
    }
    @Test fun unavailableServiceReturnsExplicitReadOnlyStatus() = runBlocking<Unit> {
        val page = ScriptPageExecutionContext(owner, {})
        val result = PostTurnPageBridge(null).call(owner, "jobs.list", buildJsonObject {}, page, {})
        assertEquals(JsonPrimitive(false), result["available"])
        assertTrue(result.getValue("jobs").jsonArray.isEmpty())
        assertFailsWith<IllegalStateException> { PostTurnPageBridge(null).call(owner, "jobs.perform", buildJsonObject {}, page, {}) }
    }
}
