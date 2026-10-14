package dev.solvit.git

import dev.solvit.core.GitRemoteEndpoint
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish

/** One host-authorized transport's credentials; never a global JGit provider. */
internal class RepositoryCredentials(
    private val endpoint: GitRemoteEndpoint,
    private val username: String,
    password: CharArray,
) : CredentialsProvider(), AutoCloseable {
    private val secret = password.copyOf()
    private val issued = mutableListOf<CredentialItem.Password>()
    private var closed = false

    init {
        require(username.isNotBlank() && username.length <= 256 && username.none(Char::isISOControl)) {
            "Git认证用户名无效"
        }
        require(secret.isNotEmpty() && secret.size <= 4096 && secret.none(Char::isISOControl)) {
            "Git认证凭据无效"
        }
    }

    override fun isInteractive() = false
    override fun supports(vararg items: CredentialItem): Boolean = items.all {
        it is CredentialItem.Username || it is CredentialItem.Password
    }

    @Synchronized override fun get(uri: URIish, vararg items: CredentialItem): Boolean {
        if (closed || !endpoint.acceptsCredentialTarget(uri.toPrivateString()) || !supports(*items)) return false
        items.forEach { item ->
            when (item) {
                is CredentialItem.Username -> item.value = username
                is CredentialItem.Password -> { item.value = secret; issued += item }
            }
        }
        return true
    }

    @Synchronized override fun close() {
        closed = true
        secret.fill('\u0000')
        issued.forEach { it.clear() }
        issued.clear()
    }

    override fun toString() = "RepositoryCredentials([redacted])"
}
