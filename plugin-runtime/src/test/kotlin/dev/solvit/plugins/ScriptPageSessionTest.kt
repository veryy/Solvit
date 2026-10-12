package dev.solvit.plugins

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import dev.solvit.core.PostTurnTaskControl
import dev.solvit.core.PageDocumentAccess
import dev.solvit.core.PageDocumentControl
import dev.solvit.core.PageTextDocument

class ScriptPageSessionTest {
    private val documentSource = """
        globalThis.solvitPlugin={tools:{},pages:{manage:async(input,ctx)=>{
          if(input.event?.action==='open'){
            const file=await ctx.documents.open();
            await ctx.storage.set('chosen',file);
          }
          if(input.event?.action==='save')await ctx.documents.save('exact exported text');
          if(input.event?.action==='twice'){await ctx.documents.open();await ctx.documents.open()}
          if(input.event?.action==='injected')await __solvitHostCall('documents.open',JSON.stringify({uri:'content://other/file'}));
          return {title:'Documents',nodes:[
            {id:'open',kind:'button',text:'Open',action:'open',hostAction:{kind:'document.open',mimeTypes:['text/plain'],maxBytes:524288}},
            {id:'save',kind:'button',text:'Save',action:'save',hostAction:{kind:'document.save',name:'export.txt',mimeType:'text/plain',maxBytes:524288}},
            {id:'twice',kind:'button',text:'Twice',action:'twice',hostAction:{kind:'document.open',mimeTypes:['text/plain'],maxBytes:524288}},
            {id:'injected',kind:'button',text:'Injected',action:'injected',hostAction:{kind:'document.open',mimeTypes:['text/plain'],maxBytes:524288}}
          ]};
        }}};
    """
    @Test fun actualQuickJsCanOnlyConsumeTheConfirmedDocumentControlOnce() = runBlocking<Unit> {
        var opens = 0; var saves = 0; var selected: JsonElement? = null
        val page = ScriptPageSession(pkg(documentSource, 11), "manage", "zh-CN", ScriptHost { _, operation, args ->
            assertEquals("storage.set", operation); selected = args.getValue("value"); JsonNull
        }, {})
        page.attachDocuments(object : PageDocumentAccess {
            override suspend fun open(control: PageDocumentControl.Open, authorize: suspend () -> Unit): PageTextDocument {
                authorize(); opens++; assertEquals(listOf("text/plain"), control.mimeTypes)
                return PageTextDocument("chosen.txt", text = "chosen content")
            }
            override suspend fun save(control: PageDocumentControl.Save, text: String, authorize: suspend () -> Unit): Boolean {
                authorize(); saves++; assertEquals("export.txt", control.name); assertEquals("exact exported text", text); return true
            }
        })
        try {
            val initial = page.open()
            assertFails { page.dispatch(initial, "open") }; assertEquals(0, opens)
            val imported = page.dispatch(initial, "open", confirmed = true)
            assertEquals("chosen content", selected?.jsonObject?.get("text")?.jsonPrimitive?.content)
            val saved = page.dispatch(imported, "save", confirmed = true)
            assertEquals(1, saves)
            assertFails { page.dispatch(saved, "injected", confirmed = true) }; assertEquals(1, opens)
            assertFails { page.dispatch(saved, "twice", confirmed = true) }; assertEquals(2, opens)
            assertFails { page.dispatch(initial, "save", confirmed = true) }; assertEquals(1, saves)
        } finally { page.close() }
    }
    @Test fun revokedAndClosedPageCannotReceiveOrPersistAPendingFileResult() = runBlocking<Unit> {
        var authorized = true; var writes = 0
        val entered = CompletableDeferred<Unit>()
        val result = CompletableDeferred<PageTextDocument?>()
        val page = ScriptPageSession(pkg(documentSource, 11), "manage", "zh-CN",
            ScriptHost { _, _, _ -> writes++; JsonNull }, { check(authorized) })
        page.attachDocuments(object : PageDocumentAccess {
            override suspend fun open(control: PageDocumentControl.Open, authorize: suspend () -> Unit): PageTextDocument? {
                authorize(); entered.complete(Unit); return result.await()
            }
            override suspend fun save(control: PageDocumentControl.Save, text: String, authorize: suspend () -> Unit) = error("Not used")
        })
        val initial = page.open()
        val invocation = async { runCatching { page.dispatch(initial, "open", confirmed = true) } }
        entered.await(); authorized = false; result.complete(PageTextDocument("late.txt", text = "private text"))
        assertTrue(invocation.await().isFailure); assertEquals(0, writes)
        page.close(); assertFails { page.dispatch(initial, "open", confirmed = true) }
    }
    @Test fun lowerApiPagesAndModelExecutionsCannotOpenDocuments() = runBlocking<Unit> {
        var opens = 0
        val host = ScriptHost { _, _, _ -> opens++; JsonNull }
        val old = ScriptPageSession(pkg(documentSource, 10), "manage", "zh-CN", host, {})
        try { val view = old.open(); assertFails { old.dispatch(view, "open", confirmed = true) } }
        finally { old.close() }
        val model = ScriptExecutor(pkg("""globalThis.solvitPlugin={tools:{},activate:async(ctx)=>{await ctx.documents.open()},pages:{manage:()=>({title:'x',nodes:[]})}};""", 11), host)
        assertFails { model.start() }; assertEquals(0, opens)
    }
    private fun pkg(source: String, api: Int = 9, pages: String = """[{"id":"manage","title":"管理"}]"""): ScriptPackage =
        ScriptPackage.parse(buildJsonObject {
            putJsonObject("manifest") {
                put("id", "test.pages"); put("name", "Page test"); put("description", "Test"); put("version", "1.0.0")
                put("apiVersion", api); put("permissions", JsonArray(listOf(JsonPrimitive("storage"))))
                put("dependencies", JsonArray(emptyList())); put("tools", JsonArray(emptyList()))
                put("pages", Json.parseToJsonElement(pages))
            }
            put("source", source)
        }.toString().toByteArray())

