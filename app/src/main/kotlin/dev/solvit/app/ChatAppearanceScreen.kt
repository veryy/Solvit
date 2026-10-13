@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.solvit.core.*

@Composable
fun ChatAppearanceScreen(store: AppearanceStore,onDismiss: () -> Unit) {
    val saved by store.chat.collectAsStateWithLifecycle()
    var edited by remember(saved) { mutableStateOf(saved) }
    fun save(value: ChatAppearance) { edited=value;store.setChat(value) }
    FullScreenPage(onDismissRequest=onDismiss) {
        Scaffold(topBar={TopAppBar(title={UiText("聊天外观")},navigationIcon={IconButton(onClick=onDismiss){UiIcon(Icons.AutoMirrored.Filled.ArrowBack,"返回设置")}})}) { padding ->
            LazyColumn(Modifier.padding(padding).consumeWindowInsets(padding).testTag("chat_appearance_settings"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                item {
                    UiText("字体大小 · ${edited.fontSize.toInt()} sp",style=MaterialTheme.typography.titleMedium)
                    Slider(edited.fontSize,{edited=edited.copy(fontSize=it)},onValueChangeFinished={store.setChat(edited)},valueRange=12f..24f,steps=11,modifier=Modifier.testTag("chat_font_size"))
                }
                item {
                    UiText("回复显示速度",style=MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "自动",30 to "慢速",80 to "中速",240 to "快速",-1 to "即时").forEach { (speed,label) ->
                            FilterChip(selected=edited.replySpeed==speed,onClick={save(edited.copy(replySpeed=speed))},label={UiText(label)},modifier=Modifier.testTag("reply_speed_$speed"))
                        }
                    }
                    if(edited.replySpeed>0) {
                        UiText("每秒 ${edited.replySpeed} 字",style=MaterialTheme.typography.bodySmall)
                        Slider(edited.replySpeed.toFloat(),{edited=edited.copy(replySpeed=it.toInt())},onValueChangeFinished={store.setChat(edited)},valueRange=10f..400f,modifier=Modifier.testTag("reply_speed_slider"))
                    }
                    UiText("只调整收到文字后的显示节奏；模型返回速度取决于服务。",style=MaterialTheme.typography.bodySmall)
                }
                item {
                    UiText("聊天样式",style=MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("bubble" to "气泡","compact" to "紧凑","plain" to "简洁").forEach { (style,label) ->
                            FilterChip(selected=edited.style==style,onClick={save(edited.copy(style=style))},label={UiText(label)},modifier=Modifier.testTag("chat_style_$style"))
                        }
                    }
                }
                item {
                    UiText("预览",style=MaterialTheme.typography.titleMedium)
                    CompositionLocalProvider(LocalChatAppearance provides edited) {
                        val user=ChatMessage("preview-user","preview",Role.USER,uiString("你好，帮我整理一下今天的计划。"))
                        val assistant=ChatMessage("preview-assistant","preview",Role.ASSISTANT,uiString("当然。先完成最重要的一件事，再安排剩余任务。"))
                        ChatBubble(ChatRow("preview-user",user=user),assistantName=uiString("角色名称"))
                        ChatBubble(ChatRow("preview-assistant",messages=listOf(assistant)),assistantName=uiString("角色名称"))
                    }
                }
                item { TextButton(onClick={save(ChatAppearance())},modifier=Modifier.testTag("reset_chat_appearance")){UiText("恢复默认")} }
            }
        }
    }
}
