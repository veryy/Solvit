package dev.solvit.app

import android.app.Application
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LanguageTest {
    @Test fun defaultsToChineseAndPersistsEverySupportedLanguage() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("appearance",0).edit().remove("language").commit()
        val store = LanguageStore(context)
        assertEquals("zh", store.language.value)
        for (language in uiLanguages) {
            store.set(language.code)
            assertEquals(language.code, LanguageStore(context).language.value)
        }
        assertFailsWith<IllegalArgumentException> { store.set("unsupported") }
        assertEquals("fr", store.language.value)
        context.getSharedPreferences("appearance",0).edit().putString("language","unknown").commit()
        assertEquals("zh", LanguageStore(context).language.value)
    }
    @Test fun catalogCoversSixLanguagesAndPreservesTemplateArguments() {
        val context = RuntimeEnvironment.getApplication()
        val catalog = JSONObject(context.assets.open("ui-locales.json").bufferedReader().use { it.readText() })
        assertTrue(catalog.length() > 250)
        val placeholders = Regex("\\{\\d+\\}")
        for (key in catalog.keys()) for (language in uiLanguages) {
            val translated = catalog.getJSONObject(key).getString(language.code)
            assertTrue(translated.isNotBlank(), "$key/${language.code}")
            assertEquals(placeholders.findAll(key).map { it.value }.sorted().toList(),
                placeholders.findAll(translated).map { it.value }.sorted().toList(), "$key/${language.code}")
        }
        val strings = UiStrings.load(context)
        assertEquals("Settings",strings.text("设置","en"))
        assertEquals("設定",strings.text("设置","ja"))
        assertEquals("unrecognised user content",strings.text("unrecognised user content","en"))
        val name = "User $ / {9} 设置"
        val template = UiStrings(mapOf("删除 {0}？" to mapOf("en" to "Delete {0}?")))
        assertEquals("Delete $name?",template.text("删除 $name？","en"))
        assertEquals("设置",strings.text("设置","zh"))
    }
}
