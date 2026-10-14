package dev.solvit.core

import kotlin.test.*

class PageFilePictureTest {
    @Test fun pictureOwnsBoundedCopiesAndClosingInvalidatesPrivatePixels() {
        val bytes = byteArrayOf(-1, -40, 42, -1, -39)
        val picture = PageFilePicture(12, 8, bytes)
        bytes.fill(0)
        val copy = picture.jpegBytes(); copy.fill(0)
        assertContentEquals(byteArrayOf(-1, -40, 42, -1, -39), picture.jpegBytes())
        assertFalse(picture.toString().contains("42"))
        picture.close(); picture.close(); assertFails { picture.jpegBytes() }
        assertFails { PageFilePicture(641, 1, byteArrayOf(-1, -40, -1, -39)) }
        assertFails { PageFilePicture(1, 1, ByteArray(PageFilePicture.MAX_BYTES + 1)) }
        assertFails { PageFilePicture(1, 1, byteArrayOf(1, 2, 3, 4)) }
    }
}
