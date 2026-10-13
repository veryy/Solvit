package dev.solvit.market

import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class GitToolBridgeTest {
    private class Fixture : WorkspaceGitService {
        var patch = "a".repeat(4095) + "😀\n"
        var closed = 0
        var reads = 0
        override fun bind(chatId: String, activeChat: () -> String?, authorizeRead: suspend () -> Unit,
            authorizeWrite: suspend () -> Unit): WorkspaceGitSession = object : WorkspaceGitSession {
            val revision = WorkspaceGitSession.Revision("head", "index", "refs/heads/main")
            suspend fun read() { authorizeRead(); reads++; assertEquals(chatId, activeChat()) }
            override suspend fun initialize(): WorkspaceGitSession.Revision = error("Must not initialize")
            override suspend fun stage(review: WorkspaceGitSession.Diff): WorkspaceGitSession.Revision = error("Must not stage")
            override suspend fun unstage(path: String, expected: WorkspaceGitSession.Revision): WorkspaceGitSession.Revision = error("Must not unstage")
            override suspend fun commit(expected: WorkspaceGitSession.Revision, message: String, author: String, email: String): WorkspaceGitSession.Commit = error("Must not commit")
            override suspend fun status(offset: Int, limit: Int): WorkspaceGitSession.Status {
                read(); return WorkspaceGitSession.Status("main", "head", listOf(WorkspaceGitSession.Entry("src/file.kt", "modified", "modified", false)), 1)
            }
            override suspend fun diff(path: String, staged: Boolean): WorkspaceGitSession.Diff {
                read(); return WorkspaceGitSession.Diff(path, staged, patch, revision, "file", false)
            }
            override suspend fun history(limit: Int): List<WorkspaceGitSession.Commit> {
                read(); return listOf(WorkspaceGitSession.Commit("head", "Reviewed", "Fixture", 1))
            }
            override suspend fun reviewIndex(): WorkspaceGitSession.Revision = error("Not exposed")
            override suspend fun close() { closed++ }
        }
        override suspend fun close() { }
    }
    private val conversation = ConversationContext("chat", "workspace")

    @Test fun completeDiffRequiresStableDigestPreservesEmojiAndClosesEveryInvocation() = runBlocking<Unit> {
        val fixture = Fixture(); val bridge = GitToolBridge(fixture)
        val first = bridge.query("git.diff", buildJsonObject { put("path", "src/file.kt") }, conversation) {}.jsonObject
        assertEquals(4095, first["nextOffset"]!!.jsonPrimitive.int)
        assertEquals("a".repeat(4095), first["patch"]!!.jsonPrimitive.content)
        val next = buildJsonObject { put("path", "src/file.kt"); put("offset", 4095); put("expectedPatchSha256", first.getValue("patchSha256")) }
        val last = bridge.query("git.diff", next, conversation) {}.jsonObject
        assertEquals("😀\n", last["patch"]!!.jsonPrimitive.content)
        assertEquals(2, fixture.closed)
        assertFailsWith<IllegalStateException> { bridge.query("git.diff", buildJsonObject { put("path", "src/file.kt"); put("offset", 4095) }, conversation) {} }
        fixture.patch = "different worktree\n"
        assertFails { bridge.query("git.diff", next, conversation) {} }
        assertEquals(4, fixture.closed)
    }

    @Test fun deniedAuthorityAndHiddenRootsCannotReadOrWriteAndAlwaysClose() = runBlocking<Unit> {
        val fixture = Fixture(); val bridge = GitToolBridge(fixture)
        assertFailsWith<IllegalStateException> { bridge.query("git.status", JsonObject(emptyMap()), conversation) { error("revoked") } }
        assertEquals(0, fixture.reads); assertEquals(1, fixture.closed)
        assertFailsWith<IllegalArgumentException> { bridge.query("git.status", buildJsonObject { put("workspaceId", "other") }, conversation) {} }
        assertFailsWith<IllegalStateException> { bridge.query("git.commit", JsonObject(emptyMap()), conversation) {} }
        assertEquals(0, fixture.reads); assertEquals(3, fixture.closed)
        val status = bridge.query("git.status", JsonObject(emptyMap()), conversation) {}.jsonObject
        val entry = status.getValue("entries").jsonArray.single().jsonObject
        assertEquals("modified", entry["staged"]!!.jsonPrimitive.content)
        assertEquals("modified", entry["working"]!!.jsonPrimitive.content)
        assertEquals(4, fixture.closed)
        var checks = 0
        assertFailsWith<IllegalStateException> { bridge.query("git.history", JsonObject(emptyMap()), conversation) {
            checks++; if (checks >= 3) error("revoked after native read")
        } }
        assertEquals(5, fixture.closed)
    }
}
