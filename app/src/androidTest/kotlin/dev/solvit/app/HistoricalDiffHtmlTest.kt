package dev.solvit.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.*
import dev.solvit.chat.DefaultChatService
import dev.solvit.market.*
import dev.solvit.storage.SqliteChatStore
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Actual APK native capture -> SQLite reopen -> chat entry -> QuickJS -> offline HTML. */
class HistoricalDiffHtmlTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private lateinit var context: Context
    private lateinit var directory: File
    private val preferences=mutableSetOf<String>()
    @Before fun isolatedStorage() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val prefix="historical-diff-"+UUID.randomUUID()
        directory=File(app.cacheDir,prefix).apply { check(mkdirs()) }
        context=object : ContextWrapper(app) {
            override fun getApplicationContext(): Context=this
            override fun getFilesDir(): File=File(directory,"files").apply { check(mkdirs()||isDirectory) }
            override fun getDatabasePath(name: String): File=File(File(directory,"databases").apply { check(mkdirs()||isDirectory) },name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,errorHandler: DatabaseErrorHandler?): SQLiteDatabase=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,errorHandler)
            override fun deleteDatabase(name: String): Boolean=SQLiteDatabase.deleteDatabase(getDatabasePath(name))
            override fun getSharedPreferences(name: String,mode: Int): android.content.SharedPreferences {
                val scoped="$prefix-$name";preferences+=scoped;return super.getSharedPreferences(scoped,mode)
            }
        }
        val application=rule.activity.application as SolvitApplication
        runBlocking { withTimeout(30_000) { application.ready.filterNotNull().first().getOrThrow() } }
    }
    @After fun cleanup() {
        if(::directory.isInitialized)directory.deleteRecursively()
        preferences.forEach(InstrumentationRegistry.getInstrumentation().targetContext::deleteSharedPreferences)
    }
    private suspend fun captured(before: String,after: String): ExecutionRun {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val workspaces=AndroidWorkspaces(context,scope)
        try { SqliteChatStore(context).use { store ->
            val chat=store.createSession();val user="user-${chat.id}";val workspace=workspaces.create("HTML evidence")
            store.append(ChatMessage(user,chat.id,Role.USER,"Edit the end of code.txt"))
            workspaces.write(workspace.id,"code.txt",before);val baseline=workspaces.snapshot(workspace.id)
            workspaces.write(workspace.id,"code.txt",after);val observed=workspaces.snapshot(workspace.id)
            val run=ExecutionRun("run-${chat.id}",chat.id,user,null,workspace.id,1)
            assertTrue(store.beginExecutionRun(run))
            assertTrue(store.appendExecutionEvents(run.id,0,listOf(ExecutionEvent(1,ExecutionKind.STEP_STARTED),
                ExecutionEvent(1,ExecutionKind.TOOL_PROPOSED,ToolCall("write","fixture_write","{}"),"fixture"),
                ExecutionEvent(1,ExecutionKind.WORKSPACE_BASELINE,workspace=baseline),ExecutionEvent(1,ExecutionKind.TOOL_STARTED,callId="write"),
                ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="write",stage=ToolStage.SUCCEEDED,result="stored fixture evidence"),
                ExecutionEvent(1,ExecutionKind.WORKSPACE_OBSERVED,workspace=observed),ExecutionEvent(1,ExecutionKind.STEP_FINISHED)),2))
            assertTrue(store.finishExecutionRun(run.id,ExecutionStatus.COMPLETE,3))
            store.append(ChatMessage("assistant-${chat.id}",chat.id,Role.ASSISTANT,"Saved execution fixture"))
            workspaces.write(workspace.id,"code.txt","LATER-LIVE-FILE")
            return run
        } } finally { workspaces.close();scope.cancel() }
    }
    private fun service(store: SqliteChatStore)=DefaultChatService(store,object : AgentRuntime {
        override fun run(request: ModelRequest): Flow<ModelChunk> = error("Review must not execute a model or tool")
    },EventBus())
    private fun waitDom(html: HtmlPageProbe, expression: String) {
        rule.waitUntil(10_000) { runCatching { html.js(expression)=="true" }.getOrDefault(false) }
    }
    private fun tap(html: HtmlPageProbe,id: String) {
        waitDom(html,"!!document.querySelector('[data-node=$id]') && !document.querySelector('[data-node=$id]').disabled")
        html.tapElement("[data-node=$id]")
    }
    @Test fun storedTailEditOpensFromSameCollapsedChatBubbleAndHtmlRemainsLiteral() {
        val before=(0..1499).joinToString("\n") { "unchanged $it" }+"\n<script>window.executed=true</script>\nOLD-TAIL\n"
        val after=before.replace("OLD-TAIL","NEW-TAIL🙂")
        val source=runBlocking { captured(before,after) };val manager=BuiltinWorkspaceManager(context);val html=HtmlPageProbe(rule.activity)
        var visible by mutableStateOf(false)
        SqliteChatStore(context).use { reopened ->
            val rows=runBlocking { attachExecutions(presentChat(reopened.history(source.sessionId)),reopened.executionRuns(source.sessionId).first()) }
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
                ChatTimeline(rows,source.sessionId,loadExecutionEvents={ reopened.executionEvents(it.sessionId,it.id) },onOpenFileChanges={ visible=true })
                if(visible)PluginPageScreen("文件变更", { manager.openPage("zh",ExecutionChangesPageBridge(service(reopened),source),pageId="changes") }) { visible=false }
            } } }
            rule.onAllNodesWithTag("assistant_message").assertCountEquals(1)
            rule.onNodeWithTag("execution_journal_details").assertDoesNotExist()
            rule.onNodeWithTag("execution_journal_toggle").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("workspace_changes_toggle").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("workspace_changes_toggle").performClick()
            waitDom(html,"window.pluginPageReady===true && !!document.querySelector('[data-node=change_0]')")
            tap(html,"change_0");waitDom(html,"!!document.querySelector('.diff-row.added code')")
            waitDom(html,"document.getElementById('content').textContent.includes('NEW-TAIL🙂')")
            assertEquals("\"undefined\"",html.js("typeof window.executed"))
            assertEquals("false",html.js("document.getElementById('content').textContent.includes('LATER-LIVE-FILE')"))
            assertEquals("false",html.js("document.querySelector('[data-node=fileIdentity] details').open"))
            assertEquals("true",html.js("!!document.querySelector('.removed') && !!document.querySelector('.added')"))
            html.screenshot("historical-file-diff-dark")
            rule.onNodeWithTag("plugin_page_close").performClick()
            rule.onNodeWithTag("plugin_web_page").assertDoesNotExist()
        }
    }
    @Test fun longUnicodeLineRemainsCompleteAcrossActualHtmlPagesAndCloseReopen() {
        val after="🙂".repeat(10000)+"LONG-TAIL"
        val source=runBlocking { captured("before\n",after) };val manager=BuiltinWorkspaceManager(context);val html=HtmlPageProbe(rule.activity)
        var visible by mutableStateOf(true)
        SqliteChatStore(context).use { reopened ->
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme(colorScheme=lightColorScheme()) {
                if(visible)PluginPageScreen("文件变更", { manager.openPage("en",ExecutionChangesPageBridge(service(reopened),source),pageId="changes") }) { visible=false }
            } } }
            waitDom(html,"!!document.querySelector('[data-node=change_0]')");tap(html,"change_0")
            waitDom(html,"!!document.querySelector('.added code')")
            val all=StringBuilder();var pages=0
            while(true) {
                assertTrue("Pagination must terminate",++pages<100)
                all.append(Json.parseToJsonElement(html.js("Array.from(document.querySelectorAll('.added code'),n=>n.textContent.substring(2)).join('')")).jsonPrimitive.content)
                if(html.js("!!document.querySelector('[data-node=next]')")!="true")break
                val previous=html.js("document.querySelector('[data-node=pageNumber]').textContent")
                tap(html,"next");waitDom(html,"document.querySelector('[data-node=pageNumber]').textContent!==$previous")
            }
            assertTrue(pages>1);assertEquals(after,all.toString());html.screenshot("historical-file-diff-light-tail")
            rule.onNodeWithTag("plugin_page_close").performClick();rule.onNodeWithTag("plugin_web_page").assertDoesNotExist()
            rule.runOnUiThread { visible=true }
            waitDom(html,"!!document.querySelector('[data-node=change_0]')");tap(html,"change_0")
            waitDom(html,"document.querySelector('[data-node=pageNumber]').textContent.startsWith('1 /')")
            rule.onNodeWithTag("plugin_page_close").performClick()
        }
    }
}
