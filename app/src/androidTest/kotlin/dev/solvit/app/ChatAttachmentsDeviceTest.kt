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
        val client = OkHttpClient()
        val store = SqliteChatStore(context)
        val models = mutableListOf<ViewModel>()
        lateinit var vm: ChatViewModel
        try {
            val files = dev.solvit.storage.AttachmentFiles(File(context.filesDir, "chat-attachments"))
            val events = EventBus()
            val chat = DefaultChatService(store, ToolAgentRuntime(OpenAiProvider(client), ToolRegistry(events)), events, attachmentStore = files)
            val settings = object : SettingsStore {
                override suspend fun load() = ModelConfig(server.url("/v1").toString(), "fixture-vision")
                override suspend fun save(value: ModelConfig) = Unit
                override suspend fun isConfigured() = true
            }
            rule.runOnUiThread {
                vm = ChatViewModel(chat, settings, attachmentStore = files)
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
            rule.onNodeWithTag("attachment_${image.id}").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("attachment_image_${image.id}").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("attachment_preview_close").performClick()
            rule.onNodeWithTag("chat_send").assertIsEnabled().performClick()
            rule.waitUntil(15_000) { !vm.busy.value && vm.messages.value.any { it.role == Role.ASSISTANT && it.content == "attachment answer" } }
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
            rule.waitUntil(10_000) { rule.onAllNodesWithText("中文 fixture code 😀\nprint(42)").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("attachment_preview_close").performClick()
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
