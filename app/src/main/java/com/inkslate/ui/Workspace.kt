package com.inkslate.ui

import com.inkslate.AppFlavor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Fullscreen
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.inkslate.ink.DrawingView
import java.io.File

/** The tab island yields to fixed action islands instead of drawing over them on narrow screens. */
internal fun tabIslandMaxWidthDp(
    screenWidthDp: Float,
    endReservedDp: Float,
    fullscreenControl: Boolean
): Float {
    val reserved = 38f + 5f + endReservedDp + if (fullscreenControl) 43f else 0f
    return (screenWidthDp - reserved - 8f).coerceAtLeast(84f)
}

/** Find moves into the document menu on very narrow screens; Save and More stay one tap away. */
internal fun compactActionIslandWidthDp(screenWidthDp: Float): Float =
    if (screenWidthDp < 360f) 86f else 126f

/** One open document: a tab in the workspace. Its id is stable for the tab's whole lifetime. */
class DocTab(
    val id: String,
    val file: File,
    val host: DocumentHost,
    fullscreen: Boolean = false,
    /** What the tab says, when that is not the file's name: a setlist's song title. */
    title: String? = null,
    /**
     * Whether the document has been read. A setlist opens all its songs as tabs at once, and a
     * concert's worth of documents read up front would be most of the memory there is; each one
     * is read the first time its tab is shown.
     */
    loaded: Boolean = true
) {
    var title by mutableStateOf(title)
    var loaded by mutableStateOf(loaded)

    /** Flipped by the tab's own close button to ask the document to save-through and close. */
    val closeRequested = mutableStateOf(false)

    /** Whether this document was left fullscreen, which is how it comes back when you return to it. */
    var fullscreen by mutableStateOf(fullscreen)
}

/** Which of the (at most two) visible panes the last touch landed in. */
enum class Pane { PRIMARY, SECONDARY }

/** What one pane shows: a document, and which of its views. */
data class PaneRef(val tabId: String, val view: Int)

/**
 * One view of a document: a drawing surface, where it is looking, and the pages it wants drawn.
 *
 * A document is usually looked at through one of these. Shown in both halves of a split it has
 * two, each with its own place and zoom over the one set of marks - see [com.inkslate.ink.InkModel]
 * - so writing in either half is writing in the same document, with one undo history between them.
 *
 * The surface itself only exists while the view is on screen. Off screen, the view keeps where it
 * was looking in [savedCamera] and gives its page pictures back; the marks and their history are
 * the document's, and never went anywhere.
 */
class DocView(val index: Int) {
    var view by mutableStateOf<DrawingView?>(null)
    var page by mutableStateOf(0)
    /** Pages actually intersecting the viewport; these alone may never be evicted. */
    var visiblePages by mutableStateOf(listOf(0))
    /** Visible pages plus the small read-ahead margin the renderer should try to keep. */
    var wantedPages by mutableStateOf(listOf(0))

    /**
     * Bumped when the surface reports a visible page still lacking a bitmap. Without it, an
     * identical page list would not re-emit and the retry would never reach the loader.
     */
    var renderNonce by mutableStateOf(0)

    /** Where the view was looking when it last left the screen, put back when it returns. */
    var savedCamera: FloatArray? = null
}

/**
 * Where an open document meets the workspace around it.
 *
 * The document itself - its marks, its history, its link to your other devices - lives in the
 * editor, which stays open for as long as the tab does. What the workspace needs from it is put
 * here: the bar that goes above the pages, the one that goes below, and a way to draw one of its
 * views into a pane. The workspace draws those bars once, for whichever document was touched last,
 * which is what keeps two documents side by side from being two copies of the whole screen.
 */
class DocumentHost(val immersive: ImmersiveController) {
    val snackbar = SnackbarHostState()

    val views = mutableStateListOf(DocView(0))

    /** The view the bars and the back gesture act on - the one last touched, of this document's. */
    var activeViewIndex by mutableStateOf(0)

    val activeView: DocView
        get() = views.firstOrNull { it.index == activeViewIndex } ?: views[0]

    var activePage: Int
        get() = activeView.page
        set(value) {
            activeView.page = value
        }

    fun viewOrFirst(index: Int): DocView = views.firstOrNull { it.index == index } ?: views[0]

