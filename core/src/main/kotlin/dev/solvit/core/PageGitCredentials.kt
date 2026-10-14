package dev.solvit.core

enum class GitCredentialOutcome { SAVED, REMOVED, CANCELLED }

/** The host form owns secret input. Scripts receive only the outcome, never these callbacks or fields. */
interface PageGitCredentialPrompt {
    suspend fun edit(endpoint: GitRemoteEndpoint, configured: Boolean, authorize: suspend () -> Unit,
        save: suspend (username: String, secret: CharArray) -> Unit,
        remove: suspend () -> Unit): GitCredentialOutcome
}
