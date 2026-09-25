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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inkslate.core.PagePlan
import com.inkslate.ink.PageLayout
import com.inkslate.pdf.BlankDocumentFactory
import com.inkslate.pdf.ImportStaging
import com.inkslate.pdf.ImportedPage
import com.inkslate.pdf.PageArrangement
import com.inkslate.pdf.PlannedPage
import com.inkslate.ui.BlankPaperOptions
import com.inkslate.ui.ColorPickerDialog
import com.inkslate.ui.PaperPreview
import com.inkslate.ui.PaperStyle
import com.inkslate.ui.PaperTarget
import com.inkslate.ui.toSpec
import com.inkslate.ui.toStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pages: what pages the document has, and how they are laid out on screen.
 *
 * Laid out the way the Windows build lays it out, because that is the version that reads well: a
 * header that says whether anything is pending, one row of actions on the page in hand, and the
 * pages beneath. It used to be a full-screen dialog with a row of labelled buttons along the bottom,
 * which put the actions as far from the pages they act on as the screen allowed.
 *
 * What the tablet has on top of that is only what the Windows sheet has no room for or no need of:
 * the arrangement and canvas controls (on Windows those sit in the view menu), holding a page to
 * drag it, and exporting a page.
 *
 * ## Several pages at once
 *
 * Each page has a ring in its corner; ticking it picks that page as well. Once more than one page
 * is picked, tapping a page ticks or unticks it too, the way a photo gallery behaves, so a stray
 * tap cannot throw away a selection of fifteen pages. There is still one row of actions: every
 * button in it acts on whatever is picked.
 *
 * Structural edits are planned rather than applied. Everything here operates on a list of
 * [PlannedPage], so deleting thirty pages, changing your mind and closing the sheet costs nothing;
 * the document on disk is not touched until Apply, and then in one verified rewrite.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    onDismiss: () -> Unit,
    /** More actions for the picked pages, from the app this runs in. */
    extraActions: (@Composable (pages: List<Int>, close: () -> Unit) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var plan by remember(pageCount) { mutableStateOf(PageArrangement.identity(pageCount)) }
    // The pages in hand, by uid rather than position, as on Windows. Copying or moving them
    // changes every position, and the selection has to follow the pages that were picked; the
    // arrows carry them along, so moving a page five places is five taps on the same button.
    var picked by remember(pageCount) {
        mutableStateOf(setOf(currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)).toLong()))
    }
    var nextUid by remember(pageCount) { mutableStateOf(pageCount.toLong() + 1000L) }
    var confirmApply by remember { mutableStateOf(false) }
    var insertOpen by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<List<ImportCandidate>?>(null) }
    var staging by remember { mutableStateOf(false) }
    var canvasPicking by remember { mutableStateOf<PaperTarget?>(null) }
    // A blank page in the plan whose paper is being changed, by uid so a drag cannot retarget it.
    var restyling by remember { mutableStateOf<Long?>(null) }
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
    val pickedAt = PagePlan.indicesOf(plan, picked)
    // New pages go after the last picked one, or at the end when nothing is picked.
    val inHand = pickedAt.lastOrNull() ?: plan.lastIndex
    val inHandPage = plan.getOrNull(inHand)
    val picking = pickedAt.size > 1

    fun toggle(uid: Long) {
        picked = if (uid in picked) picked - uid else picked + uid
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

    // Tall enough on a tablet to show a few rows of pages; never the whole screen, which is what
    // made the old dialog feel like leaving the document.
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {

            // ---- header ----
            val count = if (picking) {
                "${pickedAt.size} of ${plan.size} pages picked"
            } else {
                "${plan.size} page${if (plan.size == 1) "" else "s"}"
            }
            PanelTop(
                "Pages",
                onDismiss,
                if (changed) "$count - nothing is written until you apply" else count
            ) {
                if (changed) {
                    TextButton(onClick = { plan = PageArrangement.identity(pageCount) }) {
                        Text("Undo all")
                    }
                }
                if (canEditPages) {
                    TextButton(enabled = changed, onClick = { confirmApply = true }) {
                        Text("Apply")
                    }
                }
            }

            // ---- canvas ----
            // A canvas has one page that grows, so arranging pages is meaningless for it and the
            // arrangement row below is hidden rather than left there doing nothing.
            if (canEditPages && (pageCount == 1 || canvasPaper != null)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Canvas", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (canvasPaper != null) "This page grows as you write near an edge"
                            else "Let this page grow as you write",
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
                    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp)) {
                        BlankPaperOptions(
                            style = canvasPaper,
                            onChange = { onCanvasChange(it) },
                            onPickColour = { target, _ -> canvasPicking = target }
                        )
                    }
                }
            }

            // ---- arrangement ----
            if (canvasPaper == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
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

            // ---- what can be done to the page in hand ----
            if (canEditPages) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val earlier = PagePlan.movedAll(plan, picked, -1)
                    val later = PagePlan.movedAll(plan, picked, 1)
                    IconButton(onClick = { plan = earlier }, enabled = earlier != plan) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Move earlier")
                    }
                    IconButton(onClick = { plan = later }, enabled = later != plan) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Move later")
                    }
                    IconButton(
                        onClick = { plan = PagePlan.turnedAll(plan, picked, -1) },
                        enabled = pickedAt.isNotEmpty()
                    ) { Icon(Icons.Default.RotateLeft, "Turn left") }
                    IconButton(
                        onClick = { plan = PagePlan.turnedAll(plan, picked, 1) },
                        enabled = pickedAt.isNotEmpty()
                    ) { Icon(Icons.Default.RotateRight, "Turn right") }
                    IconButton(
                        onClick = {
                            val (copied, copies) = PagePlan.duplicatedAll(plan, picked) { nextUid++ }
                            plan = copied
                            // Onto the copies, so a second tap copies the copies rather than
                            // stacking another set of the originals in between.
                            picked = copies
                        },
                        enabled = pickedAt.isNotEmpty()
                    ) { Icon(Icons.Default.Layers, "Copy") }
                    // Not a plain white page on one tap, as on Windows: the paper is half of what a
                    // blank page is, so this asks - pattern, both colours, spacing, size, how many.
                    IconButton(onClick = { insertOpen = true }) {
                        Icon(Icons.Default.Add, "Add blank pages after this one")
                    }
                    IconButton(
                        enabled = !staging,
                        // PDFs and pictures in one picker: from here they are the same thing,
                        // which is a page this document is about to gain.
                        onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }
                    ) {
                        if (staging) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.LibraryAdd, "Add pages from another file")
                    }
                    IconButton(
                        // Exports the document as it is on disk, so an unapplied plan would export
                        // something other than what is on screen. Better impossible than explained.
                        enabled = !changed && pickedAt.isNotEmpty() &&
                            pickedAt.none { plan[it].isNew },
                        onClick = { onExport(pickedAt.map { plan[it].source }.sorted()) }
                    ) { Icon(Icons.Default.Share, "Export") }
                    if (extraActions != null && !changed && pickedAt.isNotEmpty() && pickedAt.none { plan[it].isNew }) {
                        extraActions(pickedAt.map { plan[it].source }.sorted(), onDismiss)
                    }
                    val everything = pickedAt.size == plan.size
                    IconButton(
                        onClick = {
                            picked = if (everything) {
                                setOfNotNull(plan.getOrNull(pickedAt.firstOrNull() ?: 0)?.uid)
                            } else {
                                plan.map { it.uid }.toSet()
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.SelectAll,
                            if (everything) "Pick one page" else "Pick every page",
                            tint = if (everything && plan.size > 1) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(Modifier.weight(1f))
                    val canRemove = pickedAt.isNotEmpty() && pickedAt.size < plan.size
                    IconButton(
                        onClick = {
                            val at = pickedAt.first()
                            plan = PagePlan.removedAll(plan, picked)
                            picked = setOf(plan[at.coerceAtMost(plan.lastIndex)].uid)
                        },
                        enabled = canRemove
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            if (picking) "Remove ${pickedAt.size} pages" else "Remove",
                            tint = if (canRemove) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            } else {
                Text(
                    "This document is a single picture, so it has no pages to rearrange.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            HorizontalDivider()

            // ---- the pages ----
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 118.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .then(
                        if (!canEditPages) Modifier
                        else Modifier.pointerInput(plan.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { at ->
                                    pointer = at
                                    dragIndex = indexAt(gridState, at)
                                    dragIndex?.let { i -> plan.getOrNull(i)?.let { picked = setOf(it.uid) } }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    pointer += amount
                                    val from = dragIndex ?: return@detectDragGesturesAfterLongPress
                                    val to = indexAt(gridState, pointer)
                                    if (to != null && to != from && to in plan.indices) {
                                        plan = PagePlan.moved(plan, from, to)
                                        dragIndex = to
                                    }
                                    // Creep the grid when the finger reaches an edge, so a page
                                    // can be dragged further than one screenful.
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
                        val imported = p.import
                        val raw = when {
                            imported != null -> importThumbnailFor(imported.path, imported.pageIndex)
                            else -> thumbnailFor(p.source)
                        }
                        thumbs[key] = turned(raw, p.quarterTurns)
                    }
                    PageTile(
                        planned = p,
                        position = index,
                        bitmap = key?.let { thumbs[it] },
                        selected = canEditPages && p.uid in picked,
                        tick = if (canEditPages) {
                            { toggle(p.uid) }
                        } else {
                            null
                        },
                        dragging = dragIndex == index,
                        isCurrent = !p.isNew && p.source == currentPage && !changed,
                        modifier = Modifier.animateItem(),
                        onClick = {
                            // Tapping the page already in hand opens it - the touch version of
                            // the double-click on Windows. A planned page has nowhere to open to,
                            // and neither does any page once the order has changed.
                            // A blank page just added has nothing to open, so tapping it again
                            // brings back its paper instead: what was picked is still changeable.
                            val open = !canEditPages || (!picking && p.uid in picked)
                            if (canEditPages && picking) {
                                toggle(p.uid)
                            } else if (open && canEditPages && p.isNew && !p.isImported) {
                                restyling = p.uid
                            } else if (open) {
                                if (!changed && !p.isNew) { onGoToPage(p.source); onDismiss() }
                            } else {
                                picked = setOf(p.uid)
                            }
                        }
                    )
                }
            }
        }
    }

    if (insertOpen) {
        // Match the page it is going next to rather than the first page of the document: a
        // landscape sheet dropped into the middle of a portrait chapter is never what was meant,
        // and neither is the reverse.
        val neighbour = inHandPage
        val match = when {
            neighbour == null -> defaultPageWidth to defaultPageHeight
            neighbour.isImported -> neighbour.import!!.width to neighbour.import!!.height
            neighbour.isNew -> neighbour.blankWidth to neighbour.blankHeight
            else -> runCatching { pageSizeAt(neighbour.source) }
                .getOrDefault(defaultPageWidth to defaultPageHeight)
        }
        InsertPagesDialog(
            documentWidth = match.first,
            documentHeight = match.second,
            initial = lastPaper,
            insertAfter = inHand,
            onDismiss = { insertOpen = false },
            onInsert = { count, w, h, paper ->
                insertOpen = false
                val fresh = (0 until count).map {
                    PlannedPage(source = -1, uid = nextUid++, blankWidth = w, blankHeight = h, paper = paper.toSpec())
                }
                plan = PagePlan.inserted(plan, inHand + 1, fresh)
                picked = fresh.map { it.uid }.toSet()
                lastPaper = paper
            }
        )
    }

    restyling?.let { uid ->
        val at = plan.indexOfFirst { it.uid == uid }
        val page = plan.getOrNull(at)
        if (page != null) {
            InsertPagesDialog(
                documentWidth = page.blankWidth,
                documentHeight = page.blankHeight,
                initial = page.paper.toStyle(),
                insertAfter = null,
                editingPage = at,
                onDismiss = { restyling = null },
                onInsert = { _, w, h, paper ->
                    restyling = null
                    plan = plan.toMutableList().also {
                        it[at] = page.copy(blankWidth = w, blankHeight = h, paper = paper.toSpec())
                    }
                    lastPaper = paper
                }
            )
        }
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
            insertAfter = inHand,
            onDismiss = { importing = null },
            onImport = { chosen ->
                importing = null
                if (chosen.isEmpty()) return@ImportPagesDialog
                val fresh = chosen.map { (candidate, pageIndex, fit) ->
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
                plan = PagePlan.inserted(plan, inHand + 1, fresh)
                picked = fresh.map { it.uid }.toSet()
            }
        )
    }

    // Applying rewrites the document itself, which is not something to do on one tap of a button
    // sitting next to "Undo all". The summary says what is about to happen in the terms someone is
    // thinking in - pages, turns, and where the handwriting goes.
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
                    },
                    style = MaterialTheme.typography.bodySmall
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
private fun thumbKey(p: PlannedPage): String? = p.import.let { imported ->
    when {
        imported != null -> "imp:${imported.path}:${imported.pageIndex}:${p.quarterTurns}"
        p.isNew -> null
        else -> "doc:${p.source}:${p.quarterTurns}"
    }
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

/** One page, drawn the way the Windows sheet draws it: a tinted card, the page, its number. */
@Composable
private fun PageTile(
    planned: PlannedPage,
    position: Int,
    bitmap: Bitmap?,
    selected: Boolean,
    /** Ticks or unticks the page, or null where the corner ring is not worth showing. */
    tick: (() -> Unit)?,
    dragging: Boolean,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val edge = when {
        dragging -> MaterialTheme.colorScheme.tertiary
        selected -> MaterialTheme.colorScheme.primary
        isCurrent -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected || dragging) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.77f)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .border(
                    if (selected || dragging || isCurrent) 2.dp else 1.dp,
                    edge,
                    RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                // A new blank page draws its actual paper, because the colours are half of what
                // was chosen and a label cannot show them.
                planned.isNew && !planned.isImported -> Box(Modifier.fillMaxSize()) {
                    val style = planned.paper.toStyle()
                    PaperPreview(style, Modifier.fillMaxSize())
                    Text(
                        style.background.label,
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
            if (tick != null) PickTick(selected, tick, Modifier.align(Alignment.TopStart))
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
                planned.isImported -> "  ·  imported"
                planned.isNew -> "  ·  new"
                planned.quarterTurns != 0 -> "  ·  turned"
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * The ring in a page's corner: picks the page as well as the ones already picked, or unpicks it.
 *
 * Big enough to hit with a fingertip, and drawn empty until it is ticked, so the grid says that
 * pages can be picked together without a sentence saying so.
 */
@Composable
private fun PickTick(ticked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clickable(onClick = onToggle)
            .padding(6.dp)
            .clip(CircleShape)
            .background(
                if (ticked) MaterialTheme.colorScheme.primary
                else Color.White.copy(alpha = 0.85f)
            )
            .border(
                1.5.dp,
                if (ticked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (ticked) {
            Icon(
                Icons.Default.Check, "Picked",
                Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