    private val source = """
        let visits=0;
        globalThis.solvitPlugin={tools:{},activate:()=>{throw Error('Chat lifecycle ran')},
          deactivate:()=>{throw Error('Chat lifecycle ran')},pages:{manage:async(input,ctx)=>{
            visits++;
            if(input.event){
              if(input.event.action!=='save')throw Error('Unexpected action');
              await ctx.storage.set('fact',input.event.fields.body);
            }
            const fact=await ctx.storage.get('fact');
            return {title:input.locale+':'+visits,nodes:[
              {id:'body',kind:'field',label:'内容',value:fact??''},
              {id:'save',kind:'button',text:'保存',action:'save'}]};
          }}};
    """.trimIndent()

    @Test fun declaredPagesRequireNewApiAndUnambiguousIds() {
        assertFailsWith<IllegalArgumentException> { pkg(source, api = 8) }
        assertFailsWith<IllegalArgumentException> { pkg(source, pages = """[{"id":"manage","title":"A"},{"id":"manage","title":"B"}]""") }
        assertFailsWith<IllegalArgumentException> { pkg(source, pages = """[{"id":"../escape","title":"A"}]""") }
    }

    @Test fun actualQuickJsPageEditsPersistWithIndependentStateAndNoChatLifecycle() = runBlocking<Unit> {
        var fact: JsonElement = JsonPrimitive("原始记忆")
        val host = ScriptHost { _, operation, args ->
            val authority = currentCoroutineContext()[ScriptPageExecutionContext]
            assertNotNull(authority); assertEquals("test.pages", authority.pluginId)
            withContext(Dispatchers.IO) { authority.ensureAuthorized() }
            when (operation) {
            "storage.get" -> fact
            "storage.set" -> { fact = args.getValue("value"); JsonNull }
            else -> error("Unexpected operation")
        } }
        val first = ScriptPageSession(pkg(source), "manage", "zh-CN", host) {}
        val second = ScriptPageSession(pkg(source), "manage", "en", host) {}
        try {
            val initial = first.open()
            assertEquals("zh-CN:1", initial.title)
            assertEquals("en:1", second.open().title)
            val updated = first.dispatch(initial, "save", fields = mapOf("body" to "新的记忆"))
            assertEquals("zh-CN:2", updated.title)
            assertEquals(JsonPrimitive("新的记忆"), updated.nodes[0]["value"])
            assertEquals(JsonPrimitive("新的记忆"), fact)
            assertFails { first.dispatch(updated, "missing", fields = mapOf("body" to "wrong")) }
            assertFails { first.dispatch(initial, "save", fields = mapOf("body" to "stale edit")) }
            assertEquals(JsonPrimitive("新的记忆"), fact)
        } finally { first.close(); second.close() }
        assertFails { first.open() }
    }

