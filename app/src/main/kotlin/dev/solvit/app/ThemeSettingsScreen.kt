package dev.solvit.app

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Detailed controls belong below the settings index; existing preferences remain authoritative. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeSettingsScreen(store: AppearanceStore, onDismiss: () -> Unit) {
    val theme by store.theme.collectAsStateWithLifecycle()
    val dynamic by store.dynamicColors.collectAsStateWithLifecycle()
    FullScreenPage(onDismissRequest = onDismiss) {
        Scaffold(topBar = {
            TopAppBar(title = { UiText("外观") }, navigationIcon = {
                IconButton(onClick = onDismiss) { UiIcon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置") }
            })
        }) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("theme_settings"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        UiText("主题", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Card(Modifier.fillMaxWidth()) {
                            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) ->
                                Row(
                                    Modifier.fillMaxWidth().testTag("theme_$key")
                                        .selectable(selected = theme == key, role = Role.RadioButton, onClick = { store.setTheme(key) })
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    UiText(label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
                                    RadioButton(selected = theme == key, onClick = null)
                                }
                            }
                        }
                    }
                }
                item {
                    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().testTag("theme_dynamic")
                                .toggleable(value = dynamic, enabled = supported, role = Role.Switch, onValueChange = store::setDynamicColors)
                                .padding(16.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                UiText("动态配色", style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(4.dp))
                                UiText("Android 12 及以上使用壁纸配色", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = dynamic, onCheckedChange = null, enabled = supported)
                        }
                    }
                }
            }
        }
    }
}
