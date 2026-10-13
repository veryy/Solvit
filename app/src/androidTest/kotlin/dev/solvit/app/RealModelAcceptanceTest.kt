package dev.solvit.app

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.net.URI
import java.util.UUID

/** Opt-in, disposable CI emulator only. Never substitutes a deterministic model. */
class RealModelAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val results = JSONArray()
    private var phase = "preflight"
    private lateinit var config: ModelConfig
    private lateinit var app: SolvitApplication
    private lateinit var vm: ChatViewModel

    @Test fun actualGatewayRolesVisibleStreamingAndJavaScriptToolRoundTrip() {
        app = rule.activity.application as SolvitApplication
        val input = File(app.filesDir, "model-acceptance.json")
        val sessions = linkedSetOf<String>()
        val roleIds = mutableListOf<String>()
        val pluginId = "test.realmodel"
        var originalProfiles: ModelProfilesState? = null
        val models = mutableListOf<androidx.lifecycle.ViewModel>()
        var passed = false
        var ownsPlugin = false
        try {
            check(InstrumentationRegistry.getArguments().getString("solvitRealModel") == "true") { "Explicit real-model mode required" }
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator required" }
            check(input.isFile) { "Fresh model credential file missing" }
            val settings = JSONObject(input.readText())
            val endpoint = settings.getString("baseUrl")
            val uri = URI(endpoint)
            check(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null) { "HTTPS model base address required" }
            val key = settings.getString("apiKey")
            check(key.length >= 8) { "Fresh model credential required" }
            val provider = settings.optString("provider", "openai-compatible")
            check(provider in setOf("openai-compatible", "anthropic")) { "Existing provider required" }
            config = ModelConfig(endpoint, settings.getString("model"), key, providerId = provider,
                providerOptions = mapOf("max_tokens" to "1024") + if (provider == "anthropic") mapOf("thinking_mode" to "disabled") else emptyMap())
            check(config.validate() == null) { "Model configuration invalid" }
            rule.waitUntil(15_000) { app.ready.value != null }
            val chat = app.ready.value!!.getOrThrow()
            originalProfiles = app.modelProfiles.state.value
            runBlocking {
                app.modelProfiles.replace(ModelProfilesState(listOf(ModelProfile("acceptance", "模型验收", config)),
                    mapOf(ModelTask.CHAT to "acceptance", ModelTask.TOOL_FOLLOW_UP to "acceptance")))
            }
            val suffix = UUID.randomUUID().toString().take(8)
            val cedar = AgentRole("accept-cedar-$suffix", "Solvit-Cedar-$suffix", "你的当前对话角色名如上；用简洁中文交流，按当前角色回答，不沿用历史角色。")
            val maple = AgentRole("accept-maple-$suffix", "Solvit-Maple-$suffix", "你的当前对话角色名如上；用简洁中文交流，按当前角色回答，不沿用历史角色。")
            roleIds += listOf(cedar.id, maple.id)
            runBlocking { app.roles.save(cedar); app.roles.save(maple) }
            val first = runBlocking { chat.createSession() }.id
            sessions += first
            rule.runOnUiThread {
                vm = ChatViewModel(chat, app.settings, app.roles, app.modelCatalog, app.branches,
                    app.toolApprovals, app.modelProfiles)
                val agents = AgentLibraryViewModel(app.roles, app.skills, app.marketplace)
                val workspace = WorkspaceViewModel(app.workspaces, app.pluginDrafts, app.terminals)
                val updates = UpdateViewModel(app.updates, app.updateDownloader)
                val market = MarketplaceViewModel(app.marketplace)
                models += listOf(vm, agents, workspace, updates, market)
                rule.activity.setContent { MaterialTheme { ChatScreen(vm, updates, market, agents, workspace) } }
            }
            rule.waitUntil(15_000) { vm.ready.value && vm.modelConfigured.value }
            rule.runOnUiThread { vm.select(first) }
            roleTurn("default-role", "Solvit")
            applyRole(cedar)
            roleTurn("applied-role", cedar.name)
            applyRole(maple)
            roleTurn("switched-role", maple.name)
            rule.onNodeWithContentDescription("对话历史").performClick()
            rule.onNodeWithTag("new_chat").performClick()
            rule.waitUntil(15_000) { vm.sessionId.value != first }
            val second = vm.sessionId.value!!
            sessions += second
            assertEquals(maple.id, runBlocking { app.roles.selected(second) }?.id)
            roleTurn("new-chat-inherits-role", maple.name)
            rule.runOnUiThread { vm.select(first) }
            roleTurn("reopened-chat-role", maple.name)

            phase = "visible-streaming"
            send("请用中文写五段关于如何检查一个小型 Kotlin 项目的建议，每段约80字，包含小标题和一个简短代码块。直接输出正文。")
            var visibleBeforeProviderDone = false
            var visibleAtMs: Long? = null
            rule.waitUntil(180_000) {
                val receiving = vm.replyTiming.value?.receivedEndMs == null
                val partial = vm.draft.value.isNotBlank()
                if (receiving && partial && rule.onAllNodesWithTag("streaming_body").fetchSemanticsNodes().isNotEmpty()) {
                    if (rule.onNodeWithTag("streaming_body").isDisplayed() && vm.replyTiming.value?.receivedEndMs == null) {
                        visibleBeforeProviderDone = true
                        visibleAtMs = vm.replyTiming.value?.firstVisibleMs
                        true
                    } else false
                } else !vm.busy.value
            }
            if (visibleBeforeProviderDone) screenshot("stream-before-provider-done")
            rule.waitUntil(180_000) { !vm.busy.value }
            checkNoModelError()
            val timing = vm.replyTiming.value!!
            results.put(JSONObject().put("case", phase).put("visibleBeforeProviderDone", visibleBeforeProviderDone)
                .put("firstParsedTextMs", timing.firstTextMs).put("firstVisibleStateMs", visibleAtMs)
                .put("parsedEndMs", timing.receivedEndMs).put("displayEndMs", timing.displayEndMs)
                .put("receivedTextEvents", timing.receivedTextEvents).put("lastParsedTextMs", timing.lastTextMs)
                .put("receivedCharacters", timing.receivedCharacters))
            check(visibleBeforeProviderDone) { "No visible partial text before parsed model completion; gateway buffering or display requires diagnosis" }
            check(timing.receivedTextEvents >= 2) { "Provider returned one text block; display animation alone does not prove progressive reception" }

            phase = "javascript-tool-round-trip"
            val proof = "solvit-proof-" + UUID.randomUUID().toString()
            val pkg = JSONObject().put("manifest", JSONObject().put("id", pluginId).put("name", "模型验收插件")
                .put("description", "只读工具执行验收").put("version", "1.0.0").put("apiVersion", 13)
                .put("permissions", JSONArray()).put("dependencies", JSONArray())
                .put("tools", JSONArray().put(JSONObject().put("name", "probe").put("description", "返回宿主生成的 verification 验收码，必须实际调用读取，不能猜测。")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false)))))
                .put("source", "globalThis.solvitPlugin={tools:{probe:async()=>({verification:${JSONObject.quote(proof)}})}};")
            runBlocking {
                check(app.marketplace.installed.value.none { it.id == pluginId }) { "Acceptance plugin already exists" }
                app.marketplace.installLocal(pkg.toString().toByteArray())
                ownsPlugin = true
                app.marketplace.enable(pluginId, emptySet())
                app.roles.save(maple.copy(pluginIds = setOf(pluginId)))
            }
            send("请实际调用验收插件的 probe 工具，随后只回答它返回的 verification 字段原文。不要猜测验收码。")
            rule.waitUntil(180_000) { vm.toolApproval.value != null || !vm.busy.value }
            check(vm.toolApproval.value?.descriptor?.pluginId == pluginId) { "Model did not request the allowed JS tool" }
            rule.onNodeWithText("允许这一次").performClick()
            rule.waitUntil(180_000) { !vm.busy.value }
            checkNoModelError()
            val history = runBlocking { chat.messages(vm.sessionId.value!!).first() }
            val tool = history.lastOrNull { it.role == Role.TOOL }
            check(tool != null && tool.toolStage == ToolStage.SUCCEEDED && tool.content.contains(proof)) { "Actual JS tool did not produce its unique proof" }
            val answer = history.last { it.role == Role.ASSISTANT }.content
            check(answer.contains(proof)) { "Real model did not use the tool result" }
            results.put(JSONObject().put("case", phase).put("passed", true).put("toolCallId", tool!!.toolCallId)
                .put("verification", proof).put("answer", safe(answer)))
            screenshot("tool-result-used")
            passed = true
        } catch (e: Exception) {
            results.put(JSONObject().put("case", phase).put("passed", false).put("failureType", e.javaClass.simpleName))
            if (::vm.isInitialized) screenshot("failure-$phase")
            throw AssertionError("Real model acceptance failed in $phase; inspect the sanitized report")
        } finally {
            File(app.filesDir, "model-acceptance-result.json").writeText(JSONObject().put("passed", passed)
                .put("realModel", true).put("results", results).toString(2))
            input.delete()
            if (::vm.isInitialized) {
                rule.runOnUiThread { vm.stop() }
                rule.waitUntil(30_000) { !vm.busy.value }
                rule.runOnUiThread { models.forEach { it.viewModelScope.cancel() } }
            }
            runBlocking {
                if (app.ready.value?.isSuccess == true) {
                    originalProfiles?.let { app.modelProfiles.replace(it) }
                    if (ownsPlugin && app.marketplace.installed.value.any { it.id == pluginId }) app.marketplace.uninstall(pluginId)
                    sessions.forEach { app.roles.select(it, null); app.ready.value!!.getOrThrow().deleteSession(it) }
                    roleIds.forEach { app.roles.delete(it) }
                }
            }
        }
    }

    private fun applyRole(role: AgentRole) {
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithText("角色管理与 Skills").performClick()
        rule.onNodeWithTag("apply_role_${role.id}").performScrollTo().performClick()
        rule.waitUntil(15_000) { runBlocking { app.roles.selected(vm.sessionId.value!!) }?.id == role.id }
        rule.onNodeWithContentDescription("关闭").performClick()
        rule.onNodeWithContentDescription("返回聊天").performClick()
    }

    private fun roleTurn(name: String, expected: String) {
        phase = name
        send("当前这次对话里，你的角色名称是什么？只回答当前角色名称，不查询任何工具。")
        rule.waitUntil(180_000) { vm.toolApproval.value != null || !vm.busy.value }
        check(vm.toolApproval.value == null) { "Plain role question unexpectedly called tools" }
        checkNoModelError()
        val answer = runBlocking { app.ready.value!!.getOrThrow().messages(vm.sessionId.value!!).first() }
            .last { it.role == Role.ASSISTANT }.content
        results.put(JSONObject().put("case", name).put("expectedRole", expected).put("answer", safe(answer))
            .put("preparedRole", app.requestInspection.latest.value?.roleName))
        check(answer.contains(expected, ignoreCase = true)) { "Real answer did not identify the selected role" }
        screenshot(name)
    }

    private fun send(text: String) {
        check(!vm.busy.value)
        val previousStart = vm.replyTiming.value?.startedAtMs
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.onNodeWithContentDescription("发送").performClick()
        rule.waitUntil(10_000) { vm.replyTiming.value?.startedAtMs != previousStart &&
            (vm.busy.value || vm.error.value != null || vm.replyTiming.value?.outcome == "complete") }
    }

    private fun checkNoModelError() {
        if (vm.error.value != null) {
            results.put(JSONObject().put("case", phase).put("modelError", safe(vm.error.value.orEmpty()))
                .put("httpStatus", vm.errorDetails.value?.httpStatus))
        }
        check(vm.error.value == null && vm.replyTiming.value?.outcome == "complete") { "Gateway did not complete a successful reply" }
    }

    private fun safe(value: String) = if (::config.isInitialized) value.replace(config.apiKey, "[credential hidden]").take(4096) else ""
    private fun screenshot(name: String) {
        if (!::config.isInitialized || !::vm.isInitialized) return
        val visible = vm.messages.value.joinToString { it.content } + vm.draft.value + vm.error.value.orEmpty()
        if (visible.contains(config.apiKey)) return
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(app.filesDir, "model-acceptance-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
