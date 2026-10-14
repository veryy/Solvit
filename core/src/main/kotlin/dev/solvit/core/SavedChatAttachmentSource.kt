package dev.solvit.core

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Trusted host adapter for one captured conversation; never accepts paths, URIs or supplied metadata. */
class SavedChatAttachmentSource(
    private val chats: ChatStore,
    private val files: ChatAttachmentStore,
    private val sessionId: String,
    private val activeSession: () -> String?,
) {
    init { require(sessionId.isNotBlank() && sessionId.length <= 4096) }

    private suspend fun verify(authorize: suspend () -> Unit) {
        coroutineContext.ensureActive()
        authorize()
        check(activeSession() == sessionId) { "对话已改变，请重新选择附件" }
        coroutineContext.ensureActive()
    }

    private suspend fun reference(messageId: String, attachmentId: String): ChatAttachment {
        val message = chats.history(sessionId).singleOrNull { it.id == messageId && it.sessionId == sessionId }
        check(message != null && message.role == Role.USER) { "当前对话中没有此附件来源消息" }
        return checkNotNull(message.attachments.singleOrNull { it.id == attachmentId }) { "附件来源已改变或删除" }
    }

    /** Caller owns the result. PDF selection/OCR text never replaces the complete original file. */
    suspend fun open(messageId: String, attachmentId: String, expectedSha256: String,
        authorize: suspend () -> Unit): PageBinaryDocument {
        require(messageId.isNotBlank() && messageId.length <= 4096)
        require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
        require(runCatching { java.util.UUID.fromString(attachmentId).toString() == attachmentId }.getOrDefault(false))
        verify(authorize)
        val selected = reference(messageId, attachmentId)
        check(selected.sha256 == expectedSha256) { "附件内容版本已改变" }
        verify(authorize)
        val bytes = files.read(sessionId, selected) { verify(authorize) }
        var document: PageBinaryDocument? = null
        try {
            verify(authorize)
            check(reference(messageId, attachmentId) == selected) { "附件来源已改变或删除" }
            check(bytes.size.toLong() == selected.sizeBytes) { "附件原文件大小已改变" }
            document = PageBinaryDocument(selected.name, selected.mimeType, bytes)
            check(document.sha256 == expectedSha256) { "附件原文件内容已改变" }
            verify(authorize)
            return document
        } catch (failure: Throwable) {
            document?.close()
            throw failure
        } finally { bytes.fill(0) }
    }
}
