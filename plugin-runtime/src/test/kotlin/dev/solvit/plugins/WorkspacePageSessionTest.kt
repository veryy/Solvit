package dev.solvit.plugins

import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspacePageSessionTest {
    private fun pkg(api: Int = 12, action: String = "await ctx.workspacePage.perform()") = ScriptPackage.parse(buildJsonObject {
        putJsonObject("manifest") {
            put("id", "test.workspace.page"); put("name", "Workspace"); put("description", "Test"); put("version", "1.0.0"); put("apiVersion", api)
            put("permissions", buildJsonArray { add("workspace") }); put("dependencies", JsonArray(emptyList())); put("tools", JsonArray(emptyList()))
            put("pages", Json.parseToJsonElement("""[{"id":"main","title":"Workspace"}]"""))
        }
        put("source", """globalThis.solvitPlugin={tools:{},pages:{main:async(input,ctx)=>{
          if(input.event) { $action }
          return {title:'Workspace',nodes:[{id:'create',kind:'button',text:'Create',action:'create',hostAction:{kind:'workspace.perform',operation:'create',arguments:{name:'Exact name'}}}]};
        }}};""")
    }.toString().toByteArray())
    @Test fun actualQuickJsConsumesTheDisplayedWorkspaceControlOnceWithoutForwardingScriptArguments() = runBlocking<Unit> {
        var writes = 0
        val access = object : PageWorkspaceAccess {
            override suspend fun query(operation: String, argumentsJson: String, authorize: suspend () -> Unit) = error("Not used")
            override suspend fun perform(control: PageWorkspaceControl, authorize: suspend () -> Unit): String {
                authorize(); assertEquals("create", control.operation)
                assertEquals("Exact name", Json.parseToJsonElement(control.argumentsJson).jsonObject.getValue("name").jsonPrimitive.content)
                writes++; return "{}"
            }
        }
        val page = ScriptPageSession(pkg(), "main", "en", ScriptHost { _, _, _ -> error("Not exposed") }) {}
        page.attachWorkspace(access)
        try {
            val initial = page.open()
            assertFails { page.dispatch(initial, "create") }; assertEquals(0, writes)
            page.dispatch(initial, "create", confirmed = true); assertEquals(1, writes)
            assertFails { page.dispatch(initial, "create", confirmed = true) }; assertEquals(1, writes)
        } finally { page.close() }
        for (source in listOf("await ctx.workspacePage.perform();await ctx.workspacePage.perform()", "await __solvitHostCall('workspacePage.perform',JSON.stringify({name:'hidden'}))")) {
            val other = ScriptPageSession(pkg(action = source), "main", "en", ScriptHost { _, _, _ -> error("Not exposed") }) {}
            other.attachWorkspace(access)
            try { val initial = other.open(); assertFails { other.dispatch(initial, "create", confirmed = true) } }
            finally { other.close() }
        }
        assertEquals(2, writes)
        val old = ScriptPageSession(pkg(api = 11), "main", "en", ScriptHost { _, _, _ -> error("Not exposed") }) {}
        old.attachWorkspace(access)
        try { val initial = old.open(); assertFails { old.dispatch(initial, "create", confirmed = true) }; assertEquals(2, writes) }
        finally { old.close() }
    }
}
