package dev.solvit.git

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.GitRemoteEndpoint
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class GitCredentialVaultTest {
    @Test fun boundCredentialWritesRequireLiveWorkspaceAndNetworkAuthorityAndSurviveServiceReopen() = kotlinx.coroutines.runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val workspaces = dev.solvit.workspace.AndroidWorkspaces(context, scope)
        val chat = "credential-bound-${UUID.randomUUID()}"
        val root = java.io.File(workspaces.location(workspaces.forChat(chat).id))
        val endpoint = GitRemoteEndpoint.parse("https://github.com/fixture/${UUID.randomUUID()}.git")
        var active: String? = chat
        var network = false
        val service = AndroidWorkspaceGit(workspaces, context)
        val page = service.bindRemote(chat, { active }, {}, {}, { check(network) })
        val secret = "bound-fixture-secret".toCharArray()
        var reopened: AndroidWorkspaceGit? = null
        try {
            assertFalse(page.credentialsConfigured(endpoint))
            try { page.saveCredentials(endpoint, "fixture-user", secret); fail("Missing network permission") }
            catch (_: IllegalStateException) { }
            assertFalse(page.credentialsConfigured(endpoint))
            network = true
            page.saveCredentials(endpoint, "fixture-user", secret)
            assertTrue(page.credentialsConfigured(endpoint))
            service.close()
            reopened = AndroidWorkspaceGit(workspaces, context)
            val next = reopened.bindRemote(chat, { active }, {}, {}, {})
            assertTrue(next.credentialsConfigured(endpoint))
            active = "other-chat"
            try { next.removeCredentials(endpoint); fail("Changed chat must reject credential removal") }
            catch (_: IllegalStateException) { }
            assertTrue(GitCredentialVault(context).configured(endpoint))
            active = chat
            next.removeCredentials(endpoint)
            assertFalse(next.credentialsConfigured(endpoint))
            try { page.saveCredentials(endpoint, "fixture-user", secret); fail("Closed service must reject writes") }
            catch (_: IllegalStateException) { }
        } finally {
            secret.fill('\u0000'); GitCredentialVault(context).remove(endpoint)
            service.close(); reopened?.close(); workspaces.unbind(chat)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); root.deleteRecursively()
        }
    }

    @Test fun ciphertextCopiedToAnotherRepoFailsAuthenticationWithoutReturningSecrets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = GitRemoteEndpoint.parse("https://github.com/fixture/${UUID.randomUUID()}.git")
        val second = GitRemoteEndpoint.parse("https://github.com/fixture/${UUID.randomUUID()}.git")
        val vault = GitCredentialVault(context)
        val prefs = context.getSharedPreferences("git_credentials", Context.MODE_PRIVATE)
        fun id(endpoint: GitRemoteEndpoint) = MessageDigest.getInstance("SHA-256")
            .digest(endpoint.url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        try {
            vault.save(first, "fixture-user", "private-fixture-secret".toCharArray())
            val ciphertext = prefs.getString(id(first), null)!!
            assertTrue(prefs.edit().putString(id(second), ciphertext).commit())
            try { vault.borrow(second); fail("Another repository must not decrypt this credential") }
            catch (failure: IllegalStateException) {
                assertFalse(failure.toString().contains("private-fixture-secret"))
                assertFalse(failure.toString().contains(ciphertext)); assertNull(failure.cause)
            }
            vault.borrow(first)!!.close()
        } finally { vault.remove(first); vault.remove(second) }
    }

    @Test fun actualKeystoreReopensEncryptedRepoCredentialsAndRemovalKeepsOtherRepo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val endpoint = GitRemoteEndpoint.parse("https://github.com/fixture/${UUID.randomUUID()}.git")
        val other = GitRemoteEndpoint.parse("https://github.com/fixture/${UUID.randomUUID()}.git")
        val vault = GitCredentialVault(context)
        try {
            val secret = "private-fixture-secret".toCharArray()
            vault.save(endpoint, "fixture-user", secret); secret.fill('x')
            vault.save(other, "other-user", "other-fixture-secret".toCharArray())
            val stored = context.getSharedPreferences("git_credentials", Context.MODE_PRIVATE).all.toString()
            assertFalse(stored.contains("private-fixture-secret")); assertFalse(stored.contains("fixture-user"))
            assertFalse(stored.contains(endpoint.url))
            val reopened = GitCredentialVault(context)
            assertTrue(reopened.configured(endpoint))
            reopened.borrow(endpoint)!!.use { provider ->
                val username = CredentialItem.Username(); val password = CredentialItem.Password()
                assertTrue(provider.get(URIish(endpoint.url), username, password))
                assertEquals("fixture-user", username.value)
                assertArrayEquals("private-fixture-secret".toCharArray(), password.value)
                assertFalse(provider.get(URIish(other.url), CredentialItem.Password()))
            }
            reopened.remove(endpoint)
            assertFalse(vault.configured(endpoint)); assertNull(vault.borrow(endpoint))
            assertTrue(vault.configured(other)); vault.borrow(other)!!.close()
        } finally { vault.remove(endpoint); vault.remove(other) }
    }
}
