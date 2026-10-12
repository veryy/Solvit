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
    /** A null result is an ordinary user cancellation. Recheck authorization before any I/O. */
    suspend fun open(control: PageDocumentControl.Open, authorize: suspend () -> Unit): PageTextDocument?
    /** Save only to a location selected by the user; no automatic overwrite or remembered grants. */
    suspend fun save(control: PageDocumentControl.Save, text: String, authorize: suspend () -> Unit): Boolean
}
