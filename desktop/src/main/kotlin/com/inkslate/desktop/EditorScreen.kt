package com.inkslate.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.PageLayout
import com.inkslate.core.SaveMode
import com.inkslate.core.SearchHit
import com.inkslate.core.Palette
import com.inkslate.core.Stroke
import com.inkslate.core.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the title bar says about the document's relationship to the disk. */
private enum class WriteState(val label: String) {
    SAVED("saved"), UNSAVED("unsaved"), SAVING("saving...")
}

/**
 * Objects cut or copied, kept for as long as the app runs.
 *
 * A companion object rather than editor state, because the whole point of it is to survive
 * closing one document and opening another - which is how a diagram gets moved from last week's
 * notes into this week's.
 */
private object Clipboard {
    var contents: List<Stroke> = emptyList()
}

/**
 * The editor.
 *
 * Same documents, same format, same merge rules as the tablet - the model comes from `:core` and
 * the handwriting is stored inside the document, so a file synced from the tablet opens here with
 * its annotations already on it and no companion file to bring along.
 *
 * What is deliberately different is the input. A mouse has no pressure and most desktop pens
 * report it inconsistently through the JVM, so line weight falls back to speed when pressure is
 * not there. That is a better approximation of handwriting than a dead constant width, and it
 * matches what the tablet does for finger input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    file: File,
    shortcuts: Shortcuts,
    navigation: NavigationHooks,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val textMeasurer = rememberTextMeasurer()
    val tools = remember { ToolState() }
    val prefs = remember { SavePrefs() }

    var source by remember(file) { mutableStateOf<DesktopSource?>(null) }
    var ink by remember(file) { mutableStateOf(InkDocument.create("", "pdf", 0, 0L, "")) }
    val strokes = remember(file) { mutableStateListOf<Stroke>() }
    val undo = remember(file) { mutableStateListOf<Op>() }
    val redo = remember(file) { mutableStateListOf<Op>() }

    var selection by remember(file) { mutableStateOf<Set<String>>(emptySet()) }
    var dirty by remember(file) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var status by remember(file) { mutableStateOf("Opening ${file.name}...") }
    var busy by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf<Stroke?>(null) }
    var newTextAt by remember { mutableStateOf<Stroke?>(null) }
    var pickingColour by remember { mutableStateOf(false) }
    var stampsOpen by remember { mutableStateOf(false) }
    var symbolsOpen by remember { mutableStateOf(false) }
    var pagesOpen by remember { mutableStateOf(false) }
    var versionsOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var reopenTick by remember { mutableStateOf(0) }
    // Restored once per open, or every recomposition would drag the view back.
    var positionRestored by remember(file) { mutableStateOf(false) }
    var armedStampLabel by remember { mutableStateOf<String?>(null) }
    var navOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var bookmarkPrompt by remember { mutableStateOf<Int?>(null) }
    // Null while the outline is still being read, so the sheet can tell "none" from "not yet".
    var outline by remember(file) { mutableStateOf<List<OutlineEntry>?>(null) }
    var bookmarks by remember(file) { mutableStateOf<List<InkDocument.Bookmark>>(emptyList()) }
    var searchQuery by remember(file) { mutableStateOf("") }
    var searchHits by remember(file) { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchProgress by remember { mutableStateOf(0) }
    var askMode by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var fileRulesOpen by remember { mutableStateOf(false) }

    val viewport = remember(file) { Viewport() }
    var page by remember(file) { mutableStateOf(0) }
    var layout by remember { mutableStateOf(PageLayout.VERTICAL) }
    var pageFilter by remember { mutableStateOf(PageFilter.NONE) }

    val ids = remember(file) { mutableStateOf(0) }
    val deviceTag = remember { DocumentIO.deviceTag() }
    fun nextId(): String = "$deviceTag-d${++ids.value}"

    fun pushOp(op: Op) {
        undo.add(op)
        redo.clear()
        dirty = true
    }

    // ---- opening -------------------------------------------------------------

    LaunchedEffect(file.absolutePath, reopenTick) {
        busy = true
        val loaded = withContext(Dispatchers.IO) {
            val src = DesktopSources.open(file) ?: return@withContext null
            val doc = DocumentIO.load(file, src.pageCount)
            // Anything autosaved here since the last explicit save is folded back in.
            val working = DocumentIO.loadWorking(file)
            val merged = if (working == null) doc.ink else doc.ink.mergeWith(working)
            Triple(src, doc, merged)
        }
        if (loaded == null) {
            status = "Could not open ${file.name}"
            busy = false
            return@LaunchedEffect
        }
        val (src, doc, merged) = loaded
        source = src
        ink = merged
        strokes.clear()
        strokes.addAll((0 until src.pageCount).flatMap { p ->
            merged.strokesOn(p).map { if (it.pageIndex == p) it else it.copy(pageIndex = p) }
        })
        // Never mint an id that a synced-in edit is already using.
        ids.value = strokes.mapNotNull { it.id.substringAfter("-d", "").toIntOrNull() }
            .maxOrNull() ?: 0
        undo.clear(); redo.clear()
        dirty = false
        status = buildString {
            append("${merged.totalStrokes} mark(s)")
            if (doc.mergedConflicts > 0) {
                append("  ·  merged ${doc.mergedConflicts} sync conflict(s)")
            }
            if (doc.sourceChanged) append("  ·  the file changed since these were saved")
        }
        busy = false

    }

    // Remember where the document was left, as it is left rather than on every scroll: this is a
    // file on disk, and writing it on each frame of a drag would be a stream of writes for a
    // number nobody reads until the document is opened again.
    DisposableEffect(file.absolutePath, source) {
        onDispose {
            if (source != null) {
                ReadingPosition.save(
                    file.absolutePath, page, layout,
                    viewport.scale, viewport.offset.x, viewport.offset.y
                )
            }
        }
    }

    // The table of contents is read off the file rather than held in the ink, so it is fetched
    // once the document is open and never again for that file.
    LaunchedEffect(file.absolutePath) {
        outline = withContext(Dispatchers.IO) {
            if (DesktopSources.isPdf(file)) DocumentText.outline(file) else emptyList()
        }
    }

    LaunchedEffect(ink) { bookmarks = ink.bookmarks }

    // ---- saving --------------------------------------------------------------

    fun currentInk(): InkDocument {
        var next = ink
        val byPage = strokes.groupBy { it.pageIndex }
        for (p in 0 until (source?.pageCount ?: 0)) {
            val existing = next.strokesOn(p)
            val updated = byPage[p].orEmpty()
            val same = existing.size == updated.size &&
                existing.indices.all { existing[it] === updated[it] }
            if (!same) next = next.withPage(p, updated, deviceTag)
        }
        return next
    }

    /**
     * Write the document out under the rules this file saves by.
     *
     * The working copy is written first and unconditionally: it is what stands between a failed
     * write and a lost afternoon, and it has to be ahead of the document rather than behind it.
     */
    fun writeWith(mode: SaveMode, then: (() -> Unit)? = null) {
        busy = true
        saving = true
        scope.launch {
            val doc = currentInk()
            ink = doc
            withContext(Dispatchers.IO) { DocumentIO.saveWorking(file, doc) }
            val settings = prefs.effectiveFor(file.absolutePath).copy(mode = mode)
            val result = withContext(Dispatchers.IO) {
                DocumentExport.save(file, doc, settings)
            }
            when (result) {
                is SaveResult.Written -> {
                    dirty = false
                    status = if (result.wasCopy) {
                        "Saved a copy: ${result.target.name}"
                    } else {
                        "Overwrote ${file.name}" + (result.backup?.let { " (backup kept)" } ?: "")
                    }
                    snackbar.showSnackbar(status)
                }
                is SaveResult.Failed -> {
                    // The working copy still has it, which is the whole reason that copy exists.
                    status = result.error.message ?: "Save failed"
                    snackbar.showSnackbar(status)
                }
                SaveResult.NothingToDo -> {
                    dirty = false
                    status = "Nothing drawn yet, so nothing was written"
                }
            }
            saving = false
            busy = false
            if (result is SaveResult.Written) then?.invoke()
        }
    }

    /**
     * Save, asking only what the rules say to ask.
     *
     * Overwriting is the one action here that can destroy something the app did not create, so
     * confirming it is a rule of its own rather than something assumed either way.
     */
    fun save(then: (() -> Unit)? = null) {
        val settings = prefs.effectiveFor(file.absolutePath)
        when {
            settings.mode == SaveMode.ASK -> askMode = true
            settings.mode == SaveMode.OVERWRITE && settings.confirmOverwrite ->
                confirmOverwrite = true
            else -> writeWith(settings.mode, then)
        }
    }

    fun exportFlattened() {
        busy = true
        scope.launch {
            val doc = currentInk()
            val result = withContext(Dispatchers.IO) { DocumentIO.exportFlattened(file, doc) }
            status = result.fold(
                onSuccess = { "Exported ${it.name}" },
                onFailure = { "Export failed: ${it.message}" }
            )
            busy = false
        }
    }

    // Autosave to the local working copy. The document itself is only written on an explicit
    // save, so this is what stands between a crash and a lost afternoon.
    LaunchedEffect(file.absolutePath) {
        while (true) {
            val settings = prefs.effectiveFor(file.absolutePath)
            delay((settings.autosaveSeconds.coerceIn(5, 600)) * 1000L)
            if (!dirty || !settings.autosave) continue
            val doc = currentInk()
            withContext(Dispatchers.IO) { DocumentIO.saveWorking(file, doc) }
        }
    }

    fun undoOnce() {
        val op = undo.removeLastOrNull() ?: return
        strokes.apply(op, forward = false)
        redo.add(op)
        selection = emptySet()
        dirty = true
    }

    fun redoOnce() {
        val op = redo.removeLastOrNull() ?: return
        strokes.apply(op, forward = true)
        undo.add(op)
        selection = emptySet()
        dirty = true
    }

    // ---- selection -----------------------------------------------------------

    fun selected(): List<Stroke> = strokes.filter { it.id in selection }

    fun replaceSelection(transform: (Stroke) -> Stroke) {
        val before = selected()
        if (before.isEmpty()) return
        val after = before.map(transform)
        strokes.applyEdit(before, after)
        pushOp(Op(before, after))
    }

    fun deleteSelection() {
        val before = selected()
        if (before.isEmpty()) return
        strokes.applyEdit(before, emptyList())
        pushOp(Op.removed(before))
        selection = emptySet()
    }

    fun duplicateSelection() {
        val before = selected()
        if (before.isEmpty()) return
        val now = System.currentTimeMillis()
        // Offset a little, or the copy hides exactly on top of the original and looks like
        // nothing happened.
        val copies = before.map {
            it.copy(id = nextId(), updatedUtc = now).movedBy(12f, 12f, now)
        }
        strokes.addAll(copies)
        pushOp(Op.added(copies))
        selection = copies.map { it.id }.toSet()
    }

    fun copySelection(): Int {
        val chosen = selected()
        if (chosen.isNotEmpty()) Clipboard.contents = chosen
        return chosen.size
    }

    fun paste() {
        val from = Clipboard.contents
        if (from.isEmpty()) {
            scope.launch { snackbar.showSnackbar("Nothing copied yet") }
            return
        }
        val now = System.currentTimeMillis()
        val target = page
        val pasted = from.map {
            it.copy(id = nextId(), pageIndex = target, updatedUtc = now).movedBy(16f, 16f, now)
        }
        strokes.addAll(pasted)
        pushOp(Op.added(pasted))
        selection = pasted.map { it.id }.toSet()
        tools.edit { it.tool = Tool.SELECT }
    }

    /** Put a page at the top of the window, which is what every jump here means. */
    fun goToPage(target: Int) {
        val src = source ?: return
        val clamped = target.coerceIn(0, src.pageCount - 1)
        val extents = (0 until src.pageCount).map {
            val d = src.pageDim(it)
            com.inkslate.core.PageExtent(d.width, d.height)
        }
        val origins = com.inkslate.core.PageArranger.arrange(extents, layout, clamped)
        origins.getOrNull(clamped)?.let { (x, y) -> viewport.goTo(x, y) }
        page = clamped
    }

    /**
     * Pick up where this document was left.
     *
     * Once per open, and after the pages have been measured - a camera restored before the
     * viewport knows how big the document is has nothing to be restored against. A document
     * reopened at the top when you were halfway through a problem set is a document you have to
     * find your place in again every time.
     */
    LaunchedEffect(source, viewport.viewSize) {
        val src = source ?: return@LaunchedEffect
        if (positionRestored || viewport.viewSize.width <= 0f) return@LaunchedEffect
        positionRestored = true
        val at = ReadingPosition.load(file.absolutePath) ?: return@LaunchedEffect
        layout = at.layout
        page = at.page.coerceIn(0, src.pageCount - 1)
        if (at.hasCamera) viewport.restore(at.scale!!, at.x!!, at.y!!) else goToPage(page)
    }

    fun runSearch() {
        val src = source ?: return
        searching = true
        searchHits = emptyList()
        searchProgress = 0
        scope.launch {
            withContext(Dispatchers.IO) {
                DocumentText.search(
                    file = file,
                    query = searchQuery,
                    pageCount = src.pageCount,
                    onProgress = { p -> searchProgress = p },
                    onHit = { hit -> searchHits = searchHits + hit }
                )
            }
            searching = false
        }
    }

    /**
     * Put the straightedge on the page in view, or take it off.
     *
     * Placed across the middle of the current page rather than remembered from last time: a ruler
     * left on page four is not where anyone reaching for one on page nine expects it.
     */
    fun toggleRuler() {
        if (tools.rulerVisible) {
            tools.rulerVisible = false
            return
        }
        val src = source ?: return
        val dim = src.pageDim(page)
        tools.ruler = com.inkslate.core.Ruler.across(
            com.inkslate.core.Box(0f, 0f, dim.width, dim.height), page
        )
        tools.rulerVisible = true
    }

    fun toggleBookmark() {
        if (bookmarks.any { it.page == page }) {
            ink = ink.withBookmarkRemoved(page)
            bookmarks = ink.bookmarks
            dirty = true
        } else {
            bookmarkPrompt = page
        }
    }

    /** Leaving with unsaved marks asks first; that is the whole point of tracking [dirty]. */
    fun leave() {
        if (dirty) confirmLeave = true else onClose()
    }

    // Hand the window's key handler something to call. Re-assigned on each composition so the
    // captured lambdas always see current state rather than the state at first composition.
    shortcuts.save = { save() }
    shortcuts.undo = ::undoOnce
    shortcuts.redo = ::redoOnce
    shortcuts.close = ::leave
    shortcuts.copy = { copySelection() }
    shortcuts.cut = { if (copySelection() > 0) deleteSelection() }
    shortcuts.paste = ::paste
    shortcuts.delete = ::deleteSelection
    shortcuts.selectAll = {
        selection = strokes.filter { it.pageIndex == page }.map { it.id }.toSet()
        tools.edit { it.tool = Tool.SELECT }
    }
    shortcuts.zoomIn = { viewport.zoomBy(1.2f, viewport.centreOfView()) }
    shortcuts.zoomOut = { viewport.zoomBy(1f / 1.2f, viewport.centreOfView()) }
    shortcuts.resetZoom = { viewport.fitWidth(viewport.content) }
    navigation.back = { if (selection.isNotEmpty()) selection = emptySet() else leave() }

    // ---- layout --------------------------------------------------------------

    val writeState = when {
        saving -> WriteState.SAVING
        dirty -> WriteState.UNSAVED
        else -> WriteState.SAVED
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            file.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        source?.let {
                            Text(
                                "Page ${page + 1} of ${it.pageCount}  ·  ${writeState.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = ::undoOnce, enabled = undo.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                    }
                    IconButton(onClick = ::redoOnce, enabled = redo.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                    }
                    IconButton(onClick = { searchOpen = true }) {
                        Icon(Icons.Default.Search, "Find in document")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "More")
                        }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Save into the document") },
                                onClick = { menuOpen = false; save() }
                            )
                            DropdownMenuItem(
                                text = { Text("Export...") },
                                onClick = { menuOpen = false; exportOpen = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Version history...") },
                                onClick = { menuOpen = false; versionsOpen = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Rules for this file") },
                                onClick = { menuOpen = false; fileRulesOpen = true }
                            )
                            if (tools.rulerVisible) {
                                DropdownMenuItem(
                                    text = { Text("Snap the ruler to 15°") },
                                    onClick = {
                                        menuOpen = false
                                        tools.ruler = tools.ruler?.snappedToAngle(15f)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (tools.recogniseShapes) "✓  Tidy rough shapes"
                                        else "      Tidy rough shapes"
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    tools.recogniseShapes = !tools.recogniseShapes
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Fit page") },
                                onClick = {
                                    menuOpen = false
                                    viewport.fit(viewport.content)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Fit width") },
                                onClick = { menuOpen = false; viewport.fitWidth(viewport.content) }
                            )
                            HorizontalDivider()
                            PageLayout.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (layout == option) "✓  " + option.label
                                            else "      " + option.label
                                        )
                                    },
                                    onClick = { menuOpen = false; layout = option }
                                )
                            }
                            HorizontalDivider()
                            PageFilter.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (pageFilter == option) "✓  " + option.label
                                            else "      " + option.label
                                        )
                                    },
                                    onClick = { menuOpen = false; pageFilter = option }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Clear this page") },
                                onClick = {
                                    menuOpen = false
                                    val before = strokes.filter { it.pageIndex == page }
                                    if (before.isNotEmpty()) {
                                        strokes.applyEdit(before, emptyList())
                                        pushOp(Op.removed(before))
                                        selection = emptySet()
                                    }
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                source?.let { src ->
                    PageBar(
                        page = page,
                        pageCount = src.pageCount,
                        bookmarked = bookmarks.any { it.page == page },
                        onPrev = { goToPage(page - 1) },
                        onNext = { goToPage(page + 1) },
                        onOpenNavigation = { navOpen = true },
                        onOpenPages = { pagesOpen = true },
                        onToggleBookmark = ::toggleBookmark
                    )
                }
                ToolBar(
                    state = tools,
                    selectionCount = selection.size,
                    actions = ToolBarActions(
                        onChanged = {
                            // Leaving the select tool is also leaving the selection; a frame
                            // around something you can no longer move is just clutter.
                            if (tools.active.tool != Tool.SELECT) selection = emptySet()
                        },
                        onUndo = ::undoOnce,
                        onRedo = ::redoOnce,
                        canUndo = undo.isNotEmpty(),
                        canRedo = redo.isNotEmpty(),
                        onRestyleSelection = { colour, width ->
                            replaceSelection {
                                it.copy(
                                    color = colour ?: it.color,
                                    baseWidth = width ?: it.baseWidth,
                                    updatedUtc = System.currentTimeMillis()
                                )
                            }
                        },
                        onDeleteSelection = ::deleteSelection,
                        onDuplicateSelection = ::duplicateSelection,
                        onCopySelection = {
                            val n = copySelection()
                            scope.launch {
                                snackbar.showSnackbar("Copied $n object${if (n == 1) "" else "s"}")
                            }
                        },
                        onCutSelection = { if (copySelection() > 0) deleteSelection() },
                        onClearSelection = { selection = emptySet() },
                        onPaste = ::paste,
                        canPaste = Clipboard.contents.isNotEmpty(),
                        onPickCustomColour = { pickingColour = true },
                        onInsertStamp = { stampsOpen = true },
                        onToggleRuler = ::toggleRuler,
                        onInsertSymbol = { symbolsOpen = true },
                        onMessage = { scope.launch { snackbar.showSnackbar(it) } }
                    )
                )
                StatusBar(status, busy)
            }
        }
    ) { pad ->
        val src = source
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (src == null) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                DocumentCanvas(
                    source = src,
                    viewport = viewport,
                    layout = layout,
                    currentPage = page,
                    onPageChanged = { page = it },
                    strokes = strokes,
                    selection = selection,
                    onSelection = { selection = it },
                    tools = tools,
                    textMeasurer = textMeasurer,
                    pageFilter = pageFilter,
                    newId = ::nextId,
                    onCommitted = ::pushOp,
                    onEditText = { editingText = it },
                    onStampPlaced = { armedStampLabel = null },
                    onPlaceText = { x, y, p ->
                        newTextAt = Stroke(
                            id = nextId(),
                            kind = Stroke.Kind.TEXT,
                            color = tools.active.color,
                            baseWidth = 1f,
                            points = listOf(InkPoint(x, y, 1f)),
                            text = "",
                            textSize = tools.active.textSize,
                            pageIndex = p,
                            updatedUtc = System.currentTimeMillis()
                        )
                    }
                )
            }
        }
    }

    // ---- dialogs -------------------------------------------------------------

    editingText?.let { target ->
        TextDialog(
            initial = target,
            swatches = tools.swatches(),
            onDismiss = { editingText = null },
            onDelete = {
                strokes.applyEdit(listOf(target), emptyList())
                pushOp(Op.removed(listOf(target)))
                editingText = null
            },
            onConfirm = { updated ->
                strokes.applyEdit(listOf(target), listOf(updated))
                pushOp(Op(listOf(target), listOf(updated)))
                editingText = null
            }
        )
    }

    newTextAt?.let { fresh ->
        TextDialog(
            initial = fresh,
            swatches = tools.swatches(),
            onDismiss = { newTextAt = null },
            onConfirm = { created ->
                strokes.add(created)
                pushOp(Op.added(created))
                newTextAt = null
            }
        )
    }

    if (pickingColour) {
        ColorPickerDialog(
            initial = tools.active.color,
            presets = Palette.COLORS,
            recents = tools.customColors,
            onDismiss = { pickingColour = false },
            onPick = { picked ->
                tools.edit { it.color = picked }
                tools.rememberColor(picked)
                tools.activePreset = -1
                pickingColour = false
            }
        )
    }

    if (symbolsOpen) {
        SymbolPaletteDialog(
            onDismiss = { symbolsOpen = false },
            onInsert = { text ->
                symbolsOpen = false
                // Placed at the top-left of what is in view, which is where the eye is: a symbol
                // dropped at the page origin on a document scrolled halfway down is a symbol you
                // have to go and find.
                val doc = viewport.screenToDoc(
                    androidx.compose.ui.geometry.Offset(
                        viewport.viewSize.width * 0.35f,
                        viewport.viewSize.height * 0.35f
                    )
                )
                val src = source
                val dim = src?.pageDim(page)
                val extents = if (src == null) emptyList() else (0 until src.pageCount).map {
                    val d = src.pageDim(it)
                    com.inkslate.core.PageExtent(d.width, d.height)
                }
                val origin = com.inkslate.core.PageArranger
                    .arrange(extents, layout, page)
                    .getOrNull(page) ?: (0f to 0f)
                val placed = Stroke(
                    id = nextId(),
                    kind = Stroke.Kind.TEXT,
                    color = tools.active.color,
                    baseWidth = 1f,
                    points = listOf(
                        InkPoint(
                            (doc.x - origin.first).coerceIn(0f, (dim?.width ?: 612f) - 20f),
                            (doc.y - origin.second).coerceIn(0f, (dim?.height ?: 792f) - 20f),
                            1f
                        )
                    ),
                    text = text,
                    textSize = tools.active.textSize.coerceAtLeast(16f),
                    pageIndex = page,
                    updatedUtc = System.currentTimeMillis()
                )
                strokes.add(placed)
                pushOp(Op.added(placed))
                selection = setOf(placed.id)
                tools.edit { it.tool = com.inkslate.core.Tool.SELECT }
            }
        )
    }

    if (stampsOpen) {
        StampPicker(
            colour = tools.active.color,
            onDismiss = { stampsOpen = false },
            onPickShape = { tool -> tools.edit { it.tool = tool } },
            onPick = { kind, options ->
                tools.armedStamp = kind to options
                armedStampLabel = kind.label
                stampsOpen = false
            }
        )
    }

    if (askMode) {
        AlertDialog(
            onDismissRequest = { askMode = false },
            title = { Text("Save ${file.name}") },
            text = {
                Text(
                    "Overwriting writes back over the file you opened, keeping a backup first. " +
                        "A copy leaves the original exactly as it is."
                )
            },
            confirmButton = {
                TextButton(onClick = { askMode = false; writeWith(SaveMode.OVERWRITE) }) {
                    Text("Overwrite")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askMode = false }) { Text("Cancel") }
                    TextButton(onClick = { askMode = false; writeWith(SaveMode.COPY) }) {
                        Text("Save a copy")
                    }
                }
            }
        )
    }

    if (confirmOverwrite) {
        val settings = prefs.effectiveFor(file.absolutePath)
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("Overwrite ${file.name}?") },
            text = {
                Text(
                    if (settings.backupOnOverwrite) {
                        "The original is copied into ${DocumentExport.BACKUP_DIR} first, so this " +
                            "can be undone from Version history."
                    } else {
                        "Backups are turned off for this file, so the original will not be kept."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmOverwrite = false; writeWith(SaveMode.OVERWRITE) }) {
                    Text("Overwrite")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmOverwrite = false }) { Text("Cancel") }
            }
        )
    }

    if (exportOpen) {
        ExportDialog(
            documentName = file.name,
            pageCount = source?.pageCount ?: 1,
            currentPage = page,
            isPdf = DesktopSources.isPdf(file),
            onDismiss = { exportOpen = false },
            onExport = { request ->
                exportOpen = false
                busy = true
                scope.launch {
                    val doc = currentInk()
                    val target = DocumentExport.freeTarget(
                        file.parentFile ?: File("."), request.name
                    )
                    val result = withContext(Dispatchers.IO) {
                        DocumentExport.exportTo(file, target, doc, request.pages, request.flatten)
                    }
                    status = result.fold(
                        onSuccess = { "Exported ${it.name}" },
                        onFailure = { "Export failed: ${it.message}" }
                    )
                    snackbar.showSnackbar(status)
                    busy = false
                }
            }
        )
    }

    if (versionsOpen) {
        VersionHistoryDialog(
            file = file,
            onDismiss = { versionsOpen = false },
            onRestored = {
                versionsOpen = false
                // Reopened rather than patched: the file on disk is a different document now,
                // and every raster on screen belongs to the old one.
                positionRestored = true
                reopenTick++
            },
            onMessage = { scope.launch { snackbar.showSnackbar(it) } }
        )
    }

    if (fileRulesOpen) {
        FileRulesDialog(
            path = file.absolutePath,
            name = file.name,
            prefs = prefs,
            onDismiss = { fileRulesOpen = false }
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Save before closing?") },
            text = {
                Text(
                    "There are marks on ${file.name} that are not in the document yet. They are " +
                        "kept on this machine either way, but they will not reach your other " +
                        "devices until the document itself is written."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; save { onClose() } }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmLeave = false }) { Text("Cancel") }
                    TextButton(onClick = { confirmLeave = false; onClose() }) { Text("Close") }
                }
            }
        )
    }
}

@Composable
private fun StatusBar(status: String, busy: Boolean) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            if (busy) Text("working...", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * Page navigation, above the tools.
 *
 * The page indicator in the middle opens contents, bookmarks and the page jump: it is already
 * where you look when you want to know where you are in a long document, so it is the obvious
 * thing to press when you want to be somewhere else.
 */
@Composable
private fun PageBar(
    page: Int,
    pageCount: Int,
    bookmarked: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenNavigation: () -> Unit,
    onOpenPages: () -> Unit,
    onToggleBookmark: () -> Unit
) {
    Surface(tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev, enabled = page > 0) {
                Icon(Icons.Default.ChevronLeft, "Previous page")
            }
            IconButton(onClick = onOpenPages) { Icon(Icons.Default.GridView, "Pages") }
            TextButton(onClick = onOpenNavigation, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.MenuBook, null, Modifier.size(16.dp))
                Text("  ${page + 1} / $pageCount", style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    if (bookmarked) "Remove bookmark" else "Bookmark this page",
                    tint = if (bookmarked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onNext, enabled = page < pageCount - 1) {
                Icon(Icons.Default.ChevronRight, "Next page")
            }
        }
    }
}
