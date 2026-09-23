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
import androidx.compose.material.icons.filled.FullscreenExit
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/** One open document: a tab in the workspace. Its id is stable for the tab's whole lifetime. */
class DocTab(val id: String, val file: File, val host: DocumentHost, fullscreen: Boolean = false) {
    /** Flipped by the tab's own close button to ask the document to save-through and close. */
    val closeRequested = mutableStateOf(false)

    /** Whether this document was left fullscreen, which is how it comes back when you return to it. */
    var fullscreen by mutableStateOf(fullscreen)
}

/** Which of the (at most two) visible panes the keyboard currently belongs to. */
enum class Pane { PRIMARY, SECONDARY }

/** What one pane shows: a document, and which of its views. */
data class PaneRef(val tabId: String, val view: Int)

/**
 * One camera onto a document.
 *
 * A document is usually looked at through one of these. Shown in both halves of a split it has
 * two, each with its own place and zoom, over the one set of marks - so writing in either half is
 * writing in the same document, with one undo history between them.
 */
class DocView(val index: Int) {
    val viewport = Viewport()
    var page by mutableStateOf(0)
}

/**
 * Where an open document meets the workspace around it.
 *
 * The document itself - its marks, its history, its link to your other devices - lives in the
 * editor, which stays open for as long as the tab does. What the workspace needs from it is put
 * here: the bar that goes above the pages, the one that goes below, and a way to draw one of its
 * views into a pane. The workspace draws those bars once, for whichever document was touched last,
 * which is what keeps two documents side by side from being two copies of the whole window.
 */
class DocumentHost(val immersiveState: MutableState<Boolean>) {
    val snackbar = SnackbarHostState()

    val views = mutableStateListOf(DocView(0))

    /** The view the bars and the keyboard act on - the one last touched, of this document's. */
    var activeViewIndex by mutableStateOf(0)

    val activeView: DocView
        get() = views.firstOrNull { it.index == activeViewIndex } ?: views[0]

    val activeViewport: Viewport
        get() = activeView.viewport

    var activePage: Int
        get() = activeView.page
        set(value) {
            activeView.page = value
        }

    fun viewOrFirst(index: Int): DocView = views.firstOrNull { it.index == index } ?: views[0]

    /**
     * The view with [index], made if it is not there yet - starting where the first one is, so
     * a second view of a document opens on what you were already looking at.
     */
    fun ensureView(index: Int): DocView {
        views.firstOrNull { it.index == index }?.let { return it }
        val from = views[0]
        return DocView(index).also { fresh ->
            fresh.page = from.page
            fresh.viewport.content = from.viewport.content
            fresh.viewport.restore(from.viewport.scale, from.viewport.offset.x, from.viewport.offset.y)
            views.add(fresh)
        }
    }

    /** The app bar for this document: its name, where it stands, and its own actions. */
    var topBar by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** Page navigation, the shapes tray, the tools and the status line. */
    var bottomBar by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** One view of the document; the flag says whether it is the pane the keyboard is in. */
    var pane by mutableStateOf<(@Composable (DocView, Boolean) -> Unit)?>(null)
}

/**
 * Draws the one of [host]'s bars that [pick] picks, in its own scope.
 *
 * Keyed on the document. Every document's bars are the same code, so without the key moving from
 * one document to another would look to Compose like the same bar recomposing, and whatever it
 * remembers - a menu left open, the tool it last saw - would carry across to the other document.
 */
@Composable
fun HostBar(host: DocumentHost?, pick: (DocumentHost) -> (@Composable () -> Unit)?) {
    host ?: return
    val bar = pick(host) ?: return
    androidx.compose.runtime.key(host) { bar() }
}

/**
 * Draws one of [host]'s views, in its own scope - see [HostBar].
 *
 * Keyed on the document and the view for the same reason, and here it matters a great deal: a
 * pane that goes from one document to another must get a drawing surface of its own, not keep
 * the one showing the document before - with that document's marks in it.
 */
@Composable
fun HostPane(host: DocumentHost, view: DocView, focused: Boolean) {
    androidx.compose.runtime.key(host, view) {
        host.pane?.invoke(view, focused)
    }
}

/**
 * The row of tabs across the top of the window: a pinned Home tab, one chip per open document, and
 * a button to open another.
 *
 * Clicking a document shows it in the pane the keyboard is in, or moves the keyboard to it if it is
 * already on screen. The other half of a split is set from a tab's own right-click menu, the same
 * way any other per-item action reaches its menu on this build - including a second view of the
 * document already showing, for two places in the same document at once.
 */
@Composable
fun TabStrip(
    tabs: List<DocTab>,
    homeShown: Boolean,
    /** Every document on screen, in either pane. */
    shownIds: Set<String>,
    /** The document the bars and the keyboard belong to. */
    focusedId: String?,
    /** The document in the primary pane, which "open to the side" gives a second view of. */
    primaryId: String?,
    /** The document in the other half of the split, if there is one. */
    secondaryId: String?,
    onHome: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenInSplit: (String) -> Unit,
    onCloseSplit: () -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseOthers: (String) -> Unit,
    onCloseAll: () -> Unit,
    onNewTab: () -> Unit,
    /** Set while the page has the screen to itself; the tabs stay, and this is the way back out. */
    onLeaveFullscreen: (() -> Unit)? = null
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabChip(
                selected = homeShown,
                focused = homeShown,
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
                        shown = !homeShown && tab.id in shownIds,
                        focused = !homeShown && tab.id == focusedId,
                        twice = tab.id == primaryId && tab.id == secondaryId,
                        splitLabel = when {
                            tab.id == secondaryId -> null
                            tab.id == primaryId -> "Open a second view to the side"
                            else -> "Open to the side"
                        },
                        splitActive = secondaryId != null,
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
            if (onLeaveFullscreen != null) {
                IconButton(onClick = onLeaveFullscreen) {
                    Icon(Icons.Default.FullscreenExit, "Leave fullscreen")
                }
            }
        }
    }
}

@Composable
private fun TabChip(
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    label: String,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    onSecondaryClick: (() -> Unit)? = null
) {
    val background = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent
    val content = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxHeight()
            .widthIn(min = 96.dp, max = 220.dp)
            .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            .background(background)
            // The document the bars belong to, marked the way a focused field is: a line under it.
            .drawBehind {
                if (focused) {
                    val h = 3.dp.toPx()
                    drawRect(accent, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                }
            }
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
    shown: Boolean,
    focused: Boolean,
    /** Showing in both halves at once. */
    twice: Boolean,
    /** What "open to the side" says for this tab, or null when it is already there. */
    splitLabel: String?,
    splitActive: Boolean,
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
            selected = shown,
            focused = focused,
            onClick = onSelect,
            label = tab.file.name,
            leading = { Icon(icon, null, modifier = Modifier.size(16.dp)) },
            trailing = {
                if (twice) {
                    Icon(
                        Icons.Default.VerticalSplit, "Showing in both halves",
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
            if (splitLabel != null) {
                DropdownMenuItem(
                    text = { Text(splitLabel) },
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
