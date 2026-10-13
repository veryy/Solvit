package dev.solvit.plugins

import dev.solvit.chat.ToolAgentRuntime
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AgentObservationIntegrationTest {
    @Test fun boundedSnapshotStillSettlesEveryNativeToolCall() = runBlocking {
        val source = """globalThis.solvitPlugin={tools:{},agent:async(input,ctx)=>{
          const count=await ctx.model(0),facts=await ctx.observe();
          if(count!==20||facts.tools.length!==16||facts.toolsTruncated!==true)throw new Error('Unbounded snapshot');
          for(let i=0;i<count;i++)await ctx.decline(i);
          await ctx.settle(0);await ctx.model(1);await ctx.finish(1);
        }};"""
        var round = 0
        val provider = object : ModelProvider {
            override val id = "test"
            override fun stream(request: ModelRequest): Flow<ModelChunk> {
                if (round++ == 0) return flowOf(ModelChunk.ToolCalls((0 until 20).map {
                    ToolCall("call-$it", "probe", "{}")
                }), ModelChunk.Done)
                val results = request.messages.filter { it.role == Role.TOOL }
                assertEquals((0 until 20).map { "call-$it" }, results.map { it.toolCallId })
                assertTrue(results.all { it.toolStage == ToolStage.DENIED })
                return flowOf(ModelChunk.Text("done"), ModelChunk.Done)
            }
        }
        val tools = ToolRegistry(EventBus()).apply { register(object : SolvitTool {
            override val descriptor = ToolDescriptor("probe", "Probe", "{}")
            override suspend fun execute(argumentsJson: String): String = error("Declined tool executed")
        }) }
        val chunks = ToolAgentRuntime(provider, tools, maxCalls = 32, driver = scriptPluginAgent(pkg(source)))
            .run(ModelRequest(ModelConfig(apiKey = "test"), listOf(ModelMessage(Role.USER, "review"))))
            .toList()
        assertEquals("done", chunks.filterIsInstance<ModelChunk.Text>().last().value)
    }
    private fun pkg(source: String, read: Boolean = true, api: Int = if (read) 15 else 14): ScriptPackage {
        val manifest = """{"id":"test.observer","name":"Observer","description":"Test","version":"1.0.0",
          "apiVersion":$api,"permissions":${if (read) "[\"agent\",\"agent.read\"]" else "[\"agent\"]"},"dependencies":[],"tools":[],
          "agentEnabledSetting":"selected","settings":[{"key":"selected","kind":"boolean","label":"Select","defaultValue":"false"}]}"""
        return ScriptPackage.parse(buildJsonObject { put("manifest", Json.parseToJsonElement(manifest)); put("source", source) }.toString().toByteArray())
    }
    @Test fun oldOrUngrantedPackagesHaveNeitherObservationNorDeclineBridge() = runBlocking {
        assertFailsWith<IllegalArgumentException> { pkg("globalThis.solvitPlugin={}", api = 14) }
        val source = """globalThis.solvitPlugin={tools:{},agent:async(input,ctx)=>{
          if(typeof ctx.observe!=='undefined'||typeof ctx.decline!=='undefined'||typeof __solvitAgentObserve!=='undefined')throw new Error('Leaked bridge');
          let denied=false;try{await __solvitAgentCommand('decline',0);}catch(e){denied=true;}
          if(!denied)throw new Error('Leaked command');await ctx.model(0);await ctx.finish(0);
        }};"""
        val commands = mutableListOf<String>()
        for (api in listOf(14, 15)) scriptPluginAgent(pkg(source, read = false, api = api)).run(8,
            AgentStepHost { op, _ -> commands += op; 0 })
        assertEquals(listOf("model", "finish", "model", "finish"), commands)
    }
    @Test fun actualQuickJsReadsDetachedFactsDeclinesUnwantedToolAndPreservesNativeTranscript() = runBlocking {
        val source = """globalThis.solvitPlugin={tools:{},agent:async({maxRounds},ctx)=>{
          const initial=await ctx.observe();
          if(initial.phase!=='READY'||initial.userText.text!=='only approved operation'||initial.roleName.text!=='Researcher')throw new Error('Missing input');
          if(JSON.stringify(initial).includes('credential-secret')||JSON.stringify(initial).includes('opaque-signature'))throw new Error('Native data leaked');
          await ctx.model(0);
          const before=await ctx.observe();
          if(before.assistantText.text!=='proposal'||before.reasoning.text!=='reasoning'||before.tools.length!==2)throw new Error('Missing model facts');
          before.assistantText.text='forged';before.tools[0].name='forged';
          if((await ctx.observe()).assistantText.text!=='proposal')throw new Error('Mutable native state');
          for(const call of (await ctx.observe()).tools){
            if(JSON.parse(call.arguments.text).skip)await ctx.decline(call.index);else await ctx.tool(call.index);
          }
          const after=await ctx.observe();
          if(after.tools[0].stage!=='DENIED'||after.tools[1].stage!=='SUCCEEDED'||after.tools[1].result.text!=='actual-success')throw new Error('Wrong settlement');
          await ctx.settle(0);
          if((await ctx.observe()).nextModelRound!==1)throw new Error('Wrong next round');
          await ctx.model(1);await ctx.finish(1);
        }};"""
        val native = ProviderTranscript("test", "opaque-signature", "[{\"signature\":\"opaque-signature\"}]")
        var round = 0; var effects = 0; var approvals = 0
        val provider = object : ModelProvider {
            override val id = "test"
            override fun stream(request: ModelRequest): Flow<ModelChunk> {
                if (round++ == 0) return flowOf(ModelChunk.Text("proposal"), ModelChunk.Reasoning("reasoning"),
                    ModelChunk.NativeTranscript(native), ModelChunk.ToolCalls(listOf(ToolCall("deny", "probe", "{\"skip\":true}"),
                        ToolCall("allow", "probe", "{\"skip\":false}"))), ModelChunk.Done)
                assertSame(native, request.messages.last { it.role == Role.ASSISTANT }.providerTranscript)
                val results = request.messages.filter { it.role == Role.TOOL }
                assertEquals(listOf("deny", "allow"), results.map { it.toolCallId })
                assertEquals(ToolStage.DENIED, results[0].toolStage)
                assertEquals("actual-success", results[1].content)
                return flowOf(ModelChunk.Text("done"), ModelChunk.Done)
            }
        }
        val tools = ToolRegistry(EventBus()).apply { register(object : SolvitTool {
            override val descriptor = ToolDescriptor("probe", "Probe", "{}")
            override suspend fun execute(argumentsJson: String): String { effects++; return "actual-success" }
        }) }
        val events = mutableListOf<ExecutionEvent>()
        val journal = object : ExecutionJournal {
            override suspend fun append(events_: List<ExecutionEvent>) { events += events_ }
        }
        val chunks = ToolAgentRuntime(provider, tools, driver = scriptPluginAgent(pkg(source))).run(ModelRequest(
            ModelConfig(apiKey = "credential-secret"), listOf(ModelMessage(Role.USER, "only approved operation")),
            agentRole = AgentRole("research", "Researcher", pluginIds = setOf("builtin.tools")),
            authorization = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor): Boolean { approvals++; return true } },
            executionJournal = journal)).toList()
        assertEquals(1, effects); assertEquals(1, approvals)
        assertFalse(events.any { it.callId == "deny" && it.kind == ExecutionKind.TOOL_STARTED })
        assertEquals(ToolStage.DENIED, events.single { it.callId == "deny" && it.kind == ExecutionKind.TOOL_FINISHED }.stage)
        assertEquals("done", chunks.filterIsInstance<ModelChunk.Text>().last().value)
    }
}
