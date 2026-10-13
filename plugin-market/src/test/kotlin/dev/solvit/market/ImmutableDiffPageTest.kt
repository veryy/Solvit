package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.chat.DefaultChatService
import dev.solvit.plugins.ScriptPageView
import dev.solvit.storage.SqliteChatStore
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ImmutableDiffPageTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private suspend fun saved(before: String, after: String): ExecutionRun {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val workspace=AndroidWorkspaces(context,scope)
        try { SqliteChatStore(context).use { store ->
            val session=store.createSession();val user="user-${session.id}"
            store.append(ChatMessage(user,session.id,Role.USER,"Edit this code"))
            val directory=workspace.create("Immutable test")
            workspace.write(directory.id,"code.txt",before);val baseline=workspace.snapshot(directory.id)
            workspace.write(directory.id,"code.txt",after);val observed=workspace.snapshot(directory.id)
            val run=ExecutionRun("run-${session.id}",session.id,user,null,directory.id,1)
            assertTrue(store.beginExecutionRun(run))
            assertTrue(store.appendExecutionEvents(run.id,0,listOf(ExecutionEvent(1,ExecutionKind.STEP_STARTED),
                ExecutionEvent(1,ExecutionKind.TOOL_PROPOSED,ToolCall("write","fixture","{}"),"files"),
                ExecutionEvent(1,ExecutionKind.WORKSPACE_BASELINE,workspace=baseline),
                ExecutionEvent(1,ExecutionKind.TOOL_STARTED,callId="write"),
                ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="write",stage=ToolStage.SUCCEEDED,result="saved"),
                ExecutionEvent(1,ExecutionKind.WORKSPACE_OBSERVED,workspace=observed),ExecutionEvent(1,ExecutionKind.STEP_FINISHED)),2))
            assertTrue(store.finishExecutionRun(run.id,ExecutionStatus.COMPLETE,3))
            workspace.write(directory.id,"code.txt","LATER-OVERWRITE")
            return run
        } } finally { workspace.close();scope.cancel() }
    }
    private fun service(store: SqliteChatStore)=DefaultChatService(store,object : AgentRuntime {
        override fun run(request: ModelRequest): Flow<ModelChunk> = error("Review must not run a model or tool")
    },EventBus())
    @Test fun packagedQuickJsReadsNativeCapturesAfterSqliteReopenAndFindsTailEditWithoutLiveFiles()=runBlocking<Unit> {
        val before=(0..1499).joinToString("\n") { "unchanged $it" }+"\n<script>literal</script>\nOLD-TAIL\n"
        val after=before.replace("OLD-TAIL","NEW-TAIL🙂")
        val run=saved(before,after)
        SqliteChatStore(context).use { reopened ->
            val records=reopened.executionEvents(run.sessionId,run.id)
            assertFalse(records[2].event.workspace!!.files["code.txt"]!!.excerpt!!.contains("OLD-TAIL"))
            assertEquals(after,reopened.executionFileContent(run.sessionId,run.id,5,"code.txt"))
            val manager=BuiltinWorkspaceManager(context)
            val page=manager.openPage("zh",ExecutionChangesPageBridge(service(reopened),run),pageId="changes")
            try {
                var snapshot=page.open()
                assertEquals("review/index.html",page.document()!!.entry)
                assertTrue(page.resource("review/jsdiff-LICENSE.txt").content.contains("BSD 3-Clause License"))
                snapshot=page.dispatch(snapshot.revision,"change_0",null,emptyMap(),false)
                val rows=mutableListOf<JsonObject>()
                var pages=0
                while(true) {
                    check(++pages<=1000) { "Diff pagination did not terminate" }
                    val view=ScriptPageView.parse(snapshot.json)
                    rows+=view.nodes.filter { it["id"]!!.jsonPrimitive.content.startsWith("diffRow_") }.map { Json.parseToJsonElement(it["text"]!!.jsonPrimitive.content).jsonObject }
                    if(view.node("next")==null)break
                    snapshot=page.dispatch(snapshot.revision,"next",null,emptyMap(),false)
                }
                assertTrue(rows.any { it["operation"]==JsonPrimitive("removed") && it["text"]==JsonPrimitive("OLD-TAIL") })
                assertTrue(rows.any { it["operation"]==JsonPrimitive("added") && it["text"]==JsonPrimitive("NEW-TAIL🙂") })
                assertFalse(rows.any { it["text"]==JsonPrimitive("LATER-OVERWRITE") })
            } finally { page.close() }
            assertTrue(page.closed.value)
            assertFails { page.resource("review/main.js") }
        }
    }
    @Test fun cachedLongLinePagesAndResourcesStillRejectChangedChatAndGenericManagementCannotOpenExecutionPage()=runBlocking<Unit> {
        val run=saved("before","🙂".repeat(6000));var active=run.sessionId
        SqliteChatStore(context).use { reopened ->
            val manager=BuiltinWorkspaceManager(context)
            assertFails { manager.openPage("en",pageId="changes") }
            val page=manager.openPage("en",ExecutionChangesPageBridge(service(reopened),run) { active },pageId="changes")
            try {
                val initial=page.open()
                val opened=page.dispatch(initial.revision,"change_0",null,emptyMap(),false)
                assertNotNull(ScriptPageView.parse(opened.json).node("next"))
                assertFails { page.dispatch(initial.revision,"change_0",null,emptyMap(),false) }
                active="foreign-chat"
                assertFails { page.dispatch(opened.revision,"next",null,emptyMap(),false) }
                assertFails { page.resource("review/main.js") }
                assertFails { page.document() }
            } finally { page.close() }
        }
    }
}
