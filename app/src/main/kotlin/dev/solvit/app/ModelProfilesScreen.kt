package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.solvit.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelProfilesScreen(service: ModelProfilesService, generating: Boolean, onDismiss: () -> Unit,
    onFetchModels: suspend (ModelConfig) -> List<AvailableModel>) {
    val state by service.state.collectAsStateWithLifecycle()
    val loadError by service.loadError.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ModelProfile?>(null) }
    var name by remember { mutableStateOf("") }
    var task by remember { mutableStateOf<ModelTask?>(null) }
    var deleting by remember { mutableStateOf<ModelProfile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    fun operation(action: suspend () -> Unit) {
        if (busy || generating) return
        busy=true; error=null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error=e.message ?: "模型配置操作失败，请重试" }
            finally { busy=false }
        }
    }
    val enabled = !busy && !generating && loadError == null
    FullScreenPage(onDismissRequest={ if (!busy) onDismiss() }) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar={ TopAppBar(title={ UiText("模型配置") },navigationIcon={
                IconButton(onClick=onDismiss,enabled=!busy) { UiIcon(Icons.AutoMirrored.Filled.ArrowBack,"返回设置") }
            }) }) { padding ->
                LazyColumn(Modifier.padding(padding).consumeWindowInsets(padding).testTag("model_profiles_list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item { UiText("任务模型",style=MaterialTheme.typography.titleLarge) }
                    item { UiText("普通聊天使用聊天模型；模型发起工具调用后，后续轮次可使用另一个模型，它会收到完整对话和工具结果。未单独选择时沿用聊天模型。工具后续只切换连接和模型，系统提示词仍采用聊天配置与角色。") }
                    items(ModelTask.entries) { item ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            UiText(if(item==ModelTask.CHAT) "聊天模型" else "工具后续模型",style=MaterialTheme.typography.titleMedium)
                            val selected = if(item==ModelTask.TOOL_FOLLOW_UP && item !in state.routes) null else state.selected(item)
                            if(selected==null) UiText("沿用聊天模型") else Text(selected.name + " · " + selected.config.model)
                            TextButton(onClick={ task=item },enabled=enabled,modifier=Modifier.testTag("model_task_${item.name}")) { UiText("选择配置") }
                        } }
                    }
                    item { Button(onClick={ editing=ModelProfile(UUID.randomUUID().toString(),"",ModelConfig());name="" },enabled=enabled && state.profiles.size<32,
                        modifier=Modifier.testTag("new_model_profile")) { UiText("添加模型配置") } }
                    item { UiText("已保存的配置",style=MaterialTheme.typography.titleLarge) }
                    items(state.profiles,key={it.id}) { profile ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Text(profile.name,style=MaterialTheme.typography.titleMedium)
                            Text(profile.config.model,style=MaterialTheme.typography.bodyMedium)
                            Text(profile.config.baseUrl,style=MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick={ editing=profile;name=profile.name },enabled=enabled,modifier=Modifier.testTag("edit_model_${profile.id}")) { UiText("编辑") }
                                TextButton(onClick={ editing=profile.copy(id=UUID.randomUUID().toString());name=profile.name+" 2" },enabled=enabled && state.profiles.size<32) { UiText("复制") }
                                TextButton(onClick={ deleting=profile },enabled=enabled && profile.id !in state.routes.values) { UiText("删除") }
                            }
                            if(profile.id in state.routes.values) UiText("正在用于任务；删除前请先切换任务模型",style=MaterialTheme.typography.bodySmall)
                        } }
                    }
                    if(generating) item { UiText("请先停止生成，再修改模型配置") }
                    (loadError ?: error)?.let { message -> item { UiText(message,color=MaterialTheme.colorScheme.error) } }
                }
            }
        }
    }
    editing?.let { profile -> key(profile.id) {
        ModelSettings(profile.config,busy,error,onDismiss={ if(!busy) editing=null },
            onSave={ config -> operation { service.save(profile.copy(name=name.trim(),config=config)); editing=null } },
            onFetchModels=onFetchModels,profileName=name,onProfileNameChanged={ name=it })
    } }
    task?.let { selectedTask -> AlertDialog(onDismissRequest={ if(!busy) task=null },title={ UiText("选择任务模型") },text={
        LazyColumn(Modifier.heightIn(max=360.dp)) {
            if(selectedTask==ModelTask.TOOL_FOLLOW_UP) item { TextButton(onClick={ operation { service.assign(selectedTask,null);task=null } },enabled=enabled) { UiText("沿用聊天模型") } }
            items(state.profiles,key={it.id}) { profile -> TextButton(onClick={ operation { service.assign(selectedTask,profile.id);task=null } },enabled=enabled,
                modifier=Modifier.fillMaxWidth().testTag("choose_model_${profile.id}")) { Text(profile.name+" · "+profile.config.model) } }
        }
    },confirmButton={ TextButton(onClick={ task=null },enabled=!busy) { UiText("取消") } }) }
    deleting?.let { profile -> AlertDialog(onDismissRequest={ if(!busy) deleting=null },title={ UiText("删除模型配置？") },
        text={ UiText("删除“${profile.name}”的连接和密钥。聊天记录会保留。") },
        confirmButton={ TextButton(onClick={ operation { service.delete(profile.id);deleting=null } },enabled=enabled) { UiText("删除") } },
        dismissButton={ TextButton(onClick={ deleting=null },enabled=!busy) { UiText("取消") } }) }
}