    /**
     * The view with [index], made if it is not there yet - starting where the first one is, so a
     * second view of a document opens on what you were already looking at.
     */
    fun ensureView(index: Int): DocView {
        views.firstOrNull { it.index == index }?.let { return it }
        val from = views[0]
        return DocView(index).also { fresh ->
            fresh.page = from.page
            fresh.savedCamera = from.view?.cameraState() ?: from.savedCamera
            views.add(fresh)
        }
    }

    /** The app bar for this document: its name, where it stands, and its own actions. */
    var topBar by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** Page navigation, the shapes tray and the tools. */
    var bottomBar by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** One view of the document; the flag says whether it is the pane last touched. */
    var pane by mutableStateOf<(@Composable (DocView, Boolean) -> Unit)?>(null)
}

/**
 * The drawing surface of whichever of the document's views was touched last.
 *
 * Read everywhere the editor used to hold its one surface, so everything the bars ask of "the
 * page" - undo, zoom, the ruler, going to a page - happens in the half you are working in.
 */
class ActiveSurface(private val host: DocumentHost) {
    val value: DrawingView?
        get() = host.activeView.view
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
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            host.pane?.invoke(view, focused)
            // What an app built on this one lays over the page - InkSheets' action buttons.
            if (focused) com.inkslate.AppFlavor.paneOverlay?.invoke(this)
        }
    }
}

/**
 * The row of tabs across the top of the screen: a pinned Home tab, one chip per open document, and
 * a button to open another.
 *
 * A tap on a document shows it in the pane last touched, or moves there if it is already on
 * screen. The other half of a split - on a screen wide enough for one - is set from a tab's own
 * long-press menu, the same way any other per-item action reaches its menu on this build,
 * including a second view of the document already showing.
 */
