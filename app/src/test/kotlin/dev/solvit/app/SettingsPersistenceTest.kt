package dev.solvit.app

import android.app.Application
import dev.solvit.core.ModelConfig
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SettingsPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Before fun reset() { context.getSharedPreferences("model", 0).edit().clear().commit() }
    @Test fun recreationKeepsModelPromptAndEncryptedApiKey() = runBlocking<Unit> {
        val secret = key()
        val config = ModelConfig("https://api.example.com/v1", "configured-model", "PRIVATE_API_KEY", "原始提示词")
        EncryptedSettingsStore(context) { secret }.save(config)
        val loaded = EncryptedSettingsStore(context) { secret }.load()
        assertEquals(config.baseUrl, loaded.baseUrl); assertEquals(config.model, loaded.model)
        assertEquals(config.apiKey, loaded.apiKey); assertEquals(config.systemPrompt, loaded.systemPrompt)
        val prefs = context.getSharedPreferences("model", 0)
        assertFalse(prefs.all.values.any { it.toString().contains(config.apiKey) })
    }
    @Test fun missingDeviceKeyPreservesOtherFieldsAndDoesNotResetPersistence() = runBlocking<Unit> {
        val old = key(); val replacement = key()
        EncryptedSettingsStore(context) { old }.save(ModelConfig("https://custom.example/v1", "my-model", "private", "my-prompt"))
        val before = context.getSharedPreferences("model", 0).all
        val failure = assertFailsWith<SettingsDecryptionException> { EncryptedSettingsStore(context) { replacement }.load() }
        assertEquals("https://custom.example/v1", failure.partial.baseUrl)
        assertEquals("my-model", failure.partial.model); assertEquals("my-prompt", failure.partial.systemPrompt)
        assertEquals("", failure.partial.apiKey)
        assertEquals(before, context.getSharedPreferences("model", 0).all)
        assertEquals("private", EncryptedSettingsStore(context) { old }.load().apiKey)
    }
    @Test fun appearanceChoicesSurviveRecreation() {
        val first = AppearanceStore(context)
        first.setTheme("dark"); first.setDynamicColors(false)
        val second = AppearanceStore(context)
        assertEquals("dark", second.theme.value); assertFalse(second.dynamicColors.value)
        assertFailsWith<IllegalArgumentException> { second.setTheme("unknown") }
        assertEquals("dark", AppearanceStore(context).theme.value)
    }
}
