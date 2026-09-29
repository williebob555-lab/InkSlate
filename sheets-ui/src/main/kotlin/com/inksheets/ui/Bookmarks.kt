package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.inksheets.core.Bookmark
import com.inksheets.core.Instruments
import com.inksheets.core.Song

/** How the Bookmarks tab is put in order. */
enum class BookmarkSort(val label: String) {
    MANUAL("My order"),
    TITLE("A-Z"),
    RECENT("Newest"),
    COLOUR("Colour")
}

/** One row of the Bookmarks tab: a bookmarked page of a song. */
private data class Marked(val song: Song, val mark: Bookmark) {
    val key: String get() = song.id + "|" + mark.part + "|" + mark.page + "|" + mark.label
    val colour: Int? get() = mark.color ?: song.color
}

private fun sortMarks(rows: List<Marked>, sort: BookmarkSort): List<Marked> {
    val manual = compareBy<Marked, Double?>(nullsLast()) { it.mark.rank }.thenBy { it.mark.at }.thenBy { it.song.title.lowercase() }.thenBy { it.mark.page }
    return when (sort) {
        BookmarkSort.MANUAL -> rows.sortedWith(manual)
        BookmarkSort.TITLE -> rows.sortedWith(compareBy<Marked> { it.song.title.lowercase() }.thenBy { it.mark.page })
        BookmarkSort.RECENT -> rows.sortedWith(compareByDescending<Marked> { it.mark.at }.then(manual))
        // The colours in the order they are offered in, the uncoloured last; each colour in your order.
        BookmarkSort.COLOUR -> rows.sortedWith(compareBy<Marked> { r ->
            r.colour?.let { c -> MARK_COLOURS.indexOf(c).takeIf { it >= 0 } ?: MARK_COLOURS.size } ?: Int.MAX_VALUE
        }.then(manual))
    }
}

/**
 * Every bookmarked page, as a song each: a setlist of its own, kept in its own tab. Put in order
 * by hand (hold a row, then drag it), or by name, newest or colour; each bookmark can have a
 * colour of its own, and the song's notes show beside its name. A tap opens the song at that
 * page. Its bookmark, lit, takes the bookmark off - fading out over a few seconds, while a second
 * tap puts it back - and once it has gone, the rest move up into its place.
 */
