package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.solvit.core.*
import kotlinx.coroutines.CancellationException

/** Reads through the same host service and selection that the model receives. */
@Composable
internal fun PdfAttachmentPreview(vm: ChatViewModel, session: String, attachment: ChatAttachment, modifier: Modifier) {
    var current by remember(session, attachment.id) { mutableStateOf(attachment) }
    val initial=current.documentSelection ?: DocumentSelection()
    var first by remember(session,attachment.id) { mutableStateOf(initial.firstPage.toString()) }
    var last by remember(session,attachment.id) { mutableStateOf(initial.lastPage?.toString().orEmpty()) }
    var script by remember(session,attachment.id) { mutableStateOf(initial.ocrScript) }
    var settingsOpen by remember { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    var readingEnabled by remember { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    var reading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<DocumentReadEvent.Progress?>(null) }
    var document by remember { mutableStateOf<DocumentText?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val drafts by vm.attachmentDrafts.collectAsState()
    val editable=drafts[session].orEmpty().any { it.id == attachment.id }
    LaunchedEffect(session,current,revision,readingEnabled) {
        if (!readingEnabled) return@LaunchedEffect
        document=null;progress=null;problem=null;reading=true
        try {
            vm.readDocumentEvents(session,current).collect { event ->
                when(event) {
                    is DocumentReadEvent.Progress -> progress=event
                    is DocumentReadEvent.Complete -> document=event.document
                }
            }
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(failure:Exception) { problem=failure.message?.take(240) ?: "PDF读取失败" }
        finally { reading=false }
    }
    LazyColumn(modifier.fillMaxWidth().testTag("pdf_preview"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                UiText("文字页直接提取，扫描页在本机识别。OCR可能有误，请核对原文。",style=MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick={ settingsOpen=!settingsOpen },modifier=Modifier.testTag("pdf_settings")) { UiText("读取范围与语言") }
                    if(reading) TextButton(onClick={ readingEnabled=false },modifier=Modifier.testTag("pdf_stop")) { UiText("停止读取") }
                    else TextButton(onClick={ readingEnabled=true;revision++ },modifier=Modifier.testTag("pdf_read")) { UiText("重新读取") }
                }
                if(settingsOpen) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(first,{ if(it.length<=4 && it.all(Char::isDigit)) first=it },label={ UiText("起始页") },enabled=editable,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.weight(1f).testTag("pdf_first_page"))
                        OutlinedTextField(last,{ if(it.length<=4 && it.all(Char::isDigit)) last=it },label={ UiText("结束页（留空至末页）") },enabled=editable,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.weight(1f).testTag("pdf_last_page"))
                    }
                    Box {
                        OutlinedButton(onClick={ languageMenu=true },enabled=editable,modifier=Modifier.testTag("pdf_language")) { UiText("识别语言：${uiString(scriptName(script))}") }
                        DropdownMenu(expanded=languageMenu,onDismissRequest={ languageMenu=false }) {
                            DocumentOcrScript.entries.forEach { option -> DropdownMenuItem(text={ UiText(scriptName(option)) },
                                onClick={ script=option;languageMenu=false },modifier=Modifier.testTag("pdf_language_${option.name}")) }
                        }
                    }
                    UiText("每次最多40页、256KiB文字。选择会随消息保存，范围外页面不会发给模型。",style=MaterialTheme.typography.bodySmall)
                    if(editable) Button(onClick={
                        try {
                            val start=first.toIntOrNull() ?: error("请输入有效起始页")
                            val end=if(last.isBlank()) null else last.toIntOrNull() ?: error("请输入有效结束页")
                            current=vm.setDocumentSelection(session,attachment.id,DocumentSelection(start,end,script))
                            readingEnabled=true;revision++;settingsOpen=false
                        } catch(failure:Exception) { problem=failure.message?.take(240) ?: "PDF选取页范围无效" }
                    },modifier=Modifier.testTag("pdf_apply")) { UiText("保存并读取") }
                    else UiText("已发送附件的读取选择已保存；编辑并分支后可调整。",style=MaterialTheme.typography.bodySmall)
                }
                if(reading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    val state=progress
                    if(state==null) UiText("正在打开PDF…",modifier=Modifier.testTag("pdf_progress"))
                    else UiText("正在读取第${state.page}/${state.pageCount}页",modifier=Modifier.testTag("pdf_progress"))
                }
                problem?.let { UiText(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("pdf_error")) }
                if(!readingEnabled) UiText("已停止读取",modifier=Modifier.testTag("pdf_stopped"))
                document?.let { result -> UiText("共${result.pageCount}页，本次读取第${result.firstPage}至${result.lastPage}页",modifier=Modifier.testTag("pdf_coverage")) }
            }
        }
        document?.let { result -> items(result.pages,key={ it.number }) { page ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    UiText("第${page.number}页 · ${uiString(when(page.method) {
                        DocumentPageMethod.TEXT->"文字提取"
                        DocumentPageMethod.OCR->"扫描识别"
                        DocumentPageMethod.EMPTY->"无文字"
                    })}",style=MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(page.text,modifier=Modifier.fillMaxWidth().testTag("pdf_text_${page.number}"),style=MaterialTheme.typography.bodyMedium) }
                }
            }
        } }
    }
}

private fun scriptName(script:DocumentOcrScript)=when(script) {
    DocumentOcrScript.LATIN->"拉丁字母"
    DocumentOcrScript.CHINESE->"中文"
    DocumentOcrScript.JAPANESE->"日文"
    DocumentOcrScript.KOREAN->"韩文"
}
