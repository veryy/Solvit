package dev.solvit.app

import dev.solvit.market.AndroidMarketplace
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import java.util.UUID

import dev.solvit.core.*
import dev.solvit.chat.DefaultChatService
import dev.solvit.chat.ToolAgentRuntime
import dev.solvit.storage.SqliteChatStore
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Scripted decisions, actual APK QuickJS -> Android shell/files -> durable SQLite -> reopen. */
@RunWith(AndroidJUnit4::class)
class WorkspaceExecutionDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var prefix: String
    private val preferences=mutableSetOf<String>()
    @Before fun isolatedStorage() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        prefix="workspace-device-"+UUID.randomUUID()
        directory=File(app.cacheDir,prefix).apply { check(mkdirs()) }
        context=object : ContextWrapper(app) {
            override fun getApplicationContext(): Context=this
            override fun getFilesDir(): File=File(directory,"files").apply { check(mkdirs() || isDirectory) }
            override fun getDatabasePath(name: String): File=File(File(directory,"databases").apply { check(mkdirs() || isDirectory) },name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,errorHandler)
            override fun deleteDatabase(name: String): Boolean=SQLiteDatabase.deleteDatabase(getDatabasePath(name))
            override fun getSharedPreferences(name: String,mode: Int): android.content.SharedPreferences {
                val scoped=prefix+"-"+name
                synchronized(preferences) { preferences+=scoped }
                return super.getSharedPreferences(scoped,mode)
            }
        }
    }
    @After fun removeIsolatedStorage() {
        if(::directory.isInitialized) directory.deleteRecursively()
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        synchronized(preferences) { preferences.forEach(app::deleteSharedPreferences);preferences.clear() }
    }
    @Test fun createRunFailFixAndVerifyPreservesRealExitCodesAndObservationsAcrossReopen() = runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val manager=PluginManager(scope=scope);val client=OkHttpClient()
        val ws=AndroidWorkspaces(context,scope)
        lateinit var session: ChatSession
        try {
            manager.load(ToolsPlugin(enforceSystem=true))
            manager.load(object : SolvitPlugin {
                override val id="fixture.native-workspace"
                override val provides=setOf(WorkspaceServiceKey,SystemExecutionServiceKey)
                override suspend fun enable(context: PluginContext) {
                    context.register(WorkspaceServiceKey,ws);context.register(SystemExecutionServiceKey,ws.execution)
                }
            })
            manager.enableAll()
            val market=AndroidMarketplace(context,manager,client,manager.registry.require(Services.Tools),workspaces=ws)
            market.restore();market.enable("dev.solvit.workspace",setOf("workspace","shell"))
            SqliteChatStore(context).use { store ->
                session=store.createSession();val workspace=ws.create("Execution evidence");ws.bind(session.id,workspace.id)
                val broken="printf 'broken\\n'\nexit 7\n";val fixed="printf 'verified\\n'\nexit 0\n"
                var step=0;var shellId="";var failedJob="";var verifiedJob=""
                fun args(vararg pairs: Pair<String,String>)=buildJsonObject { pairs.forEach { put(it.first,it.second) } }.toString()
                fun call(id: String,name: String,arguments: String)=ToolCall(id,"dev_solvit_workspace_$name",arguments)
                fun result(request: ModelRequest,id: String): JsonObject {
                    val message=request.messages.last { it.role==Role.TOOL && it.toolCallId==id }
                    assertEquals("Expected a successful tool return for $id",ToolStage.SUCCEEDED,message.toolStage)
                    return Json.parseToJsonElement(message.content).jsonObject
                }
                suspend fun completed(jobId: String) { withTimeout(10_000) { ws.jobs.first { jobs -> jobs.any { it.id==jobId && it.stage !in setOf(ShellStage.STARTING,ShellStage.RUNNING) } } } }
                val provider=object : ModelProvider {
                    override val id="fixture"
                    override fun stream(request: ModelRequest)=flow {
                        assertEquals(workspace.id,request.conversation!!.workspaceId)
                        assertTrue(request.messages.single { it.role==Role.SYSTEM }.content.contains("需要查询该任务直到结束"))
                        val proposed=when(step++) {
                            0->listOf(call("write-broken","write_file",args("path" to "main.sh","text" to broken)),call("create-shell","create_shell",args("name" to "Verify")))
                            1->{
                                val write=result(request,"write-broken")
                                assertTrue(write["verified"]!!.jsonPrimitive.boolean)
                                assertEquals("ABSENT",write["before"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
                                assertEquals(broken,write["after"]!!.jsonObject["excerpt"]!!.jsonPrimitive.content)
                                assertEquals(broken,ws.read(workspace.id,"main.sh"));shellId=result(request,"create-shell")["id"]!!.jsonPrimitive.content
                                listOf(call("start-broken","shell_start",args("shellId" to shellId,"command" to "sh main.sh")))
                            }
                            2->{
                                failedJob=result(request,"start-broken")["id"]!!.jsonPrimitive.content;completed(failedJob)
                                listOf(call("status-broken","shell_status",args("id" to failedJob)))
                            }
                            3->{
                                val failed=result(request,"status-broken");assertEquals(7,failed["exitCode"]!!.jsonPrimitive.int)
                                assertTrue(failed["output"]!!.jsonPrimitive.content.contains("broken"))
                                assertEquals(ws.location(workspace.id),failed["cwd"]!!.jsonPrimitive.content)
                                assertEquals(workspace.id,failed["workspaceId"]!!.jsonPrimitive.content)
                                listOf(call("write-fixed","write_file",args("path" to "main.sh","text" to fixed)))
                            }
                            4->{
                                val write=result(request,"write-fixed")
                                assertTrue(write["verified"]!!.jsonPrimitive.boolean)
                                assertTrue(write["changed"]!!.jsonPrimitive.boolean)
                                assertEquals(broken,write["before"]!!.jsonObject["excerpt"]!!.jsonPrimitive.content)
                                assertEquals(fixed,write["after"]!!.jsonObject["excerpt"]!!.jsonPrimitive.content)
                                assertEquals(fixed,ws.read(workspace.id,"main.sh"));listOf(call("start-fixed","shell_start",args("shellId" to shellId,"command" to "sh main.sh")))
                            }
                            5->{ verifiedJob=result(request,"start-fixed")["id"]!!.jsonPrimitive.content;completed(verifiedJob);listOf(call("status-fixed","shell_status",args("id" to verifiedJob))) }
                            6->{
                                val verified=result(request,"status-fixed");assertEquals(0,verified["exitCode"]!!.jsonPrimitive.int)
                                assertTrue(verified["output"]!!.jsonPrimitive.content.contains("verified"))
                                assertEquals(fixed,ws.read(workspace.id,"main.sh"));emit(ModelChunk.Text("Verified actual exit code 0 and updated main.sh"));emptyList()
                            }
                            else->error("Unexpected extra model request")
                        }
                        if(proposed.isNotEmpty()) emit(ModelChunk.ToolCalls(proposed))
                        emit(ModelChunk.Done)
                    }
                }
                val approvals=mutableListOf<ToolCall>()
                val authorization=object : ToolAuthorization {
                    override suspend fun authorize(descriptor: ToolDescriptor)=false
                    override suspend fun authorizeCall(descriptor: ToolDescriptor,call: ToolCall): Boolean {
                        assertEquals(ExecutionMode.APP,descriptor.executionMode)
                        val run=store.executionRuns(session.id).first().single()
                        assertTrue(store.executionEvents(session.id,run.id).any { it.event.call?.id==call.id && it.event.kind==ExecutionKind.TOOL_PROPOSED })
                        approvals+=call;return true
                    }
                }
                val chat=DefaultChatService(store,ToolAgentRuntime(provider,manager.registry.require(Services.Tools)),EventBus(),workspaces=ws)
                chat.send(session.id,"Create main.sh, run it, repair failures and verify the actual result",ModelConfig(),authorization).toList()
                assertEquals(7,step);assertEquals(7,approvals.size);assertNotEquals(failedJob,verifiedJob)
                assertEquals(7,ws.jobs.value.single { it.id==failedJob }.exitCode)
                assertEquals(0,ws.jobs.value.single { it.id==verifiedJob }.exitCode)
                assertEquals(ExecutionStatus.COMPLETE,store.executionRuns(session.id).first().single().status)
            }
            SqliteChatStore(context).use { reopened ->
                val run=reopened.executionRuns(session.id).first().single();val records=reopened.executionEvents(session.id,run.id)
                assertEquals(35,records.count { it.event.workspace==null })
                val baseline=records.single { it.event.kind==ExecutionKind.WORKSPACE_BASELINE }.event.workspace!!
                val observed=records.filter { it.event.kind==ExecutionKind.WORKSPACE_OBSERVED }
                assertEquals(7,observed.size)
                val comparison=compareWorkspaceSnapshots(baseline,observed.last().event.workspace!!)
                assertTrue(comparison.complete)
                assertEquals(WorkspaceChangeKind.CREATED,comparison.changes.single { it.path=="main.sh" }.kind)
                assertTrue(comparison.changes.single { it.path=="main.sh" }.after.excerpt!!.contains("verified"))
                val failed=records.single { it.event.callId=="status-broken" && it.event.kind==ExecutionKind.TOOL_FINISHED }.event
                val verified=records.single { it.event.callId=="status-fixed" && it.event.kind==ExecutionKind.TOOL_FINISHED }.event
                assertEquals(7,Json.parseToJsonElement(failed.result!!).jsonObject["exitCode"]!!.jsonPrimitive.int)
                assertEquals(0,Json.parseToJsonElement(verified.result!!).jsonObject["exitCode"]!!.jsonPrimitive.int)
                val fixedWrite=records.single { it.event.callId=="write-fixed" && it.event.kind==ExecutionKind.TOOL_FINISHED }.event
                assertEquals("printf 'broken\\n'\nexit 7\n",Json.parseToJsonElement(fixedWrite.result!!).jsonObject["before"]!!.jsonObject["excerpt"]!!.jsonPrimitive.content)
                assertTrue(reopened.history(session.id).last().content.startsWith("Verified actual"))
                assertEquals(0,reopened.recoverInterruptedExecutions(System.currentTimeMillis()))
                val rows=attachExecutions(presentChat(reopened.history(session.id)),reopened.executionRuns(session.id).first())
                rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
                    ChatTimeline(rows,session.id,loadExecutionEvents={ actual -> reopened.executionEvents(actual.sessionId,actual.id) })
                } } }
                rule.onAllNodesWithTag("assistant_message").assertCountEquals(1)
                rule.onNodeWithText("Verified actual exit code 0 and updated main.sh").assertExists()
                rule.onNodeWithTag("execution_journal_details").assertDoesNotExist()
                rule.onNodeWithTag("execution_journal_toggle").performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithTag("workspace_changes_toggle").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("workspace_changes_toggle").performClick()
                rule.onNodeWithTag("workspace_change_main.sh").performClick()
                rule.onNodeWithText(fixedPreview(),substring=true).assertExists()
                rule.onNodeWithText("仍有操作未记录后续观察，文件当前状态需重新检查").assertDoesNotExist()
                rule.onNodeWithTag("execution_journal_toggle").performClick()
            }
        } finally {
            manager.shutdown();ws.close();scope.cancel();client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
        }
    }
    private fun fixedPreview()="printf 'verified\\n'\nexit 0\n"
}
