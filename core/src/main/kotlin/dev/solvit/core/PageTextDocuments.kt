package dev.solvit.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** Bounded text/ZIP decoding in memory. Never extract archive paths or execute their contents. */
object PageTextDocuments {
    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().also { require('\u0000' !in it) { "文件不是可导入的文本" } }

    fun decode(name: String, bytes: ByteArray): PageTextDocument {
        require(bytes.size in 1..PageDocumentControl.MAX_BYTES) { "文本导入文件需在512 KiB内" }
        require(name.length in 1..256)
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte())
            return PageTextDocument(name, text = utf8(bytes))
        val files = linkedMapOf<String, String>()
        var count = 0
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 80) { "文本ZIP条目过多" }
                val path = entry.name
                require(path.length in 1..240 && !path.startsWith('/') && '\\' !in path &&
                    path.none { it.code < 32 || it.code == 127 } && path.split('/').none { it in setOf(".", "..") }) {
                    "文本ZIP路径无效"
                }
                if (entry.isDirectory) continue
                require(path !in files && files.size < 40) { "文本ZIP文件重复或过多" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= 32 * 1024) {
                    val read = zip.read(buffer, 0, minOf(buffer.size, 32 * 1024 + 1 - output.size()))
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
                val content = output.toByteArray()
                require(content.size <= 32 * 1024) { "ZIP文本文件超过32 KiB" }
                total += content.size
                require(total <= 220 * 1024) { "ZIP解压内容超过限制" }
                files[path] = utf8(content)
            }
        }
        require(files.isNotEmpty()) { "文本ZIP没有可导入文件" }
        return PageTextDocument(name, files = files)
    }
}
