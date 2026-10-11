package dev.solvit.app

import android.app.Application
import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ModelProfilesTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("model",0).edit().clear().commit() }
    @Test fun contextBudgetSurvivesEncryptedProfilesBackupAndLegacyDefaults() = runBlocking {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val budget = ContextBudgetPolicy(32_768, 4_096, 1_024, false)
        val settings = EncryptedSettingsStore(context) { key }
        settings.save(ModelConfig(contextBudget = budget))
        assertEquals(budget, EncryptedSettingsStore(context) { key }.load().contextBudget)
        val backup = encodeModelProfiles(settings.loadProfiles())
        assertEquals(budget, decodeModelProfiles(backup).selected(ModelTask.CHAT)!!.config.contextBudget)
        backup.getJSONArray("profiles").getJSONObject(0).getJSONObject("config").remove("context")
        assertEquals(ContextBudgetPolicy(), decodeModelProfiles(backup).selected(ModelTask.CHAT)!!.config.contextBudget)
        val legacy = backup.getJSONArray("profiles").getJSONObject(0).getJSONObject("config")
        legacy.put("options", org.json.JSONObject().put("max_tokens", "1000000"))
        val migrated = decodeModelProfiles(backup).selected(ModelTask.CHAT)!!.config
        assertEquals(1_000_000, migrated.contextBudget.outputTokens)
        assertNull(migrated.validate())
    }
    @Test fun nativeProtocolOptionsSurviveEncryptedSettingsAndBackupWithLegacyDefault()=runBlocking {
        val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val config=ModelConfig("https://api.anthropic.com/v1","native-model","PRIVATE_KEY",providerId="anthropic",
            providerOptions=mapOf("thinking_mode" to "adaptive","max_tokens" to "16384"))
        val settings=EncryptedSettingsStore(context) { key }
        settings.save(config)
        val loaded=settings.load()
        assertEquals("anthropic",loaded.providerId);assertEquals(config.providerOptions,loaded.providerOptions)
        val backup=encodeModelProfiles(settings.loadProfiles())
        val restored=decodeModelProfiles(backup).selected(ModelTask.CHAT)!!.config
        assertEquals(config.providerOptions,restored.providerOptions);assertEquals("anthropic",restored.providerId)
        val legacy=backup.getJSONArray("profiles").getJSONObject(0).getJSONObject("config")
        legacy.remove("provider");legacy.remove("options")
        assertEquals("openai-compatible",decodeModelProfiles(backup).selected(ModelTask.CHAT)!!.config.providerId)
        assertFalse(context.getSharedPreferences("model",0).all.values.any { it.toString().contains("PRIVATE_KEY") })
    }
    @Test fun legacyConfigurationMigratesAndTwoTaskRoutesSurviveRecreationWithoutPlaintextSecrets()=runBlocking {
        val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val settings=EncryptedSettingsStore(context) { key }
        settings.save(ModelConfig("https://chat.example/v1","chat-model","CHAT_SECRET","original prompt"))
        // Recreate a legacy-only installation using the compatible encrypted primary fields.
        context.getSharedPreferences("model",0).edit().remove("profilesCipher").remove("profilesIv").commit()
        val service=SettingsModelProfiles(settings);service.initialize()
        assertEquals("chat-model",service.state.value.selected(ModelTask.CHAT)!!.config.model)
        val follow=ModelProfile("tools","Tool model",ModelConfig("https://tools.example/v1","tool-model","TOOL_SECRET","tool prompt","https://list.example/models?token=LIST_SECRET"))
        service.save(follow);service.assign(ModelTask.TOOL_FOLLOW_UP,follow.id)
        val recreated=SettingsModelProfiles(EncryptedSettingsStore(context) { key });recreated.initialize()
        assertEquals(2,recreated.state.value.profiles.size)
        assertEquals("TOOL_SECRET",recreated.resolve(ModelTask.TOOL_FOLLOW_UP,ModelConfig()).apiKey)
        assertEquals("chat-model",settings.load().model)
        assertFailsWith<IllegalArgumentException> { recreated.delete("tools") }
        recreated.assign(ModelTask.TOOL_FOLLOW_UP,null);recreated.delete("tools")
        assertEquals(1,recreated.state.value.profiles.size)
        assertFalse(context.getSharedPreferences("model",0).all.values.any { Regex("CHAT_SECRET|TOOL_SECRET|LIST_SECRET").containsMatchIn(it.toString()) })
    }
    @Test fun firstSavedProfileBecomesChatAndInvalidChangesNeverOverwriteExistingData()=runBlocking {
        val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store=EncryptedSettingsStore(context) { key };val service=SettingsModelProfiles(store);service.initialize()
        assertFalse(service.state.value.configured)
        service.save(ModelProfile("first","Local",ModelConfig("http://127.0.0.1:8080/v1","local","")))
        assertEquals("first",service.state.value.routes[ModelTask.CHAT])
        val before=context.getSharedPreferences("model",0).all
        assertFailsWith<IllegalArgumentException> { service.assign(ModelTask.CHAT,"missing") }
        assertFailsWith<IllegalArgumentException> { service.delete("first") }
        assertEquals(before,context.getSharedPreferences("model",0).all)
        val snapshot=service.state.value
        val restored=decodeModelProfiles(encodeModelProfiles(snapshot))
        assertEquals(snapshot.routes,restored.routes)
        assertEquals("http://127.0.0.1:8080/v1",restored.profiles.single().config.baseUrl)
        assertEquals("Local",restored.profiles.single().name)
    }
    @Test fun lostKeyKeepsCiphertextAndBlocksSilentDestructiveReplacement()=runBlocking {
        fun key()=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val original=key();val replacement=key()
        EncryptedSettingsStore(context) { original }.save(ModelConfig("https://custom.example/v1","kept","secret","kept prompt"))
        val before=context.getSharedPreferences("model",0).all
        val broken=SettingsModelProfiles(EncryptedSettingsStore(context) { replacement });broken.initialize()
        assertNotNull(broken.loadError.value)
        assertFailsWith<IllegalStateException> { broken.save(ModelProfile("new","New",ModelConfig())) }
        assertEquals(before,context.getSharedPreferences("model",0).all)
        assertEquals("secret",EncryptedSettingsStore(context) { original }.load().apiKey)
    }
}
