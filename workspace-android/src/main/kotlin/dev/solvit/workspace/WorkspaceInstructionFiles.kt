package dev.solvit.workspace

import dev.solvit.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** No arbitrary paths, no symlinks, no cached content. The hook host owns authorization. */
internal object WorkspaceInstructionFiles {
    suspend fun capture(root: File, authorize: suspend () -> Unit): List<WorkspaceInstructionSource> {
        val base = root.canonicalFile
        val result = mutableListOf<WorkspaceInstructionSource>()
        for (name in WorkspaceInstructionSource.NAMES) {
            currentCoroutineContext().ensureActive(); authorize()
            val source = read(base, name)
            currentCoroutineContext().ensureActive(); authorize()
            source.validate(); result += source
        }
        return result
    }
    private suspend fun read(base: File, name: String): WorkspaceInstructionSource {
        fun status(value: WorkspaceInstructionStatus) = WorkspaceInstructionSource(name, value)
        val file = File(base, name)
        val path = file.toPath()
        return try {
            if (Files.isSymbolicLink(path)) return status(WorkspaceInstructionStatus.UNAVAILABLE)
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return status(WorkspaceInstructionStatus.ABSENT)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || file.canonicalFile != file.absoluteFile)
                return status(WorkspaceInstructionStatus.UNAVAILABLE)
            val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val cap = WorkspaceInstructionSource.MAX_SOURCE_BYTES
            val output = ByteArrayOutputStream()
            Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                val buffer = ByteBuffer.allocate(4096)
                while (output.size() <= cap) {
                    currentCoroutineContext().ensureActive()
                    buffer.clear(); buffer.limit(minOf(buffer.capacity(), cap + 1 - output.size()))
                    val count = channel.read(buffer)
                    if (count < 0) break
                    output.write(buffer.array(), 0, count)
                }
            }
            val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (Files.isSymbolicLink(path) || !after.isRegularFile || before.fileKey() != after.fileKey() ||
                before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
                file.canonicalFile != file.absoluteFile) return status(WorkspaceInstructionStatus.UNAVAILABLE)
            val bytes = output.toByteArray()
            if (bytes.size > cap) return status(WorkspaceInstructionStatus.OVERSIZED)
            if (bytes.any { it == 0.toByte() }) return status(WorkspaceInstructionStatus.BINARY)
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) { return status(WorkspaceInstructionStatus.BINARY) }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            WorkspaceInstructionSource(name, WorkspaceInstructionStatus.TEXT, text, hash)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { status(WorkspaceInstructionStatus.UNAVAILABLE) }
    }
}
