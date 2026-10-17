package dev.solvit.app

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import androidx.core.view.WindowCompat

/** Each window follows its own rendered surface, including manual app themes. */
@Suppress("DEPRECATION")
internal fun applySystemBarAppearance(window: Window, view: View, lightSurface: Boolean) {
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 29) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = lightSurface
        isAppearanceLightNavigationBars = lightSurface
    }
}
