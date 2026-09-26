package com.inksheets.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.inksheets.core.Instruments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A quick look at a part's pages from Home - to read what is printed on it, a title or a part
 * name - without opening it as a song: no tab, nothing to close. A tap on either half, or the
 * arrows, turns; "Open" plays it as usual.
 */
@Composable
internal fun PageViewer(state: SheetsState) {
    val (song, part) = state.peeking ?: return
    val file = remember(part) { state.partFile(song, part) }
    val peek = remember(file) { file?.let { f -> runCatching { state.platform.peek(f) }.getOrNull() } }
    DisposableEffect(peek) { onDispose { runCatching { peek?.close() } } }
    // A part partway into a band pack shows only its own pages.
    val first = ((part.firstPage ?: 1) - 1).coerceAtLeast(0)
    val last = ((part.lastPage ?: peek?.pageCount ?: 1) - 1).coerceAtMost(((peek?.pageCount ?: 1) - 1).coerceAtLeast(0))
    var page by remember(part) { mutableStateOf(first) }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    fun close() { state.peeking = null }

    SheetDialog(
        title = song.title + "  ·  " + Instruments.partName(part),
        onDismiss = ::close,
        wide = true,
        buttons = {
            TextButton(onClick = { close(); openSong(state, song) }) { Text("Open") }
            TextButton(onClick = ::close) { Text("Close") }
        }
    ) {
        if (peek == null) {
            Text(
                if (file == null) "This part's file is not on this device." else "This file could not be read.",
                color = MaterialTheme.colorScheme.error
            )
            return@SheetDialog
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val widthPx = (constraints.maxWidth.coerceAtLeast(600) * 1.5f).toInt().coerceAtMost(2000)
            LaunchedEffect(page, widthPx) {
                loading = true
                picture = withContext(Dispatchers.IO) { peek.render(page, widthPx) }
                loading = false
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 560.dp).background(Color(0xFFEEEEEE))
                        .pointerInput(page, first, last) {
                            detectTapGestures { at ->
                                page = if (at.x > size.width / 2f) (page + 1).coerceAtMost(last) else (page - 1).coerceAtLeast(first)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    picture?.let { Image(it, "Page ${page + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) }
                    if (loading && picture == null) CircularProgressIndicator(Modifier.size(32.dp).padding(24.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(top = 4.dp)) {
                    IconButton(onClick = { page = (page - 1).coerceAtLeast(first) }, enabled = page > first) {
                        Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Page before")
                    }
                    Text("Page ${page - first + 1} of ${last - first + 1}", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { page = (page + 1).coerceAtMost(last) }, enabled = page < last) {
                        Icon(Icons.AutoMirrored.Filled.NavigateNext, "Next page")
                    }
                }
            }
        }
    }
}
