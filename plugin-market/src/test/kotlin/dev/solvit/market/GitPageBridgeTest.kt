package dev.solvit.market

import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class GitPageBridgeTest {
    private class Fixture : WorkspaceGitService {
        val revision = WorkspaceGitSession.Revision("head", "index", "refs/heads/main")
        var writes = 0
        var closed = false
        var staged: WorkspaceGitSession.Diff? = null
        override fun bind(chatId: String, activeChat: () -> String?, authorizeRead: suspend () -> Unit,
            authorizeWrite: suspend () -> Unit): WorkspaceGitSession = object : WorkspaceGitSession {
            suspend fun read() { check(!closed); authorizeRead(); check(activeChat() == chatId) }
            suspend fun write() { read(); authorizeWrite(); writes++ }
            override suspend fun initialize(): WorkspaceGitSession.Revision { write(); return revision }
            override suspend fun status(offset: Int, limit: Int): WorkspaceGitSession.Status { read(); return WorkspaceGitSession.Status("main", "head", emptyList(), 0) }
            override suspend fun diff(path: String, staged: Boolean): WorkspaceGitSession.Diff {
                read(); return WorkspaceGitSession.Diff(path, staged, "a".repeat(4095) + "😀\n", revision, "file", false)
            }
            override suspend fun reviewIndex(): WorkspaceGitSession.Revision { read(); return revision }
            override suspend fun history(limit: Int): List<WorkspaceGitSession.Commit> { read(); return emptyList() }
            override suspend fun stage(review: WorkspaceGitSession.Diff): WorkspaceGitSession.Revision {
                write(); this@Fixture.staged = review; return revision
            }
            override suspend fun unstage(path: String, expected: WorkspaceGitSession.Revision): WorkspaceGitSession.Revision { write(); return revision }
            override suspend fun commit(expected: WorkspaceGitSession.Revision, message: String, author: String, email: String): WorkspaceGitSession.Commit {
                write(); assertSame(revision, expected); return WorkspaceGitSession.Commit("new", message, author, 1)
            }
            override suspend fun close() { closed = true }
        }
        override suspend fun close() { closed = true }
    }

    @Test fun chunkedReviewRejectsForgedOrReplacedHandlesAndNeverSplitsEmoji() = runBlocking {
        val fixture = Fixture()
        var active = "chat"
        val page = GitPageBridge(fixture, "chat", { active })
        suspend fun review(path: String) = Json.parseToJsonElement(page.query("diff", """{"path":"$path","staged":false}""") {}).jsonObject["reviewId"]!!.jsonPrimitive.content
        val old = review("old.txt")
        val id = review("new.txt")
        assertFailsWith<IllegalStateException> { page.perform(PageWorkspaceControl("stage", """{"reviewId":"$old"}""")) {} }
        assertFailsWith<IllegalArgumentException> { page.perform(PageWorkspaceControl("stage", """{"reviewId":"$id","patch":"forged"}""")) {} }
        assertEquals(0, fixture.writes)
        val first = Json.parseToJsonElement(page.query("diffChunk", """{"reviewId":"$id","offset":0}""") {}).jsonObject
        assertEquals(4095, first["nextOffset"]!!.jsonPrimitive.int)
        assertEquals("a".repeat(4095), first["text"]!!.jsonPrimitive.content)
        val last = Json.parseToJsonElement(page.query("diffChunk", """{"reviewId":"$id","offset":4095}""") {}).jsonObject
        assertEquals("😀\n", last["text"]!!.jsonPrimitive.content)
        active = "other"
        assertFailsWith<IllegalStateException> { page.query("diffChunk", """{"reviewId":"$id","offset":0}""") {} }
        active = "chat"
        page.perform(PageWorkspaceControl("stage", """{"reviewId":"$id"}""")) {}
        assertEquals("new.txt", fixture.staged!!.path)
        assertSame(fixture.revision, fixture.staged!!.revision)
        assertFailsWith<IllegalStateException> { page.perform(PageWorkspaceControl("stage", """{"reviewId":"$id"}""")) {} }
        page.close()
        assertFailsWith<IllegalStateException> { page.query("history", """{"limit":1}""") {} }
    }

    @Test fun writesRequireHostAuthorizationAndCommitRetainsNativeIndexReview() = runBlocking {
        val fixture = Fixture()
        val page = GitPageBridge(fixture, "chat", { "chat" })
        assertFailsWith<IllegalStateException> { page.query("initialize", "{}") {} }
        assertFailsWith<IllegalStateException> { page.perform(PageWorkspaceControl("initialize", "{}")) { error("revoked") } }
        assertEquals(0, fixture.writes)
        val review = Json.parseToJsonElement(page.query("reviewCommit", "{}") {}).jsonObject["revisionId"]!!.jsonPrimitive.content
        fun control(id: String) = PageWorkspaceControl("commit", """{"revisionId":"$id","message":"Reviewed","author":"Fixture","email":"test@example.invalid"}""")
        assertFailsWith<IllegalStateException> { page.perform(control("forged")) {} }
        assertEquals(0, fixture.writes)
        val result = Json.parseToJsonElement(page.perform(control(review)) {}).jsonObject
        assertEquals("new", result["id"]!!.jsonPrimitive.content)
        assertEquals(1, fixture.writes)
        assertFailsWith<IllegalStateException> { page.perform(control(review)) {} }
        page.close()
    }
}