    @Test fun revocationPreventsNextCallbackAndStorageMutation() = runBlocking<Unit> {
        var allowed = true
        var writes = 0
        val host = ScriptHost { _, operation, _ -> if (operation == "storage.get") JsonPrimitive("fact") else { writes++; JsonNull } }
        val session = ScriptPageSession(pkg(source), "manage", "ja", host) { check(allowed) }
        try {
            val initial = session.open(); allowed = false
            assertFails { session.dispatch(initial, "save", fields = mapOf("body" to "forbidden")) }
            assertEquals(0, writes)
        } finally { session.close() }
    }
    @Test fun confirmedNativeControlComesFromDisplayedNodeAndIsConsumedOnlyOnce() = runBlocking<Unit> {
        var calls = 0
        val packageWithControl = pkg("""
            globalThis.solvitPlugin={tools:{},pages:{manage:async(input,ctx)=>{
              if(input.event)await ctx.storage.set('forged-target','task-b');
              return {title:'Tasks',nodes:[{id:'retry',kind:'button',text:'Retry',action:'retry',
                hostAction:{kind:'postTurn.retry',jobId:'task-a',attempt:1}}]};
            }}};
        """.trimIndent())
        val session = ScriptPageSession(packageWithControl, "manage", "en", ScriptHost { _, _, _ ->
            val authority = checkNotNull(currentCoroutineContext()[ScriptPageExecutionContext])
            assertEquals(PostTurnTaskControl.Retry("task-a", 1), authority.consumeTaskControl())
            assertFailsWith<IllegalStateException> { authority.consumeTaskControl() }
            calls++; JsonNull
        }) {}
        try {
            val initial = session.open()
            assertFails { session.dispatch(initial, "retry") }
            assertEquals(0, calls)
            val updated = session.dispatch(initial, "retry", confirmed = true)
            assertEquals(1, calls)
            assertFails { session.dispatch(initial, "retry", confirmed = true) }
            assertEquals(1, calls)
            session.dispatch(updated, "retry", confirmed = true)
            assertEquals(2, calls)
        } finally { session.close() }
        assertFailsWith<IllegalStateException> {
            ScriptPageExecutionContext("test.pages", {}).consumeTaskControl()
        }
    }
    @Test fun foregroundToolCannotUseTaskBridgeEvenWithStoragePermission() = runBlocking<Unit> {
        var calls = 0
        val base = pkg("""
            globalThis.solvitPlugin={tools:{inspect:async(_args,ctx)=>await ctx.jobs.list()},
              pages:{manage:()=>({title:'Page',nodes:[]})}};
        """.trimIndent())
        val declared = base.copy(manifest = base.manifest.copy(tools = listOf(
            ScriptTool("inspect", "Test", buildJsonObject { put("type", "object") }))))
        val executor = ScriptExecutor(declared, ScriptHost { _, _, _ -> calls++; JsonNull }, invokeLifecycle = false)
        try {
            executor.start()
            val error = assertFailsWith<IllegalStateException> { executor.execute("inspect", "{}") }
            assertTrue(error.message.orEmpty().contains("任务管理仅供插件页面使用"))
            assertEquals(0, calls)
        } finally { executor.stop() }
    }

    @Test fun closingPageCancelsPendingNativeCallWithoutBlockingOtherPage() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val blocked = ScriptPageSession(pkg(source), "manage", "zh", ScriptHost { _, _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }) {}
        val other = ScriptPageSession(pkg(source), "manage", "en", ScriptHost { _, _, _ -> JsonNull }) {}
        try {
            val opening = async { runCatching { blocked.open() } }
            withTimeout(5000) { entered.await() }
            assertEquals("en:1", other.open().title)
            withTimeout(5000) { blocked.close(); cancelled.await(); assertTrue(opening.await().isFailure) }
        } finally { blocked.close(); other.close() }
    }
}
