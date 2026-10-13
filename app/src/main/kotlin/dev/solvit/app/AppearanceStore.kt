package dev.solvit.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppearanceStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutableTheme = MutableStateFlow(prefs.getString("theme", "system") ?: "system")
    private val mutableDynamic = MutableStateFlow(prefs.getBoolean("dynamic", true))
    private val mutableChat = MutableStateFlow(ChatAppearance(prefs.getFloat("chat_font_size",16f),
        prefs.getInt("reply_speed",0),prefs.getString("chat_style","bubble") ?: "bubble").also { it.validate() })
    val chat = mutableChat.asStateFlow()
    fun setChat(value: ChatAppearance) {
        value.validate()
        check(prefs.edit().putFloat("chat_font_size",value.fontSize).putInt("reply_speed",value.replySpeed).putString("chat_style",value.style).commit())
        mutableChat.value=value
    }
    val theme = mutableTheme.asStateFlow()
    val dynamicColors = mutableDynamic.asStateFlow()
    fun setTheme(value: String) { require(value in setOf("system", "light", "dark"));
        check(prefs.edit().putString("theme", value).commit()); mutableTheme.value = value }
    fun setDynamicColors(value: Boolean) { check(prefs.edit().putBoolean("dynamic", value).commit()); mutableDynamic.value = value }
}
