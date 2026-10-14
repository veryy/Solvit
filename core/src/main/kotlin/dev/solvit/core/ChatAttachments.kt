package dev.solvit.core

import java.io.InputStream
import java.util.UUID

/** Metadata only. Bytes remain in the host's private attachment store. */
data class ChatAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(UUID.fromString(id).toString() == id) { "附件标识无效" }
        require(name.isNotBlank() && name.length <= 255 && name.none { it.isISOControl() || it == '/' || it == '\\' }) { "附件名称无效" }
        require(mimeType.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))) { "附件类型无效" }
        require(sizeBytes in 0..MAX_ATTACHMENT_BYTES) { "附件超过大小限制" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "附件内容版本无效" }
    }
    companion object { const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024 }
}

interface ChatAttachmentStore {
    /** The caller owns and closes input. No URI or path is supplied by JS or a model. */
    suspend fun import(sessionId: String, name: String, mimeType: String, input: InputStream,
        authorize: suspend () -> Unit = {}): ChatAttachment
    suspend fun read(sessionId: String, attachment: ChatAttachment,
        authorize: suspend () -> Unit = {}): ByteArray
    suspend fun remove(sessionId: String, attachment: ChatAttachment,
        authorize: suspend () -> Unit = {})
}
val ChatAttachmentStoreKey = ServiceKey("chat.attachments", ChatAttachmentStore::class)

/** Ephemeral model input, never a database field. toString must not expose image bytes. */
class ModelImage(val attachmentId: String, val mimeType: String, val base64: String) {
    init {
        require(UUID.fromString(attachmentId).toString() == attachmentId) { "图片标识无效" }
        require(mimeType in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) { "模型不支持此图片格式" }
        require(base64.isNotEmpty() && base64.length <= 28 * 1024 * 1024) { "图片超过大小限制" }
        require(runCatching { java.util.Base64.getDecoder().decode(base64).size.toLong() <= ChatAttachment.MAX_ATTACHMENT_BYTES }.getOrDefault(false)) { "图片编码无效" }
    }
    override fun toString() = "ModelImage(attachmentId=$attachmentId, mimeType=$mimeType, bytes=<redacted>)"
}
