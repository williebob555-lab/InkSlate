package com.inkslate.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlin.math.roundToInt

/** What the title bar says about the document's relationship to the disk. */
/** How long the pointer has to be still before the document is written out behind the scenes. */
private const val IDLE_BEFORE_WRITE_MS = 1200L

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
    val tools = rememberToolState()
    val prefs = remember { SavePrefs() }
    val images = remember(file) { ImageStore(file) }
    // Loaded lazily and kept, because a page redraws far more often than its pictures change.
    val loadedImages = remember(file) { mutableStateMapOf<String, ImageBitmap>() }

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
    var cropping by remember { mutableStateOf<Stroke?>(null) }
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
    /**
     * What to do once the document has actually been written.
     *
     * The save rules can put a dialog between asking to save and the write happening, and whatever
     * was waiting on that save - closing the document, showing it in Explorer, keeping a
     * checkpoint - has to survive the dialog. Held here rather than carried through it, because a
     * dialog is a recomposition rather than a call that returns.
     */
    var afterSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var fileRulesOpen by remember { mutableStateOf(false) }

    val viewport = remember(file) { Viewport() }
    var page by remember(file) { mutableStateOf(0) }
    var layout by remember { mutableStateOf(PageLayout.VERTICAL) }
    var pageFilter by remember { mutableStateOf(PageFilter.NONE) }
    var pressureCurveOpen by remember { mutableStateOf(false) }
    var benchRunning by remember { mutableStateOf(false) }
    var benchReport by remember { mutableStateOf<String?>(null) }
    /** Focus mode: the page and the tools, nothing else. */
    var immersive by remember { mutableStateOf(false) }

    // The viewport is not Compose state on the throw path - it is read by the frame loop - so the
    // two scrolling settings are pushed into it rather than read out of the tool state there.
    // As an effect rather than in the composition body: composition can run more than once for
    // the same state, and writing through it is how a recomposition acquires a side effect.
    SideEffect {
        viewport.flingEnabled = tools.flingEnabled
        viewport.flingScale = tools.flingScale
    }

    /**
     * The file as this machine last left it.
     *
     * Compared before every save: if the document on disk is not the one we wrote or opened, a
     * sync has been through and its marks have to be folded in before ours go on top.
     */
    var diskStamp by remember(file) { mutableStateOf("") }

    /** When the last edit happened, so the document is written into a pause rather than a stroke. */
    var lastEditAt by remember(file) { mutableStateOf(0L) }

    val ids = remember(file) { mutableStateOf(0) }
    val deviceTag = remember { DocumentIO.deviceTag() }
    fun nextId(): String = "$deviceTag-d${++ids.value}"

    fun pushOp(op: Op) {
        undo.add(op)
        redo.clear()
        dirty = true
        lastEditAt = System.currentTimeMillis()
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
        diskStamp = DocumentIO.stampOf(file)
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
            if (source != null && tools.rememberView) {
                ReadingPosition.save(
                    file.absolutePath, page, layout,
                    viewport.scale, viewport.offset.x, viewport.offset.y
                )
            }
        }
    }

    // Hold the display awake while a document is open, if asked to. Started here rather than at
    // the window so it lasts exactly as long as a document is open, which is what the setting says.
    LaunchedEffect(file.absolutePath) {
        ScreenAwake.reset()
        while (true) {
            delay(ScreenAwake.INTERVAL_MS)
            if (tools.keepScreenOn) ScreenAwake.tick() else ScreenAwake.reset()
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
            var doc = currentInk()
            ink = doc
            withContext(Dispatchers.IO) { DocumentIO.saveWorking(file, doc) }
            // A synced folder can have put the tablet's copy here since this document was opened.
            // Folding it in first is what stops saving from replacing marks made elsewhere with a
            // payload that never saw them.
            val pages = source?.pageCount
            if (pages != null && DocumentIO.stampOf(file) != diskStamp) {
                doc = withContext(Dispatchers.IO) { DocumentIO.mergedWithDisk(file, doc) }
                ink = doc
                // What is on screen has to become what is about to be written, or the next edit
                // would be made against a set of marks the save has already moved past.
                strokes.clear()
                strokes.addAll((0 until pages).flatMap { p -> doc.strokesOn(p) })
            }
            // The page has to be the right size before the handwriting goes onto it, or ink
            // drawn past the old edge is ink outside the page.
            doc.canvas?.takeIf { it.paperIsBehind }?.let { canvas ->
                val matched = withContext(Dispatchers.IO) {
                    DocumentPages.growCanvas(file, canvas)
                }
                matched.getOrNull()?.let {
                    doc = doc.copy(canvas = it)
                    ink = doc
                }
            }
            val settings = prefs.effectiveFor(file.absolutePath).copy(mode = mode)
            val result = withContext(Dispatchers.IO) {
                DocumentExport.save(file, doc, settings)
            }
            when (result) {
                is SaveResult.Written -> {
                    dirty = false
                    diskStamp = DocumentIO.stampOf(file)
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
     * Bring the document on disk up to date, quietly.
     *
     * Same path as the Save button, minus the parts that only make sense when a person asked: no
     * backup - twenty rolling copies of a file written every time the pen pauses is not a version
     * history, it is a disk leak - and no message, because nothing happened that needs saying.
     *
     * This is what makes a laptop edit reach the tablet without anyone pressing anything, and it
     * is what the tablet has always done. The handwriting lives inside the document, so a document
     * that is not written is handwriting that has not left this machine.
     */
    suspend fun writeThrough() {
        if (!dirty || saving || source == null) return
        var doc = currentInk()
        ink = doc
        saving = true
        withContext(Dispatchers.IO) { DocumentIO.saveWorking(file, doc) }
        val pages = source?.pageCount
        if (pages != null && DocumentIO.stampOf(file) != diskStamp) {
            doc = withContext(Dispatchers.IO) { DocumentIO.mergedWithDisk(file, doc) }
            ink = doc
            strokes.clear()
            strokes.addAll((0 until pages).flatMap { p -> doc.strokesOn(p) })
        }
        val rules = prefs.effectiveFor(file.absolutePath)
            .copy(mode = SaveMode.OVERWRITE, backupOnOverwrite = false, confirmOverwrite = false)
        val result = withContext(Dispatchers.IO) { DocumentExport.save(file, doc, rules) }
        if (result is SaveResult.Written) {
            diskStamp = DocumentIO.stampOf(file)
            // Only settled if nothing arrived while it was being written: the write covered the
            // document as it was when it started, not as it is now.
            if (currentInk() === doc) dirty = false
        }
        saving = false
    }

    // The pen has to be still before the document is written, so a write never lands in the middle
    // of a stroke. Checked often and acted on rarely, which is what keeps it invisible.
    LaunchedEffect(file.absolutePath, source) {
        while (true) {
            delay(1200)
            if (!dirty || saving || busy) continue
            if (System.currentTimeMillis() - lastEditAt < IDLE_BEFORE_WRITE_MS) continue
            writeThrough()
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
            settings.mode == SaveMode.ASK -> { afterSave = then; askMode = true }
            settings.mode == SaveMode.OVERWRITE && settings.confirmOverwrite -> {
                afterSave = then
                confirmOverwrite = true
            }
            else -> writeWith(settings.mode, then)
        }
    }

    /** Take the waiting continuation, so a cancelled dialog cannot leave one armed. */
    fun takeAfterSave(): (() -> Unit)? = afterSave.also { afterSave = null }

    /**
     * Hand the document to something else.
     *
     * Windows has no share sheet a plain desktop program can raise, so this does what the share
     * sheet is for: it writes the marks in and then shows the file itself, selected, in Explorer -
     * from where it can be dragged into an email, a chat window or a hand-in page. Saving first
     * matters, because a file shared without it is the file as it was this morning.
     */
    fun shareDocument() {
        scope.launch {
            save {
                runCatching {
                    ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
                }.onFailure {
                    scope.launch { snackbar.showSnackbar("Could not show ${file.name}") }
                }
            }
        }
    }

    /**
     * Mark this version as one worth being able to come back to.
     *
     * Version history otherwise fills itself only from the copies taken before an overwrite, which
     * is a record of the last few saves rather than of the decisions worth undoing. This writes the
     * document and keeps a copy of it deliberately.
     */
    fun checkpoint() {
        save {
            scope.launch {
                val made = withContext(Dispatchers.IO) { DocumentExport.makeBackup(file) }
                status = made.fold(
                    onSuccess = { "Checkpointed ${file.name}" },
                    onFailure = { "Could not checkpoint: ${it.message}" }
                )
                EventLog.info("checkpoint", made.fold({ "Kept ${it.name}" }, { "failed: ${it.message}" }))
            }
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
        if (!tools.rememberView) return@LaunchedEffect
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

    /** Drop a picture onto a page as a movable, resizable object. */
    fun placeImage(id: String, pageIndex: Int, box: com.inkslate.core.Box) {
        val placed = Stroke(
            id = nextId(),
            kind = Stroke.Kind.IMAGE,
            color = 0xFF000000.toInt(),
            baseWidth = 1f,
            points = listOf(
                InkPoint(box.left, box.top, 1f),
                InkPoint(box.right, box.bottom, 1f)
            ),
            imageId = id,
            pageIndex = pageIndex,
            updatedUtc = System.currentTimeMillis()
        )
        strokes.add(placed)
        pushOp(Op.added(placed))
        selection = setOf(placed.id)
        tools.edit { it.tool = com.inkslate.core.Tool.SELECT }
    }

    /**
     * Box a figure on the page and drop it in as a movable object.
     *
     * The region is re-rendered from the document rather than grabbed off the screen, so a
     * diagram captured while zoomed out is still sharp when it is enlarged - which is most of
     * what capturing one is for.
     */
    fun captureRegion(region: com.inkslate.core.Box, pageIndex: Int) {
        val src = source ?: return
        busy = true
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching {
                    val dim = src.pageDim(pageIndex)
                    // Rendered so the region comes out at about twice its own size, capped: enough
                    // detail to enlarge without turning a page into a fifty-megapixel bitmap.
                    val wanted = (region.width * 2.4f).coerceIn(200f, 2400f)
                    val pageWidth = (dim.width * (wanted / region.width)).coerceAtMost(6000f)
                    val rendered = src.render(pageIndex, pageWidth.roundToInt())
                        ?: return@runCatching null
                    val sx = rendered.width / dim.width
                    val sy = rendered.height / dim.height
                    val l = (region.left * sx).roundToInt().coerceIn(0, rendered.width - 1)
                    val t = (region.top * sy).roundToInt().coerceIn(0, rendered.height - 1)
                    val r = (region.right * sx).roundToInt().coerceIn(l + 1, rendered.width)
                    val b = (region.bottom * sy).roundToInt().coerceIn(t + 1, rendered.height)

                    val cut = ImageBitmap(r - l, b - t)
                    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                        androidx.compose.ui.unit.Density(1f),
                        androidx.compose.ui.unit.LayoutDirection.Ltr,
                        androidx.compose.ui.graphics.Canvas(cut),
                        androidx.compose.ui.geometry.Size((r - l).toFloat(), (b - t).toFloat())
                    ) {
                        drawImage(
                            rendered,
                            srcOffset = androidx.compose.ui.unit.IntOffset(l, t),
                            srcSize = androidx.compose.ui.unit.IntSize(r - l, b - t),
                            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            dstSize = androidx.compose.ui.unit.IntSize(r - l, b - t)
                        )
                    }
                    images.put(cut)
                }.getOrNull()
            }
            if (id == null) {
                status = "Could not capture that region"
                snackbar.showSnackbar(status)
            } else {
                // Offset a little from where it came from, so it is obvious it is now a separate
                // object sitting on the page rather than part of it.
                placeImage(id, pageIndex, region.offset(18f, 18f))
            }
            busy = false
        }
    }

    /** Bring a picture in from disk, at a sensible size on the page. */
    fun importPicture() {
        val src = source ?: return
        val dialog = java.awt.FileDialog(
            null as java.awt.Frame?, "Add a picture", java.awt.FileDialog.LOAD
        )
        dialog.setFilenameFilter { _, name -> DesktopSources.isImage(File(name)) }
        dialog.isVisible = true
        val dir = dialog.directory ?: return
        val name = dialog.file ?: return
        val picked = File(dir, name)

        busy = true
        scope.launch {
            val stored = withContext(Dispatchers.IO) { images.putFile(picked) }
            if (stored == null) {
                status = "Could not read ${picked.name}"
                snackbar.showSnackbar(status)
            } else {
                val picture = withContext(Dispatchers.IO) { images.load(stored) }
                val dim = src.pageDim(page)
                // Half the page's width, keeping the picture's own proportions.
                val w = dim.width * 0.5f
                val h = if (picture == null || picture.width == 0) w
                else w * picture.height / picture.width
                val left = (dim.width - w) / 2f
                val top = (dim.height - h) / 2f
                placeImage(stored, page, com.inkslate.core.Box(left, top, left + w, top + h))
            }
            busy = false
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
        // Nothing to ask about any more: the document is written as you work, so closing is
        // either a no-op or the last few hundred milliseconds of it. A dialog about a decision
        // the app has already made is worse than making it silently.
        if (!dirty) {
            onClose()
        } else {
            scope.launch {
                writeThrough()
                onClose()
            }
        }
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
    navigation.back = {
        // Escape steps back out of one thing at a time, innermost first. Focus mode before the
        // document especially: hitting Escape to get the toolbars back and having the document
        // close instead is the kind of surprise that costs an unsaved minute.
        when {
            immersive -> immersive = false
            selection.isNotEmpty() -> selection = emptySet()
            else -> leave()
        }
    }

    // ---- layout --------------------------------------------------------------

    val writeState = when {
        saving -> WriteState.SAVING
        dirty -> WriteState.UNSAVED
        else -> WriteState.SAVED
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = topBar@{
            // In focus mode the app bar goes too, leaving only the page and the drawing tools.
            if (immersive) return@topBar
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
                    // The same button the tablet has, in the same place: a control that says
                    // "keep this, now" rather than a menu entry two taps away. Tinted while
                    // there is something not yet in the document.
                    IconButton(onClick = { save() }) {
                        Icon(
                            Icons.Default.Save,
                            "Save into the document",
                            tint = if (writeState == WriteState.SAVED) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                    IconButton(onClick = ::undoOnce, enabled = undo.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                    }
                    IconButton(onClick = ::redoOnce, enabled = redo.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                    }
                    IconButton(onClick = { searchOpen = true }) {
                        Icon(Icons.Default.Search, "Find in document")
                    }
                    IconButton(onClick = { immersive = true }) {
                        Icon(Icons.Default.Fullscreen, "Focus mode")
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
                                text = { Text("Share...") },
                                onClick = { menuOpen = false; shareDocument() }
                            )
                            DropdownMenuItem(
                                text = { Text("Export...") },
                                onClick = { menuOpen = false; exportOpen = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Checkpoint this version") },
                                onClick = { menuOpen = false; checkpoint() }
                            )
                            if ((source?.pageCount ?: 0) == 1 && DesktopSources.isPdf(file)) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (ink.canvas != null) "Back to a fixed page"
                                            else "Make this a canvas that grows"
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        val src = source
                                        ink = if (ink.canvas != null) {
                                            ink.copy(canvas = null)
                                        } else {
                                            val dim = src?.pageDim(0)
                                            ink.copy(
                                                canvas = com.inkslate.core.InkCanvas.startingAt(
                                                    dim?.width ?: 612f,
                                                    dim?.height ?: 792f
                                                )
                                            )
                                        }
                                        dirty = true
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Version history...") },
                                onClick = { menuOpen = false; versionsOpen = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Rules for this file") },
                                onClick = { menuOpen = false; fileRulesOpen = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Measure save speed") },
                                onClick = {
                                    menuOpen = false
                                    benchRunning = true
                                    scope.launch {
                                        val text = withContext(Dispatchers.IO) {
                                            SaveBenchmark.run(
                                                file, ink,
                                                prefs.effectiveFor(file.absolutePath).inkFormat
                                            )
                                        }
                                        benchRunning = false
                                        benchReport = text
                                    }
                                }
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
                                text = {
                                    Text(
                                        if (tools.cropMargins) "✓  Trim page margins"
                                        else "      Trim page margins"
                                    )
                                },
                                onClick = { menuOpen = false; tools.cropMargins = !tools.cropMargins }
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
                            DropdownMenuItem(
                                text = { Text("Reset view") },
                                onClick = {
                                    menuOpen = false
                                    viewport.stop()
                                    viewport.restore(1f, 0f, 0f)
                                    goToPage(page)
                                }
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
                        onInsertPicture = ::importPicture,
                        onCapture = { tools.edit { it.tool = com.inkslate.core.Tool.REGION } },
                        onEditPressureCurve = { pressureCurveOpen = true },
                        onSnapRuler = { tools.ruler = tools.ruler?.snappedToAngle(15f) },
                        onRotateRuler = { deg -> tools.ruler = tools.ruler?.rotatedBy(deg) },
                        onResetRuler = {
                            val src = source
                            if (src != null) {
                                val dim = src.pageDim(page)
                                tools.ruler = com.inkslate.core.Ruler.across(
                                    com.inkslate.core.Box(0f, 0f, dim.width, dim.height), page
                                )
                            }
                        },
                        onBeginCrop = {
                            cropping = selected().singleOrNull()
                                ?.takeIf { it.kind == Stroke.Kind.IMAGE }
                        },
                        canCrop = selected().singleOrNull()?.kind == Stroke.Kind.IMAGE,
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
                    cropMargins = tools.cropMargins,
                    wordsUnder = if (DesktopSources.isPdf(file)) { pageIndex, path ->
                        DocumentText.wordsUnderPath(file, pageIndex, path, tolerance = 6f)
                    } else {
                        null
                    },
                    newId = ::nextId,
                    onCommitted = ::pushOp,
                    onEditText = { editingText = it },
                    canvas = ink.canvas,
                    images = { id ->
                        loadedImages[id] ?: images.load(id)?.also { loadedImages[id] = it }
                    },
                    onCaptureRegion = { region, pageIndex -> captureRegion(region, pageIndex) },
                    onStampPlaced = { armedStampLabel = null },
                    onDrew = { drawn ->
                        // Growth is free while drawing: the extra room is a rectangle in memory
                        // and the paper outside the page is painted rather than written. It
                        // becomes real once, when the document is saved.
                        ink.canvas?.let { current ->
                            val grown = current.grownTo(drawn)
                            if (grown !== current) {
                                ink = ink.copy(canvas = grown)
                                dirty = true
                            }
                        }
                    },
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

            // The app bar is gone in focus mode, so this is the only way back out.
            if (immersive) {
                FilledTonalIconButton(
                    onClick = { immersive = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                ) { Icon(Icons.Default.FullscreenExit, "Leave focus mode") }
            }
        }
    }

    // ---- dialogs -------------------------------------------------------------

    if (pressureCurveOpen) {
        PressureCurveDialog(
            initialGamma = tools.active.pressureGamma,
            initialMin = tools.active.pressureMin,
            initialDynamics = tools.active.dynamics,
            onDismiss = { pressureCurveOpen = false },
            onReset = {
                tools.edit { it.pressureGamma = 1f; it.pressureMin = 0.35f; it.dynamics = 1f }
                pressureCurveOpen = false
            },
            onApply = { g, m, d ->
                tools.edit { it.pressureGamma = g; it.pressureMin = m; it.dynamics = d }
                pressureCurveOpen = false
            }
        )
    }

    if (benchRunning) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Measuring") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "Timing each phase of a save against this document.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            },
            confirmButton = { }
        )
    }

    benchReport?.let { text ->
        AlertDialog(
            onDismissRequest = { benchReport = null },
            title = { Text("Save timings") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(onClick = { benchReport = null }) { Text("Done") } }
        )
    }

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
            onDismissRequest = { askMode = false; takeAfterSave() },
            title = { Text("Save ${file.name}") },
            text = {
                Text(
                    "Overwriting writes back over the file you opened, keeping a backup first. " +
                        "A copy leaves the original exactly as it is."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askMode = false
                    writeWith(SaveMode.OVERWRITE, takeAfterSave())
                }) { Text("Overwrite") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askMode = false; takeAfterSave() }) { Text("Cancel") }
                    TextButton(onClick = {
                        askMode = false
                        writeWith(SaveMode.COPY, takeAfterSave())
                    }) { Text("Save a copy") }
                }
            }
        )
    }

    if (confirmOverwrite) {
        val settings = prefs.effectiveFor(file.absolutePath)
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false; takeAfterSave() },
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
                TextButton(onClick = {
                    confirmOverwrite = false
                    writeWith(SaveMode.OVERWRITE, takeAfterSave())
                }) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOverwrite = false; takeAfterSave() }) {
                    Text("Cancel")
                }
            }
        )
    }

    cropping?.let { target ->
        CropDialog(
            stroke = target,
            picture = target.imageId?.let { id ->
                loadedImages[id] ?: images.load(id)?.also { loadedImages[id] = it }
            },
            onDismiss = { cropping = null },
            onApply = { updated ->
                strokes.applyEdit(listOf(target), listOf(updated))
                pushOp(Op(listOf(target), listOf(updated)))
                cropping = null
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
