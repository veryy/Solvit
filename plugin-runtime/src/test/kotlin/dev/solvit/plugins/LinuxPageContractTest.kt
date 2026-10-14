package dev.solvit.plugins

import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class LinuxPageContractTest {
    private fun pkg(api: Int = 21, declared: Boolean = true, network: Boolean = true) = ScriptPackage.parse(buildJsonObject {
        putJsonObject("manifest") {
            put("id", "test.linux.page"); put("name", "Linux"); put("description", "Test"); put("version", "1.0.0"); put("apiVersion", api)
            put("permissions", buildJsonArray { add("workspace"); add("shell"); if (network) add("network") })
            put("dependencies", JsonArray(emptyList())); put("tools", JsonArray(emptyList()))
            put("nativeServices", buildJsonArray { if (declared) add("environment.linux") })
            put("pages", Json.parseToJsonElement("""[{"id":"main","title":"Linux"}]"""))
        }
        put("source", """globalThis.solvitPlugin={tools:{},pages:{main:async(input,ctx)=>{
          if(input.event) await ctx.workspacePage.perform();
          return {title:'Linux',nodes:[{id:'install',kind:'button',text:'Install',action:'install',
            hostAction:{kind:'workspace.perform',operation:'installEnvironment',arguments:{}}}]};
        }}};""")
    }.toString().toByteArray())

    @Test fun linuxRequiresNewApiAllPermissionsAndDeclaredLifecycleDependency() {
        assertFails { pkg(api = 20) }; assertFails { pkg(network = false) }
        val valid = ScriptToolPlugin(pkg(), ScriptHost { _, _, _ -> error("Not used") })
        assertTrue(LinuxEnvironmentServiceKey in valid.requires)
        val hiddenCommand = Json.parseToJsonElement("""{"kind":"workspace.perform","operation":"installEnvironment","arguments":{"command":"unreviewed"}}""").jsonObject
        assertFails { ScriptPageHostAction.workspace(hiddenCommand) }
        assertFails { ScriptPageHostAction.workspace(Json.parseToJsonElement("""{"kind":"workspace.perform","operation":"installSoftware","arguments":{"software":"arbitrary-package"}}""").jsonObject) }
    }

    @Test fun actualQuickJsInstallationConsumesOneConfirmedControlAndUndeclaredPagesCannotInstall(): Unit = runBlocking {
        var starts = 0
        val access = object : PageWorkspaceAccess {
            override suspend fun query(operation: String, argumentsJson: String, authorize: suspend () -> Unit) = error("Not used")
            override suspend fun perform(control: PageWorkspaceControl, authorize: suspend () -> Unit): String {
                authorize(); assertEquals("installEnvironment", control.operation); assertEquals("{}", control.argumentsJson)
                starts++; return "{}"
            }
        }
        val page = ScriptPageSession(pkg(), "main", "en", ScriptHost { _, _, _ -> error("Not exposed") }) {}
        page.attachWorkspace(access)
        try {
            val initial = page.open()
            assertFails { page.dispatch(initial, "install") }; assertEquals(0, starts)
            page.dispatch(initial, "install", confirmed = true); assertEquals(1, starts)
            assertFails { page.dispatch(initial, "install", confirmed = true) }; assertEquals(1, starts)
        } finally { page.close() }
        for (source in listOf(pkg(declared = false), pkg(api = 20, declared = false))) {
            val blocked = ScriptPageSession(source, "main", "en", ScriptHost { _, _, _ -> error("Not exposed") }) {}
            blocked.attachWorkspace(access)
            try { assertFails { blocked.dispatch(blocked.open(), "install", confirmed = true) }; assertEquals(1, starts) }
            finally { blocked.close() }
        }
    }
}
