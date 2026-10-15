package dev.solvit.linuxprobe

import android.content.Context
import dev.solvit.core.*
import dev.solvit.chat.ToolAgentRuntime
import dev.solvit.market.AndroidMarketplace
import dev.solvit.terminal.StdioProcessPlugin
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import java.io.File

/** Real QuickJS package -> host authority -> production Ubuntu -> Agent follow-up. */
internal suspend fun verifyLocalMcpAgent(context: Context, scope: CoroutineScope, linux: LinuxEnvironmentService) {
    val manager = PluginManager(scope = scope)
    val workspaces = AndroidWorkspaces(context, scope, linux = linux)
    val client = OkHttpClient()
    val pluginId = "dev.solvit.mcp"
    val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
    try {
        manager.load(ToolsPlugin()); manager.load(StdioProcessPlugin())
        manager.load(object : SolvitPlugin {
            override val id = "fixture.mcp.environment"
            override val provides = setOf(WorkspaceServiceKey, LinuxEnvironmentServiceKey)
            override suspend fun enable(context: PluginContext) {
                context.register(WorkspaceServiceKey, workspaces); context.register(LinuxEnvironmentServiceKey, linux)
            }
        })
        manager.enableAll()
        val tools = manager.registry.require(Services.Tools)
        val market = AndroidMarketplace(context, manager, client, tools, workspaces = workspaces,
            stdio = manager.registry.require(StdioProcessServiceKey), linux = linux)
        market.restore()
        val project = workspaces.create("MCP agent actual Ubuntu")
        val other = workspaces.create("MCP other chat")
        workspaces.bind("mcp-agent", project.id); workspaces.bind("mcp-other", other.id)
        val directory = File(workspaces.location(project.id))
        val python = """
            import os,sys,json
            from pathlib import Path
            Path('mcp-python.pid').write_text(str(os.getpid()))
            print('diagnostic-only-never-model',file=sys.stderr,flush=True)
            for line in sys.stdin:
                request=json.loads(line)
                if 'id' not in request: continue
                if request['method']=='initialize': result={'protocolVersion':'2025-11-25','capabilities':{'tools':{}},'serverInfo':{'name':'python-real'}}
                elif request['method']=='tools/list': result={'tools':[{'name':'write_fixture','inputSchema':{'type':'object','properties':{'text':{'type':'string'}},'required':['text']}}]}
                else:
                    text=request['params']['arguments']['text']
                    Path('agent-mcp-python.txt').write_text(text)
                    result={'content':[{'type':'text','text':text}]}
                print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}),flush=True)
        """.trimIndent()
        val node = """
            import readline from 'node:readline';import{writeFileSync}from'node:fs';
            writeFileSync('mcp-node.pid',String(process.pid));console.error('diagnostic-only-never-model');
            for await(const line of readline.createInterface({input:process.stdin,crlfDelay:Infinity})){
              const request=JSON.parse(line);if(request.id===undefined)continue;let result;
              if(request.method==='initialize')result={protocolVersion:'2025-11-25',capabilities:{tools:{}},serverInfo:{name:'node-real'}};
              else if(request.method==='tools/list')result={tools:[{name:'write_fixture',inputSchema:{type:'object',properties:{text:{type:'string'}},required:['text']}}]};
              else{const text=request.params.arguments.text;writeFileSync('agent-mcp-node.txt',text);result={content:[{type:'text',text}]}}
              process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:request.id,result})+'\n');
            }
        """.trimIndent()
        File(directory, "agent-server.py").writeText(python)
        File(directory, "agent-server.mjs").writeText(node)
        val profiles = buildJsonArray {
            for ((name, command, file) in listOf(Triple("python", "python3", "agent-server.py"), Triple("node", "node", "agent-server.mjs"))) add(buildJsonObject {
                put("name", name); put("type", "stdio"); put("command", command)
                put("args", buildJsonArray { add(file) }); put("env", buildJsonObject { put("PRIVATE_FIXTURE", "never-in-model") })
            })
        }.toString()
        market.configure(pluginId, mapOf("connections" to profiles))
        market.enable(pluginId, setOf("network", "workspace", "shell"))
        val role = AgentRole("mcp", "MCP tester", pluginIds = setOf(pluginId))
        for (server in listOf("python", "node")) {
            var step = 0
            val value = "$server actual QuickJS Agent 写入🌌"
            val provider = object : ModelProvider {
                override val id = "deterministic-mcp-provider"
                override fun stream(request: ModelRequest): Flow<ModelChunk> {
                    assertTrue(request.tools.any { it.name == "dev_solvit_mcp_mcp_call" })
                    val results = request.messages.filter { it.role == Role.TOOL }
                    assertFalse(results.any { "never-in-model" in it.content || "diagnostic-only-never-model" in it.content })
                    val call = when (step++) {
                        0 -> ToolCall("servers", "dev_solvit_mcp_mcp_servers", "{}")
                        1 -> {
                            assertTrue(results.last().content.contains(server))
                            ToolCall("discover", "dev_solvit_mcp_mcp_tools", buildJsonObject { put("server", server) }.toString())
                        }
                        2 -> {
                            assertTrue(results.last().content.contains("write_fixture"))
                            ToolCall("write", "dev_solvit_mcp_mcp_call", buildJsonObject {
                                put("server", server); put("name", "write_fixture"); put("arguments", buildJsonObject { put("text", value) })
                            }.toString())
                        }
                        else -> {
                            assertTrue(results.last().content.contains(value))
                            return flowOf(ModelChunk.Text("Verified $server"), ModelChunk.Done)
                        }
                    }
                    return flowOf(ModelChunk.ToolCalls(listOf(call)), ModelChunk.Done)
                }
            }
            val chunks = withTimeout(60_000) { ToolAgentRuntime(provider, tools).run(ModelRequest(ModelConfig(),
                listOf(ModelMessage(Role.USER, "Write and verify $server")), authorization = allow, agentRole = role,
                conversation = ConversationContext("mcp-agent", project.id))).toList() }
            assertTrue(chunks.filterIsInstance<ModelChunk.Text>().any { it.value == "Verified $server" })
            assertEquals(value, workspaces.read(project.id, "agent-mcp-$server.txt"))
            android.util.Log.i("SolvitLinuxProbe", "STDIO_MCP_AGENT|$server|QuickJS|host-authority|discovery|tool-call|follow-up|shared-file")
        }
        // A different chat cannot execute this workspace's configured script.
        val list = withContext(ConversationContext("mcp-other", other.id)) {
            tools.executeCall(ToolCall("other", "dev_solvit_mcp_mcp_servers", "{}"), allow, role)
        }
        assertTrue(list.contains("not_connected"))
        val pids = listOf("python", "node").map { File(directory, "mcp-$it.pid").readText().trim().toInt() }
        market.disable(pluginId)
        withTimeout(10_000) { while (pids.any { File("/proc/$it").exists() }) delay(20) }
        android.util.Log.i("SolvitLinuxProbe", "STDIO_MCP_AGENT|disable|both-processes-reaped|chat-isolation")
    } finally {
        manager.shutdown(); workspaces.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }
}
