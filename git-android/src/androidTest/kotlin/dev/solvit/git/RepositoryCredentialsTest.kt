package dev.solvit.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.solvit.core.GitRemoteEndpoint
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the actual bundled JGit challenge types, with no real GitHub secret. */
@RunWith(AndroidJUnit4::class)
class RepositoryCredentialsTest {
    @Test fun realJgitChallengeOnlyReceivesThisRepositoryCredentialsAndCloseRevokesIssuedCopies() {
        val supplied = "fixture-secret".toCharArray()
        val credentials = RepositoryCredentials(GitRemoteEndpoint.parse("https://github.com/veryy/Solvit.git"), "fixture-user", supplied)
        supplied.fill('x')
        val username = CredentialItem.Username()
        val password = CredentialItem.Password()
        assertTrue(credentials.get(URIish("https://github.com/veryy/Solvit.git"), username, password))
        assertEquals("fixture-user", username.value)
        val issued = password.value!!
        assertArrayEquals("fixture-secret".toCharArray(), issued)
        assertFalse(credentials.toString().contains("fixture-secret"))
        credentials.close()
        assertTrue(issued.all { it == '\u0000' })
        assertNull(password.value)
        assertFalse(credentials.get(URIish("https://github.com/veryy/Solvit.git"), username, password))
        credentials.close()
    }

    @Test fun redirectsOtherRepositoriesAndTlsBypassPromptsReceiveNoCredentials() {
        RepositoryCredentials(GitRemoteEndpoint.parse("https://github.com/veryy/Solvit.git"), "fixture-user", "fixture-secret".toCharArray()).use { credentials ->
            for (target in listOf("https://elsewhere.example/veryy/Solvit.git", "https://github.com/other/Solvit.git",
                "http://github.com/veryy/Solvit.git", "https://github.com:444/veryy/Solvit.git",
                "https://user:password@github.com/veryy/Solvit.git")) {
                val username = CredentialItem.Username(); val password = CredentialItem.Password()
                assertFalse(credentials.get(URIish(target), username, password))
                assertNull(username.value); assertNull(password.value)
            }
            assertFalse(credentials.isInteractive())
            assertFalse(credentials.supports(CredentialItem.YesNoType("Disable TLS verification?")))
            val username = CredentialItem.Username()
            assertFalse(credentials.get(URIish("https://github.com/veryy/Solvit.git"), username,
                CredentialItem.YesNoType("Disable TLS verification?")))
            assertNull(username.value)
        }
    }
}
