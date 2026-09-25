package com.inkslate.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.inkslate.core.QrCodes
import com.inkslate.data.EventLog

/** A QR code, drawn square on white with the border scanners need. */
@Composable
fun QrCodeImage(text: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) { runCatching { QrCodes.encode(text) }.getOrNull() } ?: return
    Canvas(
        modifier
            .size(size)
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(size / (matrix.size + 8) * 4)
    ) {
        val cell = this.size.minDimension / matrix.size
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}

/** Reading QR codes: with the camera, or out of whatever is on the clipboard. */
object QrBits {

    /**
     * Google's code scanner - its own camera screen, so the app asks for no camera permission.
     * [onResult] hears the code's text, or null if it was cancelled or could not run.
     */
    fun scan(context: Context, onResult: (String?) -> Unit) {
        runCatching {
            val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                .build()
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { onResult(it.rawValue) }
                .addOnCanceledListener { onResult(null) }
                .addOnFailureListener {
                    EventLog.warn("qr", "Scan failed: ${it.message}")
                    onResult(null)
                }
        }.onFailure { EventLog.warn("qr", "Scanner unavailable: ${it.message}"); onResult(null) }
    }

    /** The clipboard's text - or the QR code in a picture copied from a chat. */
    fun readClipboard(context: Context): String? = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip ?: return null
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            item.uri?.let { uri ->
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                }.getOrNull()?.let { bitmap ->
                    val px = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    QrCodes.decode(bitmap.width, bitmap.height, px)?.let { return it }
                }
            }
            item.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        null
    }.getOrNull()
}
