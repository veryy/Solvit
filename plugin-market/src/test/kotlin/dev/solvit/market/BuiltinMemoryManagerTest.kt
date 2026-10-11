package dev.solvit.market

import android.content.Context
import dev.solvit.core.*
import dev.solvit.storage.SqliteChatStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BuiltinMemoryManagerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() {
        context.deleteDatabase("solvit.db")
        context.getSharedPreferences("extension_data.dev.solvit.memory", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("extension_permissions", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun humanManagementWorksWithoutEnablingPluginOrGrantingModelAndSurvivesRestart() = runBlocking<Unit> {
        val manager = BuiltinMemoryManager(context)
        assertTrue(manager.snapshot().getValue("facts").jsonArray.isEmpty())
        val created = manager.save("Prefer Kotlin", "preference", listOf("coding"))
        val id = created.getValue("fact").jsonObject.getValue("id").jsonPrimitive.content
        val reopened = BuiltinMemoryManager(context)
        assertEquals("Prefer Kotlin", reopened.snapshot().getValue("facts").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        reopened.save("Prefer TypeScript", "preference", listOf("plugins"), id)
        assertEquals(id, manager.snapshot().getValue("facts").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        reopened.delete(id)
        assertTrue(manager.snapshot().getValue("facts").jsonArray.isEmpty())
        assertFailsWith<Exception> { reopened.save("Do not recreate", "fact", emptyList(), id) }
        assertTrue(context.getSharedPreferences("extension_permissions", Context.MODE_PRIVATE).all.isEmpty())
    }
    @Test fun editingAutomaticFactPreservesOriginAndDeletionKeepsReplayProtection() = runBlocking<Unit> {
        val manager = BuiltinMemoryManager(context)
        val created = manager.save("Prefer Kotlin", "preference", emptyList())
        val fact = created.getValue("fact").jsonObject
        val id = fact.getValue("id").jsonPrimitive.content
        val source = buildJsonObject {
            put("turnId", "answer"); put("sessionId", "session"); put("userMessageId", "user")
            put("assistantMessageId", "answer"); put("roleId", "coder"); put("roleName", "Coder")
        }
        val provenance = JsonObject(source + ("quote" to JsonPrimitive("Prefer Kotlin")))
        val receipt = JsonObject(source + ("factIds" to JsonArray(listOf(JsonPrimitive(id)))))
        val snapshot = manager.snapshot()
        val seeded = JsonObject(snapshot + mapOf(
            "facts" to JsonArray(listOf(JsonObject(fact + ("source" to provenance)))),
            "completedSources" to JsonArray(listOf(receipt))))
        PluginDataStore(context).set("dev.solvit.memory", "memory.v1", seeded, true) {}
        manager.save("Prefer TypeScript", "preference", emptyList(), id)
        val edited = manager.snapshot().getValue("facts").jsonArray.single().jsonObject
        assertEquals(provenance, edited["source"])
        assertEquals(JsonPrimitive(true), edited["modifiedByUser"])
        manager.delete(id)
        val deleted = manager.snapshot()
        assertTrue(deleted.getValue("facts").jsonArray.isEmpty())
        assertEquals(JsonArray(listOf(receipt)), deleted["completedSources"])
        assertEquals(0, manager.pruneRetiredSources { false })
        assertEquals(deleted, manager.snapshot())
        var checks = 0
        assertFailsWith<Exception> { manager.pruneRetiredSources { ++checks == 1 } }
        assertTrue(checks >= 2)
        assertEquals(deleted, manager.snapshot())
        assertEquals(1, manager.pruneRetiredSources { true })
        assertEquals(0, manager.snapshot().getValue("completedSources").jsonArray.size)
    }

    @Test fun retiredSqliteJobsReleaseReceiptsButFailedTasksKeepReplayProtection() = runBlocking<Unit> {
        SqliteChatStore(context).use { store ->
            val session = store.createSession()
            val target = PostTurnTarget("dev.solvit.memory", "1.2.0", "a".repeat(64))
            val receipts = (0..1).map { index ->
                val user = "user-$index"; val answer = "answer-$index"
                store.append(ChatMessage(user, session.id, Role.USER, "Preference"))
                val turn = CompletedChatTurn(answer, session.id, user, answer, null, null, null, null,
                    "fixture", "fixture", 1, listOf(target))
                store.appendCompletedTurn(listOf(ChatMessage(answer, session.id, Role.ASSISTANT, "Saved")), turn)
                val jobId = "$answer:${target.pluginId}"
                assertTrue(store.claimPostTurnJob(jobId, 0, 2))
                assertTrue(store.finishPostTurnJob(jobId, 1,
                    if (index == 0) PostTurnJobStatus.SUCCEEDED else PostTurnJobStatus.FAILED,
                    if (index == 0) null else PostTurnFailure.STORAGE, 3))
                buildJsonObject {
                    put("turnId", answer); put("sessionId", session.id); put("userMessageId", user)
                    put("assistantMessageId", answer); put("roleId", JsonNull); put("roleName", JsonNull)
                    put("factIds", JsonArray(emptyList()))
                }
            }
            val database = buildJsonObject {
                put("format", 1); put("sequence", 0); put("facts", JsonArray(emptyList()))
                put("edges", JsonArray(emptyList())); put("chunks", JsonArray(emptyList()))
                put("profile", buildJsonObject {}); put("completedSources", JsonArray(receipts))
            }
            PluginDataStore(context).set(target.pluginId, "memory.v1", database, true) {}
            val manager = BuiltinMemoryManager(context)
            assertEquals(0, manager.pruneRetiredSources { store.postTurnJob("$it:${target.pluginId}") == null })
            assertEquals(1, store.deleteFinishedPostTurnJobs(target.pluginId))
            assertEquals(1, manager.pruneRetiredSources { store.postTurnJob("$it:${target.pluginId}") == null })
            assertEquals(JsonArray(listOf(receipts[1])), manager.snapshot()["completedSources"])
            assertEquals(4, store.history(session.id).size)
            assertEquals(2, store.recordedTurns(0).size)
            assertTrue(store.retryPostTurnJob("answer-1:${target.pluginId}", 1, 4))
            assertEquals(0, manager.pruneRetiredSources { store.postTurnJob("$it:${target.pluginId}") == null })
        }
    }

    @Test fun invalidStoredDataIsPreservedInsteadOfOverwritten() = runBlocking<Unit> {
        val prefs = context.getSharedPreferences("extension_data.dev.solvit.memory", Context.MODE_PRIVATE)
        prefs.edit().putString("memory.v1", "{\"format\":99}").commit()
        assertFailsWith<Exception> { BuiltinMemoryManager(context).save("A fact", "fact", emptyList()) }
        assertEquals("{\"format\":99}", prefs.getString("memory.v1", null))
    }
}
