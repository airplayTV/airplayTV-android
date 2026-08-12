package com.airplay.tv.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingQrImageTest {
    @Test
    fun exposesImageOnlyWhenItBelongsToCurrentContent() {
        val roomAImage = PairingQrImage(content = "room-a", bitmap = "bitmap-a")

        assertEquals("bitmap-a", roomAImage.bitmapFor("room-a"))
        assertNull(roomAImage.bitmapFor("room-b"))
    }

    @Test
    fun missingImageRemainsLoading() {
        val image: PairingQrImage<String>? = null

        assertNull(image.bitmapFor("room-a"))
    }
}
