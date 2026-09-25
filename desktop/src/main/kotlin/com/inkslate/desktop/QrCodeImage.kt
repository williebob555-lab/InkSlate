package com.inkslate.desktop

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
