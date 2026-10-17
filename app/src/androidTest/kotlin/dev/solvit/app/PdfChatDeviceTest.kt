package dev.solvit.app

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.chat.*
import dev.solvit.core.*
import dev.solvit.documents.AndroidDocumentTextService
import dev.solvit.provider.OpenAiProvider
import dev.solvit.storage.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class PdfChatDeviceTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val rule=createAndroidComposeRule<MainActivity>(
        effectContext=kotlinx.coroutines.test.StandardTestDispatcher()
    )
    private fun screenshot(name:String="pdf-chat-selected-scan") {
        rule.waitForIdle()
        runBlocking {
            val frame=CompletableDeferred<Unit>()
            rule.activity.runOnUiThread {
                val root=android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }
                check(root.isHardwareAccelerated)
                root.viewTreeObserver.registerFrameCommitCallback { frame.complete(Unit) };root.invalidate()
            }
            withTimeout(10_000) { frame.await() }
        }
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/solvit-ui")).use { it.readBytes() }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /data/local/tmp/solvit-ui/$name.png")).use { it.readBytes() }
    }
    @Test fun pickerSelectedScanPreviewHttpRetryReopenAndEditPreserveTheSameSelection() {
        val arguments=InstrumentationRegistry.getArguments()
        val traceSend=arguments.getString("pdfSendTrace")=="true"
        val awaitNativeIme=arguments.getString("pdfAwaitNativeIme")=="true"
        var observingSend=false
        var sendTouchNode:androidx.compose.ui.semantics.SemanticsNode?=null
        val sendTouches=mutableListOf<Boolean>()
        val app=rule.activity.application as SolvitApplication
        rule.waitUntil(15_000) { app.ready.value!=null };check(app.ready.value!!.isSuccess)
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(target.cacheDir,"pdf-chat-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context=object:ContextWrapper(target) {
            override fun getApplicationContext()=this
            override fun getFilesDir()=File(directory,"files").apply { check(mkdirs()||isDirectory) }
            override fun getCacheDir()=File(directory,"cache").apply { check(mkdirs()||isDirectory) }
            override fun getDatabasePath(name:String)=File(File(directory,"db").apply { check(mkdirs()||isDirectory) },name)
            override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory)
            override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,errorHandler:DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,errorHandler)
        }
        val export=File(target.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"updates").apply { check(mkdirs()||isDirectory) }
        val file=File(export,"pdf-${UUID.randomUUID()}.pdf")
        val bytes=DocumentReaderDeviceTest.pdf(scanned=true);file.writeBytes(bytes)
        val uri=FileProvider.getUriForFile(target,"${target.packageName}.updates",file)
        val picker=object:ActivityResultRegistry() {
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
                val intent=contract.createIntent(target,input)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.action)
                assertTrue(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.contains("application/pdf"))
                Handler(Looper.getMainLooper()).post { dispatchResult(requestCode,Activity.RESULT_OK,Intent().setData(uri)) }
            }
        }
        val owner=object:ActivityResultRegistryOwner { override val activityResultRegistry=picker }
        val server=MockWebServer().apply { start();enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":{\"message\":\"fixture failure\"}}")) }
        val endpoint=server.url("/v1")
        val client=OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).addInterceptor { chain ->
            val request=chain.request()
            check(request.url.host==endpoint.host && request.url.port==endpoint.port) { "PDF fixture refused non-local model" }
            chain.proceed(request)
        }.build()
        val store=SqliteChatStore(context);val models=mutableListOf<ViewModel>();lateinit var vm:ChatViewModel
        try {
            val files=AttachmentFiles(File(context.filesDir,"chat-attachments"));val documents=AndroidDocumentTextService(context,files)
            val events=EventBus();val chat=DefaultChatService(store,ToolAgentRuntime(OpenAiProvider(client),ToolRegistry(events)),events,
                attachmentStore=files,documentText=documents)
            val settings=object:SettingsStore {
                override suspend fun load()=ModelConfig(endpoint.toString(),"fixture-documents")
                override suspend fun save(value:ModelConfig)=Unit
                override suspend fun isConfigured()=true
            }
            rule.runOnUiThread {
                vm=ChatViewModel(chat,settings,branches=DefaultChatBranchService(store),attachmentStore=files,documentText=documents)
                val agents=AgentLibraryViewModel(app.roles,app.skills,app.marketplace)
                val workspaces=WorkspaceViewModel(app.workspaces,app.pluginDrafts,app.terminals)
                val updates=UpdateViewModel(app.updates,app.updateDownloader);val market=MarketplaceViewModel(app.marketplace)
                models+=listOf(vm,agents,workspaces,updates,market)
                rule.activity.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme {
                        if(traceSend)Box(Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while(true) {
                                    val event=awaitPointerEvent(PointerEventPass.Initial)
                                    if(observingSend)event.changes.filter { it.pressed!=it.previousPressed }.forEach { pointer ->
                                        val bounds=sendTouchNode?.boundsInRoot
                                        val decor=rule.activity.window.decorView
                                        val insets=decor.rootWindowInsets
                                        val ime=insets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom?:0
                                        val hit=bounds?.contains(pointer.position)==true
                                        sendTouches+=hit
                                        android.util.Log.i("SolvitPdfFixture","[DEBUG-solvit-pdf-touch] ${if(pointer.pressed)"down" else "up"}|hit=$hit|point=${pointer.position}|bounds=$bounds|ime=$ime|focus=${decor.hasWindowFocus()}")
                                    }
                                }
                            }
                        }) { ChatScreen(vm,updates,market,agents,workspaces) }
                        else ChatScreen(vm,updates,market,agents,workspaces)
                    }
                } }
            }
            rule.waitUntil(10_000) { vm.ready.value && vm.sessionId.value!=null }
            val session=vm.sessionId.value!!
            rule.onNodeWithTag("chat_attach").performClick()
            rule.waitUntil(15_000) { vm.attachmentDrafts.value[session].orEmpty().size==1 && !vm.importingAttachment.value }
            val ref=vm.attachmentDrafts.value.getValue(session).single();assertEquals("application/pdf",ref.mimeType)
            rule.onNodeWithTag("attachment_${ref.id}").performClick()
            try {
                rule.waitUntil(90_000) { rule.onAllNodesWithTag("pdf_text_1").fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: Throwable) {
                // Synthetic PDF fixture only: distinguish parser/OCR failure from lazy layout.
                for (tag in listOf("pdf_preview", "pdf_progress", "pdf_error", "pdf_coverage", "pdf_text_1", "pdf_text_2")) {
                    val nodes = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
                    val text = nodes.flatMap { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty() }
                        .joinToString(" | ") { it.text }.take(400)
                    android.util.Log.i("SolvitPdfFixture", "PDF_WAIT_STATE|$tag|${nodes.size}|$text")
                }
                runCatching {
                    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/solvit-ui")).use { it.readBytes() }
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /data/local/tmp/solvit-ui/pdf-fixture-wait-failure.png")).use { it.readBytes() }
                }
                throw failure
            }
            rule.onNodeWithTag("pdf_text_1").assertTextEquals("SHORT")
            rule.onNodeWithTag("pdf_preview").performQueuedScrollToNode(rule, hasTestTag("pdf_settings"))
            rule.onNodeWithTag("pdf_settings").performClick()
            rule.onNodeWithTag("pdf_first_page").performTextReplacement("2")
            rule.onNodeWithTag("pdf_last_page").performTextReplacement("2")
            rule.onNodeWithTag("pdf_preview").performQueuedScrollToNode(rule, hasTestTag("pdf_apply"))
            rule.onNodeWithTag("pdf_apply").performClick()
            val selection=DocumentSelection(2,2,DocumentOcrScript.CHINESE)
            rule.waitUntil(10_000) { vm.attachmentDrafts.value[session]?.single()?.documentSelection==selection }
            rule.waitUntil(90_000) { rule.onAllNodesWithTag("pdf_text_2").fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithTag("pdf_text_1").assertCountEquals(0)
            rule.onNodeWithTag("pdf_coverage").assertTextEquals("共2页，本次读取第2至2页")
            rule.onNodeWithTag("pdf_preview").performQueuedScrollToNode(rule, hasTestTag("pdf_text_2"))
            rule.onNodeWithTag("pdf_text_2").assertIsDisplayed()
            val scan=rule.onNodeWithTag("pdf_text_2").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }.replace(Regex("\\s"),"")
            assertTrue(scan.contains("扫描测试") && scan.contains("2026"));assertEquals(0,server.requestCount)
            screenshot()
            rule.onNodeWithTag("pdf_preview").performQueuedScrollToNode(rule, hasTestTag("pdf_original_2"))
            rule.onNodeWithTag("pdf_original_2").performClick()
            rule.waitUntil(30_000) { rule.onAllNodesWithTag("pdf_original_image").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("pdf_original_image").assertIsDisplayed()
            screenshot("pdf-chat-original-scan")
            rule.onNodeWithTag("pdf_original_close").performClick()
            rule.onNodeWithTag("attachment_preview_close").performClick()
            rule.onNodeWithTag("chat_input").performTextInput("read selected page")
            if(awaitNativeIme)rule.waitUntil(10_000) {
                val node=rule.onAllNodesWithTag("chat_composer").fetchSemanticsNodes().singleOrNull()
                    ?:return@waitUntil false
                rule.runOnUiThread {
                    val decor=rule.activity.window.decorView
                    val insets=decor.rootWindowInsets
                    val ime=insets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom?:0
                    val bounds=node.boundsInRoot
                    decor.hasWindowFocus()&&insets?.isVisible(android.view.WindowInsets.Type.ime())==true&&ime>0&&
                        bounds.width>0&&bounds.height>0&&bounds.bottom<=decor.height-ime+1
                }
            }
            if(traceSend) {
                val node=rule.onNodeWithTag("chat_send").fetchSemanticsNode()
                rule.runOnUiThread { sendTouchNode=node;observingSend=true }
            }
            rule.onNodeWithTag("chat_send").performClick()
            if(traceSend)rule.runOnUiThread {
                android.util.Log.i("SolvitPdfFixture","[DEBUG-solvit-pdf-touch] after|touches=$sendTouches|sessionMatches=${vm.sessionId.value==session}|draftPresent=${vm.inputDrafts.value[session]?.text?.isNotBlank()==true}|busy=${vm.busy.value}|queued=${vm.queuedMessages.value.size}|requests=${server.requestCount}")
            }
            try {
                rule.waitUntil(90_000) { !vm.busy.value && vm.messages.value.lastOrNull()?.status==MessageStatus.FAILED }
            } catch (failure: Throwable) {
                // Synthetic local HTTP/PDF fixture only; never log requests or model configuration.
                android.util.Log.i("SolvitPdfFixture", "PDF_SEND_WAIT|ready=${vm.ready.value}|busy=${vm.busy.value}|error=${vm.error.value!=null}|requests=${server.requestCount}|messages=${vm.messages.value.map { it.status }}|attachments=${vm.attachmentDrafts.value[session].orEmpty().size}")
                for (tag in listOf("chat_input", "chat_send", "pdf_preview", "attachment_preview_close")) {
                    val nodes = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
                    val bounds = rule.runOnUiThread { nodes.map { it.boundsInWindow.toString() } }
                    android.util.Log.i("SolvitPdfFixture", "PDF_SEND_NODE|$tag|count=${nodes.size}|bounds=$bounds")
                }
                val inputMatches = rule.onAllNodes(hasTestTag("chat_input") and hasText("read selected page")).fetchSemanticsNodes().isNotEmpty()
                val ime = rule.runOnUiThread { rule.activity.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0 }
                android.util.Log.i("SolvitPdfFixture", "PDF_SEND_INPUT|matches=$inputMatches|ime=$ime")
                runCatching { screenshot("pdf-chat-send-failure") }
                throw failure
            } finally {
                if(traceSend)rule.runOnUiThread {
                    observingSend=false
                    android.util.Log.i("SolvitPdfFixture","[DEBUG-solvit-pdf-touch] settled|touches=$sendTouches|sessionMatches=${vm.sessionId.value==session}|draftPresent=${vm.inputDrafts.value[session]?.text?.isNotBlank()==true}|busy=${vm.busy.value}|queued=${vm.queuedMessages.value.size}|requests=${server.requestCount}")
                }
            }
            assertTrue("An accepted PDF send must clear its conversation draft after real HTTP failure settles",
                vm.inputDrafts.value[session]?.text.isNullOrEmpty())
            rule.onNodeWithTag("chat_input").assertTextEquals("")
            if(traceSend)assertEquals("The original single physical send must land inside its actual button on down and up",listOf(true,true),rule.runOnUiThread { sendTouches.toList() })
            fun requestContent():String {
                val request=server.takeRequest(5,TimeUnit.SECONDS)?:error("missing PDF request")
                val messages=JSONObject(request.body.readUtf8()).getJSONArray("messages")
                return (0 until messages.length()).map { messages.getJSONObject(it) }.single { it.getString("role")=="user" }.getString("content")
            }
            val first=requestContent();assertTrue(first.contains("本次读取第2至2页"));assertTrue(first.replace(Regex("\\s"),"").contains("扫描测试"));assertFalse(first.contains("SHORT"))
            val failed=vm.messages.value.last()
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"pdf answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            rule.onNodeWithTag("chat_messages").performQueuedScrollToNode(rule, hasTestTag("retry_reply_${failed.id}"))
            rule.onNodeWithTag("retry_reply_${failed.id}").performClick()
            rule.waitUntil(90_000) { !vm.busy.value && vm.messages.value.lastOrNull()?.content=="pdf answer" }
            assertEquals(first,requestContent())
            val user=runBlocking { store.history(session).single { it.role==Role.USER } }
            assertEquals(selection,user.attachments.single().documentSelection)
            rule.onNodeWithTag("chat_messages").performQueuedScrollToNode(rule, hasTestTag("message_actions_${user.id}"))
            rule.onNodeWithTag("message_actions_${user.id}").performClick();rule.onNodeWithText("编辑并分支").performClick()
            rule.onNodeWithText("创建分支").performClick()
            rule.waitUntil(15_000) { vm.sessionId.value!=session && vm.attachmentDrafts.value[vm.sessionId.value].orEmpty().size==1 && !vm.branching.value }
            val copy=vm.attachmentDrafts.value.getValue(vm.sessionId.value!!).single()
            assertEquals(selection,copy.documentSelection);assertNotEquals(ref.id,copy.id)
            assertArrayEquals(bytes,runBlocking { files.read(vm.sessionId.value!!,copy) })
            rule.runOnUiThread { vm.viewModelScope.cancel() };store.close()
            SqliteChatStore(context).use { reopened -> runBlocking {
                val saved=reopened.history(session).single { it.role==Role.USER }.attachments.single()
                assertEquals(selection,saved.documentSelection);assertArrayEquals(bytes,files.read(session,saved))
            } }
        } finally {
            rule.runOnUiThread { rule.activity.setContent { };models.forEach { it.viewModelScope.cancel() } }
            store.close();server.shutdown();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll();file.delete();directory.deleteRecursively()
        }
    }
}
