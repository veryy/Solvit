package dev.solvit.app

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** One inset owner: TopAppBar handles the status bar, this page handles navigation/IME. */
@Composable
fun FullScreenPage(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val light = MaterialTheme.colorScheme.surface.luminance() > .5f
        SideEffect {
            // LocalView can have intermediate parents; do not silently miss the dialog window.
            var parent = view.parent
            while (parent is View && parent !is DialogWindowProvider) parent = parent.parent
            val window = (parent as? DialogWindowProvider)?.window
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                // Older Compose Dialog implementations leave floating-window fit flags enabled.
                // On SDK 35 this produces zero Compose insets and clips content behind navigation.
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                if (Build.VERSION.SDK_INT >= 30) {
                    window.attributes = window.attributes.apply {
                        setFitInsetsTypes(0)
                        setFitInsetsSides(0)
                    }
                }
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                @Suppress("DEPRECATION")
                window.statusBarColor = Color.TRANSPARENT
                @Suppress("DEPRECATION")
                window.navigationBarColor = Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= 29) {
                    window.isStatusBarContrastEnforced = false
                    window.isNavigationBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) { content() }
        }
    }
}
