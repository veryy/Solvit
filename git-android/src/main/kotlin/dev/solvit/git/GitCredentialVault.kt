package dev.solvit.git

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.solvit.core.GitRemoteEndpoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.CharBuffer
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Native-only encrypted credentials. Neither JS nor model tools receive decrypted fields. */
internal class GitCredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("git_credentials", Context.MODE_PRIVATE)
    private val alias = "solvit.git-credentials.v1"

    private fun id(endpoint: GitRemoteEndpoint): String = MessageDigest.getInstance("SHA-256")
        .digest(endpoint.url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun configured(endpoint: GitRemoteEndpoint): Boolean = prefs.contains(id(endpoint))

    /** The caller still owns its input char array and must clear it when its UI closes. */
    @Synchronized fun save(endpoint: GitRemoteEndpoint, username: String, password: CharArray) {
        RepositoryCredentials(endpoint, username, password).close() // Same validation as the transport.
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        if (encoded.hasArray()) encoded.array().fill(0)
        var plain: ByteArray? = null
        try {
            val buffer = object : ByteArrayOutputStream() { fun erase() { buf.fill(0); reset() } }
            plain = try {
                DataOutputStream(buffer).apply {
                    writeUTF(username); writeInt(bytes.size); write(bytes); flush()
                }
                buffer.toByteArray()
            } finally { buffer.erase() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(endpoint.url.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(plain)
            val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            check(prefs.edit().putString(id(endpoint), stored).commit()) { "Git认证配置保存失败" }
        } finally { bytes.fill(0); plain?.fill(0) }
    }

    /** A separate provider is issued per operation and must be closed by that operation. */
    @Synchronized fun borrow(endpoint: GitRemoteEndpoint): RepositoryCredentials? {
        val stored = prefs.getString(id(endpoint), null) ?: return null
        var plain: ByteArray? = null
        var secretBytes: ByteArray? = null
        var secret: CharArray? = null
        try {
            require(stored.length <= 40_000)
            val parts = stored.split(':'); require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP); require(iv.size == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.updateAAD(endpoint.url.toByteArray(Charsets.UTF_8))
            plain = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
            return DataInputStream(ByteArrayInputStream(plain)).use { input ->
                val username = input.readUTF()
                val length = input.readInt(); require(length in 1..16_384)
                val bytes = ByteArray(length); secretBytes = bytes; input.readFully(bytes)
                require(input.read() == -1)
                val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
                val chars = CharArray(decoded.remaining()).also { decoded.get(it) }; secret = chars
                if (decoded.hasArray()) decoded.array().fill('\u0000')
                RepositoryCredentials(endpoint, username, chars)
            }
        } catch (_: Exception) {
            // No ciphertext, credential or input-bearing nested exception enters a UI error.
            throw IllegalStateException("Git认证配置无法读取，请重新配置")
        } finally { plain?.fill(0); secretBytes?.fill(0); secret?.fill('\u0000') }
    }

    @Synchronized fun remove(endpoint: GitRemoteEndpoint) {
        check(prefs.edit().remove(id(endpoint)).commit()) { "Git认证配置移除失败" }
    }

    companion object { private val keyLock = Any() }
}
