package dev.solvit.git

import dev.solvit.core.GitRemoteEndpoint
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.*

/** Owns every socket in one operation, including sockets created concurrently with cancellation. */
internal class GitHttpsConnections(
    private val endpoint: GitRemoteEndpoint,
    private val checkActive: () -> Unit,
    private val fixtureTls: ((HttpsURLConnection) -> Unit)? = null,
) : HttpConnectionFactory, AutoCloseable {
    private val lock = Any()
    private val connections = mutableSetOf<HttpsURLConnection>()
    private var closed = false
    private val received = AtomicLong()
    private val sent = AtomicLong()
    @Volatile var lastStatus: Int? = null
        private set

    override fun create(url: URL): HttpConnection = create(url, Proxy.NO_PROXY)
    override fun create(url: URL, proxy: Proxy): HttpConnection {
        checkActive()
        val uri = try { url.toURI() } catch (_: Exception) { throw IOException("Git传输地址无效") }
        val root = "https://${uri.rawAuthority}${endpoint.path}"
        val suffix = uri.rawPath.removePrefix(endpoint.path + "/")
        val service = uri.rawQuery
        if (!endpoint.acceptsCredentialTarget(root) || uri.scheme != "https" ||
            suffix !in setOf("info/refs", "git-upload-pack", "git-receive-pack") ||
            uri.rawUserInfo != null || uri.rawFragment != null ||
            service != null && service !in setOf("service=git-upload-pack", "service=git-receive-pack")) {
            throw IOException("Git传输目标与已批准仓库不一致")
        }
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        fixtureTls?.invoke(connection) // Native test injection only; no plugin or configuration field.
        synchronized(lock) {
            if (closed) { connection.disconnect(); throw IOException("Git传输已取消") }
            connections += connection
        }
        return Socket(connection)
    }

    override fun close() {
        val owned = synchronized(lock) { closed = true; connections.toList().also { connections.clear() } }
        owned.forEach { it.disconnect() }
    }

    private fun bound(counter: AtomicLong, bytes: Int) {
        checkActive()
        if (bytes > 0 && counter.addAndGet(bytes.toLong()) > 128L * 1024 * 1024) {
            throw GitTransferLimitException()
        }
    }

    private inner class Socket(private val connection: HttpsURLConnection) : HttpConnection {
        override fun getResponseCode(): Int = connection.responseCode.also { lastStatus = it }
        override fun getURL(): URL = connection.url
        override fun getResponseMessage(): String = connection.responseMessage.orEmpty()
        override fun getHeaderFields(): Map<String, List<String>> = connection.headerFields
            .filterKeys { it != null }
        override fun setRequestProperty(key: String, value: String) = connection.setRequestProperty(key, value)
        override fun setRequestMethod(method: String) { connection.requestMethod = method }
        override fun setUseCaches(usecaches: Boolean) { connection.useCaches = usecaches }
        override fun setConnectTimeout(timeout: Int) { connection.connectTimeout = timeout.coerceIn(1, 30_000) }
        override fun setReadTimeout(timeout: Int) { connection.readTimeout = timeout.coerceIn(1, 30_000) }
        override fun getContentType(): String = connection.contentType.orEmpty()
        override fun getInputStream(): InputStream = object : FilterInputStream(connection.inputStream) {
            override fun read(): Int = `in`.read().also { bound(received, if (it < 0) 0 else 1) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                `in`.read(bytes, offset, length).also { bound(received, it) }
        }
        override fun getHeaderField(name: String): String? = connection.getHeaderField(name)
        override fun getHeaderFields(name: String): List<String> = connection.headerFields.entries
            .filter { it.key?.equals(name, true) == true }.flatMap { it.value.orEmpty() }
        override fun getContentLength(): Int = connection.contentLength
        override fun setInstanceFollowRedirects(followRedirects: Boolean) { connection.instanceFollowRedirects = false }
        override fun setDoOutput(dooutput: Boolean) { connection.doOutput = dooutput }
        override fun setFixedLengthStreamingMode(contentLength: Int) = connection.setFixedLengthStreamingMode(contentLength)
        override fun getOutputStream(): OutputStream = object : FilterOutputStream(connection.outputStream) {
            override fun write(value: Int) { bound(sent, 1); out.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { bound(sent, length); out.write(bytes, offset, length) }
        }
        override fun setChunkedStreamingMode(chunklen: Int) = connection.setChunkedStreamingMode(chunklen)
        override fun getRequestMethod(): String = connection.requestMethod
        override fun usingProxy(): Boolean = connection.usingProxy()
        override fun connect() { checkActive(); connection.connect() }
        override fun configure(km: Array<out KeyManager>?, tm: Array<out TrustManager>?, random: SecureRandom?) {
            throw IllegalStateException("Git传输不能替换TLS认证策略")
        }
        override fun setHostnameVerifier(hostnameverifier: HostnameVerifier) {
            throw IllegalStateException("Git传输不能关闭主机名验证")
        }
    }
}

internal class GitTransferLimitException : IOException("Git传输超过128MiB限制")
