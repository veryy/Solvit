package dev.solvit.core

import kotlin.test.*

class GitRemoteEndpointTest {
    @Test fun normalizedRepositoryStillRestrictsCredentialsToTheSameRepoAndTlsPort() {
        val endpoint = GitRemoteEndpoint.parse("HTTPS://GitHub.com:443/veryy/Solvit.git/")
        assertEquals("https://github.com/veryy/Solvit.git", endpoint.url)
        assertTrue(endpoint.acceptsCredentialTarget("https://github.com/veryy/Solvit.git"))
        for (target in listOf("http://github.com/veryy/Solvit.git", "https://github.com:444/veryy/Solvit.git",
            "https://other.example/veryy/Solvit.git", "https://github.com/other/Solvit.git",
            "https://github.com/veryy/Solvit.git/info/refs", "https://github.com/veryy/Solvit.git?token=secret")) {
            assertFalse(endpoint.acceptsCredentialTarget(target), "Must not authorize $target")
        }
    }

    @Test fun secretBearingAndAmbiguousAddressesCannotReachTransportOrDiagnostics() {
        for (value in listOf("https://user:private-secret@github.com/veryy/Solvit.git",
            "https://github.com/veryy/Solvit.git?token=private-secret",
            "https://github.com/veryy/Solvit.git#private-secret", "not a URI private-secret",
            "file:///tmp/repository", "ssh://git@github.com/veryy/Solvit.git",
            "https://github.com/a/../b", "https://github.com/a/%2e%2e/b",
            "https://github.com/a//b", "https://github.com./a/b")) {
            val failure = assertFailsWith<IllegalArgumentException> { GitRemoteEndpoint.parse(value) }
            assertFalse(failure.toString().contains("private-secret"))
            assertNull(failure.cause)
        }
    }
}
