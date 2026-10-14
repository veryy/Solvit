package dev.solvit.core

import java.net.URI
import java.util.Locale

/** Public repository address only. Credentials belong to a host-owned credential store. */
class GitRemoteEndpoint private constructor(
    val url: String,
    val host: String,
    val port: Int,
    val path: String,
) {
    /** Challenge targets must name this repository, including after an HTTP redirect. */
    fun acceptsCredentialTarget(target: String): Boolean = runCatching {
        val other = parse(target)
        other.host == host && other.port == port && other.path == path
    }.getOrDefault(false)

    override fun toString(): String = url

    companion object {
        fun parse(value: String): GitRemoteEndpoint {
            // Never attach URI's input-bearing exception to a public diagnostic.
            val uri = try { URI(value) } catch (_: Exception) {
                throw IllegalArgumentException("Git仓库地址无效")
            }
            require(value.length in 1..2048 && value.none { it.isISOControl() || it.isWhitespace() }) {
                "Git仓库地址无效"
            }
            require(uri.scheme.equals("https", true) && uri.host != null && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null) { "Git仓库需要不含凭据的HTTPS地址" }
            val host = uri.host.lowercase(Locale.ROOT)
            require(host.all { it.isLetterOrDigit() || it in ".-" } && !host.endsWith('.')) { "Git仓库主机无效" }
            val port = if (uri.port == -1) 443 else uri.port
            require(port in 1..65535) { "Git仓库端口无效" }
            val path = uri.rawPath.orEmpty().trimEnd('/')
            require(path.startsWith('/') && path.length > 1 &&
                path.all { it.isLetterOrDigit() && it.code < 128 || it in "/._-" } &&
                path.split('/').drop(1).all { it.isNotEmpty() && it !in setOf(".", "..") }) {
                "Git仓库路径无效"
            }
            val authority = host + if (port == 443) "" else ":$port"
            return GitRemoteEndpoint("https://$authority$path", host, port, path)
        }
    }
}
