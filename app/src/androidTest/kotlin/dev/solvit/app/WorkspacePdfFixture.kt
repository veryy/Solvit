package dev.solvit.app

import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.ByteArrayOutputStream

internal fun workspacePdfFixture(app: android.content.Context, password: Boolean = false, scanSecond: Boolean = false, longText: Boolean = false): ByteArray {
    PDFBoxResourceLoader.init(app)
    return PDDocument().use { document ->
        for (number in 1..2) {
            val page = PDPage(PDRectangle(400f, 240f)); document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                if (number == 1) stream.setNonStrokingColor(1f, 0f, 0f) else stream.setNonStrokingColor(0f, 0f, 1f)
                stream.addRect(10f, 10f, 180f, 100f); stream.fill()
                if (scanSecond && number == 2) {
                    val pixels = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
                    try { pixels.eraseColor(Color.BLUE); stream.drawImage(LosslessFactory.createFromImage(document, pixels), 20f, 20f, 240f, 120f) }
                    finally { pixels.recycle() }
                } else {
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 18f); stream.newLineAtOffset(20f, 180f)
                    stream.showText(if (longText) "fixture text ".repeat(900) else "PAGE $number"); stream.endText()
                }
            }
        }
        if (password) document.protect(StandardProtectionPolicy("owner fixture", "user fixture", AccessPermission()).apply { encryptionKeyLength = 128 })
        ByteArrayOutputStream().use { document.save(it); it.toByteArray() }
    }
}