@Composable
fun TabStrip(
    tabs: List<DocTab>,
    homeShown: Boolean,
    shownIds: Set<String>,
    focusedId: String?,
    primaryId: String?,
    secondaryId: String?,
    splitAvailable: Boolean,
    /** The split puts the second document below rather than beside. */
    stacked: Boolean = false,
    onHome: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenInSplit: (String) -> Unit,
    onCloseSplit: () -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseOthers: (String) -> Unit,
    onCloseAll: () -> Unit,
    onNewTab: () -> Unit,
    /** Present while a document is showing; the separate island toggles its fullscreen state. */
    onToggleFullscreen: (() -> Unit)? = null,
    fullscreen: Boolean = false,
    /** Space occupied by the document-action island at the opposite end of the same row. */
    endReserved: Dp = 0.dp,
    /** Move the document tab at the first index to the second. Home is not in [tabs] and stays put. */
    onMoveTab: (Int, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // A tab being dragged along the strip, and how far it sits from its own slot. It changes places
    // with a neighbour once it is more than halfway over it, so the finger and the tab stay together.
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val widths = remember { mutableMapOf<String, Int>() }
    val move by rememberUpdatedState(onMoveTab)
    fun dragBy(id: String, dx: Float) {
        dragOffset += dx
        while (true) {
            val i = tabs.indexOfFirst { it.id == id }
            if (i < 0) return
            val next = tabs.getOrNull(i + 1)?.let { widths[it.id] }
            val prev = tabs.getOrNull(i - 1)?.let { widths[it.id] }
            if (dragOffset > 0 && next != null && dragOffset > next / 2f) {
                move(i, i + 1); dragOffset -= next
            } else if (dragOffset < 0 && prev != null && -dragOffset > prev / 2f) {
                move(i, i - 1); dragOffset += prev
            } else return
        }
    }

    // This island is overlaid directly on the app's root Surface. A Material Surface blocks
    // touches behind its whole measured bounds even when its colour is transparent. Keeping a
    // transparent full-width Surface here therefore made an invisible input shield; after the
    // strip moved into the root overlay that shield could be measured as the entire screen, which
    // is why only controls inside the strip (notably a tab's close button) remained reachable.
    // Layout-only boxes do not own pointer input. Only the visible Home/tab/fullscreen Surfaces
    // below are interactive, so every genuinely open part of the top edge belongs to the page or
    // Home screen underneath it.
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)) {
        // On a narrow portrait screen, document actions outrank showing a whole tab label. The
        // tab contents scroll inside their island; allowing the island itself to overlap the
        // actions made both sets of buttons unreliable.
        val tabIslandMax = tabIslandMaxWidthDp(
            maxWidth.value,
            endReserved.value,
            onToggleFullscreen != null
        ).dp
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onHome,
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = if (homeShown) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 3.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Home, "Home", modifier = Modifier.size(19.dp))
                }
            }
            Spacer(Modifier.width(5.dp))
            Surface(
                modifier = Modifier.widthIn(max = tabIslandMax).fillMaxHeight(),
                // A true quarter-circle at each top corner meets the screen edge tangentially;
                // it is the silhouette of an attached island, not a rectangle hung from it.
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp
                ),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 1.dp,
                shadowElevation = 2.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Opening another document sits beside Home, where it is found.
                    IconButton(onClick = onNewTab, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Add, "Open another document")
                    }
                    com.inkslate.AppFlavor.onSaveTabs?.let { save ->
                        if (tabs.isNotEmpty()) {
                            IconButton(
                                onClick = { save(tabs.filter { !it.closeRequested.value }.map { it.file }) },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Default.PlaylistAdd, "Save as setlist", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxHeight().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        // Folder tabs overlap at their shoulders rather than leaving a stack of
                        // unrelated rounded buttons. The curved edge remains the divider.
                        horizontalArrangement = Arrangement.spacedBy((-7).dp)
                    ) {
                        for ((tabIndex, tab) in tabs.withIndex()) key(tab.id) {
                            DocumentTabChip(
                                tab = tab,
                                position = tabIndex,
                                tabCount = tabs.size,
                                dragOffset = if (draggingId == tab.id) dragOffset else null,
                                onWidth = { widths[tab.id] = it },
                                onDragStart = { draggingId = tab.id; dragOffset = 0f },
                                onDragBy = { dragBy(tab.id, it) },
                                onDragEnd = { draggingId = null; dragOffset = 0f },
                                shown = !homeShown && tab.id in shownIds,
                                focused = !homeShown && tab.id == focusedId,
                                twice = tab.id == primaryId && tab.id == secondaryId,
                                splitLabel = when {
                                    !splitAvailable -> null
                                    tab.id == secondaryId -> null
                                    tab.id == primaryId -> if (stacked) "Open a second view below" else "Open a second view to the side"
                                    else -> if (stacked) "Open below" else "Open to the side"
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
                }
            }
            Spacer(Modifier.weight(1f))
            if (onToggleFullscreen != null) {
                Spacer(Modifier.width(5.dp))
                Surface(
                    onClick = onToggleFullscreen,
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 3.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            if (fullscreen) "Leave fullscreen" else "Enter fullscreen",
                            Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
        }
    }
}

/** A folder tab: narrow at the top, widening through quarter-curves into its neighbours. */
private val ConnectedTabShape = GenericShape { size, _ ->
    val shoulder = (size.height * .32f).coerceAtMost(size.width * .22f)
    // The 0.552 coefficient is the cubic approximation of a circular quadrant. Both sides are
    // tangent to the flat top and flat base, like a physical divider tab in a folder.
    val k = .5522848f
    moveTo(shoulder, 0f)
    lineTo(size.width - shoulder, 0f)
    cubicTo(
        size.width - shoulder + shoulder * k, 0f,
        size.width - shoulder * k, size.height,
        size.width, size.height
    )
    lineTo(0f, size.height)
    cubicTo(
        shoulder * k, size.height,
        shoulder - shoulder * k, 0f,
        shoulder, 0f
    )
    close()
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
    onLongClick: (() -> Unit)? = null,
    /** Replaces the plain tap and long-press when the chip handles its own touches. */
    gestures: Modifier? = null,
    position: Int = 0,
    tabCount: Int = 1
) {
    val step = if (tabCount <= 1) 0f else position / (tabCount - 1f)
    // Deliberately span the full container range. The previous .90 blend was so subtle that the
    // tabs looked flat; this reads clearly from brighter on the left to darker on the right.
    val resting = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surfaceContainerHighest,
        MaterialTheme.colorScheme.surfaceContainerLowest,
        step
    )
    // Selection adds colour without erasing the positional gradient (and therefore muscle memory).
    val background = if (selected) androidx.compose.ui.graphics.lerp(
        resting,
        MaterialTheme.colorScheme.primaryContainer,
        .24f
    ) else resting
    val content = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .zIndex(if (selected) 2f else if (focused) 1f else 0f)
            .fillMaxHeight()
            .widthIn(min = 96.dp, max = 220.dp)
            .clip(ConnectedTabShape)
            .background(background)
            // The document the bars belong to, marked the way a focused field is: a line under it.
            .drawBehind {
                if (focused) {
                    val h = 3.dp.toPx()
                    drawRect(accent, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                }
            }
            .then(gestures ?: Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))
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
    position: Int,
    tabCount: Int,
    /** How far the tab has been dragged from its slot, or null when it is not being dragged. */
    dragOffset: Float?,
    onWidth: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDragBy: (Float) -> Unit,
    onDragEnd: () -> Unit,
    shown: Boolean,
    focused: Boolean,
    /** Showing in both halves at once. */
    twice: Boolean,
    /** What "open to the side" says for this tab, or null when it cannot or already is. */
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
    val haptics = LocalHapticFeedback.current
    val select by rememberUpdatedState(onSelect)
    // A tap selects. A long press either opens the menu, if the finger lifts where it went down, or
    // picks the tab up to drag along the strip. A finger that moves before the long press is the
    // strip being scrolled, and is left to it.
    val gestures = Modifier.pointerInput(tab.id) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var lifted: androidx.compose.ui.input.pointer.PointerInputChange? = null
            var moved = false
            val waited = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) { lifted = c; break }
                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        moved = true; break
                    }
                }
            }
            if (waited != null) {
                // The close button inside the chip takes its own taps.
                val up = lifted
                if (!moved && up != null && !up.isConsumed) { up.consume(); select() }
                return@awaitEachGesture
            }
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onDragStart()
            var travel = 0f
            while (true) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) { c.consume(); break }
                val dx = c.positionChange().x
                travel += kotlin.math.abs(dx)
                c.consume()
                onDragBy(dx)
            }
            onDragEnd()
            if (travel < viewConfiguration.touchSlop) menuOpen = true
        }
    }
    Box(
        Modifier
            .onSizeChanged { onWidth(it.width) }
            .zIndex(if (dragOffset != null) 1f else 0f)
            .graphicsLayer {
                translationX = dragOffset ?: 0f
                if (dragOffset != null) shadowElevation = 6.dp.toPx()
            }
    ) {
        TabChip(
            selected = shown || dragOffset != null,
            focused = focused,
            onClick = onSelect,
            position = position,
            tabCount = tabCount,
            gestures = gestures,
            label = tab.title ?: tab.file.name,
            leading = { if(!AppFlavor.musicView) Icon(icon, null, modifier = Modifier.size(16.dp)) },
            trailing = {
                if (twice) {
                    Icon(
                        Icons.Default.VerticalSplit, "Showing in both halves",
                        modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary
                    )
                }
                // A setlist's songs close together, from Home; a song opened on its own has its X.
                if (!com.inkslate.AppFlavor.musicView || tab.title == null) {
                    IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, "Close ${tab.file.name}", modifier = Modifier.size(14.dp))
                    }
                }
            },
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
 * The bar between two documents shown side by side, or one above the other when [stacked]. Dragged
 * to resize, tapped to fold the split away again - the document in the other half keeps the whole
 * screen. [onDrag] is told how far the finger moved along the split, in pixels.
 */
/** How thick the bar between the two halves of a split is. */
val SPLIT_DIVIDER = 14.dp

@Composable
fun SplitDivider(
    onDrag: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    stacked: Boolean = false
) {
    val drag by rememberUpdatedState(onDrag)
    Box(
        modifier
            .then(
                if (stacked) Modifier.fillMaxWidth().height(SPLIT_DIVIDER)
                else Modifier.fillMaxHeight().width(SPLIT_DIVIDER)
            )
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(stacked) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    drag(if (stacked) dragAmount.y else dragAmount.x)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Close, "Close split view", modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * Notices a touch landing anywhere in [this] without taking it from whatever is underneath -
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
