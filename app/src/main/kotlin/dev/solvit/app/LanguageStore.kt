package dev.solvit.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UiLanguage(val code: String, val label: String)
val uiLanguages = listOf(UiLanguage("zh", "中文"), UiLanguage("en", "English"), UiLanguage("ja", "日本語"),
    UiLanguage("ko", "한국어"), UiLanguage("es", "Español"), UiLanguage("fr", "Français"))
class LanguageStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance",Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(prefs.getString("language","zh")?.takeIf { code -> uiLanguages.any { it.code == code } } ?: "zh")
    val language = mutable.asStateFlow()
    fun set(value: String) {
        require(uiLanguages.any { it.code == value })
        check(prefs.edit().putString("language",value).commit()) { "语言设置保存失败" }
        mutable.value = value
    }
}
