package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Keep the next useful action available before adding decorative empty-state content. */
@Composable
internal fun ChatEmptyState(modelConfigured: Boolean, hasWorkspace: Boolean, ready: Boolean,
    onConfigureModel: () -> Unit, onCreateWorkspace: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val expanded = maxHeight >= 260.dp * fontScale
        val showTitle = maxHeight >= 144.dp * fontScale
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (expanded) Icon(painterResource(R.drawable.ic_solvit_monochrome), null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(80.dp))
            if (showTitle) UiText("从一个想法开始",
                style = if (expanded) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center)
            if (expanded) UiText(if (modelConfigured) "一起整理思路、解答问题" else "先连接模型，再开始聊天",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
            if (!modelConfigured) Button(onClick = onConfigureModel, modifier = Modifier.testTag("chat_model_setup")) {
                UiText("配置你的模型")
            }
            if (!hasWorkspace && (modelConfigured || expanded)) {
                if (modelConfigured) OutlinedButton(onClick = onCreateWorkspace, enabled = ready) { UiText("新建工作区") }
                else TextButton(onClick = onCreateWorkspace, enabled = ready) { UiText("新建工作区") }
            }
        }
    }
}
