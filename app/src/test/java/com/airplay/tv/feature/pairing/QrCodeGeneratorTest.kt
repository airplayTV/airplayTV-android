package com.airplay.tv.feature.pairing

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class QrCodeGeneratorTest {
    @Test
    fun usesHighErrorCorrectionForTvScanning() {
        assertEquals(
            ErrorCorrectionLevel.H,
            QrCodeGenerator.encodingHints()[EncodeHintType.ERROR_CORRECTION],
        )
    }
}
