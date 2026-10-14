package dev.solvit.app

import android.graphics.BitmapFactory
import dev.solvit.core.ChatAttachment
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction.REPORT

internal fun validateChatAttachment(attachment: ChatAttachment, bytes: ByteArray) {
    if (attachment.mimeType.startsWith("image/")) {
        require(attachment.mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) { "暂不支持此图片格式" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 36_000_000L &&
            options.outWidth <= 12_000 && options.outHeight <= 12_000) { "图片无效或尺寸超过限制" }
        require(options.outMimeType == attachment.mimeType) { "图片内容与文件类型不匹配" }
    } else {
        require(attachment.mimeType.startsWith("text/") || attachment.mimeType in setOf("application/json", "application/xml", "application/javascript", "application/x-python")) { "暂不支持此文件格式，当前可上传图片、文本和代码" }
        require(bytes.size <= 256 * 1024) { "文本附件超过256KiB读取限制" }
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(REPORT).onUnmappableCharacter(REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { error("文本附件不是有效UTF-8") }
        require('\u0000' !in text) { "附件包含二进制内容" }
    }
}
