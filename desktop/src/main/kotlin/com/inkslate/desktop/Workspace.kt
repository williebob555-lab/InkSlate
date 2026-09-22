package com.inkslate.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/** One open document: a tab in the workspace. Its id is stable for the tab's whole lifetime. */
class DocTab(val id: String, val file: File) {
    /** Flipped by the tab's own close button to ask the document to save-through and close. */
    val closeRequested = mutableStateOf(false)
    /** Kept in sync by the editor itself, so the tab strip can get out of the way of focus mode. */
    val immersive = mutableStateOf(false)
}

/** Which of the (at most two) visible panes the keyboard currently belongs to. */
enum class Pane { PRIMARY, SECONDARY }

/**
 * The row of tabs across the top of the window: a pinned Home tab, one chip per open document, and
 * a button to open another.
 *
 * A single click on a document brings it to the primary pane; the split partner - the other
 * document showing at the same time - is set from a tab's own right-click menu, the same way any
 * other per-item action reaches its menu on this build.
 */
@Composable
fun TabStrip(
    tabs: List<DocTab>,
    homeShown: Boolean,
    activeTabId: String?,
    splitTabId: String?,
    onHome: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenInSplit: (String) -> Unit,
    onCloseSplit: () -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseOthers: (String) -> Unit,
    onCloseAll: () -> Unit,
    onNewTab: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabChip(
                selected = homeShown,
                onClick = onHome,
                leading = { Icon(Icons.Default.Home, null, modifier = Modifier.size(18.dp)) },
                label = "Home"
            )
            Row(
                Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (tab in tabs) {
                    DocumentTabChip(
                        tab = tab,
                        selected = !homeShown && tab.id == activeTabId,
                        split = tab.id == splitTabId,
                        splitActive = splitTabId != null,
                        canSplit = tabs.size > 1 && tab.id != activeTabId,
                        onSelect = { onSelect(tab.id) },
                        onOpenInSplit = { onOpenInSplit(tab.id) },
                        onCloseSplit = onCloseSplit,
                        onClose = { onCloseTab(tab.id) },
                        onCloseOthers = { onCloseOthers(tab.id) },
                        onCloseAll = onCloseAll
                    )
                }
            }
            IconButton(onClick = onNewTab) {
                Icon(Icons.Default.Add, "Open another document")
            }
        }
    }
}

@Composable
private fun TabChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    onSecondaryClick: (() -> Unit)? = null
) {
    val background = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent
    val content = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .fillMaxHeight()
            .widthIn(min = 96.dp, max = 220.dp)
            .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .then(
                if (onSecondaryClick != null) Modifier.secondaryClick(onSecondaryClick) else Modifier
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        leading()
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        trailing()
    }
}

@Composable
private fun DocumentTabChip(
    tab: DocTab,
    selected: Boolean,
    split: Boolean,
    splitActive: Boolean,
    canSplit: Boolean,
    onSelect: () -> Unit,
    onOpenInSplit: () -> Unit,
    onCloseSplit: () -> Unit,
    onClose: () -> Unit,
    onCloseOthers: () -> Unit,
    onCloseAll: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val icon = when (tab.file.extension.lowercase()) {
        "pdf" -> Icons.Default.PictureAsPdf
        "png", "jpg", "jpeg", "webp" -> Icons.Default.Image
        else -> Icons.Default.Description
    }
    Box {
        TabChip(
            selected = selected || split,
            onClick = onSelect,
            label = tab.file.name,
            leading = { Icon(icon, null, modifier = Modifier.size(16.dp)) },
            trailing = {
                if (split) {
                    Icon(
                        Icons.Default.VerticalSplit, "Showing in the split pane",
                        modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Close, "Close ${tab.file.name}", modifier = Modifier.size(14.dp))
                }
            },
            onSecondaryClick = { menuOpen = true }
        )
        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
            if (canSplit) {
                DropdownMenuItem(
                    text = { Text("Open to the side") },
                    onClick = { menuOpen = false; onOpenInSplit() }
                )
            }
            if (splitActive) {
                DropdownMenuItem(
                    text = { Text("Close split view") },
                    onClick = { menuOpen = false; onCloseSplit() }
                )
            }
            DropdownMenuItem(text = { Text("Close") }, onClick = { menuOpen = false; onClose() })
            DropdownMenuItem(text = { Text("Close other tabs") }, onClick = { menuOpen = false; onCloseOthers() })
            DropdownMenuItem(text = { Text("Close all tabs") }, onClick = { menuOpen = false; onCloseAll() })
        }
    }
}

/**
 * The bar between two documents shown side by side. Dragged to resize, clicked to fold the split
 * away again - the same document that was primary keeps the whole width.
 */
@Composable
fun SplitDivider(onDrag: (Float) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxHeight()
            .width(10.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) {
            Icon(Icons.Default.Close, "Close split view", modifier = Modifier.size(12.dp))
        }
    }
}

/**
 * Notices a press landing anywhere in [this] without taking it from whatever is underneath -
 * split-view drawing has to keep working in both panes, so this only watches.
 */
fun Modifier.observeFocus(onFocused: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.changedToDown() }) onFocused()
        }
    }
}
