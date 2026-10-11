package dev.solvit.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.ModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*

/** Invoked explicitly on the public 0.7.0 APK before installing the new build. */
class SeedUpgradeConfigurationTest {
    @Test fun seedActualOldRelease() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        assertEquals("0.7.0", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        // This runs inside the actual old APK. New protocol fields change Kotlin's
        // generated constructor signature; use the old release's six-argument API.
        val oldConfig = ModelConfig::class.java.getConstructor(String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!)
            .newInstance("https://fixture.example/v1", "upgrade-fixture-model", "solvit-upgrade-fixture-key",
                "升级保留提示词", "https://fixture.example/models?token=fixture-token", false)
        EncryptedSettingsStore(context).save(oldConfig)
        val app = context.applicationContext as SolvitApplication
        val chat = withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() }
        val session = chat.createSession()
        check(context.getSharedPreferences("upgrade_fixture", 0).edit()
            .putString("seededVersion", "0.7.0").putString("sessionId", session.id).commit())
    }
}
class UpgradePersistenceTest {
    @Test fun encryptedConfigurationAndConversationSurviveInPlaceUpgrade() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = context.getSharedPreferences("upgrade_fixture", 0)
        assertEquals("0.7.0", marker.getString("seededVersion", null))
        @Suppress("DEPRECATION")
        assertEquals(BuildConfig.VERSION_NAME, context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val config = EncryptedSettingsStore(context).load()
        assertEquals("https://fixture.example/v1", config.baseUrl)
        assertEquals("upgrade-fixture-model", config.model)
        assertEquals("solvit-upgrade-fixture-key", config.apiKey)
        assertEquals("升级保留提示词", config.systemPrompt)
        assertEquals("https://fixture.example/models?token=fixture-token", config.modelListUrl)
        val app = context.applicationContext as SolvitApplication
        val chat = withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() }
        assertTrue(chat.sessions().first().any { it.id == marker.getString("sessionId", null) })
    }
}
