package dev.solvit.app

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.chat.DefaultChatService
import dev.solvit.chat.ToolAgentRuntime
import dev.solvit.core.*
import dev.solvit.provider.OpenAiProvider
import dev.solvit.storage.SqliteChatStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real picker contract/URI, Compose, private files, SQLite, and actual HTTP payload. */
class ChatAttachmentsDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        require(name.matches(Regex("[a-z-]+")))
        rule.waitForIdle()
        runBlocking {
            val frame = CompletableDeferred<Unit>()
            rule.activity.runOnUiThread {
                val view = rule.activity.window.decorView
                check(view.isHardwareAccelerated)
                view.viewTreeObserver.registerFrameCommitCallback { frame.complete(Unit) }
                view.invalidate()
            }
            withTimeout(10_000) { frame.await() }
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/solvit-ui")).use { it.readBytes() }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /data/local/tmp/solvit-ui/$name.png")).use { it.readBytes() }
    }
    private data class AttachmentViewport(val bounds:androidx.compose.ui.geometry.Rect,val height:Int,val ime:Int,val keyboardVisible:Boolean)
    private fun waitForStableAttachmentAfterBranchKeyboard(tag:String) {
        var previous:AttachmentViewport?=null
        var stableSince=0L
        // Compose idle does not mean the separate OS keyboard animation has finished.
        // Observe actual insets and card bounds across frames rather than tapping cached coordinates.
        rule.waitUntil(10_000) {
            val bounds=rule.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            if(bounds==null)false else {
                val current=rule.runOnUiThread {
                    val decor=rule.activity.window.decorView
                    val insets=decor.rootWindowInsets
                    AttachmentViewport(bounds,decor.height,insets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom?:0,
                        insets?.isVisible(android.view.WindowInsets.Type.ime())==true)
                }
                val now=android.os.SystemClock.uptimeMillis()
                if(current!=previous){previous=current;stableSince=now}
                current.keyboardVisible&&current.ime>0&&current.bounds.bottom<=current.height-current.ime&&now-stableSince>=250
            }
        }
    }
    @Test fun pickerImportsImagesAndTextThenHttpSendAndReopenPreserveTheirContent() {
        val app = rule.activity.application as SolvitApplication
        rule.waitUntil(15_000) { app.ready.value != null }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "attachment-device-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { check(mkdirs() || isDirectory) }
            override fun getDatabasePath(name: String) = File(File(directory, "db").apply { check(mkdirs() || isDirectory) }, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        }
        val export = File(target.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { check(mkdirs() || isDirectory) }
        val png = File(export, "fixture-${UUID.randomUUID()}.png")
        val text = File(export, "fixture-${UUID.randomUUID()}.txt")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        text.writeText("中文 fixture code 😀\nprint(42)")
        val imageBytes = png.readBytes()
        val imageUri = FileProvider.getUriForFile(target, "${target.packageName}.updates", png)
        val textUri = FileProvider.getUriForFile(target, "${target.packageName}.updates", text)
        var launches = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                val intent = contract.createIntent(target, input)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
                assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                launches++
                if (launches == 1) {
                    Handler(Looper.getMainLooper()).post { dispatchResult(requestCode, Activity.RESULT_CANCELED, null) }
                    return
                }
                val result = Intent().apply {
                    clipData = ClipData.newUri(target.contentResolver, "fixture", imageUri).apply { addItem(ClipData.Item(textUri)) }
                }
                Handler(Looper.getMainLooper()).post { dispatchResult(requestCode, Activity.RESULT_OK, result) }
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val server = MockWebServer().apply { start(); enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"attachment answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")) }
        val fixtureUrl = server.url("/v1")
        val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != fixtureUrl.host || request.url.port != fixtureUrl.port)
                throw java.io.IOException("Attachment fixture refused non-local model endpoint")
            chain.proceed(request)
        }.build()
        val store = SqliteChatStore(context)
        val models = mutableListOf<ViewModel>()
        lateinit var vm: ChatViewModel
        try {
            val files = dev.solvit.storage.AttachmentFiles(File(context.filesDir, "chat-attachments"))
            val events = EventBus()
            val chat = DefaultChatService(store, ToolAgentRuntime(OpenAiProvider(client), ToolRegistry(events)), events, attachmentStore = files)
            val settings = object : SettingsStore {
                override suspend fun load() = ModelConfig(fixtureUrl.toString(), "fixture-vision")
                override suspend fun save(value: ModelConfig) = Unit
                override suspend fun isConfigured() = true
            }
            rule.runOnUiThread {
                vm = ChatViewModel(chat, settings, branches = dev.solvit.chat.DefaultChatBranchService(store), attachmentStore = files)
                val agents = AgentLibraryViewModel(app.roles, app.skills, app.marketplace)
                val workspaces = WorkspaceViewModel(app.workspaces, app.pluginDrafts, app.terminals)
                val updates = UpdateViewModel(app.updates, app.updateDownloader)
                val market = MarketplaceViewModel(app.marketplace)
                models += listOf(vm, agents, workspaces, updates, market)
                rule.activity.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme { ChatScreen(vm, updates, market, agents, workspaces) }
                } }
            }
            rule.waitUntil(10_000) { vm.ready.value && vm.sessionId.value != null }
            assertEquals("Attachment test must use its own local model endpoint", fixtureUrl.toString(), vm.config.value.baseUrl)
            assertEquals("fixture-vision", vm.config.value.model)
            rule.onNodeWithTag("chat_attach").assertIsEnabled().performClick()
            rule.waitUntil(5_000) { launches == 1 }
            rule.waitForIdle()
            assertTrue(vm.attachmentDrafts.value[vm.sessionId.value].orEmpty().isEmpty())
            rule.onNodeWithTag("chat_attach").performClick()
            rule.waitUntil(10_000) { vm.attachmentDrafts.value[vm.sessionId.value].orEmpty().size == 2 && !vm.importingAttachment.value }
            assertEquals(2, launches)
            val session = vm.sessionId.value!!
            val attachments = vm.attachmentDrafts.value.getValue(session)
            val image = attachments.single { it.mimeType == "image/png" }
            val document = attachments.single { it.mimeType == "text/plain" }
            screenshot("chat-attachment-drafts")
            rule.onNodeWithTag("attachment_${image.id}").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("attachment_image_${image.id}").fetchSemanticsNodes().isNotEmpty() }
            screenshot("chat-attachment-image-preview")
            rule.onNodeWithTag("attachment_preview_close").performClick()
            rule.onNodeWithTag("chat_send").assertIsEnabled().performClick()
            try {
                rule.waitUntil(15_000) { !vm.busy.value && vm.messages.value.any { it.role == Role.ASSISTANT && it.content == "attachment answer" } }
            } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("Attachment fixture: busy=${vm.busy.value}, error=${vm.error.value}, HTTP requests=${server.requestCount}, stored states=${vm.messages.value.map { it.role to it.status }}", timeout)
            }
            val http = server.takeRequest(5, TimeUnit.SECONDS) ?: error("missing HTTP request")
            val sent = JSONObject(http.body.readUtf8()).getJSONArray("messages")
            val user = (0 until sent.length()).map { sent.getJSONObject(it) }.single { it.getString("role") == "user" }
            val blocks = user.getJSONArray("content")
            val textBlock = (0 until blocks.length()).map { blocks.getJSONObject(it) }.single { it.getString("type") == "text" }
            val imageBlock = (0 until blocks.length()).map { blocks.getJSONObject(it) }.single { it.getString("type") == "image_url" }
            assertTrue(textBlock.getString("text").contains("中文 fixture code 😀\nprint(42)"))
            val uri = imageBlock.getJSONObject("image_url").getString("url")
            assertTrue(uri.startsWith("data:image/png;base64,"))
            assertArrayEquals(imageBytes, java.util.Base64.getDecoder().decode(uri.substringAfter(',')))
            assertTrue(vm.attachmentDrafts.value[session].orEmpty().isEmpty())
            rule.onNodeWithTag("attachment_${document.id}").performClick()
            try {
                rule.waitUntil(10_000) { rule.onAllNodesWithText("中文 fixture code 😀\nprint(42)").fetchSemanticsNodes().isNotEmpty() }
            } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                val body = rule.onAllNodesWithTag("attachment_text_${document.id}").fetchSemanticsNodes()
                    .map { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString() }
                throw AssertionError("Saved attachment viewer: body=$body, close controls=${rule.onAllNodesWithTag("attachment_preview_close").fetchSemanticsNodes().size}", timeout)
            }
            rule.onNodeWithTag("attachment_text_${document.id}").assertTextEquals("中文 fixture code 😀\nprint(42)")
            screenshot("chat-attachment-saved-text-preview")
            rule.onNodeWithTag("attachment_preview_close").performClick()
            server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":{\"message\":\"fixture temporary failure\"}}"))
            rule.onNodeWithTag("chat_attach").performClick()
            rule.waitUntil(10_000) { vm.attachmentDrafts.value[session].orEmpty().size == 2 && !vm.importingAttachment.value }
            rule.onNodeWithTag("chat_send").performClick()
            rule.waitUntil(15_000) { !vm.busy.value && vm.messages.value.lastOrNull()?.status == MessageStatus.FAILED }
            val failed = vm.messages.value.last()
            val originalUsers = vm.messages.value.filter { it.role == Role.USER }
            assertEquals(2, originalUsers.size)
            val failureHttp = server.takeRequest(5,TimeUnit.SECONDS) ?: error("missing failure request")
            val failedBody = failureHttp.body.readUtf8()
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"retry answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            rule.onNodeWithTag("retry_reply_${failed.id}").performScrollTo().performClick()
            rule.waitUntil(15_000) { !vm.busy.value && vm.messages.value.lastOrNull()?.content == "retry answer" }
            val retryHttp = server.takeRequest(5,TimeUnit.SECONDS) ?: error("missing retry request")
            assertEquals("Retry must send identical original user content, including image/text bytes",failedBody,retryHttp.body.readUtf8())
            assertEquals(originalUsers,vm.messages.value.filter { it.role == Role.USER })
            assertTrue(vm.messages.value.any { it.id == failed.id && it.status == MessageStatus.FAILED })
            rule.onNodeWithTag("reply_attempts_reply-${originalUsers.last().id}").performScrollTo().assertExists()
            rule.onAllNodesWithTag("retry_reply_${failed.id}").assertCountEquals(0)
            // Edit the first attachment-only question through the real menu/dialog.
            val originalUser = originalUsers.first()
            rule.onNodeWithTag("chat_messages").performScrollToNode(hasTestTag("message_actions_${originalUser.id}"))
            rule.onNodeWithTag("message_actions_${originalUser.id}").performClick()
            rule.onNodeWithText("编辑并分支").performClick()
            rule.onNodeWithText("创建分支").assertIsEnabled() // The original question contains attachments and no text.
            rule.onNodeWithTag("branch_edit").performTextReplacement("edited attachment question")
            rule.onNodeWithText("创建分支").assertIsEnabled().performClick()
            rule.waitUntil(10_000) { vm.sessionId.value != session && vm.attachmentDrafts.value[vm.sessionId.value].orEmpty().size == 2 && !vm.branching.value }
            val branch = vm.sessionId.value!!
            val editCopies = vm.attachmentDrafts.value.getValue(branch)
            assertTrue(editCopies.none { copy -> attachments.any { it.id == copy.id } })
            assertEquals(0, runBlocking { store.history(branch).size })
            rule.onNode(hasSetTextAction()).assertTextEquals("edited attachment question")
            val copiedImage = editCopies.single { it.mimeType == "image/png" }
            val copiedText = editCopies.single { it.mimeType == "text/plain" }
            assertArrayEquals(imageBytes, runBlocking { files.read(branch, copiedImage) })
            val previewTag="attachment_${copiedText.id}"
            var previewBounds=rule.onNodeWithTag(previewTag).fetchSemanticsNode().boundsInRoot
            try {
                waitForStableAttachmentAfterBranchKeyboard(previewTag)
                rule.onNodeWithTag(previewTag).performScrollTo().assertIsDisplayed()
                previewBounds=rule.onNodeWithTag(previewTag).fetchSemanticsNode().boundsInRoot
                rule.onNodeWithTag(previewTag).performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithTag("attachment_text_${copiedText.id}").fetchSemanticsNodes().isNotEmpty() }
            } catch (timeout: Throwable) {
                screenshot("chat-edit-attachment-preview-failure")
                throw AssertionError("Branch preview: bounds=$previewBounds, selected=${vm.sessionId.value}, branch=$branch, refs=${vm.attachmentDrafts.value[branch].orEmpty().map { it.id }}, error=${vm.error.value}\n"+rule.onRoot(useUnmergedTree=true).printToString(),timeout)
            }
            rule.onNodeWithTag("attachment_text_${copiedText.id}").assertTextEquals("中文 fixture code 😀\nprint(42)")
            rule.onNodeWithTag("attachment_preview_close").performClick()
            // The second card can be clipped by the horizontal row; bring its actual button into view.
            rule.onNodeWithTag("attachment_remove_${copiedText.id}").performScrollTo().assertIsDisplayed().performClick()
            try {
                rule.waitUntil(10_000) { vm.attachmentDrafts.value[branch].orEmpty().size == 1 }
            } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                screenshot("chat-edit-attachment-remove-failure")
                throw AssertionError("Edited draft removal: selected=${vm.sessionId.value}, branch=$branch, importing=${vm.importingAttachment.value}, refs=${vm.attachmentDrafts.value[branch].orEmpty().map { it.id }}, error=${vm.error.value}", timeout)
            }
            assertEquals("中文 fixture code 😀\nprint(42)", runBlocking { files.read(session, document).toString(Charsets.UTF_8) })
            rule.onNodeWithTag("attachment_${copiedText.id}").assertDoesNotExist()
            rule.onNodeWithTag("attachment_${copiedImage.id}").assertIsDisplayed()
            screenshot("chat-edit-attachment-draft")
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"edited answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            rule.onNodeWithTag("chat_send").performClick()
            rule.waitUntil(15_000) { !vm.busy.value && vm.messages.value.lastOrNull()?.content == "edited answer" }
            val editedHttp = server.takeRequest(5,TimeUnit.SECONDS) ?: error("missing edited attachment request")
            val editMessages = JSONObject(editedHttp.body.readUtf8()).getJSONArray("messages")
            val editUser = (0 until editMessages.length()).map { editMessages.getJSONObject(it) }.single { it.getString("role") == "user" }
            val editBlocks = editUser.getJSONArray("content")
            val editText = (0 until editBlocks.length()).map { editBlocks.getJSONObject(it) }.single { it.getString("type") == "text" }.getString("text")
            assertEquals("edited attachment question", editText)
            val editImage = (0 until editBlocks.length()).map { editBlocks.getJSONObject(it) }.single { it.getString("type") == "image_url" }.getJSONObject("image_url").getString("url")
            assertArrayEquals(imageBytes, java.util.Base64.getDecoder().decode(editImage.substringAfter(',')))
            assertEquals(originalUsers, runBlocking { store.history(session).filter { it.role == Role.USER } })
            assertEquals(listOf(copiedImage), runBlocking { store.history(branch).first { it.role == Role.USER }.attachments })
            rule.runOnUiThread { vm.viewModelScope.cancel() }
            store.close()
            SqliteChatStore(context).use { reopened -> runBlocking {
                val saved = reopened.history(session).first { it.role == Role.USER }
                assertEquals(attachments, saved.attachments)
                assertArrayEquals(imageBytes, files.read(session, saved.attachments.single { it.mimeType == "image/png" }))
            } }
        } finally {
            rule.runOnUiThread { rule.activity.setContent { }; models.forEach { it.viewModelScope.cancel() } }
            store.close(); server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            png.delete(); text.delete(); directory.deleteRecursively()
        }
    }
}
