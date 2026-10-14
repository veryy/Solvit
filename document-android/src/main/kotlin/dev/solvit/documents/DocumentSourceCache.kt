package dev.solvit.documents

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** Startup only, before exposing the document service. Never collects a live read. */
class DocumentSourceCache(private val directory: File) {
    fun removeInterruptedReads() {
        check(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile == File(directory.absoluteFile.parentFile.canonicalFile, directory.name)) { "文档缓存目录已改变" }
        if (!directory.exists()) return
        check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) { "文档缓存目录无效" }
        val folders=checkNotNull(directory.listFiles()) { "无法读取文档缓存目录" }
        folders.forEach { folder ->
            if (runCatching { UUID.fromString(folder.name).toString() == folder.name }.getOrDefault(false) &&
                Files.isDirectory(folder.toPath(), NOFOLLOW_LINKS)) {
                val files=checkNotNull(folder.listFiles()) { "无法读取文档缓存文件" }
                // Only this service's UUID staging directories contain source.pdf and PDFBox scratch files.
                // Unknown nested directories are preserved, and links are deleted without following them.
                files.filter { !Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }.forEach { Files.delete(it.toPath()) }
                if (checkNotNull(folder.listFiles()).isEmpty()) Files.delete(folder.toPath())
            }
        }
    }
}
