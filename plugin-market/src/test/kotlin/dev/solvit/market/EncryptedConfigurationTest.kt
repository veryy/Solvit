package dev.solvit.market

import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EncryptedConfigurationTest {
    @Test fun encryptsValuesAndRejectsCrossPluginCiphertextSwaps() {
        val context = RuntimeEnvironment.getApplication()
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val store = EncryptedPluginConfiguration(context) { key }
        val values = mapOf("url" to "https://example.com/mcp", "token" to "secret-token")
        store.write("test.first", values)
        assertEquals(values, EncryptedPluginConfiguration(context) { key }.read("test.first"))
        val prefs = context.getSharedPreferences("extension_configuration", Context.MODE_PRIVATE)
        val cipher = prefs.getString("test.first", null)!!
        assertFalse(cipher.contains("secret-token")); assertFalse(cipher.contains("example.com"))
        prefs.edit().putString("test.second", cipher).commit()
        assertFailsWith<Exception> { store.read("test.second") }
        prefs.edit().putString("test.first", "invalid").commit()
        assertFailsWith<IllegalStateException> { store.read("test.first") }
        store.remove("test.first")
        assertTrue(store.read("test.first").isEmpty())
    }
}
