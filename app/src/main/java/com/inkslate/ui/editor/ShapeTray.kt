package com.inkslate.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkslate.core.MathSymbols
import com.inkslate.core.StampShelf
import com.inkslate.core.Stamps

private val SHAPES = listOf(Stamps.Kind.LINE, Stamps.Kind.BOX, Stamps.Kind.OVAL)

/**
 * The shapes tray: one row over the toolbar, in place of the dialog that used to cover the page.
 *
 * Tapping a tile puts that shape in hand; tapping the lit tile again puts it down. With a shape in
 * hand every tap on the page places one and every stroke is ordinary writing that puts the shape
 * away - the tray does not have to be closed to stop, and does not have to be reopened to go on.
 *
 * The symbols live here too, on a second row reached by the sigma: they follow the same rule, and
 * tapping several in a row builds one string, which is how "x₁²" gets made.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ShapeTray(
    shelf: StampShelf,
    armed: Stamps.Kind?,
    /** The symbol string in hand, or null. */
    armedText: String?,
    symbols: Boolean,
    onArm: (Stamps.Kind) -> Unit,
    onDisarm: () -> Unit,
    /** Open one stamp's settings straight from its tile. */
    onSettingsFor: (Stamps.Kind) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowSymbols: (Boolean) -> Unit,
    onSymbol: (String) -> Unit,
    onBackspace: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                // Dragged down, like every other panel, rather than only closed by its cross.
                .pointerInput(Unit) {
                    var travelled = 0f
                    detectVerticalDragGestures(
                        onDragStart = { travelled = 0f },
                        onDragCancel = { travelled = 0f },
                        onDragEnd = {
                            if (travelled > 60f) onClose()
                            travelled = 0f
                        }
                    ) { change, amount ->
                        travelled += amount
                        change.consume()
                    }
                }
                .padding(start = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (symbols) {
                IconButton(onClick = { onShowSymbols(false) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to shapes")
                }
                if (armedText != null) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            armedText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1
                        )
                        IconButton(onClick = onBackspace, Modifier.size(40.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Backspace, "Remove the last symbol", Modifier.size(18.dp))
                        }
                    }
                }
                LazyRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MathSymbols.groups.forEachIndexed { g, group ->
                        if (g > 0) item(key = "gap$g") { Box(Modifier.width(10.dp)) }
                        itemsIndexed(group.symbols, key = { i, _ -> "$g:$i" }) { _, sym ->
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onSymbol(sym) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(sym, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp))
                            }
                        }
                    }
                }
            } else {
                LazyRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(SHAPES, key = { it.name }) { k ->
                        TrayTile(k, shelf.optionsFor(k), armed == k, { onSettingsFor(k) }) {
                            if (armed == k) onDisarm() else onArm(k)
                        }
                    }
                    // Music needs lines, boxes and ovals; the graphs and symbols are for homework.
                    if (!com.inkslate.AppFlavor.musicView) {
                        item(key = "divider") {
                            Box(Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        }
                        items(shelf.trayKinds(), key = { it.name }) { k ->
                            TrayTile(k, shelf.optionsFor(k), armed == k, { onSettingsFor(k) }) {
                                if (armed == k) onDisarm() else onArm(k)
                            }
                        }
                        item(key = "all") {
                            IconButton(onClick = onOpenLibrary) { Icon(Icons.Default.Apps, "All shapes and stamps") }
                        }
                        item(key = "symbols") {
                            IconButton(onClick = { onShowSymbols(true) }) { Icon(Icons.Default.Functions, "Symbols") }
                        }
                    }
                }
            }
            // A labelled button, not a lone icon: this is the way into everything a stamp
            // can be set to, and an unlabelled slider glyph is a guess.
            TextButton(onClick = onOpenSettings, enabled = armed != null) {
                Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                Text("  Settings")
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close the tray") }
        }
    }
}

/**
 * One shape in the tray, drawn as itself rather than as an icon of itself.
 *
 * [onSettings] is the second way into its settings, for anyone who reaches for the thing itself
 * rather than for a button at the end of the row.
 */
@Composable
private fun TrayTile(
    kind: Stamps.Kind,
    options: Stamps.StampOptions,
    lit: Boolean,
    onSettings: () -> Unit,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (lit) MaterialTheme.colorScheme.primaryContainer else PAPER)
            .border(
                if (lit) 2.dp else 1.dp,
                if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(10.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onSettings),
        contentAlignment = Alignment.Center
    ) {
        // Previewed without labels: at this size numbers are noise, and the outline is what
        // tells one stamp from another.
        // Heavier than life, too: a hairline at this size is how a line and an arrow look the same.
        val bare = options.copy(
            labels = false, tickValues = false, xName = "", yName = "", weight = options.weight * 3.5f
        )
        StampPreview(kind, bare, Modifier.size(44.dp))
    }
}
