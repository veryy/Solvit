package dev.solvit.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable password encryption; never depends on the device's deleted Keystore. */
object ConfigurationBackupCipher {
    const val MAX_PAYLOAD = 2 * 1024 * 1024
    private val magic = "SOLVIT01".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 8 + 16 + 12
    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "配置备份过大" }
        validatePassword(password)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        return magic + salt + iv + crypt(Cipher.ENCRYPT_MODE, payload, password, salt, iv)
    }
    fun decrypt(archive: ByteArray, password: CharArray): ByteArray {
        validatePassword(password)
        require(archive.size in HEADER + 16..HEADER + 16 + MAX_PAYLOAD && archive.copyOfRange(0, 8).contentEquals(magic)) { "配置备份格式不正确" }
        return crypt(Cipher.DECRYPT_MODE, archive.copyOfRange(HEADER, archive.size), password,
            archive.copyOfRange(8, 24), archive.copyOfRange(24, HEADER))
    }
    private fun validatePassword(password: CharArray) { require(password.size in 8..1024) { "备份密码至少 8 位，最多 1024 位" } }
    private fun crypt(mode: Int, data: ByteArray, password: CharArray, salt: ByteArray, iv: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(magic)
            cipher.doFinal(data)
        } finally { key.fill(0) }
    }
}
