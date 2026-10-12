package dev.solvit.workspace

import dev.solvit.core.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Read at most cap+1 bytes. An unread/oversized file must never be treated as absent or unchanged. */
internal object FileObservations {
    const val CAP=256*1024
    private const val EXCERPT=6*1024
    fun capture(file: File): FileObservation {
        if(!file.exists()) return FileObservation(FileObservationKind.ABSENT)
        if(!file.isFile) throw ToolExecutionException(ToolFailureCode.NOT_REGULAR_FILE)
        val output=ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer=ByteArray(8192)
            while(output.size()<=CAP) {
                val count=input.read(buffer,0,minOf(buffer.size,CAP+1-output.size()))
                if(count<0) break
                output.write(buffer,0,count)
            }
        }
        val bytes=output.toByteArray()
        if(bytes.size>CAP) return FileObservation(FileObservationKind.OVERSIZED)
        return fromBytes(bytes)
    }
    fun fromBytes(bytes: ByteArray): FileObservation {
        require(bytes.size<=CAP)
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val text=if(bytes.any { it==0.toByte() }) null else runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if(text==null) return FileObservation(FileObservationKind.BINARY,bytes.size.toLong(),hash)
        val excerpt=if(bytes.size<=EXCERPT) text else Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE).decode(ByteBuffer.wrap(bytes,0,EXCERPT)).toString()
        return FileObservation(FileObservationKind.TEXT,bytes.size.toLong(),hash,excerpt,bytes.size>EXCERPT)
    }
    fun changed(before: FileObservation,after: FileObservation): Boolean? = when {
        before.kind==FileObservationKind.ABSENT -> true
        before.sha256!=null && after.sha256!=null -> before.sha256!=after.sha256
        else -> null
    }
}
