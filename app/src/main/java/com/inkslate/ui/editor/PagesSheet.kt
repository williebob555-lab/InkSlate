package com.inkslate.ui.editor

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.inkslate.ink.PageLayout
import com.inkslate.pdf.PageArrangement
import com.inkslate.pdf.BlankDocumentFactory
import com.inkslate.pdf.ImportStaging
import com.inkslate.pdf.ImportedPage
import com.inkslate.ui.PaperPreview
import com.inkslate.ui.BlankPaperOptions
import com.inkslate.ui.ColorPickerDialog
import com.inkslate.ui.PaperTarget
import com.inkslate.ui.PaperStyle
import com.inkslate.pdf.PlannedPage
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pages mode: how the document is laid out on screen, and what pages it has.
 *
 * The two used to be separate - an arrangement submenu buried in the overflow, and no way at all
 * to add, remove or move a page - and they answer the same question, which is what the document
 * looks like. They are one screen.
 *
 * Structural edits are planned rather than applied. Everything here operates on a list of
 * [PlannedPage], so deleting thirty pages, changing your mind and closing the sheet costs nothing;
 * the document on disk is not touched until Apply, and then in one verified rewrite.
 */
@Composable
fun PagesSheet(
    pageCount: Int,
    currentPage: Int,
    layout: PageLayout,
    /** False for images and anything else with no page tree to edit. */
    canEditPages: Boolean,
    defaultPageWidth: Float,
    defaultPageHeight: Float,
    /** Size of one of the document's own pages, so an inserted page can match its neighbour. */
    pageSizeAt: (Int) -> Pair<Float, Float>,
    thumbnailFor: suspend (Int) -> Bitmap?,
    /** Thumbnail of a page in a file being imported, which this document has never opened. */
    importThumbnailFor: suspend (path: String, page: Int) -> Bitmap?,
    /** The document's canvas paper, or null when it is an ordinary fixed-page document. */
    canvasPaper: PaperStyle?,
    onCanvasChange: (PaperStyle?) -> Unit,
    onLayoutChange: (PageLayout) -> Unit,
    onGoToPage: (Int) -> Unit,
    onApply: (List<PlannedPage>) -> Unit,
    onExport: (pages: List<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    var plan by remember(pageCount) { mutableStateOf(PageArrangement.identity(pageCount)) }
    var selected by remember(pageCount) { mutableStateOf(setOf<Long>()) }
    var nextUid by remember(pageCount) { mutableStateOf(pageCount.toLong() + 1) }
    var confirmApply by remember { mutableStateOf(false) }
    var insertOpen by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<List<ImportCandidate>?>(null) }
    var staging by remember { mutableStateOf(false) }
    var canvasPicking by remember { mutableStateOf<PaperTarget?>(null) }
    // Remembered across insertions, so adding a second matching page is one tap rather than
    // rebuilding the same choices.
    var lastPaper by remember { mutableStateOf(PaperStyle()) }

    // Keyed by page *and* turn: a rotated thumbnail is a different picture, and two copies of
    // one page turned different ways must not share an entry.
    val thumbs = remember { mutableStateMapOf<String, Bitmap?>() }
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // The page being dragged, by position in the plan. Reordering happens as the finger passes
    // over another cell rather than on release, so the layout you can see is the layout you get.
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }

    val changed = !PageArrangement.isUnchanged(plan, pageCount)

    fun selectedIndices(): List<Int> =
        plan.indices.filter { plan[it].uid in selected }

    fun mutate(block: (MutableList<PlannedPage>) -> Unit) {
        plan = plan.toMutableList().also(block)
    }

    /** Where new pages go: after the last selected page, or at the end if nothing is selected. */
    fun insertionPoint(): Int? = selectedIndices().maxOrNull()

    fun insert(count: Int, w: Float, h: Float, paper: PaperStyle) {
        val at = insertionPoint()?.plus(1) ?: plan.size
        val fresh = (0 until count).map { PlannedPage(-1, nextUid++, w, h, paper) }
        mutate { it.addAll(at, fresh) }
        selected = fresh.map { it.uid }.toSet()
        lastPaper = paper
    }

    fun addImported(picked: List<Triple<ImportCandidate, Int, Boolean>>) {
        if (picked.isEmpty()) return
        val at = insertionPoint()?.plus(1) ?: plan.size
        val fresh = picked.map { (candidate, pageIndex, fit) ->
            val w = if (candidate.isImage && fit) defaultPageWidth else candidate.width
            val h = if (candidate.isImage && fit) defaultPageHeight else candidate.height
            PlannedPage(
                source = -1,
                uid = nextUid++,
                import = ImportedPage(
                    path = candidate.path,
                    pageIndex = pageIndex,
                    isImage = candidate.isImage,
                    width = w,
                    height = h,
                    fitToPage = fit
                )
            )
        }
        mutate { it.addAll(at, fresh) }
        selected = fresh.map { it.uid }.toSet()
    }

    fun turnSelected(quarterTurns: Int) {
        val picked = selectedIndices()
        if (picked.isEmpty()) return
        mutate { list ->
            for (i in picked) {
                list[i] = list[i].copy(
                    quarterTurns = (list[i].quarterTurns + quarterTurns).mod(4)
                )
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        staging = true
        scope.launch {
            val staged = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    ImportStaging.stage(context, uri)?.let {
                        ImportCandidate(
                            path = it.file.absolutePath,
                            displayName = it.displayName,
                            isImage = it.isImage,
                            pageCount = it.pageCount,
                            width = it.width,
                            height = it.height
                        )
                    }
                }
            }
            staging = false
            if (staged.isNotEmpty()) importing = staged
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {

                // ---- header ----
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                    Column(Modifier.weight(1f)) {
                        Text("Pages", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (changed) "${plan.size} pages after your changes"
                            else "$pageCount pages",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (changed) {
                        TextButton(onClick = { plan = PageArrangement.identity(pageCount) }) {
                            Text("Reset")
                        }
                        Button(onClick = { confirmApply = true }) { Text("Apply") }
                    }
                }

                // ---- canvas ----
                // A canvas has one page that grows, so arranging pages is meaningless for it and
                // the section below is hidden rather than left there doing nothing.
                if (canEditPages && (pageCount == 1 || canvasPaper != null)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Canvas", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (canvasPaper != null) {
                                    "This page grows whenever you write near an edge."
                                } else {
                                    "Let this page grow as you write, instead of ending where " +
                                        "it ends."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = canvasPaper != null,
                            onCheckedChange = { on ->
                                onCanvasChange(if (on) canvasPaper ?: PaperStyle() else null)
                            }
                        )
                    }
                    if (canvasPaper != null) {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Text(
                                "Paper beyond the original page",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            BlankPaperOptions(
                                style = canvasPaper,
                                onChange = { onCanvasChange(it) },
                                onPickColour = { target, _ -> canvasPicking = target }
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }

                // ---- arrangement ----
                // Meaningless for a canvas: it is one page, and it is placed at its own origin
                // whatever the arrangement says.
                if (canvasPaper == null) {
                    Text(
                        "Arrangement",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 6.dp)
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PageLayout.entries.forEach { option ->
                            FilterChip(
                                selected = layout == option,
                                onClick = { onLayoutChange(option) },
                                label = { Text(option.label) }
                            )
                        }
                    }
                }

                HorizontalDivider()

                if (!canEditPages) {
                    Text(
                        "This document has a single page that cannot be rearranged. " +
                            "The arrangement options above still apply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                // ---- the pages ----
                Box(Modifier.weight(1f)) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(116.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (!canEditPages) Modifier
                                else Modifier.pointerInput(plan.size) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { at ->
                                            pointer = at
                                            dragIndex = indexAt(gridState, at)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            pointer += amount
                                            val from = dragIndex ?: return@detectDragGesturesAfterLongPress
                                            val to = indexAt(gridState, pointer)
                                            if (to != null && to != from && to in plan.indices) {
                                                mutate { it.add(to, it.removeAt(from)) }
                                                dragIndex = to
                                            }
                                            // Creep the grid when the finger reaches an edge, so a
                                            // page can be dragged further than one screenful.
                                            val h = size.height
                                            if (pointer.y < h * 0.12f) {
                                                scope.launch { gridState.creepBy(-24f) }
                                            } else if (pointer.y > h * 0.88f) {
                                                scope.launch { gridState.creepBy(24f) }
                                            }
                                        },
                                        onDragEnd = { dragIndex = null },
                                        onDragCancel = { dragIndex = null }
                                    )
                                }
                            )
                    ) {
                        itemsIndexed(plan, key = { _, p -> p.uid }) { index, p ->
                            val key = thumbKey(p)
                            LaunchedEffect(key) {
                                if (key == null || thumbs.containsKey(key)) return@LaunchedEffect
                                val raw = when {
                                    p.import != null ->
                                        importThumbnailFor(p.import.path, p.import.pageIndex)
                                    else -> thumbnailFor(p.source)
                                }
                                thumbs[key] = turned(raw, p.quarterTurns)
                            }
                            PageCell(
                                planned = p,
                                position = index,
                                bitmap = key?.let { thumbs[it] },
                                selected = p.uid in selected,
                                dragging = dragIndex == index,
                                isCurrent = !p.isNew && p.source == currentPage && !changed,
                                modifier = Modifier.animateItem(),
                                onClick = {
                                    if (!canEditPages) {
                                        if (!p.isNew) { onGoToPage(p.source); onDismiss() }
                                        return@PageCell
                                    }
                                    selected = if (p.uid in selected) selected - p.uid
                                    else selected + p.uid
                                },
                                onOpen = {
                                    // Jumping to a page you have already planned changes for
                                    // would show the old document, which is only confusing.
                                    if (!changed && !p.isNew) { onGoToPage(p.source); onDismiss() }
                                }
                            )
                        }
                    }
                }

                // ---- actions ----
                if (canEditPages) {
                    HorizontalDivider()
                    val count = selectedIndices().size
                    Text(
                        if (count == 0) "Tap to select · hold to drag"
                        else "$count selected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(onClick = { insertOpen = true }) {
                            Icon(Icons.Default.Add, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Insert")
                        }

                        TextButton(
                            enabled = !staging,
                            onClick = {
                                // PDFs and pictures in one picker: from here they are the same
                                // thing, which is a page this document is about to gain.
                                picker.launch(arrayOf("application/pdf", "image/*"))
                            }
                        ) {
                            Icon(Icons.Default.FileOpen, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (staging) "Reading..." else "Import")
                        }

                        TextButton(
                            enabled = count > 0,
                            onClick = { turnSelected(1) }
                        ) {
                            Icon(Icons.Default.RotateRight, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Rotate")
                        }

                        TextButton(
                            enabled = count > 0,
                            onClick = {
                                val picked = selectedIndices()
                                val copies = ArrayList<Long>()
                                mutate { list ->
                                    // Right to left, so earlier insertions do not shift the
                                    // indices of the ones still to come.
                                    for (i in picked.sortedDescending()) {
                                        val uid = nextUid++
                                        copies.add(uid)
                                        list.add(i + 1, list[i].copy(uid = uid))
                                    }
                                }
                                selected = copies.toSet()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Duplicate")
                        }

                        TextButton(
                            // Exports the document as it is on disk, so an unapplied plan would
                            // export something other than what is on screen. Better to make that
                            // impossible than to explain it afterwards.
                            enabled = count > 0 && !changed,
                            onClick = {
                                onExport(selectedIndices().mapNotNull {
                                    plan[it].source.takeIf { s -> s >= 0 }
                                })
                            }
                        ) {
                            Icon(Icons.Default.Share, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Export")
                        }

                        TextButton(
                            // A document with no pages is not a document.
                            enabled = count > 0 && count < plan.size,
                            onClick = {
                                mutate { list -> list.removeAll { it.uid in selected } }
                                selected = emptySet()
                            }
                        ) {
                            Icon(
                                Icons.Default.Delete, null, Modifier.size(17.dp),
                                tint = if (count > 0 && count < plan.size) {
                                    MaterialTheme.colorScheme.error
                                } else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }

    if (insertOpen) {
        // Match the page it is going next to rather than the first page of the document: a
        // landscape sheet dropped into the middle of a portrait chapter is never what was meant,
        // and neither is the reverse.
        val neighbour = insertionPoint()?.let { plan.getOrNull(it) }
        val match = when {
            neighbour == null -> defaultPageWidth to defaultPageHeight
            neighbour.isNew -> neighbour.blankWidth to neighbour.blankHeight
            else -> runCatching { pageSizeAt(neighbour.source) }
                .getOrDefault(defaultPageWidth to defaultPageHeight)
        }
        InsertPagesDialog(
            documentWidth = match.first,
            documentHeight = match.second,
            initial = lastPaper,
            insertAfter = insertionPoint(),
            onDismiss = { insertOpen = false },
            onInsert = { count, w, h, paper ->
                insertOpen = false
                insert(count, w, h, paper)
            }
        )
    }

    canvasPicking?.let { target ->
        val isPaper = target == PaperTarget.PAPER
        val style = canvasPaper ?: PaperStyle()
        ColorPickerDialog(
            initial = if (isPaper) style.paperColor else style.lineColor,
            title = if (isPaper) "Paper colour" else "Ruling colour",
            presets = if (isPaper) BlankDocumentFactory.PAPER_COLORS
            else BlankDocumentFactory.LINE_COLORS,
            onDismiss = { canvasPicking = null },
            onPick = { picked ->
                onCanvasChange(
                    if (isPaper) style.copy(paperColor = picked)
                    else style.copy(lineColor = picked)
                )
                canvasPicking = null
            }
        )
    }

    importing?.let { candidates ->
        ImportPagesDialog(
            candidates = candidates,
            documentWidth = defaultPageWidth,
            documentHeight = defaultPageHeight,
            insertAfter = insertionPoint(),
            onDismiss = { importing = null },
            onImport = { picked ->
                importing = null
                addImported(picked)
            }
        )
    }

    if (confirmApply) {
        AlertDialog(
            onDismissRequest = { confirmApply = false },
            title = { Text("Rearrange the document?") },
            text = {
                val turned = plan.count { it.quarterTurns != 0 }
                val brought = plan.count { it.isImported }
                Text(
                    buildString {
                        append("This rewrites the file itself: ${plan.size} page")
                        append(if (plan.size == 1) "" else "s")
                        append(", from $pageCount.")
                        if (turned > 0) append(" $turned turned.")
                        if (brought > 0) append(" $brought brought in from other files.")
                        append(
                            "\n\nYour handwriting moves and turns with its pages, and marks on " +
                                "removed pages go with them.\n\n" +
                                "The previous version of your handwriting is kept in version " +
                                "history. Your other devices will pick up the new order when " +
                                "they next sync."
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmApply = false; onApply(plan) }) { Text("Rearrange") }
            },
            dismissButton = {
                TextButton(onClick = { confirmApply = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Cache key for a page's thumbnail: which page, from which file, turned which way.
 *
 * Null for a blank page, which has no picture to fetch - it draws its own paper.
 */
private fun thumbKey(p: PlannedPage): String? = when {
    p.import != null -> "imp:${p.import.path}:${p.import.pageIndex}:${p.quarterTurns}"
    p.isNew -> null
    else -> "doc:${p.source}:${p.quarterTurns}"
}

/**
 * Turn a thumbnail to match its page.
 *
 * Rotating the bitmap rather than the composable that shows it: a view rotated ninety degrees
 * inside a portrait cell overflows it, and correcting for that in layout is more code than
 * turning a 320-pixel image once and caching the result.
 */
private fun turned(bitmap: Bitmap?, quarterTurns: Int): Bitmap? {
    val src = bitmap ?: return null
    val q = ((quarterTurns % 4) + 4) % 4
    if (q == 0) return src
    val m = android.graphics.Matrix().apply { postRotate(90f * q) }
    return runCatching {
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }.getOrDefault(src)
}

/** Which cell a point in the grid is over, or null between them. */
private fun indexAt(
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    at: Offset
): Int? = state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
    at.x >= item.offset.x && at.x <= item.offset.x + item.size.width &&
        at.y >= item.offset.y && at.y <= item.offset.y + item.size.height
}?.index

/** Nudge the grid while a page is being dragged past its edge. */
private suspend fun androidx.compose.foundation.lazy.grid.LazyGridState.creepBy(px: Float) {
    scroll { scrollBy(px) }
}

@Composable
private fun PageCell(
    planned: PlannedPage,
    position: Int,
    bitmap: Bitmap?,
    selected: Boolean,
    dragging: Boolean,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onOpen: () -> Unit
) {
    val border = when {
        dragging -> MaterialTheme.colorScheme.tertiary
        selected -> MaterialTheme.colorScheme.primary
        isCurrent -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.77f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    if (selected || dragging) 2.5.dp else 1.dp,
                    border,
                    RoundedCornerShape(6.dp)
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            when {
                // A new page draws its actual paper, because the colours are half of what was
                // chosen and a label cannot show them. An imported one has a thumbnail like any
                // other page, so it falls through to the branch below.
                planned.isNew && !planned.isImported -> Box(Modifier.fillMaxSize()) {
                    PaperPreview(planned.paper, Modifier.fillMaxSize())
                    Text(
                        planned.paper.background.label,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                    )
                }
                bitmap != null -> Image(
                    bitmap.asImageBitmap(),
                    "Page ${position + 1}",
                    Modifier.fillMaxSize()
                )
                else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (dragging) {
                Icon(
                    Icons.Default.DragIndicator, null,
                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        Text(
            "${position + 1}" + when {
                planned.isImported -> " · imported"
                planned.isNew -> " · new"
                planned.quarterTurns != 0 -> " · turned"
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).clickable(onClick = onOpen)
        )
    }
}
