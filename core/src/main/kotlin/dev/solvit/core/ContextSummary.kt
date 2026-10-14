package dev.solvit.core

import java.security.MessageDigest

/** Durable projection checkpoint; original messages remain available for display and branching. */
data class ContextSummary(
    val id: String,
    val throughMessageId: String,
    val prefixDigest: String,
    val messageCount: Int,
    val text: String,
    val providerId: String,
    val model: String,
    val estimatedBefore: Long,
    val estimatedAfter: Long,
    val createdAt: Long,
) {
    fun validate() {
        require(id.isNotBlank() && id.length <= 128 && throughMessageId.isNotBlank() && throughMessageId.length <= 128)
        require(prefixDigest.matches(Regex("[a-f0-9]{64}")) && messageCount > 0)
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 32 * 1024) { "摘要为空或超过限制" }
        require(providerId.matches(Regex("[A-Za-z0-9_.-]{1,80}")) && model.isNotBlank() && model.length <= 256)
        require(estimatedBefore > estimatedAfter && estimatedAfter >= 0 && createdAt >= 0) { "摘要未缩小上下文" }
    }
    override fun toString() = "ContextSummary(id=$id, messageCount=$messageCount, characters=${text.length})"
}

/** Length framing prevents ambiguities; covers content, status and signed/native tool data. */
fun contextPrefixDigest(messages: List<ChatMessage>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun field(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
        digest.update(bytes)
    }
    messages.forEach { message ->
        field(message.id); field(message.role.name); field(message.status.name); field(message.content)
        field(message.reasoning); field(message.toolCallId.orEmpty()); field(message.toolStage?.name.orEmpty())
        if (message.attachments.isNotEmpty()) {
            field("chat-attachments-v1"); field(message.attachments.size.toString())
            message.attachments.forEach {
                field(it.id); field(it.name); field(it.mimeType); field(it.sizeBytes.toString()); field(it.sha256)
                it.documentSelection?.let { selection ->
                    field("document-selection-v1"); field(selection.firstPage.toString())
                    field(selection.lastPage?.toString().orEmpty()); field(selection.ocrScript.name)
                }
            }
        }
        field(message.toolCalls.size.toString())
        message.toolCalls.forEach { field(it.id); field(it.name); field(it.argumentsJson) }
        val native = message.providerTranscript
        field((native != null).toString())
        if (native != null) {
            field(native.providerId); field(native.model); field(native.blocksJson); field(native.prefixHash)
            if (native.requestPrefixJson.isNotEmpty()) field(native.requestPrefixJson)
            field(native.options.size.toString())
            native.options.toSortedMap().forEach { (key, value) -> field(key); field(value) }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
