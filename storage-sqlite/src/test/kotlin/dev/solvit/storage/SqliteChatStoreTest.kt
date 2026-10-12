package dev.solvit.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SqliteChatStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun before() { context.deleteDatabase("solvit.db") }
    @After fun after() { context.deleteDatabase("solvit.db") }

    @Test fun taskPageQueriesAndControlsStayWithinOwnerAndAuthorizeAtTheWriteLock() = runBlocking<Unit> {
        val mine = PostTurnTarget("dev.solvit.memory", "1.3.0", "a".repeat(64))
        val other = PostTurnTarget("other.memory", "1.0.0", "b".repeat(64))
        SqliteChatStore(context).use { store ->
            val session = store.createSession()
            repeat(23) { index ->
                val suffix = index.toString().padStart(3, '0')
                store.append(ChatMessage("u-$suffix", session.id, Role.USER, "Preference"))
                val turn = CompletedChatTurn("a-$suffix", session.id, "u-$suffix", "a-$suffix", null, null,
                    null, null, "fixture", "fixture", index.toLong(), listOf(mine, other))
                store.appendCompletedTurn(listOf(ChatMessage(turn.id, session.id, Role.ASSISTANT, "Saved")), turn)
            }
            var authorized = 0
            val first = store.ownPostTurnJobs(mine.pluginId, null, 20) { authorized++ }
            assertEquals(20, first.size); assertTrue(first.all { it.target == mine }); assertEquals(1, authorized)
            val cursor = first.last().let { PostTurnJobCursor(it.updatedAt, it.id) }
            val second = store.ownPostTurnJobs(mine.pluginId, cursor, 20) {}
            assertEquals(3, second.size); assertEquals(23, (first + second).map { it.id }.toSet().size)
            assertFailsWith<IllegalArgumentException> { store.ownPostTurnJobs(mine.pluginId, null, 21) {} }
            val foreign = store.ownPostTurnJobs(other.pluginId, null, 1) {}.single()
            assertEquals(0, store.controlPostTurnJobs(mine.pluginId,
                PostTurnTaskControl.Delete(foreign.id, 0, PostTurnJobStatus.WAITING), 24) {})
            val selected = first.first()
            assertTrue(store.claimPostTurnJob(selected.id, 0, 24))
            assertTrue(store.finishPostTurnJob(selected.id, 1, PostTurnJobStatus.FAILED, PostTurnFailure.MODEL, 25))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var allowed = true
            val pending = async {
                runCatching { store.controlPostTurnJobs(mine.pluginId, PostTurnTaskControl.Retry(selected.id, 1), 26) {
                    entered.complete(Unit); release.await(); check(allowed) { "Revoked" }
                } }
            }
            entered.await(); allowed = false; release.complete(Unit)
            assertTrue(pending.await().isFailure)
            assertEquals(PostTurnJobStatus.FAILED, store.postTurnJob(selected.id)!!.status)
            assertEquals(1, store.controlPostTurnJobs(mine.pluginId, PostTurnTaskControl.Retry(selected.id, 1), 26) {})
            assertEquals(0, store.controlPostTurnJobs(mine.pluginId,
                PostTurnTaskControl.Delete(selected.id, 1, PostTurnJobStatus.FAILED), 27) {})
            assertTrue(store.claimPostTurnJob(selected.id, 1, 28))
            assertTrue(store.finishPostTurnJob(selected.id, 2, PostTurnJobStatus.SUCCEEDED, null, 29))
            assertEquals(0, store.controlPostTurnJobs(other.pluginId, PostTurnTaskControl.Cleanup, 30) {})
            assertEquals(1, store.controlPostTurnJobs(mine.pluginId, PostTurnTaskControl.Cleanup, 30) {})
            assertEquals(46, store.history(session.id).size)
            assertNotNull(store.postTurnJob(foreign.id))
            assertFailsWith<IllegalStateException> { store.ownPostTurnJobs(mine.pluginId, null, 20) { error("Revoked") } }
        }
    }

    @Test fun taskRetirementPreservesActiveAndRetryableWorkAndOriginalMessages() = runBlocking<Unit> {
        SqliteChatStore(context).use { store ->
            val session = store.createSession()
            val target = PostTurnTarget("dev.solvit.memory", "1.2.0", "a".repeat(64))
            val jobs = (0..4).map { index ->
                store.append(ChatMessage("user-$index", session.id, Role.USER, "Preference $index"))
                val turn = CompletedChatTurn("answer-$index", session.id, "user-$index", "answer-$index",
                    null, null, null, null, "openai-compatible", "fixture", 1, listOf(target))
                store.appendCompletedTurn(listOf(ChatMessage(turn.id, session.id, Role.ASSISTANT, "Saved")), turn)
                store.postTurnJob("${turn.id}:${target.pluginId}")!!
            }
            jobs.drop(1).forEach { assertTrue(store.claimPostTurnJob(it.id, 0, 2)) }
            assertTrue(store.finishPostTurnJob(jobs[2].id, 1, PostTurnJobStatus.SUCCEEDED, null, 3))
            assertTrue(store.finishPostTurnJob(jobs[3].id, 1, PostTurnJobStatus.FAILED, PostTurnFailure.MODEL, 3))
            assertTrue(store.finishPostTurnJob(jobs[4].id, 1, PostTurnJobStatus.SKIPPED, null, 3))
            assertEquals(1, store.deleteFinishedPostTurnJobs(target.pluginId, 1))
            assertEquals(1, store.deleteFinishedPostTurnJobs(target.pluginId, 100))
            assertEquals(0, store.deleteFinishedPostTurnJobs(target.pluginId, 100))
            assertEquals(PostTurnJobStatus.RUNNING, store.postTurnJob(jobs[1].id)!!.status)
            assertEquals(PostTurnJobStatus.FAILED, store.postTurnJob(jobs[3].id)!!.status)
            assertTrue(store.retryPostTurnJob(jobs[3].id, 1, 4))
            assertFalse(store.deletePostTurnJob(jobs[3].id, 1, PostTurnJobStatus.FAILED))
            assertTrue(store.claimPostTurnJob(jobs[3].id, 1, 5))
            assertFalse(store.deletePostTurnJob(jobs[3].id, 1, PostTurnJobStatus.WAITING))
            assertFailsWith<IllegalArgumentException> { store.deletePostTurnJob(jobs[1].id, 1, PostTurnJobStatus.RUNNING) }
            assertTrue(store.deletePostTurnJob(jobs[0].id, 0, PostTurnJobStatus.WAITING))
            assertFalse(store.claimPostTurnJob(jobs[0].id, 0, 6))
            assertFalse(store.retryPostTurnJob(jobs[2].id, 1, 6))
            assertEquals(10, store.history(session.id).size)
            assertEquals(5, store.recordedTurns(0).size)
            assertFailsWith<IllegalArgumentException> { store.deleteFinishedPostTurnJobs(target.pluginId, 101) }
        }
    }

    @Test fun recentTasksPageBeyondOneHundredWithoutChangingConsumerOrder() = runBlocking<Unit> {
        val target = PostTurnTarget("dev.solvit.memory", "1.2.0", "a".repeat(64))
        SqliteChatStore(context).use { store ->
            val session = store.createSession()
            repeat(105) { index ->
                val suffix = index.toString().padStart(3, '0')
                store.append(ChatMessage("user-$suffix", session.id, Role.USER, "Preference $suffix"))
                val turn = CompletedChatTurn("answer-$suffix", session.id, "user-$suffix", "answer-$suffix",
                    null, null, null, null, "openai-compatible", "fixture", 1, listOf(target))
                store.appendCompletedTurn(listOf(ChatMessage(turn.id, session.id, Role.ASSISTANT, "Saved")), turn)
            }
            val first = store.recentPostTurnJobs(limit = 100)
            assertEquals(100, first.size)
            assertEquals("answer-104", first.first().turn.id)
            val cursor = first.last().let { PostTurnJobCursor(it.updatedAt, it.id) }
            val second = store.recentPostTurnJobs(cursor, 100)
            assertEquals(5, second.size)
            assertEquals(105, (first + second).map { it.id }.toSet().size)
            assertEquals("answer-000", second.last().turn.id)
            assertEquals("answer-000", store.postTurnJobs(setOf(PostTurnJobStatus.WAITING), 1).single().turn.id)
            val oldest = second.last()
            assertTrue(store.claimPostTurnJob(oldest.id, 0, 2))
            assertEquals(oldest.id, store.recentPostTurnJobs(limit = 1).single().id)
            assertFailsWith<IllegalArgumentException> { store.recentPostTurnJobs(limit = 101) }
            assertTrue(store.recentPostTurnJobs(PostTurnJobCursor(0, "end")).isEmpty())
        }
        SqliteChatStore(context).use { store ->
            assertEquals("answer-000", store.recentPostTurnJobs(limit = 1).single().turn.id)
        }
    }

    @Test fun completedTurnAndReplyCommitAtomicallyAndSourcesSurviveRestart() = runBlocking<Unit> {
        lateinit var session: ChatSession
        lateinit var record: CompletedChatTurn
        SqliteChatStore(context).use { store ->
            session = store.createSession()
            store.append(ChatMessage("user", session.id, Role.USER, "Remember my editor"))
            record = CompletedChatTurn("answer", session.id, "user", "answer", "coder", "Coder",
                setOf("dev.solvit.memory"), "primary", "openai-compatible", "fixture", 1,
                listOf(PostTurnTarget("dev.solvit.memory", "1.2.0", "a".repeat(64))))
            val reply = ChatMessage("answer", session.id, Role.ASSISTANT, "Acknowledged")
            SQLiteDatabase.openDatabase(context.getDatabasePath("solvit.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TRIGGER reject_turn BEFORE INSERT ON completed_turns BEGIN SELECT RAISE(ABORT,'fixture failure'); END")
            }
            assertFails { store.appendCompletedTurn(listOf(reply), record) }
            assertEquals(listOf("user"), store.history(session.id).map { it.id })
            assertTrue(store.recordedTurns(0).isEmpty())
            assertTrue(store.postTurnJobs(PostTurnJobStatus.entries.toSet()).isEmpty())
            SQLiteDatabase.openDatabase(context.getDatabasePath("solvit.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TRIGGER reject_turn") }
            assertFailsWith<IllegalArgumentException> { store.appendCompletedTurn(listOf(reply), record.copy(userMessageId = "missing")) }
            assertEquals(1, store.history(session.id).size)
            store.appendCompletedTurn(listOf(reply), record)
        }
        SqliteChatStore(context).use { store ->
            assertTrue(store.supportsCompletedTurns)
            val saved = store.recordedTurns(0, 1).single()
            assertEquals(record, saved.turn)
            assertEquals(listOf("user", "answer"), store.postTurnMessages(record).map { it.id })
            assertEquals(record.targets.single(), store.postTurnJobs(setOf(PostTurnJobStatus.WAITING)).single().target)
            assertTrue(store.recordedTurns(saved.sequence).isEmpty())
            assertFailsWith<IllegalArgumentException> { store.recordedTurns(-1) }
            assertFailsWith<IllegalArgumentException> { store.recordedTurns(0, 101) }
            val branch = store.forkSession(session.id, BranchPoint("answer", BranchMode.AFTER_REPLY))
            assertEquals(2, store.history(branch.id).size)
            assertEquals(listOf(record), store.recordedTurns(0).map { it.turn })
            store.deleteSession(session.id)
            assertTrue(store.recordedTurns(0).isEmpty())
            assertTrue(store.postTurnJobs(PostTurnJobStatus.entries.toSet()).isEmpty())
            assertEquals(2, store.history(branch.id).size)
        }
    }

    @Test fun versionSixMigrationDoesNotInventMemoryWorkFromOldChats() = runBlocking<Unit> {
        SqliteChatStore(context).use { store ->
            val session = store.createSession()
            store.appendBatch(listOf(ChatMessage("u", session.id, Role.USER, "old"), ChatMessage("a", session.id, Role.ASSISTANT, "reply")))
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath("solvit.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE execution_events"); db.execSQL("DROP TABLE execution_runs")
            db.execSQL("DROP TABLE post_turn_jobs"); db.execSQL("DROP TABLE completed_turns"); db.version = 6
        }
        SqliteChatStore(context).use { store ->
            assertEquals(2, store.history(store.sessions().first().single().id).size)
            assertTrue(store.recordedTurns(0).isEmpty())
        }
    }

    @Test fun postTurnJobClaimsAreExclusiveAndInterruptedWorkNeedsBoundedExplicitRetry() = runBlocking<Unit> {
        lateinit var job: PostTurnJob
        SqliteChatStore(context).use { store ->
            val session = store.createSession()
            store.append(ChatMessage("u", session.id, Role.USER, "I use Emacs"))
            val target = PostTurnTarget("dev.solvit.memory", "1.2.0", "b".repeat(64))
            val turn = CompletedChatTurn("a", session.id, "u", "a", null, null, null, null, "fixture", "model", 1, listOf(target))
            store.appendCompletedTurn(listOf(ChatMessage("a", session.id, Role.ASSISTANT, "Understood")), turn)
            job = store.postTurnJobs(setOf(PostTurnJobStatus.WAITING)).single()
            assertEquals(job, store.postTurnJob(job.id))
            assertTrue(store.claimPostTurnJob(job.id, 0, 2))
            assertEquals(PostTurnJobStatus.RUNNING, store.postTurnJob(job.id)!!.status)
            assertFalse(store.claimPostTurnJob(job.id, 0, 2))
            assertFalse(store.finishPostTurnJob(job.id, 2, PostTurnJobStatus.SUCCEEDED, null, 3))
        }
        SqliteChatStore(context).use { store ->
            assertEquals(1, store.recoverInterruptedPostTurnJobs(4))
            val failed = store.postTurnJobs(setOf(PostTurnJobStatus.FAILED)).single()
            assertEquals(1, failed.attempt); assertEquals(PostTurnFailure.INTERRUPTED, failed.failure)
            assertFalse(store.claimPostTurnJob(job.id, 1, 5))
            assertFalse(store.retryPostTurnJob(job.id, 2, 5))
            assertTrue(store.retryPostTurnJob(job.id, 1, 5))
            assertTrue(store.claimPostTurnJob(job.id, 1, 6))
            assertTrue(store.finishPostTurnJob(job.id, 2, PostTurnJobStatus.FAILED, PostTurnFailure.MODEL, 7))
            assertFalse(store.finishPostTurnJob(job.id, 2, PostTurnJobStatus.SUCCEEDED, null, 8))
            assertTrue(store.retryPostTurnJob(job.id, 2, 8))
            assertTrue(store.claimPostTurnJob(job.id, 2, 9))
            assertTrue(store.finishPostTurnJob(job.id, 3, PostTurnJobStatus.FAILED, PostTurnFailure.INVALID_OUTPUT, 10))
            assertFalse(store.retryPostTurnJob(job.id, 3, 11))
            assertTrue(store.postTurnJobs(emptySet()).isEmpty())
            assertFailsWith<IllegalArgumentException> { store.postTurnJobs(setOf(PostTurnJobStatus.FAILED), 101) }
            assertFailsWith<IllegalArgumentException> { store.finishPostTurnJob(job.id, 3, PostTurnJobStatus.SUCCEEDED, PostTurnFailure.MODEL, 12) }
        }
    }

    @Test fun summaryCommitsAgainstExactPrefixSurvivesRestartAndLeavesOriginalHistory() = runBlocking<Unit> {
        lateinit var session: ChatSession
        lateinit var summary: ContextSummary
        SqliteChatStore(context).use { store ->
            session = store.createSession()
            val history = listOf(ChatMessage("u", session.id, Role.USER, "old task"), ChatMessage("a", session.id, Role.ASSISTANT, "old answer"))
            store.appendBatch(history)
            summary = ContextSummary("s1", "a", contextPrefixDigest(history), 2, "Task completed; keep preference", "test", "model", 1_000, 100, 1)
            assertFalse(store.saveContextSummary(session.id, null, summary.copy(prefixDigest = "0".repeat(64))))
            assertTrue(store.saveContextSummary(session.id, null, summary))
            assertFalse(store.saveContextSummary(session.id, null, summary.copy(id = "stale")))
            assertEquals(history, store.history(session.id))
            store.append(ChatMessage("next", session.id, Role.USER, "new task"))
            assertEquals(summary, store.contextSummary(session.id))
            val branch = store.forkSession(session.id, BranchPoint("a", BranchMode.AFTER_REPLY))
            assertNull(store.contextSummary(branch.id))
        }
        SqliteChatStore(context).use { store ->
            assertEquals(summary, store.contextSummary(session.id))
            assertEquals(3, store.history(session.id).size)
            store.deleteSession(session.id)
            assertNull(store.contextSummary(session.id))
        }
    }

    @Test fun chunkedHistoryPreservesUnicodeAcrossByteBoundariesAndEmbeddedNuls() = runBlocking<Unit> {
        val text="a".repeat(16*1024-1)+"🚀中文\u0000"+"尾".repeat(30_000)
        val native=ProviderTranscript("anthropic","test",org.json.JSONArray().put(org.json.JSONObject().put("type","text").put("text",text)).toString())
        lateinit var session:ChatSession
        SqliteChatStore(context).use { store ->
            session=store.createSession()
            store.append(ChatMessage("a",session.id,Role.ASSISTANT,text,reasoning=text,providerTranscript=native))
        }
        SqliteChatStore(context).use { store ->
            val loaded=store.history(session.id).single()
            assertEquals(text,loaded.content);assertEquals(text,loaded.reasoning);assertEquals(native,loaded.providerTranscript)
        }
    }

    @Test fun nativeContentSurvivesReopeningAndBranchingWithoutChangingOpaqueData() = runBlocking<Unit> {
        val native = ProviderTranscript("anthropic", "test-model",
            """[{"type":"thinking","thinking":"reason","signature":"opaque+/=="},{"type":"text","text":"answer"}]""",
            mapOf("thinking_mode" to "enabled"), "a".repeat(64), """{"system":"fixture","tools":[],"messages":[],"canonicalHash":"fixture","runtimeContext":["saved data"]}""")
        lateinit var session: ChatSession
        SqliteChatStore(context).use { store ->
            session = store.createSession()
            store.append(ChatMessage("u",session.id,Role.USER,"question"))
            store.append(ChatMessage("a",session.id,Role.ASSISTANT,"answer",providerTranscript=native))
        }
        SqliteChatStore(context).use { store ->
            assertEquals(native,store.history(session.id).last().providerTranscript)
            val branch = store.forkSession(session.id,BranchPoint("a",BranchMode.AFTER_REPLY))
            assertEquals(native,store.history(branch.id).last().providerTranscript)
            assertNotEquals("a",store.history(branch.id).last().id)
            store.deleteSession(session.id)
            assertEquals(native,store.history(branch.id).last().providerTranscript)
        }
    }

    @Test fun migratesOldMessagesAndPersistsToolCallsAcrossReopening() = runBlocking<Unit> {
        val file = context.getDatabasePath("solvit.db")
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL)")
            db.execSQL("INSERT INTO sessions VALUES ('s','old chat',1)")
            db.execSQL("INSERT INTO messages (id,session_id,role,content,status) VALUES ('u','s','USER','hello','COMPLETE')")
            db.version = 1
        }
        val call = ToolCall("call", "clock", "{\"zone\":\"UTC\"}")
        val assistant = ChatMessage("a", "s", Role.ASSISTANT, "", toolCalls = listOf(call), reasoning = "Check the live clock")
        val result = ChatMessage("t", "s", Role.TOOL, "UTC", toolCallId = call.id, toolStage = ToolStage.SUCCEEDED)
        SqliteChatStore(context).use { store ->
            assertEquals("hello", store.history("s").single().content)
            assertTrue(store.history("s").single().toolCalls.isEmpty())
            assertEquals("", store.history("s").single().reasoning)
            store.appendBatch(listOf(assistant, result))
        }
        SqliteChatStore(context).use { store ->
            assertEquals(listOf(assistant, result), store.history("s").drop(1))
            store.deleteSession("s")
            assertTrue(store.history("s").isEmpty())
        }
    }

    @Test fun branchCopiesWholeToolTurnAndSurvivesDeletingOriginalAndReopening() = runBlocking<Unit> {
        lateinit var original: ChatSession
        lateinit var branch: ChatSession
        lateinit var source: List<ChatMessage>
        SqliteChatStore(context).use { store ->
            original = store.createSession()
            source = listOf(ChatMessage("u", original.id, Role.USER, "time"),
                ChatMessage("a", original.id, Role.ASSISTANT, "", toolCalls = listOf(ToolCall("call", "clock", "{}")), reasoning = "thought"),
                ChatMessage("t", original.id, Role.TOOL, "actual-time", toolCallId = "call", toolStage = ToolStage.SUCCEEDED),
                ChatMessage("final", original.id, Role.ASSISTANT, "answer"),
                ChatMessage("later", original.id, Role.USER, "later question"))
            store.appendBatch(source)
            branch = store.forkSession(original.id, BranchPoint("a", BranchMode.AFTER_REPLY))
            assertEquals(original.id, branch.originSessionId)
            assertEquals("a", branch.originMessageId)
            assertEquals(source, store.history(original.id))
            val copy = store.history(branch.id)
            assertEquals(4, copy.size)
            assertTrue(copy.map { it.id }.toSet().intersect(source.map { it.id }.toSet()).isEmpty())
            assertEquals(source.take(4).map { it.copy(id = "", sessionId = "") }, copy.map { it.copy(id = "", sessionId = "") })
            store.deleteSession(original.id)
        }
        SqliteChatStore(context).use { store ->
            assertEquals(branch, store.sessions().first().single())
            assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), store.history(branch.id).map { it.role })
        }
    }
    @Test fun invalidAnchorDoesNotLeaveEmptyBranchAndEditingFirstQuestionStartsEmpty() = runBlocking<Unit> {
        SqliteChatStore(context).use { store ->
            val original = store.createSession()
            store.append(ChatMessage("u", original.id, Role.USER, "question"))
            assertFailsWith<IllegalArgumentException> { store.forkSession(original.id, BranchPoint("missing", BranchMode.AFTER_REPLY)) }
            assertEquals(1, store.sessions().first().size)
            val branch = store.forkSession(original.id, BranchPoint("u", BranchMode.BEFORE_USER))
            assertTrue(store.history(branch.id).isEmpty())
            assertEquals("question", store.history(original.id).single().content)
        }
    }

    @Test fun failedBatchRollsBackEarlierMessages() = runBlocking<Unit> {
        SqliteChatStore(context).use { store ->
            val session = store.createSession()
            val message = ChatMessage("duplicate", session.id, Role.USER, "first")
            assertFailsWith<Exception> { store.appendBatch(listOf(message, message)) }
            assertTrue(store.history(session.id).isEmpty())
        }
    }
}
