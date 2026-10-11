package dev.solvit.core

import kotlin.test.*

class ConfigurationBackupCipherTest {
    @Test fun backupCanBeRestoredWithoutOriginalDeviceKeyAndUsesFreshSalt() {
        val data = "apiKey=private;角色=工作".toByteArray()
        val password = "备份密码123456".toCharArray()
        val first = ConfigurationBackupCipher.encrypt(data, password)
        val second = ConfigurationBackupCipher.encrypt(data, password)
        assertFalse(first.contentEquals(second))
        assertContentEquals(data, ConfigurationBackupCipher.decrypt(first, password))
        assertFalse(first.toString(Charsets.UTF_8).contains("private"))
    }
    @Test fun wrongPasswordAndTamperingCannotRestoreConfiguration() {
        val password = "correct-password".toCharArray()
        val archive = ConfigurationBackupCipher.encrypt("secret".toByteArray(), password)
        assertFails { ConfigurationBackupCipher.decrypt(archive, "wrong-password".toCharArray()) }
        archive[archive.lastIndex] = (archive.last().toInt() xor 1).toByte()
        assertFails { ConfigurationBackupCipher.decrypt(archive, password) }
    }
    @Test fun malformedOversizedArchivesAndWeakPasswordsAreRejected() {
        assertFailsWith<IllegalArgumentException> { ConfigurationBackupCipher.decrypt(ByteArray(12), "password".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { ConfigurationBackupCipher.encrypt(byteArrayOf(), "short".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { ConfigurationBackupCipher.encrypt(ByteArray(ConfigurationBackupCipher.MAX_PAYLOAD + 1), "password".toCharArray()) }
    }
}
