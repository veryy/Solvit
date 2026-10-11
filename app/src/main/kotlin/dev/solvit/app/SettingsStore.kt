package dev.solvit.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.solvit.core.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SettingsStore {
    suspend fun load(): ModelConfig
    suspend fun save(config: ModelConfig)
}

/** Keys stay in Android Keystore; no plaintext API key or cloud backup. */
class EncryptedSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("model", Context.MODE_PRIVATE)
    private val alias = "solvit.model-key.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override suspend fun load(): ModelConfig = withContext(Dispatchers.IO) {
        val encoded = prefs.getString("key", null)
        val apiKey = if (encoded == null) "" else {
            val iv = Base64.decode(prefs.getString("iv", null) ?: error("Missing encryption IV"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
        }
        ModelConfig(
            prefs.getString("base", "https://api.openai.com/v1")!!,
            prefs.getString("model", "gpt-4o-mini")!!,
            apiKey,
            prefs.getString("prompt", "你是 Solvit，一个认真、可靠的 AI 助手。")!!,
        )
    }
    override suspend fun save(config: ModelConfig) = withContext(Dispatchers.IO) {
        config.validate()?.let { error(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.doFinal(config.apiKey.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("base", config.baseUrl.trimEnd('/')).putString("model", config.model)
            .putString("prompt", config.systemPrompt).putString("key", encoded)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) { "保存配置失败" }
    }
}
