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
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.inkslate.core.peer.ImageLink
import com.inkslate.core.peer.DocumentSync
import com.inkslate.core.peer.FileRevision
import com.inkslate.core.peer.LinkHub
import com.inkslate.core.peer.PeerMessage
import com.inkslate.core.SaveMode
import com.inkslate.core.SearchHit
import com.inkslate.core.Palette
import com.inkslate.core.Stroke
import com.inkslate.core.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** What the title bar says about the document's relationship to the disk. */
/** How long the pointer has to be still before the document is written out behind the scenes. */
private const val IDLE_BEFORE_WRITE_MS = 1200L

/**
 * How long rearranging pages waits for another device with the document open to hand over writing
 * it. Longer than the link's own patience with a silent device, which then carries on without it.
 */
private const val REARRANGE_WAIT_MS = 40_000L

/** The furthest outside the canvas a mark may be and still count as asking it to grow. */
private const val MAX_GROWTH_STEP = 2_000f

/** The most the canvas may span either way, the same figure the tablet keeps. */
private const val MAX_CANVAS_SPAN = 48_000f

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
    onClose: () -> Unit,
    /**
     * Where this document meets the workspace. The editor lays nothing out itself: it hands its
     * bars and its views to the host, and the workspace puts them where they go - once, for the
     * document last touched, rather than a whole window's worth per document.
     */
    host: DocumentHost,
    /**
     * Whether this is the document the keyboard belongs to right now.
     *
     * Two documents can be on screen at once in split view, and only one of them may claim
     * Ctrl+S/Ctrl+Z/Escape and the rest - otherwise whichever composed last would always win,
     * regardless of which one the marks are actually going on.
     */
    focused: Boolean,
    /** Flipped from outside - a tab's own close button - to ask this document to close itself. */
    closeRequested: State<Boolean>
) {
    val scope = rememberCoroutineScope()
    val snackbar = host.snackbar
    val textMeasurer = rememberTextMeasurer()
    // The workspace's one set of tools; see LocalToolState.
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

    /**
     * The pages are being rearranged, here or by reading another device's rearrangement. Until the
     * document is read again, the marks on screen belong to the old pages and nothing may be
     * written, merged or put on the page.
     */
    var restructuring by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf<Stroke?>(null) }
    var newTextAt by remember { mutableStateOf<Stroke?>(null) }
    var pickingColour by remember { mutableStateOf(false) }
    // The shapes tray, and what is open off it. See ShapeTray and PageGestures. The workspace's,
    // not this document's: it belongs to the one bar of tools under every document.
    var trayOpen by tools::trayOpen
    var traySymbols by tools::traySymbols
    var libraryOpen by tools::libraryOpen
    /** The kind whose settings are open for the next one placed, or null. */
    var armedSettings by tools::armedSettings
    /** The stamp on the page whose settings are open, and the strokes it was when they opened. */
    var placedSettings by remember { mutableStateOf<com.inkslate.core.StampTag?>(null) }
    var stampEditBefore by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    // A symbol clicked after the last string was placed starts a new string.
    var symbolPlaced by tools::symbolPlaced
    var pagesOpen by remember { mutableStateOf(false) }
    var versionsOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    /** Pages picked in the pages sheet, carried into the export dialog as its starting choice. */
    var exportPreset by remember { mutableStateOf<List<Int>?>(null) }
    var cropping by remember { mutableStateOf<Stroke?>(null) }
    var reopenTick by remember { mutableStateOf(0) }
    /** The canvas paper the page was last read again for; see [refreshPageIfGrown]. */
    var pageReadFor by remember(file) { mutableStateOf<com.inkslate.core.Box?>(null) }
    // Restored once per open, or every recomposition would drag the view back.
    var positionRestored by remember(file) { mutableStateOf(false) }
    var navOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var controlsOpen by remember { mutableStateOf(false) }
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

    // The camera and the page are the view's, not the document's: a document shown in both halves
    // of a split has two of each. Everything here that moves the camera or asks which page this is
    // means the view last touched - the one the bars above and below are showing.
    val viewport: Viewport by host::activeViewport
    var page: Int by host::activePage

    // A page turn in music, shown: the new page slides in from the side it came from, or fades up.
    val turnAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    var turnDir by remember { mutableStateOf(0) }
    // The scale music was last fitted at; closer than this, a finger reads up close rather than turning.
    var fittedScale by remember { mutableStateOf(0f) }
    var layout by remember { mutableStateOf(if (AppFlavor.musicView) PageLayout.SINGLE else PageLayout.VERTICAL) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var pageFilter by remember { mutableStateOf(PageFilter.NONE) }
    var pressureCurveOpen by remember { mutableStateOf(false) }
    var benchRunning by remember { mutableStateOf(false) }
    var benchReport by remember { mutableStateOf<String?>(null) }
    /** Focus mode: the page and the tools, nothing else. The workspace's, like the bars it hides. */
    var immersive by host.immersiveState

    // The viewport is not Compose state on the throw path - it is read by the frame loop - so the
    // two scrolling settings are pushed into it rather than read out of the tool state there.
    // As an effect rather than in the composition body: composition can run more than once for
    // the same state, and writing through it is how a recomposition acquires a side effect.
    SideEffect {
        host.views.forEach {
            it.viewport.flingEnabled = tools.flingEnabled
            it.viewport.flingScale = tools.flingScale
        }
    }

    /**
     * The file as this machine last left it.
     *
     * Compared before every save: if the document on disk is not the one we wrote or opened, a
     * sync has been through and its marks have to be folded in before ours go on top.
     */
    var diskStamp by remember(file) { mutableStateOf("") }

    /** The handwriting the document on disk is known to hold: what was read, or last written. */
    var writtenInk by remember(file) { mutableStateOf<InkDocument?>(null) }

    /**
     * This document's end of the link to your other devices. See [DocumentSync]: every decision
     * about what to send, what to take in and when to write while another device has the document
     * open is made there, not here.
     */
    var link by remember(file) { mutableStateOf<DocumentSync?>(null) }
    var linkStatus by remember(file) { mutableStateOf(DocumentSync.Status.ALONE) }

    /** Bumped each time a document finishes loading, so the link is set up for what was loaded. */
    var loadCount by remember(file) { mutableStateOf(0) }

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
            val src = DesktopSources.open(file, detached = true) ?: return@withContext null
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
        writtenInk = doc.ink
        restructuring = false
        loadCount++
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
        // Captured now. Read at dispose time, `source` is already the document that just finished
        // loading - so this saved the untouched default camera the moment a document opened, and
        // the restore below then put that back instead of fitting the page to the window.
        val opened = source
        onDispose {
            if (opened != null && positionRestored && tools.rememberView) {
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
        try {
            while (true) {
                if (AppDirs.isLinux) {
                    ScreenInhibit.hold(file.absolutePath, tools.keepScreenOn)
                    delay(2_000)
                } else {
                    delay(ScreenAwake.INTERVAL_MS)
                    if (tools.keepScreenOn) ScreenAwake.tick() else ScreenAwake.reset()
                }
            }
        } finally {
            if (AppDirs.isLinux) ScreenInhibit.hold(file.absolutePath, false)
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
     * Read the page again when the canvas's paper no longer matches the page that was read.
     *
     * The page on disk changes size under an open whiteboard in two ordinary ways: a save here
     * that grew it, and another device's grown copy folded in before a save. The picture of the
     * page was still the one taken when the document opened, so the paper and the picture of it
     * disagreed about where the page ends. Only the page is read again - the handwriting, the undo
     * history and the view are all left exactly as they are.
     */
    suspend fun refreshPageIfGrown() {
        val c = ink.canvas ?: return
        val src = source ?: return
        if (!DesktopSources.isPdf(file) || src.pageCount == 0) return
        val read = src.pageDim(0)
        val same = kotlin.math.abs(read.width - c.paperWidth) < 0.5f &&
            kotlin.math.abs(read.height - c.paperHeight) < 0.5f
        if (same) return
        // Once per size the paper claims. Two devices that grew the canvas in different directions
        // merge to paper bigger than either page, and no amount of reading will make them agree -
        // so without this every quiet save would read the whole document again.
        if (pageReadFor == c.paperBox) return
        pageReadFor = c.paperBox
        val fresh = withContext(Dispatchers.IO) { DesktopSources.open(file, detached = true) } ?: return
        source = fresh
        withContext(Dispatchers.IO) { runCatching { src.close() } }
        EventLog.info(
            "canvas",
            "${file.name}: page read again at ${c.paperWidth.toInt()}x${c.paperHeight.toInt()} " +
                "(was ${read.width.toInt()}x${read.height.toInt()})"
        )
    }

    /**
     * Tell the link that a write of this document has just finished, and what went into it.
     */
    suspend fun linkWritten(written: InkDocument, tookMs: Long = 0L) {
        val session = link ?: return
        val now = System.currentTimeMillis()
        val rev = withContext(Dispatchers.IO) { FileRevision.of(file) }
        if (rev == null) {
            session.writeFailed(now)
            return
        }
        session.written(rev, written, now, tookMs)
        DesktopPeers.hub.wrote(written.docId, session.lastWrite)
        linkStatus = session.status(now)
    }

    /**
     * Write the document out under the rules this file saves by.
     *
     * The working copy is written first and unconditionally: it is what stands between a failed
     * write and a lost afternoon, and it has to be ahead of the document rather than behind it.
     */
    fun writeWith(mode: SaveMode, then: (() -> Unit)? = null) {
        // Another device writes this document while it has it open. Overwriting it from here as
        // well would be a rival file; asking the link hands the writing over first, and the next
        // write - which carries everything on this page - happens here.
        val session = link
        if (mode == SaveMode.OVERWRITE && session != null &&
            !session.mayWriteNow(System.currentTimeMillis())
        ) {
            session.requestSave()
            scope.launch {
                withContext(Dispatchers.IO) { DocumentIO.saveWorking(file, currentInk()) }
                snackbar.showSnackbar("Saving once your other device hands the document over")
            }
            then?.invoke()
            return
        }
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
                    if (!result.wasCopy) {
                        writtenInk = doc
                        linkWritten(doc)
                        refreshPageIfGrown()
                    }
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
     * Whether to write at all is the link's answer; see the heartbeat below.
     */
    suspend fun writeThrough(): Boolean {
        if (saving || source == null) return false
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
        // Timed, so the link knows what writing this document costs and can leave a gap to match.
        val startedAt = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) { DocumentExport.save(file, doc, rules) }
        val tookMs = System.currentTimeMillis() - startedAt
        val wrote = result is SaveResult.Written
        if (wrote) {
            diskStamp = DocumentIO.stampOf(file)
            writtenInk = doc
            linkWritten(doc, tookMs)
            DesktopPeers.announceWrote(file)
            refreshPageIfGrown()
            // Only settled if nothing arrived while it was being written: the write covered the
            // document as it was when it started, not as it is now.
            if (currentInk() === doc) dirty = false
        }
        saving = false
        return wrote
    }

    /**
     * Put what arrived from another device - over the link, or in the file - on the page.
     *
     * The caller folds the strokes on screen into [ink] first, so this replaces nothing that has
     * not already been taken in.
     */
    fun takeIn(next: InkDocument) {
        if (next === ink) return
        ink = next
        val pages = source?.pageCount ?: 0
        strokes.clear()
        strokes.addAll((0 until pages).flatMap { p -> next.strokesOn(p) })
        selection = selection.filter { id -> strokes.any { it.id == id } }.toSet()
        dirty = true
    }

    // Markings brought across from another app (InkSheets' MobileSheets import), put on the pages
    // the first time the document is open here. Nothing happens when they are already on.
    LaunchedEffect(loadCount) {
        if (loadCount == 0) return@LaunchedEffect
        val src = source ?: return@LaunchedEffect
        val mine = currentInk()
        com.inkslate.core.Perform.withImported(mine, file.absolutePath) { p ->
            runCatching { src.pageDim(p) }.getOrNull()?.let { it.width to it.height }
        }?.let {
            EventLog.info("open", "${file.name}: brought in markings from another app")
            ink = mine
            takeIn(it)
        }
    }

    /**
     * Look at the file on disk, and hand any change in it to the link.
     *
     * A stat each time and a read only when it moved. This window has no watch on the folder, and a
     * write arriving from the tablet has to be noticed before this machine writes on top of it.
     */
    suspend fun noticeDiskChange(session: DocumentSync) {
        val stamp = withContext(Dispatchers.IO) { DocumentIO.stampOf(file) }
        if (stamp == diskStamp) return
        val rev = withContext(Dispatchers.IO) { FileRevision.of(file) }
        val onDisk = withContext(Dispatchers.IO) {
            runCatching { if (DesktopEmbedder.supports(file)) DesktopEmbedder.read(file) else null }
                .getOrNull()
        }
        if (saving || restructuring) return
        diskStamp = stamp
        val current = currentInk()
        ink = current
        // Pages rearranged in InkSlate on another device carry a record of how, which is what lets
        // the handwriting here follow them: the document is read again, and the read folds the
        // working copy - everything drawn here - onto the page it was drawn on, wherever it went.
        if (onDisk != null && onDisk.docId == current.docId && onDisk.layout != current.layout) {
            val arrival = session.diskChanged(rev, { onDisk }, current, System.currentTimeMillis())
            if (arrival.pagesChanged) {
                EventLog.info("sync", "${file.name}: pages were rearranged on another device; reading it again")
                restructuring = true
                withContext(Dispatchers.IO) { DocumentIO.saveWorking(file, current) }
                positionRestored = true
                reopenTick++
                snackbar.showSnackbar("Pages were rearranged on your other device")
                return
            }
            if (arrival.merged) takeIn(arrival.ink)
            return
        }
        // Pages added or removed by anything else. Marks cannot be laid onto pages that are not the
        // pages they were drawn on, so the document is read again - with everything drawn here kept
        // in the working copy, which the read folds back in.
        if (onDisk != null && onDisk.source.pageCount != current.source.pageCount) {
            EventLog.warn("sync", "${file.name} changed shape on disk while it was open; reading it again")
            withContext(Dispatchers.IO) { DocumentIO.saveWorking(file, current) }
            reopenTick++
            return
        }
        val arrival = session.diskChanged(rev, { onDisk }, current, System.currentTimeMillis())
        if (arrival.merged) takeIn(arrival.ink)
        if (onDisk != null) writtenInk = onDisk
        if (arrival.holdsEverything && ink === arrival.ink) dirty = false
        refreshPageIfGrown()
    }

    /**
     * The link's heartbeat, and the document written into a pause rather than a stroke.
     *
     * Whether to write at all is the link's answer - alone, whenever there is something new; with
     * another device holding the document open, only when this machine is the one agreed to write
     * it and its disk has the other device's latest write.
     */
    LaunchedEffect(file.absolutePath, loadCount) {
        while (true) {
            delay(400)
            val session = link ?: continue
            if (source == null || restructuring) continue
            if (!saving) noticeDiskChange(session)
            val current = currentInk()
            if (current !== ink) ink = current
            val now = System.currentTimeMillis()
            val free = !saving && !busy
            // How long a pause counts as one depends on what a write of this document costs.
            val idle = free && now - lastEditAt >= session.idleNeededMs()
            if (session.tick(now, ink, idle)) {
                if (!free || !writeThrough()) session.writeFailed(System.currentTimeMillis())
            }
            linkStatus = session.status(System.currentTimeMillis())
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
     * sheet is for: it writes the marks in and then shows the file itself, selected, in the file manager -
     * from where it can be dragged into an email, a chat window or a hand-in page. Saving first
     * matters, because a file shared without it is the file as it was this morning.
     */
    fun shareDocument() {
        scope.launch {
            save {
                runCatching {
                    SystemShell.reveal(file)
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

    /**
     * Build the export the dialog asked for, and put it where it asked.
     *
     * Never touches the original. A canvas whose paper has not caught up with the handwriting is
     * grown on a staged copy rather than in place: exporting is not saving, and while another
     * device holds the document it is not this one's to rewrite.
     */
    fun runExport(request: ExportRequest) {
        val pdf = DesktopSources.isPdf(file)
        val extension = if (pdf) "pdf" else request.format.extension
        val fileName = request.baseName + "." + extension
        val target = when (request.destination) {
            ExportDestination.SAVE_AS -> askExportTarget(file, fileName) ?: return
            ExportDestination.BESIDE ->
                DocumentExport.freeTarget(file.parentFile ?: File("."), request.baseName, extension)
        }
        if (target.canonicalPath == file.canonicalPath) {
            scope.launch { snackbar.showSnackbar("An export cannot replace the document itself") }
            return
        }
        busy = true
        scope.launch {
            val doc = currentInk()
            val result = withContext(Dispatchers.IO) {
                if (!pdf) {
                    return@withContext DocumentExport.exportImage(
                        file, target, doc, asPdf = request.format == ExportFormat.PDF
                    )
                }
                val canvas = doc.canvas?.takeIf { it.paperIsBehind }
                if (canvas == null) {
                    DocumentExport.exportTo(file, target, doc, request.pages, request.flatten)
                } else {
                    val staged = File.createTempFile("inkslate-export", ".pdf")
                    try {
                        file.copyTo(staged, overwrite = true)
                        val grown = DocumentPages.growCanvas(staged, canvas).getOrThrow()
                        DocumentExport.exportTo(
                            staged, target, doc.copy(canvas = grown), request.pages,
                            request.flatten, imagesOf = file
                        )
                    } catch (t: Throwable) {
                        Result.failure(t)
                    } finally {
                        staged.delete()
                    }
                }
            }
            busy = false
            result.fold(
                onSuccess = { written ->
                    status = "Exported ${written.name}"
                    val choice = snackbar.showSnackbar(status, actionLabel = "Show in folder")
                    if (choice == SnackbarResult.ActionPerformed) {
                        runCatching {
                            SystemShell.reveal(written)
                        }
                    }
                },
                onFailure = {
                    status = "Export failed: ${it.message}"
                    snackbar.showSnackbar(status)
                }
            )
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

    /**
     * The link to your other devices, while this document is open.
     *
     * One [DocumentSync] per loaded document, plugged into the app's [LinkHub], which tells it about
     * every device already on the link and everything they say afterwards - on this thread.
     */
    DisposableEffect(file.absolutePath, loadCount) {
        if (loadCount == 0) return@DisposableEffect onDispose { }
        val start = currentInk()
        val session = DocumentSync(
            me = DocumentIO.deviceTag(),
            docId = start.docId,
            fileName = file.name,
            disk = FileRevision.of(file),
            diskInk = writtenInk,
            lastKnownWrite = DesktopPeers.hub.lastWrite(start.docId),
            send = { peer, message -> DesktopPeers.send(peer, message) },
            log = { EventLog.info("link", it) },
            images = ImageLink(
                docId = start.docId,
                have = { images.exists(it) },
                serve = { peer, ids ->
                    scope.launch(Dispatchers.IO) {
                        for (id in ids) {
                            val bytes = images.bytes(id) ?: continue
                            ImageLink.dataFor(start.docId, id, bytes)?.let { DesktopPeers.send(peer, it) }
                        }
                    }
                },
                keep = { id, png ->
                    scope.launch {
                        val picture = withContext(Dispatchers.IO) {
                            if (ImageStore.keepLinked(id, png)) images.load(id) else null
                        }
                        if (picture != null) {
                            EventLog.info("image", "Picture $id arrived over the link")
                            loadedImages[id] = picture
                        }
                    }
                },
                send = { peer, message -> DesktopPeers.send(peer, message) },
                log = { EventLog.info("link", it) }
            )
        )
        link = session
        val attached = object : LinkHub.Document {
            override val docId = start.docId

            override fun onConnected(peer: String) {
                val current = currentInk()
                ink = current
                session.connected(peer, current, System.currentTimeMillis())
                linkStatus = session.status(System.currentTimeMillis())
            }

            override fun onDisconnected(peer: String) {
                session.disconnected(peer, System.currentTimeMillis())
                linkStatus = session.status(System.currentTimeMillis())
            }

            override fun onMessage(peer: String, message: PeerMessage) {
                // Mid-rearrangement nothing can be put on the page. Whatever this was is exchanged
                // again once the rearranged document is open.
                if (link !== session || restructuring) return
                val current = currentInk()
                ink = current
                takeIn(session.received(peer, message, current, System.currentTimeMillis()))
                linkStatus = session.status(System.currentTimeMillis())
            }
        }
        DesktopPeers.hub.attach(attached)
        onDispose {
            DesktopPeers.hub.detach(attached)
            session.closed(System.currentTimeMillis())
            DesktopPeers.hub.wrote(start.docId, session.lastWrite)
            if (link === session) link = null
            linkStatus = DocumentSync.Status.ALONE
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

    /** Put a shape or stamp in hand, with its own settings, and move it to the front of the tray. */
    fun armKind(kind: com.inkslate.core.Stamps.Kind) {
        tools.editStampShelf { it.used(kind) }
        tools.arm(kind, tools.stampShelf.optionsFor(kind))
        traySymbols = false
    }

    /** Opening the tray puts the last shape back in hand, so "another one of those" is one click. */
    fun openTray() {
        trayOpen = true
        traySymbols = false
        if (!tools.hasArmed) tools.stampShelf.lastKind?.let(::armKind)
    }

    fun closeTray() {
        tools.disarm()
        trayOpen = false
        traySymbols = false
        armedSettings = null
    }

    /** Close the settings of a stamp on the page, recording everything changed as one undo. */
    fun endPlacedSettings() {
        if (placedSettings == null) return
        placedSettings = null
        val before = stampEditBefore
        stampEditBefore = emptyList()
        // By group, not by selection: this runs when the selection has just moved elsewhere.
        val first = before.firstOrNull()
        val group = first?.stamp?.group
        val after = strokes.filter { it.stamp?.group == group && it.pageIndex == first?.pageIndex }
        if (before.isNotEmpty() && before != after) {
            pushOp(Op(before, after))
            dirty = true
        }
    }

    /**
     * Build the stamp being changed again with [options], where it stands.
     *
     * Applied straight to the page without an undo entry each time; closing the settings records
     * the whole change as one.
     */
    fun previewStampEdit(options: com.inkslate.core.Stamps.StampOptions) {
        val current = strokes.filter { it.id in selection }
        if (com.inkslate.core.Stamps.stampOf(current) == null) return
        val rebuilt = com.inkslate.core.Stamps.rebuild(current, options, ::nextId)
        if (rebuilt === current) return
        strokes.applyEdit(current, rebuilt)
        selection = rebuilt.map { it.id }.toSet()
    }

    fun duplicateSelection() {
        val before = selected()
        if (before.isEmpty()) return
        val now = System.currentTimeMillis()
        // Offset a little, or the copy hides exactly on top of the original and looks like
        // nothing happened.
        val copies = com.inkslate.core.Stamps.regroup(
            before.map { it.copy(id = nextId(), updatedUtc = now).movedBy(12f, 12f, now) }
        ) { nextId() }
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
        val pasted = com.inkslate.core.Stamps.regroup(
            from.map {
                it.copy(id = nextId(), pageIndex = target, updatedUtc = now).movedBy(16f, 16f, now)
            }
        ) { nextId() }
        strokes.addAll(pasted)
        pushOp(Op.added(pasted))
        selection = pasted.map { it.id }.toSet()
        tools.edit { it.tool = Tool.SELECT }
    }

    /** Put a page at the top of the window, which is what every jump here means. */
    /**
     * Where the page itself is, in the coordinates the camera works in.
     *
     * On a whiteboard that is not the document: the canvas is the document, and the page is a
     * rectangle somewhere inside it - usually with a good deal of empty canvas above and to the
     * left of it, because a canvas grows in every direction. Opening at the document's corner
     * therefore opened on nothing at all.
     */
    fun paperBox(target: Int): com.inkslate.core.Box? {
        val src = source ?: return null
        val c = ink.canvas
        if (c != null) {
            val origins = com.inkslate.core.PageArranger.arrange(
                listOf(com.inkslate.core.PageExtent(c.width, c.height)), layout, 0, c.box
            )
            val (ox, oy) = origins.firstOrNull() ?: return null
            return com.inkslate.core.Box(
                ox + (c.paperLeft - c.left),
                oy + (c.paperTop - c.top),
                ox + (c.paperRight - c.left),
                oy + (c.paperBottom - c.top)
            )
        }
        val extents = (0 until src.pageCount).map {
            val d = src.pageDim(it)
            com.inkslate.core.PageExtent(d.width, d.height)
        }
        val clamped = target.coerceIn(0, src.pageCount - 1)
        val origins = com.inkslate.core.PageArranger.arrange(extents, layout, clamped)
        val (ox, oy) = origins.getOrNull(clamped) ?: return null
        val d = src.pageDim(clamped)
        return com.inkslate.core.Box(ox, oy, ox + d.width, oy + d.height)
    }

    fun goToPage(target: Int) {
        val src = source ?: return
        val clamped = target.coerceIn(0, src.pageCount - 1)
        val extents = (0 until src.pageCount).map {
            val d = src.pageDim(it)
            com.inkslate.core.PageExtent(d.width, d.height)
        }
        val from = page
        page = clamped
        // Music is read a whole page at a time: fitted, centred, nothing off the edge.
        if (AppFlavor.musicView) {
            if (clamped != from && AppFlavor.turnAnimation != "none") {
                turnDir = if (clamped > from) 1 else -1
                scope.launch {
                    turnAnim.snapTo(1f)
                    turnAnim.animateTo(0f, androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                }
            }
            paperBox(clamped)?.let {
                viewport.fitClear(it, padding = 8f, lane = AppFlavor.stripLaneDp * density, laneOnLeft = AppFlavor.stripOnLeft)
                fittedScale = viewport.scale
                return
            }
        }
        val origins = com.inkslate.core.PageArranger.arrange(extents, layout, clamped)
        origins.getOrNull(clamped)?.let { (x, y) -> viewport.goTo(x, y) }
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
        // A page asked for from outside - a band-pack part, another tablet - wins over the
        // remembered one.
        com.inkslate.core.Perform.takePage(file.absolutePath)?.let { asked ->
            goToPage(asked)
            return@LaunchedEffect
        }
        val at = if (tools.rememberView) ReadingPosition.load(file.absolutePath) else null
        if (at == null) {
            // Nothing remembered: open on the page, in the middle of the window. The corner of a
            // document is the corner of a whiteboard's canvas, which is empty space some distance
            // from anything anybody wrote.
            if (AppFlavor.musicView) goToPage(page) else paperBox(page)?.let { viewport.fit(it) }
            return@LaunchedEffect
        }
        if (!AppFlavor.musicView) layout = at.layout
        page = at.page.coerceIn(0, src.pageCount - 1)
        if (AppFlavor.musicView) {
            goToPage(page)
        } else if (at.hasCamera) {
            viewport.restore(at.scale!!, at.x!!, at.y!!)
        } else {
            paperBox(page)?.let { viewport.fit(it) } ?: goToPage(page)
        }
    }

    /**
     * Write down where the reader is, as they move, rather than on the way out.
     *
     * It was written in the editor's teardown, which does not happen when the program is closed:
     * the process ends and nothing is saved, so "open where I left off" only ever worked if you
     * had gone back to the library first. Everything else that survives a close - the window's own
     * shape - is written as it settles, and this now is too.
     *
     * Settled rather than continuous: a write for every frame of a drag is a stream of writes for
     * a number nobody reads until the document is opened again.
     */
    LaunchedEffect(file.absolutePath, source) {
        if (source == null) return@LaunchedEffect
        snapshotFlow { Triple(viewport.scale, viewport.offset, page to layout) }
            .debounce(600)
            .collect {
                if (!tools.rememberView) return@collect
                if (!positionRestored) return@collect
                ReadingPosition.save(
                    file.absolutePath, page, layout,
                    viewport.scale, viewport.offset.x, viewport.offset.y
                )
            }
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
     * Show a search result: the page it is on, with the words themselves in the middle.
     *
     * The hit's boxes are in the page's own points and the camera works in the document's, which
     * on a whiteboard are not the same thing - the page is a rectangle somewhere inside a canvas.
     */
    fun goToHit(hit: com.inkslate.core.SearchHit) {
        val src = source ?: return
        page = hit.page.coerceIn(0, src.pageCount - 1)
        val box = hit.boxes.firstOrNull()
        val paper = paperBox(page)
        if (box == null || paper == null || viewport.viewSize.width <= 0f) {
            goToPage(page)
        } else {
            viewport.panTo(
                paper.left + box.centerX - viewport.viewSize.width / (2f * viewport.scale),
                paper.top + box.centerY - viewport.viewSize.height / (2f * viewport.scale)
            )
        }
        searchOpen = false
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
        if (tools.rulerVisible && tools.rulerOwner === host) {
            tools.rulerVisible = false
            return
        }
        val src = source ?: return
        val dim = src.pageDim(page)
        tools.ruler = com.inkslate.core.Ruler.across(
            com.inkslate.core.Box(0f, 0f, dim.width, dim.height), page
        )
        tools.rulerOwner = host
        tools.rulerVisible = true
    }

    // The ruler is lifted off a document when you move to another one, so the toolbar's ruler
    // button always describes the document it is sitting under.
    LaunchedEffect(focused) {
        if (focused && tools.rulerVisible && tools.rulerOwner !== host) tools.rulerVisible = false
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
        // either a no-op or the last few hundred milliseconds of it. With another device linked,
        // whether this machine writes on the way out is the link's answer; a machine that does not
        // write this document leaves the file alone, with everything kept in its working copy.
        val session = link
        scope.launch {
            if (session != null) {
                val current = currentInk()
                ink = current
                if (session.closing(current, System.currentTimeMillis())) {
                    writeThrough()
                } else {
                    withContext(Dispatchers.IO) { DocumentIO.saveWorking(file, current) }
                }
            } else if (dirty) {
                writeThrough()
            }
            onClose()
        }
    }

    // In the music view the page stays whole on screen whatever the window does - the window
    // resized, a monitor swapped, the tools put away. Bringing the tools out keeps the view as it
    // is: whatever was zoomed in on to write is still there to write on.
    LaunchedEffect(viewport.viewSize, immersive) {
        if (AppFlavor.musicView && positionRestored && source != null && immersive) goToPage(page)
    }

    // Where the reader is, for another tablet following this one.
    LaunchedEffect(focused, page, source?.pageCount) {
        if (focused) {
            com.inkslate.core.Perform.onPage?.invoke(file.absolutePath, page)
            com.inkslate.core.Perform.onPosition?.invoke(page, source?.pageCount ?: 0)
        }
    }

    // Hand the window's key handler something to call. Re-assigned on each composition so the
    // captured lambdas always see current state rather than the state at first composition. Only
    // the focused pane may do this - in split view the other one is still on screen, but the
    // keyboard follows whichever document was clicked or tapped last.
    if (focused) {
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
        shortcuts.shapes = { if (trayOpen) closeTray() else openTray() }
        // Pedals and page keys. A turn this document cannot make (past its last page) returns
        // false, and InkSheets takes it as a turn to the next song.
        if (positionRestored) {
            com.inkslate.core.Perform.takePage(file.absolutePath)?.let { asked -> goToPage(asked) }
        }
        com.inkslate.core.Perform.jumpTo = { path, target ->
            if (path == file.absolutePath) goToPage(target)
        }
        // A leading tablet's marks, for a player on the same part - see Perform.mergeInk.
        com.inkslate.core.Perform.inkOf = { path -> if (path == file.absolutePath) currentInk() else null }
        com.inkslate.core.Perform.mergeInk = { path, incoming ->
            if (path != file.absolutePath) false else {
                val mine = currentInk()
                if (mine.layout != incoming.layout) false else {
                    com.inkslate.core.Perform.mergedInk(mine, incoming)?.let { merged -> ink = mine; takeIn(merged) }
                    true
                }
            }
        }
        com.inkslate.core.Perform.openPages = { pagesOpen = true }
        com.inkslate.core.Perform.clearInk = { path ->
            if (path != file.absolutePath) false else {
                strokes.clear()
                selection = emptySet()
                dirty = true
                true
            }
        }
        com.inkslate.core.Perform.recentre = { goToPage(page) }
        com.inkslate.core.Perform.document = { action ->
            val count = source?.pageCount ?: 0
            when (action) {
                com.inkslate.core.PerformAction.NEXT_PAGE ->
                    (page < count - 1).also { if (it) goToPage(page + 1) }
                com.inkslate.core.PerformAction.PREVIOUS_PAGE ->
                    (page > 0).also { if (it) goToPage(page - 1) }
                com.inkslate.core.PerformAction.FIRST_PAGE -> { goToPage(0); true }
                com.inkslate.core.PerformAction.LAST_PAGE -> { goToPage(count - 1); true }
                com.inkslate.core.PerformAction.UNDO -> { undoOnce(); true }
                com.inkslate.core.PerformAction.REDO -> { redoOnce(); true }
                com.inkslate.core.PerformAction.HALF_PAGE_FORWARD, com.inkslate.core.PerformAction.HALF_PAGE_BACK -> {
                    // Half the window: the bottom half of what was showing moves to the top, so
                    // the next lines are there before the last ones have gone.
                    val before = viewport.offset
                    val sign = if (action == com.inkslate.core.PerformAction.HALF_PAGE_FORWARD) -1f else 1f
                    viewport.panBy(0f, sign * viewport.viewSize.height * 0.5f)
                    viewport.offset != before
                }
                else -> false
            }
        }
        navigation.back = {
            // Escape steps back out of one thing at a time, innermost first. Focus mode before the
            // document especially: hitting Escape to get the toolbars back and having the document
            // close instead is the kind of surprise that costs an unsaved minute.
            when {
                immersive -> immersive = false
                placedSettings != null -> endPlacedSettings()
                armedSettings != null -> armedSettings = null
                tools.hasArmed -> closeTray()
                selection.isNotEmpty() -> selection = emptySet()
                else -> leave()
            }
        }
    }
    // A stroke put the item in hand away: the tray has done its job and folds out of the way.
    // Every pane keeps this regardless of focus - it is about that pane's own tray, not the
    // keyboard.
    tools.onPutAway = {
        trayOpen = false
        traySymbols = false
        armedSettings = null
    }

    // A tab's close button reaches in from outside exactly the way Ctrl+W does from inside.
    LaunchedEffect(closeRequested.value) {
        if (closeRequested.value) leave()
    }

    // ---- layout --------------------------------------------------------------
    //
    // Nothing is laid out here. The bars and the views are handed to the host, and the workspace
    // puts them where they go: the bars once, across the window, for the document last touched;
    // the views into whichever panes are showing this document.

    val linkSummary = rememberLinkSummary()

    val writeState = when {
        saving -> WriteState.SAVING
        dirty -> WriteState.UNSAVED
        else -> WriteState.SAVED
    }

    val topBar: @Composable () -> Unit = topBar@{
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
                            "Page ${page + 1} of ${it.pageCount}  ·  " + when (linkStatus) {
                                // The other device writes it; "unsaved" here would only be
                                // saying that its write is still on the way.
                                DocumentSync.Status.WRITTEN_ELSEWHERE ->
                                    "linked, saved by your other device"
                                DocumentSync.Status.WRITING_HERE -> "${writeState.label}  ·  linked, saved here"
                                DocumentSync.Status.AGREEING -> "${writeState.label}  ·  linking..."
                                DocumentSync.Status.WAITING_FOR_FILE ->
                                    "${writeState.label}  ·  waiting for sync"
                                // The other device does not have this open, or is not
                                // connected at all - which one is worth knowing.
                                DocumentSync.Status.ALONE -> writeState.label + linkSummary?.takeIf { it.linked }?.let {
                                    "  ·  " + it.label.replaceFirstChar { c -> c.lowercase() }
                                }.orEmpty()
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            actions = {
                // Only there while linked, so a glance says whether marks are travelling.
                LinkIndicator(short = true, modifier = Modifier.align(Alignment.CenterVertically))
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
                // In the document, not behind the settings: what a button does is something
                // you want to check or change in the middle of using it.
                IconButton(onClick = { controlsOpen = true }) {
                    Icon(Icons.Default.Mouse, "Controls")
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
                        // The page arrangement lives in the Pages sheet, as on the tablet.
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
    }

    val bottomBar: @Composable () -> Unit = {
        Column {
            // Music has its page number and overview on the strip beside the page, and turns by
            // swipe, pedal or the strip - not a second row of arrows here.
            if (!AppFlavor.musicView) source?.let { src ->
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
            if (trayOpen) {
                // Read so the tray follows what is in hand, which is not itself Compose state.
                @Suppress("UNUSED_VARIABLE") val tick = tools.armedTick
                ShapeTray(
                    shelf = tools.stampShelf,
                    armed = tools.armedStamp?.first,
                    armedText = tools.armedText,
                    symbols = traySymbols,
                    onArm = ::armKind,
                    onDisarm = { tools.disarm() },
                    onOpenLibrary = { libraryOpen = true },
                    onSettingsFor = { kind ->
                        endPlacedSettings()
                        armedSettings = kind
                    },
                    onOpenSettings = {
                        tools.armedStamp?.first?.let { kind ->
                            endPlacedSettings()
                            armedSettings = kind
                        }
                    },
                    onShowSymbols = { traySymbols = it },
                    onSymbol = { sym ->
                        val held = tools.armedText
                        tools.armText(if (held == null || symbolPlaced) sym else held + sym)
                        symbolPlaced = false
                    },
                    onBackspace = {
                        val held = tools.armedText
                        if (held != null) {
                            if (held.codePointCount(0, held.length) <= 1) {
                                tools.disarm()
                            } else {
                                tools.armText(held.substring(0, held.offsetByCodePoints(held.length, -1)))
                            }
                        }
                    },
                    onClose = ::closeTray
                )
            }
            val shapeTool = remember { mutableStateOf(tools.active.tool) }
            ToolBar(
                state = tools,
                selectionCount = selection.size,
                shapesOpen = trayOpen,
                actions = ToolBarActions(
                    onChanged = {
                        // Leaving the select tool is also leaving the selection; a frame
                        // around something you can no longer move is just clutter.
                        if (tools.active.tool != Tool.SELECT) selection = emptySet()
                        // Picking another tool puts a shape away; changing the pen does not.
                        if (tools.active.tool != shapeTool.value) {
                            shapeTool.value = tools.active.tool
                            if (tools.hasArmed) closeTray()
                        }
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
                    onToggleShapes = { if (trayOpen) closeTray() else openTray() },
                    onEditStamp = com.inkslate.core.Stamps.stampOf(selected())?.let { tag ->
                        {
                            armedSettings = null
                            stampEditBefore = selected()
                            placedSettings = tag
                        }
                    },
                    onToggleRuler = ::toggleRuler,
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
                            tools.rulerOwner = host
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

    val pane: @Composable (DocView, Boolean) -> Unit = { view, inFocus ->
        val src = source
        Box(Modifier.fillMaxSize()) {
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
                    modifier = Modifier.graphicsLayer {
                        val p = turnAnim.value
                        if (p > 0f) {
                            if (AppFlavor.turnAnimation == "fade") {
                                alpha = 1f - p * 0.9f
                            } else {
                                translationX = turnDir * p * size.width * 0.45f
                                alpha = 1f - p * 0.7f
                            }
                        }
                    },
                    // A fitted page of music: a finger turns it rather than moving it.
                    // Music with its tools put away: a finger turns pages rather than moving them.
                    atRest = { AppFlavor.musicView && immersive },
                    recentre = { goToPage(page) },
                    viewport = view.viewport,
                    layout = layout,
                    currentPage = view.page,
                    onPageChanged = { view.page = it },
                    strokes = strokes,
                    selection = selection,
                    onSelection = {
                        selection = it
                        // Selecting something else closes the settings of the stamp that was
                        // being changed, as one undo step.
                        val group = placedSettings?.group
                        if (group != null &&
                            com.inkslate.core.Stamps.stampOf(strokes.filter { s -> s.id in it })?.group != group
                        ) {
                            endPlacedSettings()
                        }
                    },
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
                    rulerOwner = host,
                    // Music with the tools put away: a sideways swipe turns the page. With them out,
                    // the page moves as it always has, for marking it up.
                    // Music: a sideways swipe or a tap turns the page whether or not the tools are
                    // out - the pen writes, the finger turns. Past the last page it is the next song.
                    onSwipe = if (AppFlavor.musicView) { dir ->
                        com.inkslate.core.Perform.run(
                            if (dir > 0) com.inkslate.core.PerformAction.NEXT_PAGE else com.inkslate.core.PerformAction.PREVIOUS_PAGE
                        )
                    } else null,
                    images = { id ->
                        loadedImages[id] ?: images.load(id)?.also { loadedImages[id] = it }
                    },
                    onCaptureRegion = { region, pageIndex -> captureRegion(region, pageIndex) },
                    // A thumb button, by default; whatever the table says otherwise.
                    onUndo = ::undoOnce,
                    onRedo = ::redoOnce,
                    onStampPlaced = { symbolPlaced = true },
                    onDrew = { drawn ->
                        // Growth is free while drawing: the extra room is a rectangle in memory
                        // and the paper outside the page is painted rather than written. It
                        // becomes real once, when the document is saved.
                        ink.canvas?.let { current ->
                            // The same two limits the tablet keeps, and for the same reason: a
                            // mark far outside the canvas is a bug in whatever placed it rather
                            // than a request to grow that far, and "infinite" is how the canvas
                            // should feel rather than a promise about the arithmetic. Without
                            // them a coordinate that is merely wrong grows the page until it is
                            // a way to lose the work inside a scroll bar.
                            val tooFar = drawn.left < current.left - MAX_GROWTH_STEP ||
                                drawn.top < current.top - MAX_GROWTH_STEP ||
                                drawn.right > current.right + MAX_GROWTH_STEP ||
                                drawn.bottom > current.bottom + MAX_GROWTH_STEP
                            if (tooFar) {
                                EventLog.warn(
                                    "canvas",
                                    "Ignored a growth request " +
                                        "${drawn.left.toInt()},${drawn.top.toInt()} far outside " +
                                        "the canvas ${current.left.toInt()}," +
                                        "${current.top.toInt()}-${current.right.toInt()}," +
                                        "${current.bottom.toInt()}"
                                )
                                return@let
                            }
                            val grown = current.grownTo(drawn, maxSpan = MAX_CANVAS_SPAN)
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
                // The settings panels sit over the pane the keyboard is in, and nowhere else.
                if (inFocus) {
                    armedSettings?.let { kind ->
                        StampSettingsPanel(
                            kind = kind,
                            options = tools.stampShelf.optionsFor(kind),
                            subtitle = "For the next one you place",
                            editKey = "armed:" + kind.name,
                            preview = true,
                            recentColours = tools.customColors,
                            onChange = { o ->
                                tools.editStampShelf { it.withOptions(kind, o) }
                                if (tools.armedStamp?.first == kind) tools.arm(kind, tools.stampShelf.optionsFor(kind))
                            },
                            onDone = { armedSettings = null },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                    placedSettings?.let { tag ->
                        com.inkslate.core.Stamps.kindOf(tag)?.let { kind ->
                            StampSettingsPanel(
                                kind = kind,
                                options = tag.options,
                                subtitle = "This one, on the page - and the next one you place",
                                editKey = "placed:" + tag.group,
                                preview = false,
                                recentColours = tools.customColors,
                                onChange = { o ->
                                    previewStampEdit(o)
                                    tools.editStampShelf {
                                        it.withOptions(kind, o.copy(size = it.optionsFor(kind).size))
                                    }
                                    dirty = true
                                },
                                onDone = ::endPlacedSettings,
                                modifier = Modifier.align(Alignment.CenterEnd)
                            )
                        }
                    }
                }
            }

            // Messages about this document, over the view its bars are showing.
            if (view.index == host.activeViewIndex) {
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    SideEffect {
        host.topBar = topBar
        host.bottomBar = bottomBar
        host.pane = pane
    }

    // Keeps this document's messages moving while none of its views is on screen. A message
    // nobody draws never times out, and neither does whatever is waiting for it to go: a save
    // that ends by saying so would hold the document "saving" until its tab was looked at again.
    SnackbarHost(snackbar, Modifier.size(0.dp))


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

    if (libraryOpen) {
        StampLibrary(
            shelf = tools.stampShelf,
            onDismiss = { libraryOpen = false },
            onTogglePin = { kind -> tools.editStampShelf { it.togglePin(kind) } },
            onSettings = { kind ->
                libraryOpen = false
                armKind(kind)
                endPlacedSettings()
                armedSettings = kind
            },
            onPick = { kind ->
                libraryOpen = false
                armKind(kind)
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
            presetPages = exportPreset,
            onDismiss = { exportOpen = false; exportPreset = null },
            onExport = { request ->
                exportOpen = false
                exportPreset = null
                runExport(request)
            }
        )
    }

    if (controlsOpen) ControlsSheet(onDismiss = { controlsOpen = false })

    // Page management: also written, also never shown. Rearranging rewrites the document, so it
    // saves what is in hand first and then reopens - the file on disk is a different document
    // afterwards, and every page picture on screen belongs to the one before it.
    if (pagesOpen) {
        source?.let { src ->
            PagesSheet(
                source = src,
                currentPage = page,
                layout = layout,
                onLayoutChange = { layout = it },
                thumbnailFor = { index -> src.render(index, 180) },
                onApply = { plan ->
                    pagesOpen = false
                    scope.launch {
                        // Rearranging writes the document, and while another device has it open
                        // only one of the two writes. This one takes that over first; the other
                        // carries on drawing, and its marks follow the pages to where they went.
                        val session = link
                        if (session != null && !session.mayWriteNow(System.currentTimeMillis())) {
                            session.requestSave()
                            val waiting = launch {
                                snackbar.showSnackbar(
                                    "Waiting for your other device...",
                                    duration = SnackbarDuration.Indefinite
                                )
                            }
                            val giveUpAt = System.currentTimeMillis() + REARRANGE_WAIT_MS
                            while (link === session && System.currentTimeMillis() < giveUpAt &&
                                !session.mayWriteNow(System.currentTimeMillis())
                            ) delay(250)
                            waiting.cancel()
                            if (link !== session || !session.mayWriteNow(System.currentTimeMillis())) {
                                snackbar.showSnackbar(
                                    "Your other device did not answer, so the pages were left " +
                                        "as they were. Try again in a moment."
                                )
                                return@launch
                            }
                        }
                        busy = true
                        writeThrough()
                        restructuring = true
                        val done = withContext(Dispatchers.IO) {
                            DocumentPages.rearrange(file, ink, plan)
                        }
                        busy = false
                        done.onSuccess { rearranged ->
                            DocumentIO.saveWorking(file, rearranged)
                            // Announced like any other write, so a device with this open waits for
                            // these bytes, and takes the new pages from them.
                            link?.let { s ->
                                val rev = withContext(Dispatchers.IO) { FileRevision.of(file) }
                                if (rev != null) {
                                    s.written(rev, rearranged, System.currentTimeMillis())
                                    DesktopPeers.hub.wrote(rearranged.docId, s.lastWrite)
                                }
                            }
                            diskStamp = DocumentIO.stampOf(file)
                            positionRestored = true
                            reopenTick++
                            snackbar.showSnackbar("Pages rearranged")
                        }.onFailure {
                            restructuring = false
                            snackbar.showSnackbar(
                                it.message ?: "The pages could not be rearranged"
                            )
                        }
                    }
                },
                onGoToPage = { target ->
                    pagesOpen = false
                    goToPage(target)
                },
                onExport = { pages ->
                    // Through the same dialog as the menu's Export, starting from these pages,
                    // because where it goes and whether it is flattened are still questions.
                    pagesOpen = false
                    exportPreset = pages
                    exportOpen = true
                },
                onDismiss = { pagesOpen = false },
                extraActions = AppFlavor.pagesActions?.let { actions ->
                    { pages, close -> actions(file.absolutePath, pages, close) }
                }
            )
        }
    }

    // The page indicator's sheet and the bookmark prompt. Both went missing along with the stamp
    // picker's arrival, leaving the page bar's middle button and its bookmark button doing nothing.
    if (navOpen) {
        NavigationSheet(
            pageCount = source?.pageCount ?: 1,
            currentPage = page,
            outline = outline,
            bookmarks = bookmarks,
            onGoToPage = { navOpen = false; goToPage(it) },
            onRemoveBookmark = { p ->
                ink = ink.withBookmarkRemoved(p)
                bookmarks = ink.bookmarks
                dirty = true
            },
            onDismiss = { navOpen = false }
        )
    }

    bookmarkPrompt?.let { target ->
        BookmarkDialog(
            page = target,
            onDismiss = { bookmarkPrompt = null },
            onConfirm = { label ->
                ink = ink.withBookmarkAdded(target, label)
                bookmarks = ink.bookmarks
                dirty = true
                bookmarkPrompt = null
            }
        )
    }

    // The button for this has been in the toolbar all along with nothing behind it: the sheet was
    // written, the search was written, and the two were never introduced.
    if (searchOpen) {
        source?.let {
            SearchSheet(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = ::runSearch,
                hits = searchHits,
                searching = searching,
                progressPage = searchProgress,
                pageCount = it.pageCount,
                onGoToHit = ::goToHit,
                onDismiss = { searchOpen = false }
            )
        }
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

/** The room kept clear beside a page of music for the action strip, so it never covers a note. */
const val STRIP_LANE_DP = 64f
