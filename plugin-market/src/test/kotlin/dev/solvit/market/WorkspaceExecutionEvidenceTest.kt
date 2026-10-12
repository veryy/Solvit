package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.chat.DefaultChatService
import dev.solvit.chat.ToolAgentRuntime
import dev.solvit.storage.SqliteChatStore
import dev.solvit.system.ShellLauncher
import dev.solvit.system.ShellPaths
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

/** Scripted model decisions, actual packaged JS -> native files/Linux subprocess -> SQLite facts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WorkspaceExecutionEvidenceTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun clean() { context.deleteDatabase("solvit.db") }
    @Test fun createRunFailFixAndVerifyPreservesRealExitCodesAndObservationsAcrossReopen() = runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val manager=PluginManager(scope=scope);val client=OkHttpClient()
        val ws=AndroidWorkspaces(context,scope,ShellLauncher(ShellPaths("/bin/sh","/usr/bin/setsid")))
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
                    assertEquals(ToolStage.SUCCEEDED,message.toolStage,"Expected a successful tool return for $id")
                    return Json.parseToJsonElement(message.content).jsonObject
                }
                suspend fun completed(jobId: String) { withTimeout(10_000) { ws.jobs.first { jobs -> jobs.any { it.id==jobId && it.stage !in setOf(ShellStage.STARTING,ShellStage.RUNNING) } } } }
                val provider=object : ModelProvider {
                    override val id="fixture"
                    override fun stream(request: ModelRequest)=flow {
                        assertEquals(workspace.id,request.conversation!!.workspaceId)
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
                assertEquals(35,records.size)
                val failed=records.single { it.event.callId=="status-broken" && it.event.kind==ExecutionKind.TOOL_FINISHED }.event
                val verified=records.single { it.event.callId=="status-fixed" && it.event.kind==ExecutionKind.TOOL_FINISHED }.event
                assertEquals(7,Json.parseToJsonElement(failed.result!!).jsonObject["exitCode"]!!.jsonPrimitive.int)
                assertEquals(0,Json.parseToJsonElement(verified.result!!).jsonObject["exitCode"]!!.jsonPrimitive.int)
                val fixedWrite=records.single { it.event.callId=="write-fixed" && it.event.kind==ExecutionKind.TOOL_FINISHED }.event
                assertEquals("printf 'broken\\n'\nexit 7\n",Json.parseToJsonElement(fixedWrite.result!!).jsonObject["before"]!!.jsonObject["excerpt"]!!.jsonPrimitive.content)
                assertTrue(reopened.history(session.id).last().content.startsWith("Verified actual"))
                assertEquals(0,reopened.recoverInterruptedExecutions(System.currentTimeMillis()))
            }
        } finally {
            manager.shutdown();ws.close();scope.cancel();client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
        }
    }
}
