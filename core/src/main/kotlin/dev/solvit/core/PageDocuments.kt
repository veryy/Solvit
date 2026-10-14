package dev.solvit.core

/** Metadata owned by one displayed human page control, never arbitrary paths or URIs. */
sealed interface PageDocumentControl {
    data class Open(val mimeTypes: List<String>, val maxBytes: Int) : PageDocumentControl {
        init {
            require(mimeTypes.size in 1..8 && mimeTypes.distinct().size == mimeTypes.size)
            require(mimeTypes.all { mime(it, true) }); require(maxBytes in 1..MAX_BYTES)
        }
    }
    data class Save(val name: String, val mimeType: String, val maxBytes: Int) : PageDocumentControl {
        init {
            require(name.length in 1..128 && name == name.trim() && name !in setOf(".", ".."))
            require(name.none { it in "/\\:" || it.code < 32 || it.code == 127 })
            require(mime(mimeType, false)); require(maxBytes in 1..MAX_BYTES)
        }
    }
    companion object {
        const val MAX_BYTES = 512 * 1024
        private fun mime(value: String, wildcards: Boolean): Boolean {
            if (wildcards && value == "*/*") return true
            val parts = value.split('/')
            val token = Regex("[a-z0-9][a-z0-9!#$&^_.+-]{0,63}")
            return parts.size == 2 && token.matches(parts[0]) &&
                (token.matches(parts[1]) || wildcards && parts[1] == "*")
        }
    }
}

/** The host exposes only the chosen text document, without URI or reusable filesystem access. */
data class PageTextDocument(val name: String, val text: String? = null, val files: Map<String, String>? = null)

interface PageDocumentAccess {
    /** User-selected binary file, bounded to 20MiB. The caller owns and closes the returned document. */
    suspend fun selectFile(authorize: suspend () -> Unit): PageBinaryDocument? { error("此页面没有文件导入选择器") }
    /** A null result is an ordinary user cancellation. Recheck authorization before any I/O. */
    suspend fun open(control: PageDocumentControl.Open, authorize: suspend () -> Unit): PageTextDocument?
    /** Save only to a location selected by the user; no automatic overwrite or remembered grants. */
    suspend fun save(control: PageDocumentControl.Save, text: String, authorize: suspend () -> Unit): Boolean
}

/** A selected file owned by one human page. URI and host path never leave the picker. */
class PageBinaryDocument(val name: String, val mimeType: String, bytes: ByteArray) : AutoCloseable {
    private val content: ByteArray
    val sizeBytes: Long = bytes.size.toLong()
    val sha256: String
    private var closed = false
    init {
        require(name.length in 1..256 && name.none(Char::isISOControl)) { "选择的文件名称无效" }
        require(mimeType.matches(Regex("[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}"))) { "选择的文件类型无效" }
        require(bytes.size <= MAX_WORKSPACE_IMPORT_BYTES) { "选择的文件超过20MiB导入限制" }
        content=bytes.copyOf()
        sha256=java.security.MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @Synchronized fun readBytes(): ByteArray { check(!closed) { "选择的文件已失效，请重新选择" };return content.copyOf() }
    /** Preview only. The import always retains the complete original binary content. */
    @Synchronized fun textPreview(): PageImportTextPreview {
        check(!closed) { "选择的文件已失效，请重新选择" }
        if (content.size > MAX_FILE_CAPTURE_BYTES) return PageImportTextPreview("OVERSIZED")
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(content)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { return PageImportTextPreview("BINARY") }
        if ('\u0000' in decoded) return PageImportTextPreview("BINARY")
        var end = minOf(decoded.length, 8192)
        if (end < decoded.length && end > 0 && decoded[end - 1].isHighSurrogate()) end--
        return PageImportTextPreview("TEXT", decoded.substring(0, end), decoded.length > end)
    }
    @Synchronized override fun close() { closed=true;content.fill(0) }
    override fun toString()="PageBinaryDocument(bytes=$sizeBytes, content=<redacted>)"
}

data class PageImportTextPreview(val kind: String, val text: String? = null, val truncated: Boolean = false) {
    override fun toString() = "PageImportTextPreview(kind=$kind, truncated=$truncated, text=<redacted>)"
}
