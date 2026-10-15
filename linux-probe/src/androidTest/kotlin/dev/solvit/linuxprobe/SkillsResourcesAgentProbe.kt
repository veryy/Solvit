package dev.solvit.linuxprobe

import android.content.Context
import dev.solvit.core.*
import dev.solvit.chat.ToolAgentRuntime
import dev.solvit.market.AndroidMarketplace
import dev.solvit.market.NativeSkillLibrary
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import java.io.File

/** Deterministic provider; real QuickJS, authority, resource publication and Ubuntu processes. */
internal suspend fun verifySkillsResourcesAgent(context: Context, scope: CoroutineScope, linux: LinuxEnvironmentService) {
    val manager = PluginManager(scope = scope)
    val workspaces = AndroidWorkspaces(context, scope, linux = linux)
    val client = OkHttpClient()
    val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
    try {
        manager.load(ToolsPlugin()); manager.load(ContextHooksPlugin())
        manager.load(object : SolvitPlugin {
            override val id = "fixture.skills.environment"
            override val provides = setOf(WorkspaceServiceKey, LinuxEnvironmentServiceKey)
            override suspend fun enable(context: PluginContext) {
                context.register(WorkspaceServiceKey, workspaces); context.register(LinuxEnvironmentServiceKey, linux)
            }
        })
        manager.enableAll()
        val tools = manager.registry.require(Services.Tools)
        val market = AndroidMarketplace(context, manager, client, tools, workspaces = workspaces, linux = linux)
        market.restore()
        val project = workspaces.create("Skills actual Ubuntu")
        workspaces.bind("skills-agent", project.id)
        workspaces.createShell(project.id, "Skills Ubuntu", ShellRuntime.UBUNTU)
        val directory = File(workspaces.location(project.id))
        market.enable("dev.solvit.skills", setOf("storage", "workspace"))
        market.enable("dev.solvit.workspace", setOf("workspace", "shell"))
        val library = NativeSkillLibrary(market)
        val literal = "中文 🌌 ; $(not-a-command) ' quoted"
        for (runtime in listOf("python", "node")) {
            val skill = "resource-$runtime"
            val extension = if (runtime == "python") "py" else "mjs"
            val script = if (runtime == "python") """
                import os,sys
                from pathlib import Path
                reference=(Path(__file__).resolve().parent.parent/'references/input.txt').read_text()
                value=Path('input-python.txt').read_text()
                result=reference+'|'+value+'|'+sys.argv[1]+'|'+os.getcwd()
                Path('output-python.txt').write_text(result)
                print(result)
            """.trimIndent() else """
                import {readFileSync,writeFileSync} from 'node:fs';
                const reference=readFileSync(new URL('../references/input.txt',import.meta.url),'utf8');
                const value=readFileSync('input-node.txt','utf8');
                const result=[reference,value,process.argv[2],process.cwd()].join('|');
                writeFileSync('output-node.txt',result);console.log(result);
            """.trimIndent()
            library.importSkill(SkillBundle(null, mapOf(
                "SKILL.md" to "---\nname: $skill\ndescription: Verify $runtime resource execution\n---\nLoad scripts/main.$extension relative to the prepared resourceBase. Read missing project input from the error, repair it and rerun.",
                "scripts/main.$extension" to script, "references/input.txt" to "reference-$runtime"
            )), false)
            assertFalse("Import must not execute a script", File(directory, "output-$runtime.txt").exists())
            val role = AgentRole("skills-$runtime", "Skills tester", pluginIds = setOf("dev.solvit.skills", "dev.solvit.workspace"), skillIds = setOf(skill))
            // Minimal native/QuickJS reproduction before the Agent's deliberately sanitized errors.
            // Fixture-only inputs contain no account configuration or credentials.
            tools.executeCall(ToolCall("direct-resource-$runtime", "dev_solvit_skills_skills_prepare",
                buildJsonObject { put("name", skill) }.toString()), allow, role,
                conversation = ConversationContext("skills-agent", project.id))
            var phase = 0
            var polls = 0
            var resourcePath = ""
            var shellId = ""
            var jobId = ""
            var command = ""
            var observedFailure = false
            val provider = object : ModelProvider {
                override val id = "deterministic-skills-provider"
                override fun stream(request: ModelRequest): Flow<ModelChunk> = flow {
                    // Real processes run asynchronously; do not busy-poll or mock their completion.
                    delay(125)
                    assertTrue(request.tools.any { it.name == "dev_solvit_skills_skills_prepare" })
                    val last = request.messages.lastOrNull { it.role == Role.TOOL }?.content
                    fun args(vararg fields: Pair<String, String>) = buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }.toString()
                    fun call(name: String, body: String = "{}") = ToolCall("skills-${phase}-${polls++}", name, body)
                    val next = when (phase) {
                        0 -> { phase++; call("dev_solvit_skills_skills_load", args("name" to skill)) }
                        1 -> {
                            assertTrue(last.orEmpty().contains("opaque")); phase++
                            call("dev_solvit_skills_skills_prepare", args("name" to skill))
                        }
                        2 -> {
                            val prepared = Json.parseToJsonElement(checkNotNull(last)).jsonObject
                            resourcePath = prepared.getValue("workspacePath").jsonPrimitive.content
                            assertEquals("/workspace/$resourcePath", prepared.getValue("resourceBase").jsonObject.getValue("path").jsonPrimitive.content)
                            assertEquals(script, File(directory, "$resourcePath/scripts/main.$extension").readText())
                            assertFalse("Prepare must not execute", File(directory, "output-$runtime.txt").exists())
                            phase++; call("dev_solvit_workspace_info")
                        }
                        3 -> {
                            val info = Json.parseToJsonElement(checkNotNull(last)).jsonObject
                            shellId = info.getValue("shells").jsonArray.first { it.jsonObject.getValue("runtime").jsonPrimitive.content == "UBUNTU" }.jsonObject.getValue("id").jsonPrimitive.content
                            fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
                            command = (if (runtime == "python") "python3" else "node") + " " + quote("/workspace/$resourcePath/scripts/main.$extension") + " " + quote(literal)
                            phase++; call("dev_solvit_workspace_shell_start", args("shellId" to shellId, "command" to command))
                        }
                        4, 8 -> {
                            jobId = Json.parseToJsonElement(checkNotNull(last)).jsonObject.getValue("id").jsonPrimitive.content
                            phase++; call("dev_solvit_workspace_shell_status", args("id" to jobId))
                        }
                        5, 9 -> {
                            val status = Json.parseToJsonElement(checkNotNull(last)).jsonObject
                            val exit = status.getValue("exitCode").jsonPrimitive.intOrNull
                            if (exit == null) call("dev_solvit_workspace_shell_status", args("id" to jobId))
                            else if (phase == 5) {
                                assertNotEquals(0, exit)
                                assertTrue(status.getValue("output").jsonPrimitive.content.contains("input-$runtime.txt"))
                                observedFailure = true; phase++
                                call("dev_solvit_workspace_write_file", args("path" to "input-$runtime.txt", "text" to "repaired-$runtime"))
                            } else {
                                assertEquals(status.toString(), 0, exit)
                                assertTrue(status.getValue("output").jsonPrimitive.content.contains(literal))
                                phase++; call("dev_solvit_workspace_read_file", args("path" to "output-$runtime.txt"))
                            }
                        }
                        6 -> {
                            assertTrue(Json.parseToJsonElement(checkNotNull(last)).jsonObject.getValue("verified").jsonPrimitive.boolean)
                            // Next start's response is consumed by phase 8.
                            phase = 8; call("dev_solvit_workspace_shell_start", args("shellId" to shellId, "command" to command))
                        }
                        else -> {
                            assertEquals("reference-$runtime|repaired-$runtime|$literal|/workspace", Json.parseToJsonElement(checkNotNull(last)).jsonPrimitive.content)
                            emit(ModelChunk.Text("Verified $runtime Skills")); emit(ModelChunk.Done); return@flow
                        }
                    }
                    emit(ModelChunk.ToolCalls(listOf(next))); emit(ModelChunk.Done)
                }
            }
            val chunks = withTimeout(60_000) {
                ToolAgentRuntime(provider, tools, maxRounds = 64, maxCalls = 64).run(ModelRequest(ModelConfig(),
                    listOf(ModelMessage(Role.USER, "Prepare and run $skill; inspect and repair errors.")),
                    authorization = allow, agentRole = role, conversation = ConversationContext("skills-agent", project.id))).toList()
            }
            assertTrue(observedFailure)
            assertTrue(chunks.filterIsInstance<ModelChunk.Text>().any { it.value == "Verified $runtime Skills" })
            assertEquals("reference-$runtime|repaired-$runtime|$literal|/workspace", workspaces.read(project.id, "output-$runtime.txt"))
            assertEquals(script, Json.parseToJsonElement(library.export(skill)).jsonObject.getValue("files").jsonObject.getValue("scripts/main.$extension").jsonPrimitive.content)
            android.util.Log.i("SolvitLinuxProbe", "SKILLS_RESOURCES_AGENT|$runtime|QuickJS|prepare|Ubuntu|failed-exit|repair|follow-up|shared-file|import-no-exec")
        }
    } finally {
        manager.shutdown(); workspaces.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }
}
