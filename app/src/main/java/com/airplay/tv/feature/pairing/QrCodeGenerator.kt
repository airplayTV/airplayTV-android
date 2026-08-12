package com.airplay.tv.feature.pairing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

class QrCodeGenerator {
    fun generate(content: String, size: Int): Bitmap {
        require(content.startsWith(JOIN_URL_PREFIX)) { "Unsupported QR code URL" }
        require(size > 0) { "size must be positive" }

        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            encodingHints(),
        )
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
        }
    }

    companion object {
        const val JOIN_URL_PREFIX = "https://airplay-tv.pages.dev/join?"

        internal fun encodingHints(): Map<EncodeHintType, Any> = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
        )
    }
}
