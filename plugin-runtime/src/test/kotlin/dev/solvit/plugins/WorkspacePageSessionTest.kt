package dev.solvit.plugins

import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspacePageSessionTest {
    @Test fun api12SeparatesShellPermissionFromPtyAndHttpFromBrowserDependencies() {
        val host = ScriptHost { _, _, _ -> error("Not invoked") }
        val source = pkg()
        fun dependencies(api: Int, services: Set<String> = emptySet()) = ScriptToolPlugin(source.copy(manifest = source.manifest.copy(
            apiVersion = api, permissions = setOf("workspace", "shell", "network"), nativeServices = services)), host).requires
        assertTrue(WorkspaceServiceKey in dependencies(12))
        assertFalse(TerminalServiceKey in dependencies(12)); assertFalse(WebPageServiceKey in dependencies(12))
        assertTrue(TerminalServiceKey in dependencies(12, setOf("terminal.pty")))
        assertTrue(WebPageServiceKey in dependencies(12, setOf("web.pages")))
        assertTrue(TerminalServiceKey in dependencies(11)); assertTrue(WebPageServiceKey in dependencies(11))
    }
    private fun pkg(api: Int = 12, action: String = "await ctx.workspacePage.perform()",
        control:String="{kind:'workspace.perform',operation:'create',arguments:{name:'Exact name'}}") = ScriptPackage.parse(buildJsonObject {
        putJsonObject("manifest") {
            put("id", "test.workspace.page"); put("name", "Workspace"); put("description", "Test"); put("version", "1.0.0"); put("apiVersion", api)
            put("permissions", buildJsonArray { add("workspace") }); put("dependencies", JsonArray(emptyList())); put("tools", JsonArray(emptyList()))
            put("pages", Json.parseToJsonElement("""[{"id":"main","title":"Workspace"}]"""))
        }
        put("source", """globalThis.solvitPlugin={tools:{},pages:{main:async(input,ctx)=>{
          if(input.event) { $action }
          return {title:'Workspace',nodes:[{id:'create',kind:'button',text:'Create',action:'create',hostAction:$control}]};
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
    @Test fun fullTextChunkQueriesRequireApi18AndActuallyCrossTheQuickJsPageBoundary()=runBlocking<Unit> {
        var reads=0
        val access=object:PageWorkspaceAccess {
            override suspend fun query(operation:String,argumentsJson:String,authorize:suspend ()->Unit):String {
                authorize();assertEquals("readChunk",operation)
                val args=Json.parseToJsonElement(argumentsJson).jsonObject
                assertEquals(JsonPrimitive("code.kt"),args["path"]);assertEquals(JsonNull,args["expectedSha256"])
                reads++;return """{"text":"fixture","offset":0,"nextOffset":7,"total":7,"sha256":"${"a".repeat(64)}"}"""
            }
            override suspend fun perform(control:PageWorkspaceControl,authorize:suspend ()->Unit)=error("Preview must not write")
        }
        val action="await ctx.workspacePage.query('readChunk',{path:'code.kt',offset:0,expectedSha256:null})"
        for(api in listOf(17,18)) {
            val page=ScriptPageSession(pkg(api=api,action=action),"main","en",ScriptHost { _,_,_ -> error("Not exposed") }) {}
            page.attachWorkspace(access)
            try {
                val initial=page.open()
                if(api==17) { assertFails { page.dispatch(initial,"create",confirmed=true) };assertEquals(0,reads) }
                else { page.dispatch(initial,"create",confirmed=true);assertEquals(1,reads) }
            }finally { page.close() }
        }
    }

    @Test fun documentDraftCallsAndReviewedCommitRequireApi18AndCloseTheirPageCapability()=runBlocking<Unit> {
        val control="{kind:'workspace.perform',operation:'writeDraft',arguments:{draftId:'draft',path:'code.kt',sha256:'${"a".repeat(64)}'}}"
        for(action in listOf("await ctx.workspacePage.perform()")+
            listOf("editBegin","editAppend","editFinish","editDiscard").map { "await ctx.workspacePage.query('$it',{})" }) {
            for(api in listOf(17,18)) {
                var calls=0;var closed=false
                val access=object:PageWorkspaceAccess {
                    override suspend fun query(operation:String,argumentsJson:String,authorize:suspend ()->Unit):String {
                        authorize();calls++;return "{}"
                    }
                    override suspend fun perform(control:PageWorkspaceControl,authorize:suspend ()->Unit):String {
                        authorize();assertEquals("writeDraft",control.operation);calls++;return "{}"
                    }
                    override suspend fun close() { closed=true }
                }
                val page=ScriptPageSession(pkg(api,action,control),"main","en",ScriptHost { _,_,_ -> error("Not exposed") }) {}
                page.attachWorkspace(access)
                try {
                    val initial=page.open()
                    if(api==17) { assertFails { page.dispatch(initial,"create",confirmed=true) };assertEquals(0,calls) }
                    else {
                        if(action.contains("perform")) { assertFails { page.dispatch(initial,"create") };assertEquals(0,calls) }
                        page.dispatch(initial,"create",confirmed=true);assertEquals(1,calls)
                    }
                }finally { page.close() }
                assertTrue(closed)
            }
        }
    }
}
