package dev.solvit.market

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption binds each configuration to its plugin ID. */
class EncryptedPluginConfiguration(context: Context, private val keyProvider: (() -> SecretKey)? = null) {
    private val prefs = context.getSharedPreferences("extension_configuration", Context.MODE_PRIVATE)
    private val alias = "solvit.plugin-configuration.v1"
    @Synchronized private fun key(): SecretKey {
        keyProvider?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(id: String): Map<String, String> {
        val stored = prefs.getString(id, null) ?: return emptyMap()
        val parts = stored.split(':')
        check(parts.size == 2) { "插件配置格式损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        return Json.parseToJsonElement(plain).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }
    fun write(id: String, values: Map<String, String>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val data = buildJsonObject { values.forEach { (name, value) -> put(name, value) } }.toString()
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ':' + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString(id, stored).commit()) { "插件配置保存失败" }
    }
    fun remove(id: String) { check(prefs.edit().remove(id).commit()) { "插件配置移除失败" } }
}
