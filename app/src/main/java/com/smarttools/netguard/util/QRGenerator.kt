package com.smarttools.netguard.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QRGenerator {

    fun generate(text: String, size: Int = 512): Bitmap {
        val qrSize = size.coerceIn(128, 1024)
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, qrSize, qrSize, hints)
        val bitmap = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.RGB_565)
        for (x in 0 until qrSize) {
            for (y in 0 until qrSize) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