@Composable
internal fun BookmarksPane(state: SheetsState, marked: List<Song>) {
    var editing by remember { mutableStateOf<Song?>(null) }
    var addingToSetlist by remember { mutableStateOf<Song?>(null) }
    var colouring by remember { mutableStateOf<Marked?>(null) }
    val sort = state.bookmarkSort
    // Bookmarks on their way out: pressed off, not yet gone.
    val leaving = remember { mutableStateMapOf<String, Boolean>() }
    val order = remember(marked, sort) {
        sortMarks(marked.flatMap { song -> song.bookmarks.map { Marked(song, it) } }, sort).toMutableStateList()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookmarkSort.entries.forEach { o ->
                FilterChip(selected = sort == o, onClick = { state.bookmarkSort = o }, label = { Text(o.label) })
            }
            // Seen sorted, and wanted that way: one tap makes it your order.
            if (sort != BookmarkSort.MANUAL) {
                TextButton(onClick = {
                    state.orderBookmarks(order.map { it.song.id to it.mark })
                    state.bookmarkSort = BookmarkSort.MANUAL
                }) { Text("Keep this order") }
            }
        }
        HorizontalDivider()

        val listState = rememberLazyListState()
        val draggable = sort == BookmarkSort.MANUAL
        var dragging by remember { mutableStateOf<String?>(null) }
        var anchor by remember { mutableStateOf(0f) }
        var finger by remember { mutableStateOf(0f) }
        fun rowOffset(key: String?): Float? = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset?.toFloat()
        fun pickUp(key: String) {
            dragging = key
            anchor = rowOffset(key) ?: 0f
            finger = 0f
        }
        fun dragTo(delta: Float) {
            val key = dragging ?: return
            finger += delta
            val items = listState.layoutInfo.visibleItemsInfo
            val me = items.firstOrNull { it.key == key } ?: return
            val centre = anchor + finger + me.size / 2f
            val over = items.firstOrNull { it.key != key && it.index in order.indices && centre > it.offset && centre < it.offset + it.size } ?: return
            val from = order.indexOfFirst { it.key == key }
            if (from < 0 || over.index == from) return
            val first = listState.firstVisibleItemIndex
            val firstOffset = listState.firstVisibleItemScrollOffset
            order.add(over.index, order.removeAt(from))
            listState.requestScrollToItem(first, firstOffset)
        }
        fun dropped() {
            if (dragging != null) state.orderBookmarks(order.map { it.song.id to it.mark })
            dragging = null
            finger = 0f
        }

        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(order, key = { _, r -> r.key }) { _, row ->
                val (song, mark) = row
                val key = row.key
                val going = leaving[key] == true
                val alpha by androidx.compose.animation.core.animateFloatAsState(
                    if (going) 0f else 1f,
                    androidx.compose.animation.core.tween(if (going) FADE_MS else 200), label = "bookmark"
                )
                androidx.compose.runtime.LaunchedEffect(going) {
                    if (going) {
                        kotlinx.coroutines.delay(FADE_MS.toLong())
                        if (leaving[key] == true) {
                            leaving.remove(key)
                            state.library?.song(song.id)?.let { now -> state.removeBookmark(now, mark) }
                        }
                    }
                }
                val part = song.parts.firstOrNull { it.id == mark.part }
                val lifted = dragging == key
                Column(
                    (if (lifted) Modifier.zIndex(1f).graphicsLayer { translationY = anchor + finger - (rowOffset(key) ?: (anchor + finger)) }
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    else Modifier.animateItem())
                        .graphicsLayer { this.alpha = 0.15f + 0.85f * alpha }
                        // Hold anywhere on it, then drag. A quick swipe still scrolls, a tap still opens.
                        .dragAnywhere(
                            key,
                            enabled = draggable && !going,
                            onStart = { pickUp(key) },
                            onDrag = { dragTo(it.y) },
                            onEnd = { dropped() },
                            onCancel = { dropped() }
                        )
                ) {
                    SongRow(
                        song = song.copy(color = row.colour),
                        unsure = false,
                        note = mark.label + (part?.let { "  ·  " + Instruments.partName(it) } ?: ""),
                        onOpen = { if (!going) state.openBookmark(song, mark) },
                        onEdit = { editing = song },
                        onAddToSetlist = { addingToSetlist = song },
                        onNotes = { state.notesFor = song },
                        onColour = { colouring = row },
                        colourLabel = "Colour this bookmark...",
                        onLook = { part?.let { state.peeking = song to it } },
                        trailing = {
                            IconButton(onClick = { if (going) leaving.remove(key) else leaving[key] = true }) {
                                Icon(
                                    if (going) Icons.Default.BookmarkBorder else Icons.Default.Bookmark,
                                    if (going) "Keep the bookmark" else "Take the bookmark off",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    editing?.let { song -> SongEditorDialog(state, song, onClose = { editing = null }) }
    colouring?.let { row ->
        ColourDialog(
            "Colour for ${row.song.title}, ${row.mark.label}", row.mark.color,
            onChosen = { state.setBookmarkColour(row.song.id, row.mark, it); colouring = null },
            onDismiss = { colouring = null }
        )
    }
    addingToSetlist?.let { song ->
        SetlistChooserDialog(
            state,
            onChosen = { setlist -> state.change { addToSetlist(setlist.id, song.id) }; addingToSetlist = null },
            onDismiss = { addingToSetlist = null }
        )
    }
}

/** How long a bookmark taken off takes to fade and go - time enough to change your mind. */
private const val FADE_MS = 3000
