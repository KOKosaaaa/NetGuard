package com.smarttools.netguard.util

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object QRScanner {

    suspend fun scanBitmap(bitmap: Bitmap): String? {
        return suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            val scanner = BarcodeScanning.getClient()
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val qrValue = barcodes.firstOrNull {
                        it.format == Barcode.FORMAT_QR_CODE
                    }?.rawValue
                    if (cont.isActive) cont.resume(qrValue)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }
    }
}
