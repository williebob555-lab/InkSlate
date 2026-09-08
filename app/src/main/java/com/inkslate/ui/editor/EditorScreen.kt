package com.inkslate.ui.editor

import com.inkslate.data.InkBookmark
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.inkslate.data.CrashLog
import com.inkslate.data.DeviceId
import com.inkslate.data.DocumentRepo
import com.inkslate.data.EventLog
import com.inkslate.data.FileOverride
import com.inkslate.data.FileRepo
import com.inkslate.data.FileWatch
import com.inkslate.data.ImageStore
import com.inkslate.data.PictureImport
import com.inkslate.data.InkDocument
import com.inkslate.data.OpenDocument
import com.inkslate.data.ReadingPosition
import com.inkslate.data.SaveMode
import com.inkslate.data.InkFormat
import com.inkslate.data.SavePrefs
import com.inkslate.data.SaveResult
import com.inkslate.data.StrokeIdGen
import com.inkslate.ink.*
import com.inkslate.ink.DrawingView
import com.inkslate.ink.PageFilter
import com.inkslate.ink.PageLayout
import com.inkslate.ink.RenderStats
import com.inkslate.ink.Stroke
import com.inkslate.ink.StrokeRasteriser
import com.inkslate.pdf.OutlineEntry
import com.inkslate.pdf.PageDim
import com.inkslate.pdf.PageSources
import com.inkslate.pdf.PdfOutline
import com.inkslate.pdf.PdfText
import com.inkslate.pdf.SearchHit
import com.inkslate.ui.ColorPickerDialog
import com.inkslate.ui.rememberImmersive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.io.File
import com.inkslate.core.StylusButtonAction
import com.inkslate.core.InputMode
import com.inkslate.ink.Stamps
import com.inkslate.core.Tool

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { DocumentRepo(context) }
    val fileRepo = remember { FileRepo(context) }
    val prefs = remember { SavePrefs(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var doc by remember { mutableStateOf<OpenDocument?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(0) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pageDims by remember { mutableStateOf<List<PageDim?>>(emptyList()) }
    var renderFailed by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf(PageLayout.VERTICAL) }
    var wantedPages by remember { mutableStateOf(listOf(0)) }
    // Bumped when the surface reports a visible page still lacking a bitmap. Without it, an
    // identical page list would not re-emit and the retry would never reach the loader.
    var renderNonce by remember { mutableStateOf(0) }
    var strokesLoaded by remember { mutableStateOf(false) }
    var positionRestored by remember { mutableStateOf(false) }
    /**
     * The camera to put back once the pages exist, and the page to fall back to.
     *
     * Held rather than applied on the spot because declaring the pages resets the view - it has
     * to, since it is what frames a document that has just been opened. Restoring before that
     * happens is restoring into something about to be thrown away.
     */
    var pendingCamera by remember { mutableStateOf<FloatArray?>(null) }
    var pendingPage by remember { mutableStateOf(-1) }
    var dirty by remember { mutableStateOf(false) }
    var selectionCount by remember { mutableStateOf(0) }
    // DrawingView owns the undo stacks, which are not Compose state; this counter is bumped on
    // every edit so the buttons re-evaluate whether they should be enabled.
    var undoTick by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    // Timings from "Measure save speed", shown so they can be read without a cable.
    var leaving by remember { mutableStateOf(false) }
    // When the pen last touched the page, and whether the document on disk has caught up.
    var lastEditAt by remember { mutableStateOf(0L) }
    var pendingWrite by remember { mutableStateOf(false) }
    // What the document on disk is doing, so the bar can say "saved" rather than say nothing.
    var writeState by remember { mutableStateOf(WriteState.UNSAVED) }
    var benchRunning by remember { mutableStateOf(false) }
    var benchReport by remember { mutableStateOf<String?>(null) }
    var showFileRules by remember { mutableStateOf(false) }
    var textPrompt by remember { mutableStateOf<TextPromptRequest?>(null) }
    var cellPrompt by remember { mutableStateOf<CellPromptRequest?>(null) }
    var navOpen by remember { mutableStateOf(false) }
    var pagesOpen by remember { mutableStateOf(false) }
    // Bumped to force a full reopen after the document's own structure changes underneath us.
    var reloadNonce by remember { mutableStateOf(0) }
    var restructuring by remember { mutableStateOf(false) }
    /**
     * What the document looked like the last time *we* wrote it.
     *
     * Every save rewrites the file, so a change notification means nothing on its own. Comparing
     * against this is what tells our own writing apart from somebody else's.
     */
    var ourStamp by remember { mutableStateOf("") }
    var externalTick by remember { mutableStateOf(0) }
    var externalChange by remember { mutableStateOf(false) }
    // How many of our own writes to the document are in flight. See [writingDocument].
    var selfWrites by remember { mutableStateOf(0) }
    /** A change the user chose to leave alone, so it is not raised again. */
    var ignoredStamp by remember { mutableStateOf("") }
    var pageFilter by remember { mutableStateOf(PageFilter.NONE) }
    var symbolPaletteOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchProgress by remember { mutableStateOf(0) }
    var pressureCurveOpen by remember { mutableStateOf(false) }
    // A snapshot holding markedly more handwriting than the file does, found on open.
    var inkRecovery by remember { mutableStateOf<Pair<java.io.File, Int>?>(null) }
    // True when handwriting has been autosaved but not yet written into the document itself.
    // `dirty` cannot answer this: autosave clears it, and the document is only written on an
    // explicit save or on the way out.
    var unbaked by remember { mutableStateOf(false) }
    var stampPickerOpen by remember { mutableStateOf(false) }
    // Whatever is currently waiting to be placed, mirrored out of the drawing surface so the
    // banner and the toolbar can react to it.
    var armedItem by remember { mutableStateOf<DrawingView.Placement?>(null) }
    var colourPickerOpen by remember { mutableStateOf(false) }
    var armedSymbol by remember { mutableStateOf<String?>(null) }
    var conflictNotice by remember { mutableStateOf<String?>(null) }
    var exportOpen by remember { mutableStateOf(false) }
    // Pages chosen in the page manager, waiting for the export dialog to ask what to do with them.
    var exportPreset by remember { mutableStateOf<List<Int>?>(null) }
    // A finished export waiting for the system picker to say where it goes. Held rather than
    // written twice: the document has already been built, and building it again to a second
    // destination would double the wait on a long file for no reason.
    var pendingExport by remember { mutableStateOf<java.io.File?>(null) }
    var exporting by remember { mutableStateOf(false) }
    // Where the camera has been told to write, kept across the trip out to the camera app.
    var cameraFile by remember { mutableStateOf<java.io.File?>(null) }
    var versionsOpen by remember { mutableStateOf(false) }
    val imageStore = remember(file.absolutePath) { ImageStore(file) }
    var bookmarkPrompt by remember { mutableStateOf<Int?>(null) }
    var outline by remember { mutableStateOf<List<OutlineEntry>?>(null) }
    var bookmarks by remember { mutableStateOf<List<InkBookmark>>(emptyList()) }
    val readingPosition = remember { ReadingPosition(context) }

    val tools = rememberToolState(context)
    val drawingView = remember { mutableStateOf<DrawingView?>(null) }
    val immersive = rememberImmersive()
    val canUndo = remember(undoTick) { drawingView.value?.canUndo() == true }
    val canRedo = remember(undoTick) { drawingView.value?.canRedo() == true }
    val canPaste = remember(undoTick) { drawingView.value?.canPaste() == true }

    // Render roughly two device-widths across: sharp at normal zoom without allocating a bitmap
    // proportional to how far someone might pinch in.
    val config = LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val renderWidthPx = remember(config.screenWidthDp, doc) {
        val base = with(density) { (config.screenWidthDp.dp.toPx() * 2f).toInt() }
        val pages = doc?.pageCount ?: 1
        // A thousand-page textbook is read, not written on at high zoom. Rendering every page at
        // full detail is what exhausts memory and starts the cascade of blank pages.
        val cap = when {
            pages > 400 -> 1500
            pages > 120 -> 1900
            else -> 2600
        }
        base.coerceIn(800, cap)
    }

    // Roughly a quarter of the heap for page rasters, leaving room for ink, UI and the PDF
    // renderer's own working memory.
    val bitmapBudgetBytes = remember {
        (Runtime.getRuntime().maxMemory() / 4).coerceIn(48L * 1024 * 1024, 220L * 1024 * 1024)
    }

    // ---- load ----------------------------------------------------------------

    LaunchedEffect(file.absolutePath, reloadNonce) {
        // Everything cached about the old page tree has to go, or a rearranged document is drawn
        // with the previous document's page rasters. Clearing `doc` first is what stops the
        // render loop reaching a source that is about to be closed underneath it.
        val previous = doc
        if (previous != null) {
            doc = null
            strokesLoaded = false
            positionRestored = true          // a reopen keeps the page it is on, not the saved one
            pageBitmap = null
            pageDims = emptyList()
            // Deliberately *not* clearing the view's strokes here. It reads as tidying up, but
            // an empty view is indistinguishable from a document with nothing in it, and
            // everything downstream that writes the view back treats it as the truth.
            withContext(Dispatchers.IO) { runCatching { previous.close() } }
        }
        val opened = withContext(Dispatchers.IO) { repo.open(file) }
        if (opened == null) {
            loadError = "Could not open ${file.name}. It may be corrupt, password protected, or " +
                "an unsupported format."
        } else {
            doc = opened
            // Not a default of "saved". Until the file is known to match, the honest answer is
            // that it does not.
            writeState =
                if (opened.savedInk != null) WriteState.SAVED else WriteState.UNSAVED
            pendingWrite = opened.savedInk == null
            ourStamp = PageSources.fingerprint(file)
            externalChange = false
            fileRepo.noteOpened(file)

            // Only on a genuine open. A reload after the pages were rearranged keeps whatever is
            // on screen, because the saved position describes a document that no longer exists.
            if (previous == null && tools.rememberView) {
                readingPosition.load(file.absolutePath)?.let { saved ->
                    saved.layout?.let { if (it != layout) layout = it }
                    pendingCamera = saved.camera
                    pendingPage = saved.page
                }
            }
            conflictNotice = when {
                opened.mergedConflicts > 0 -> {
                    val who = opened.mergedFrom.distinct().joinToString(", ")
                    "Merged " + opened.mergedConflicts + " edit set" +
                        (if (opened.mergedConflicts == 1) "" else "s") +
                        " that arrived from another device" +
                        (if (who.isNotEmpty()) " (" + who + ")" else "") +
                        ". Nothing was discarded."
                }
                opened.sourceChanged ->
                    "The PDF itself has changed since these annotations were made, so they may " +
                        "no longer line up with the page."
                else -> null
            }
        }
    }

    /**
     * Watch the document for changes made outside this app.
     *
     * See [FileWatch]. The notification only says "something touched it"; whether that something
     * was us is settled by comparing fingerprints below.
     */
    DisposableEffect(file.absolutePath) {
        val watch = FileWatch(file) { externalTick++ }
        watch.start()
        onDispose { watch.stop() }
    }

    // A canvas is one page by definition, so the arrangement options do not apply to it.
    LaunchedEffect(doc, drawingView.value) {
        val d = doc ?: return@LaunchedEffect
        val view = drawingView.value ?: return@LaunchedEffect
        view.canvas = d.ink.canvas
        if (d.ink.canvas != null && layout != PageLayout.SINGLE) {
            layout = PageLayout.SINGLE
            view.setLayout(PageLayout.SINGLE)
        }
    }

    LaunchedEffect(doc) {
        val d = doc ?: return@LaunchedEffect
        bookmarks = d.ink.bookmarks
        outline = null
    }

    /**
     * The table of contents, read only when someone goes looking for it.
     *
     * Reading it means opening the document again: on a nine-hundred-page textbook that measured
     * 8824ms, spent on every open, for a list most opens never show. It is wanted at the moment
     * the navigation sheet appears and not before.
     */
    LaunchedEffect(navOpen, doc) {
        val d = doc ?: return@LaunchedEffect
        if (!navOpen || outline != null) return@LaunchedEffect
        outline = if (PageSources.isPdf(d.file)) {
            withContext(Dispatchers.IO) { PdfOutline.read(d.file) }
        } else {
            emptyList()
        }
    }

    /**
     * Notice when a document has come back emptier than it was.
     *
     * Silence here would be the wrong default. Missing handwriting is not obvious - a page can
     * look plausibly blank - and by the time it is noticed the snapshots may have rotated out.
     */
    LaunchedEffect(doc, strokesLoaded) {
        val d = doc ?: return@LaunchedEffect
        if (!strokesLoaded || inkRecovery != null) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            // The count on screen is passed in rather than re-read from disk: the editor is
            // already holding the document, and reading it back cost a full parse on every open.
            repo.inkRecoveryCandidate(d.file, d.ink)
        }
        if (found != null) {
            EventLog.warn(
                "sidecar",
                "${d.file.name} opened with ${d.ink.totalStrokes} strokes; a snapshot has " +
                    "${found.second}"
            )
            inkRecovery = found
        }
    }

    DisposableEffect(doc, page, layout) {
        val atDoc = doc
        val atPage = page
        val atLayout = layout
        val view = drawingView.value
        onDispose {
            atDoc?.let {
                // Read the camera at dispose time, not now: this effect is recreated whenever the
                // page changes, and the position that matters is the one when the document is
                // actually being left.
                readingPosition.save(
                    it.file.absolutePath, atPage, atLayout, view?.cameraState()
                )
            }
        }
    }

    /**
     * Hold the screen awake while a document is open.
     *
     * Scoped to the editor rather than the whole app: reading a page for two minutes without
     * touching it is ordinary, and having the screen go out mid-sentence is not something a
     * timeout setting elsewhere on the device should be deciding. Cleared on the way out, so it
     * cannot leak into the rest of the system.
     */
    val hostView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(tools.keepScreenOn) {
        hostView.keepScreenOn = tools.keepScreenOn
        onDispose { hostView.keepScreenOn = false }
    }

    DisposableEffect(doc) {
        // Capture the value now. Reading `doc` inside onDispose would read it at dispose time,
        // and this effect is disposed the instant `doc` changes from null to the loaded
        // document - closing the very document that just opened, so every later render returns
        // nothing and the page shows blank.
        val opened = doc
        onDispose { opened?.close() }
    }

    // ---- render the current page --------------------------------------------

    // Page geometry is read once, off the main thread. PdfRenderer serialises every call on one
    // lock, so asking for it during composition would block the UI behind an in-flight render.
    LaunchedEffect(doc) {
        val d = doc ?: return@LaunchedEffect

        // Show something immediately. Measuring a thousand pages means a thousand openPage calls,
        // and making the document wait on all of them before anything appears is the wrong
        // trade: assume the first page's geometry, then correct it in the background.
        val first = withContext(Dispatchers.IO) {
            runCatching { d.source.pageDim(0) }.getOrNull()
        } ?: PageDim(612f, 792f)
        pageDims = List(d.pageCount) { first }

        if (d.pageCount > 1) {
            val started = System.currentTimeMillis()
            val measured = withContext(Dispatchers.IO) {
                (0 until d.pageCount).map { runCatching { d.source.pageDim(it) }.getOrNull() ?: first }
            }
            if (measured != pageDims) pageDims = measured
            EventLog.info(
                "open",
                "Measured ${d.pageCount} pages in ${System.currentTimeMillis() - started}ms"
            )
        }
    }

    // Declare the pages to the surface as soon as their geometry is known. Bitmaps follow
    // lazily; the surface asks for whichever pages are actually on screen.
    // Keyed on the view as well as the data. Without that, an effect that ran before the
    // AndroidView existed simply did nothing and was never retried, leaving a document with no
    // pages at all - a permanently blank white screen that no amount of scrolling recovers.
    LaunchedEffect(doc, pageDims, layout, drawingView.value) {
        val d = doc ?: return@LaunchedEffect
        if (pageDims.isEmpty()) return@LaunchedEffect
        val view = drawingView.value ?: return@LaunchedEffect
        view.setLayout(layout)
        view.setPages(pageDims.map { (it ?: PageDim(612f, 792f)).let { p -> p.width to p.height } },
            resetView = true)

        // Put the camera back, now that there is something to put it back onto. The camera
        // restores zoom and scroll together and knows nothing about pages, which is what makes it
        // work on a canvas as well as on a chapter; the page is the fallback for documents saved
        // before the camera was recorded.
        if (!positionRestored) {
            positionRestored = true
            val restored = pendingCamera?.let { view.restoreCamera(it) } == true
            if (!restored && pendingPage in 1 until d.pageCount) view.goToPage(pendingPage)
            if (restored || pendingPage > 0) {
                EventLog.info(
                    "open",
                    "Resumed ${d.file.name} at " +
                        (if (restored) "the saved view" else "page ${pendingPage + 1}")
                )
            }
            pendingCamera = null
            pendingPage = -1
        }

        if (!strokesLoaded) {
            view.setStrokes(d.allStrokes())
            strokesLoaded = true
        }
        EventLog.info(
            "open",
            "Declared ${pageDims.size} page(s) for ${d.file.name}, layout ${layout.name}"
        )
        wantedPages = listOf(view.currentPage)
    }

    // Render what is visible, and keep doing so.
    //
    // The previous version remembered which pages it had *asked* for and never asked twice. A
    // render that failed - which on a long textbook eventually happens under memory pressure -
    // left that page marked as requested with no bitmap, so it could never be retried and stayed
    // blank forever. Whether a page has a bitmap is now the only state consulted, which makes
    // this self-healing: anything missing, for any reason, is simply rendered again.
    LaunchedEffect(doc, renderWidthPx) {
        val d = doc ?: return@LaunchedEffect
        EventLog.info("open", "Render loop started for ${d.file.name} at ${renderWidthPx}px")
        // The view is part of what this observes. Reading it inside the collector would not
        // re-trigger anything, so an emission that arrived before the view was ready used to be
        // lost - which is why content only appeared once a pan or zoom changed wantedPages.
        snapshotFlow { Triple(drawingView.value, wantedPages, renderNonce) }
            .collectLatest { (view, pages, _) ->
                view ?: return@collectLatest

            for (index in pages) {
                if (index !in 0 until d.pageCount) continue
                if (view.hasBitmap(index)) continue

                val result = withContext(Dispatchers.IO) {
                    runCatching { d.source.renderPage(index, renderWidthPx) }
                }
                val bmp = result.getOrNull()
                if (bmp == null) {
                    result.exceptionOrNull()?.let {
                        CrashLog.note(context, "renderPage($index)", it)
                        EventLog.error("render", "Page ${index + 1} failed: ${it.message}")
                    }
                    // Free everything that is not on screen and try this page once more. Running
                    // out of memory is the usual cause, and it is recoverable.
                    view.retainBitmaps(pages, 0L)
                    val retry = withContext(Dispatchers.IO) {
                        runCatching { d.source.renderPage(index, renderWidthPx / 2) }.getOrNull()
                    }
                    view.setPageBitmap(index, retry)
                    if (retry == null) {
                        EventLog.error("render", "Page ${index + 1} still blank after retry")
                        if (index == view.currentPage) renderFailed = true
                    } else {
                        EventLog.warn("render", "Page ${index + 1} rendered at reduced resolution")
                    }
                } else {
                    view.setPageBitmap(index, bmp)
                    if (index == view.currentPage) renderFailed = false
                }
            }

            // Hold a margin of pages around the viewport, bounded by memory rather than by a
            // page count, so scrolling back a page does not force a re-render.
            view.retainBitmaps(pages, bitmapBudgetBytes)
            RenderStats.pagesResident = view.residentPageCount()
        }
    }

    // ---- autosave ------------------------------------------------------------

    val settings = remember(doc) {
        doc?.let { prefs.effectiveFor(it.file.absolutePath) } ?: prefs.global()
    }

    LaunchedEffect(doc, settings.autosave, settings.autosaveSeconds) {
        if (!settings.autosave) return@LaunchedEffect
        while (true) {
            delay(settings.autosaveSeconds * 1000L)
            val d = doc ?: continue
            if (!dirty) continue
            if (!strokesLoaded) continue
            drawingView.value?.let {
                d.updateAll(it.strokesSnapshot(), callerHoldsWholeDocument = true)
            }
            val ok = withContext(Dispatchers.IO) { repo.saveWorking(d) }
            if (ok) { dirty = false; unbaked = true }
        }
    }

    /**
     * Push the on-screen strokes into the document. Cheap; safe to call often.
     *
     * Never before the strokes have been handed to the view. Until then it is empty for a
     * perfectly ordinary reason, and writing that back deletes the document.
     */
    fun syncPage() {
        val d = doc ?: return
        val view = drawingView.value ?: return
        if (!strokesLoaded) return
        val before = d.ink
        d.updateAll(view.strokesSnapshot(), callerHoldsWholeDocument = true)
        // Whatever this turned up is the same thing closing asks about, so the indicator has to
        // hear about it too. Otherwise the bar can read "saved" while leaving still has work to
        // do - which is exactly the state that makes the label worth nothing.
        if (d.ink !== before && d.ink !== d.savedInk) {
            pendingWrite = true
            writeState = WriteState.UNSAVED
        }
    }

    /**
     * Run something that writes the document, without the watcher taking it for someone else.
     *
     * Every save renames a rewritten file into place, and the folder watch reports that exactly
     * as it reports a copy syncing in. The two used to be told apart by comparing fingerprints
     * afterwards, which only works if our write finishes before the watcher gets round to
     * looking - and an overwrite writes the file twice, seconds apart, because the pages and the
     * embedded handwriting are written separately. The watcher fingerprinted the file in the gap,
     * found it changed, and announced that another device had touched it. That is why saving
     * reliably accused itself.
     *
     * Holding a marker for the whole operation, and taking the new fingerprint at the end of it,
     * is what actually separates our writes from everyone else's.
     */
    suspend fun <T> writingDocument(block: suspend () -> T): T {
        selfWrites++
        try {
            return block()
        } finally {
            ourStamp = withContext(Dispatchers.IO) { PageSources.fingerprint(file) }
            selfWrites--
        }
    }

    /**
     * Write the handwriting to the app's own store. Cheap, invisible, and safe to call often.
     *
     * This does not touch the document. That happens in [bakeDocument].
     */
    fun persistWorking(onDone: (Boolean) -> Unit = {}) {
        val d = doc ?: return
        // Nothing loaded means nothing to write, and writing anyway is how an untouched
        // document emptied itself simply by being opened and closed again.
        if (!strokesLoaded) { onDone(true); return }
        syncPage()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { repo.saveWorking(d) }
            if (ok) { dirty = false; unbaked = true }
            onDone(ok)
        }
    }

    /**
     * Bring the document on disk up to date, quietly.
     *
     * Same path as the Save button, minus the parts that only make sense when a person asked:
     * no backup - twenty rolling copies of a file that is written every time the pen pauses is
     * not a version history, it is a disk leak - and no message, because nothing happened that
     * the user needs told about.
     *
     * Cheap because of what came before it: only the pages whose ink moved are rebuilt, and the
     * result is appended rather than the whole document written out again. Called while the
     * document is open, so that closing it, or losing the app, finds the file already finished.
     */
    suspend fun writeThrough(): Boolean {
        val d = doc ?: return false
        if (!strokesLoaded) return false
        val rules = prefs.effectiveFor(d.file.absolutePath).copy(mode = SaveMode.OVERWRITE)
        syncPage()
        if (d.ink === d.savedInk) {
            pendingWrite = false
            writeState = WriteState.SAVED
            return true
        }
        writeState = WriteState.SAVING
        val ok = writingDocument {
            withContext(Dispatchers.IO) {
                repo.saveWorkingQuietly(d)
                repo.export(d, rules.copy(backupOnOverwrite = false), recordHistory = false)
            }
        }
        if (ok is SaveResult.Written) {
            // Only settled if nothing arrived while it was being written. The write covered the
            // document as it was when it started, not as it is now.
            if (d.ink === d.savedInk) {
                pendingWrite = false
                dirty = false
                unbaked = false
                writeState = WriteState.SAVED
            }
            fileRepo.invalidateThumb(d.file)
            return true
        }
        writeState = WriteState.UNSAVED
        return false
    }

    /**
     * Write the document out once the hand comes off the page.
     *
     * Not on a fixed interval: a write while someone is mid-sentence is the one moment it could
     * be felt, and there is nothing to write until they stop anyway. A short settle after the
     * last mark catches the ordinary rhythm of writing a line and pausing to think.
     */
    LaunchedEffect(doc, strokesLoaded) {
        if (doc == null || !strokesLoaded) return@LaunchedEffect
        while (true) {
            delay(1200)
            if (!pendingWrite || selfWrites > 0 || leaving) continue
            if (System.currentTimeMillis() - lastEditAt < IDLE_BEFORE_WRITE_MS) continue
            writeThrough()
        }
    }

    /**
     * Write on the way to the background, which is the last moment anything of ours runs.
     *
     * A process killed for memory while the app is behind another one gets no further warning
     * than this. It does not cover a hard crash - nothing does - but the handwriting is in the
     * journal either way, and this is what makes the *document* current rather than only
     * recoverable.
     */
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, doc) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && pendingWrite) {
                scope.launch { writeThrough() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(externalTick) {
        if (externalTick == 0 || doc == null) return@LaunchedEffect
        // Sync writes arrive as a burst - a temporary file, a rename, a timestamp touch - so let
        // it settle rather than asking three times about one arrival.
        delay(1200)
        // Our own saves land here too, and saving a marked-up document is not instantaneous.
        // Judging the file while we are still part-way through writing it is how a save came to
        // report itself as an arrival from another device.
        while (selfWrites > 0) delay(250)
        if (!file.isFile) return@LaunchedEffect          // mid-rename; the next event will tell us
        val now = withContext(Dispatchers.IO) { PageSources.fingerprint(file) }
        if (now == ourStamp || now == ignoredStamp) return@LaunchedEffect

        // The length and the timestamp only say the file was written, and every save writes it -
        // ours included, twice over when the pages are overwritten as well as the payload. So
        // before saying anything, ask the question that actually matters: is the handwriting in
        // there still the handwriting we put there? A save changes the bytes and leaves that
        // answer alone; a copy arriving from another device does not.
        val stillOurs = withContext(Dispatchers.IO) { repo.documentStillOurs(file) }
        if (stillOurs) {
            // Re-baseline so the cheap check catches it next time round.
            ourStamp = now
            return@LaunchedEffect
        }
        EventLog.warn("sync", "${file.name} changed on disk while it was open")
        // Get this device's marks into the working store first. Whatever the user decides next,
        // the reload merges the two sides, and it can only do that if ours are written down.
        persistWorking()
        externalChange = true
    }

    /**
     * Write the handwriting into the document itself, so it travels with the file.
     *
     * Called on an explicit save and on the way out - the two moments the user named. The working
     * copy is written first and kept afterwards, so a document that cannot be written to (read
     * only, a format that cannot carry a payload, a storage provider having a bad day) costs
     * nothing beyond a warning.
     */
    fun bakeDocument(onDone: (Boolean) -> Unit = {}) {
        val d = doc ?: return
        if (!strokesLoaded) { onDone(true); return }
        syncPage()
        scope.launch {
            val ok = writingDocument {
                withContext(Dispatchers.IO) {
                    repo.saveWorking(d)
                    repo.bakeInto(d)
                }
            }
            ok.fold(
                onSuccess = {
                    dirty = false
                    unbaked = false
                    onDone(true)
                },
                onFailure = {
                    // Deliberately loud. The handwriting is safe in the working store, but the
                    // user needs to know it is not in the file they are about to sync or hand in.
                    dirty = false
                    snackbar.showSnackbar(
                        "Kept your handwriting, but could not write it into " +
                            "${d.file.name}: ${it.message}"
                    )
                    onDone(false)
                }
            )
        }
    }

    /**
     * Hand a finished export to whatever the user picked.
     *
     * Sharing needs a URI the other app is allowed to read, which is why the file is built into
     * this app's cache and served through the file provider rather than shared from wherever it
     * happens to live - a path on shared storage is not something another app can be granted.
     */
    fun sendExported(target: java.io.File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", target
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = if (target.extension.equals("pdf", true)) "application/pdf" else "image/*"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, target.name)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(send, "Send " + target.name)
            )
        }.onFailure {
            scope.launch { snackbar.showSnackbar("Could not send it: " + it.message) }
        }
    }

    // Writes a finished export to wherever the system picker landed. The document itself is
    // already built by this point; all that is left is to copy the bytes across.
    val saveToLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            // A concrete type, not a wildcard: some document providers refuse "image/*" and
            // create a file with no extension, which then will not open anywhere.
            when (file.extension.lowercase()) {
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "jpg", "jpeg" -> "image/jpeg"
                else -> "application/pdf"
            }
        )
    ) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    } ?: error("Could not write there")
                }
            }
            ok.fold(
                onSuccess = { snackbar.showSnackbar("Saved " + source.name) },
                onFailure = { snackbar.showSnackbar(it.message ?: "Could not save it") }
            )
        }
    }

    /**
     * Build the export the dialog asked for, then do the thing it asked for with it.
     *
     * Always written into the cache first, whatever the destination. That is required for
     * sharing, it is what the system picker copies from, and for "keep it here" it costs one
     * extra copy of a file that has just been written anyway - which is a fair price for one code
     * path instead of three.
     */
    fun runExport(request: ExportRequest) {
        val d = doc ?: return
        syncPage()
        scope.launch {
            exporting = true
            val extension =
                if (PageSources.isPdf(d.file)) "pdf"
                else d.file.extension.ifBlank { "png" }
            val staging = java.io.File(context.cacheDir, "exports").also { it.mkdirs() }
            // Anything still here is a previous export that has already been sent or saved.
            withContext(Dispatchers.IO) {
                staging.listFiles()?.forEach { f ->
                    if (f.lastModified() < System.currentTimeMillis() - 3600_000L) f.delete()
                }
            }
            val staged = java.io.File(staging, request.baseName + "." + extension)
            if (d.ink !== d.savedInk) withContext(Dispatchers.IO) { repo.saveWorking(d) }
            val built = withContext(Dispatchers.IO) {
                repo.exportTo(d, staged, request.pages, request.flatten)
            }
            exporting = false
            built.fold(
                onSuccess = { produced ->
                    when (request.destination) {
                        ExportDestination.SHARE -> sendExported(produced)
                        ExportDestination.PICK_FOLDER -> {
                            pendingExport = produced
                            runCatching { saveToLauncher.launch(produced.name) }.onFailure {
                                pendingExport = null
                                snackbar.showSnackbar("No file picker on this device")
                            }
                        }
                        ExportDestination.BESIDE -> {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val dir = d.file.parentFile!!
                                    var out = java.io.File(dir, produced.name)
                                    var n = 2
                                    while (out.exists()) {
                                        out = java.io.File(
                                            dir,
                                            request.baseName + " (" + n + ")." + extension
                                        )
                                        n++
                                    }
                                    produced.copyTo(out)
                                    out
                                }
                            }
                            result.fold(
                                onSuccess = {
                                    fileRepo.invalidateThumb(it)
                                    snackbar.showSnackbar("Saved " + it.name + " here")
                                },
                                onFailure = {
                                    snackbar.showSnackbar(it.message ?: "Could not save it")
                                }
                            )
                        }
                    }
                },
                onFailure = { snackbar.showSnackbar(it.message ?: "Export failed") }
            )
        }
    }

    /**
     * Put a picture on the page.
     *
     * Armed rather than dropped in the middle: the space a photograph of your working belongs in
     * is a space you left for it, and only you know where that is.
     */
    fun placePicture(uri: android.net.Uri, label: String) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { PictureImport.decode(context, uri) }
            if (bmp == null) {
                snackbar.showSnackbar("Could not read that picture")
                return@launch
            }
            val aspect = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
            val id = withContext(Dispatchers.IO) { imageStore.put(bmp) }
            bmp.recycle()
            if (id == null) {
                snackbar.showSnackbar("Could not store that picture")
                return@launch
            }
            drawingView.value?.armImage(id, aspect, label)
            dirty = true
        }
    }

    val picturePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) placePicture(uri, "Picture") }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { taken ->
        val f = cameraFile
        cameraFile = null
        if (taken && f != null) placePicture(android.net.Uri.fromFile(f), "Photo")
    }

    fun takePhoto() {
        val prepared = PictureImport.cameraTarget(context)
        if (prepared == null) {
            scope.launch { snackbar.showSnackbar("Could not prepare the camera") }
            return
        }
        cameraFile = prepared.first
        runCatching { cameraLauncher.launch(prepared.second) }.onFailure {
            cameraFile = null
            scope.launch { snackbar.showSnackbar("No camera app on this device") }
        }
    }

    fun doExport(mode: SaveMode, onDone: () -> Unit = {}) {
        val d = doc ?: return
        syncPage()
        scope.launch {
            val effective = prefs.effectiveFor(d.file.absolutePath).copy(mode = mode)
            // The working copy is a full serialisation of every stroke. Skipped when the document
            // has not moved since it was last written, which after the background write-through
            // is most of the time - it was the remaining wait on a close that had nothing to do.
            if (d.ink !== d.savedInk) withContext(Dispatchers.IO) { repo.saveWorking(d) }
            val result = writingDocument {
                withContext(Dispatchers.IO) { repo.export(d, effective) }
            }
            dirty = false
            // Overwriting writes the handwriting into the document as part of the export, so
            // there is nothing left for the exit-time bake to do.
            if (result is SaveResult.Written && !result.wasCopy) {
                unbaked = false
                // Without this the write on the way to the background sees work still pending
                // and does the whole thing again - which is most of what made leaving feel slow,
                // because the two are serialised and you wait for both.
                if (d.ink === d.savedInk) {
                    pendingWrite = false
                    writeState = WriteState.SAVED
                }
            }
            fileRepo.invalidateThumb(d.file)
            when (result) {
                is SaveResult.Written -> snackbar.showSnackbar(
                    if (result.wasCopy) "Saved a copy: ${result.target.name}"
                    else "Overwrote ${result.target.name}" +
                        (result.backup?.let { " (backup kept)" } ?: "")
                )
                is SaveResult.Failed ->
                    snackbar.showSnackbar(result.error.message ?: "Save failed")
                SaveResult.NothingToDo ->
                    snackbar.showSnackbar("Nothing drawn yet, so nothing was exported")
            }
            onDone()
        }
    }

    /**
     * Leaving the document writes it out.
     *
     * Only when something actually changed: writing an unmodified document gains nothing, and
     * when the load had not finished it once cost everything.
     */
    /**
     * Write the finished document on the way out, not just the editable copy.
     *
     * Leaving used to embed the handwriting and stop there, which left the pages themselves
     * looking blank to anything that is not this app until the next explicit save. When the file
     * rules say to overwrite, closing now produces the same document the Save button does - the
     * marks on the page and the editable copy inside it - because that is what the file is for.
     *
     * Only for overwrite. A rule that says "always write a copy" means a new file per save, and
     * quietly making one every time the document is closed is not what anyone asked for.
     */
    fun finishAndClose() {
        val d = doc
        if (d != null) {
            // The background write-through has usually already done this. Ask before waiting:
            // making someone watch a progress dialog for a file that is already finished is the
            // whole of what they notice.
            syncPage()
            if (d.ink === d.savedInk) { onClose(); return }
            leaving = true
            doExport(SaveMode.OVERWRITE) { leaving = false; onClose() }
        } else {
            bakeDocument { onClose() }
        }
    }

    /**
     * Leaving writes the document, and there is nothing to ask about.
     *
     * There used to be a prompt here, and a save button, and a rule deciding whether saving meant
     * overwriting or copying. All of it existed because the file could be behind what was on
     * screen. It cannot any more: the document is written as you work, so closing is either a
     * no-op or the last few hundred milliseconds of it. Handing someone a dialog about a decision
     * the app has already made for them is worse than making it silently.
     */
    fun leave() = finishAndClose()

    /**
     * Hand the finished document to whatever the assignment gets submitted through.
     *
     * The other half of arriving here from Canvas or Gmail, which the app has always handled and
     * never answered: the annotated PDF is a file on disk and there was no way out of the app
     * except a file manager. Written first, because sharing a document that is a stroke behind is
     * the one way this can be wrong.
     */
    fun shareDocument() {
        scope.launch {
            writeThrough()
            val d = doc ?: return@launch
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", d.file
                )
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = if (PageSources.isPdf(d.file)) "application/pdf" else "image/*"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, d.file.name)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    android.content.Intent.createChooser(send, "Send ${d.file.name}")
                )
            }.onFailure {
                snackbar.showSnackbar("Could not share ${d.file.name}: ${it.message}")
            }
        }
    }

    /**
     * Mark this version as one worth being able to come back to.
     *
     * Version history fills itself from explicit save points, and there are no explicit saves any
     * more. Without something like this the history would only ever hold the automatic writes,
     * which is a record of the last few minutes rather than of the decisions worth undoing.
     */
    fun checkpoint() {
        scope.launch {
            val d = doc ?: return@launch
            writeThrough()
            withContext(Dispatchers.IO) { repo.checkpoint(d) }
            snackbar.showSnackbar("Checkpointed ${d.file.name}")
        }
    }

    BackHandler(enabled = true) { leave() }

    // ---- UI ------------------------------------------------------------------

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // In focus mode the app bar goes too, leaving only the drawing tools.
            if (!immersive.isFullscreen) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                file.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            doc?.let {
                                Text(
                                    "Page ${page + 1} of ${it.pageCount}  ·  " +
                                        when (writeState) {
                                            WriteState.SAVING -> "saving..."
                                            WriteState.SAVED -> "saved"
                                            WriteState.UNSAVED -> "unsaved"
                                        },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { leave() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { drawingView.value?.undo(); dirty = true },
                            enabled = drawingView.value?.canUndo() == true
                        ) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                        IconButton(
                            onClick = { drawingView.value?.redo(); dirty = true },
                            enabled = drawingView.value?.canRedo() == true
                        ) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                        IconButton(onClick = { searchOpen = true }) {
                        Icon(Icons.Default.Search, "Find in document")
                    }
                    IconButton(onClick = { immersive.toggle() }) {
                            Icon(Icons.Default.Fullscreen, "Fullscreen")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, "More")
                            }
                            DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
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
                                DropdownMenuItem(
                                    text = { Text("Version history...") },
                                    onClick = { menuOpen = false; versionsOpen = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rules for this file") },
                                    onClick = { menuOpen = false; showFileRules = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Measure save speed") },
                                    onClick = {
                                        menuOpen = false
                                        syncPage()
                                        benchRunning = true
                                        scope.launch {
                                            val text = withContext(Dispatchers.IO) {
                                                com.inkslate.pdf.SaveBenchmark.run(
                                                    context, file, doc!!.ink,
                                                    prefs.effectiveFor(file.absolutePath).inkFormat
                                                )
                                            }
                                            benchRunning = false
                                            benchReport = text
                                        }
                                    }
                                )
                                PageFilter.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (pageFilter == option) "\u2713  " + option.label
                                                else "      " + option.label
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            pageFilter = option
                                            drawingView.value?.pageFilter = option
                                        }
                                    )
                                }
                                HorizontalDivider()
                                // Arrangement used to be five loose entries here. It belongs with
                                // the pages themselves, so it moved into Pages along with adding,
                                // removing, duplicating and reordering them.
                                DropdownMenuItem(
                                    text = { Text("Pages...") },
                                    onClick = { menuOpen = false; pagesOpen = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Fit page") },
                                    onClick = { menuOpen = false; drawingView.value?.fitToScreen() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Fit width") },
                                    onClick = { menuOpen = false; drawingView.value?.fitWidth() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Reset view") },
                                    onClick = {
                                        menuOpen = false
                                        drawingView.value?.resetView()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear this page") },
                                    onClick = {
                                        menuOpen = false
                                        drawingView.value?.clearPage(); dirty = true
                                    }
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            doc?.let { d ->
                // navigationBarsPadding keeps the toolbar clear of the gesture bar, which
                // otherwise sits directly on top of the tool buttons
                Column(Modifier.navigationBarsPadding()) {
                    if (d.pageCount > 1 || PageSources.isPdf(d.file)) {
                        PageBar(
                            page = page,
                            pageCount = d.pageCount,
                            bookmarked = bookmarks.any { it.page == page },
                            onPrev = { drawingView.value?.goToPage(page - 1) },
                            onNext = { drawingView.value?.goToPage(page + 1) },
                            onOpenNavigation = { navOpen = true },
                            onOpenPages = { pagesOpen = true },
                            onToggleBookmark = {
                                if (bookmarks.any { it.page == page }) {
                                    d.ink = d.ink.withBookmarkRemoved(page)
                                    bookmarks = d.ink.bookmarks
                                    persistWorking()
                                } else {
                                    bookmarkPrompt = page
                                }
                            }
                        )
                    }
                    ToolBar(
                        state = tools,
                        selectionCount = selectionCount,
                        actions = ToolBarActions(
                            onChanged = {
                                tools.applyTo(drawingView.value)
                                // choosing any other tool means the symbol is no longer wanted
                                if (tools.active.tool != Tool.TEXT && armedSymbol != null) {
                                    armedSymbol = null
                                    drawingView.value?.disarmPlacement()
                                } else if (armedSymbol != null) {
                                    drawingView.value?.armText(armedSymbol!!)
                                }
                            },
                            onUndo = { drawingView.value?.undo(); dirty = true; undoTick++ },
                            onRedo = { drawingView.value?.redo(); dirty = true; undoTick++ },
                            canUndo = canUndo,
                            canRedo = canRedo,
                            onRestyleSelection = { color, width ->
                                drawingView.value?.restyleSelection(newColor = color, newWidth = width)
                                dirty = true; undoTick++
                            },
                            onDeleteSelection = {
                                drawingView.value?.deleteSelection(); dirty = true; undoTick++
                            },
                            onDuplicateSelection = {
                                drawingView.value?.duplicateSelection(); dirty = true; undoTick++
                            },
                            onCopySelection = {
                                val n = drawingView.value?.copySelection() ?: 0
                                scope.launch {
                                    snackbar.showSnackbar("Copied $n object${if (n == 1) "" else "s"}")
                                }
                            },
                            onCutSelection = {
                                val n = drawingView.value?.cutSelection() ?: 0
                                dirty = true; undoTick++
                                scope.launch {
                                    snackbar.showSnackbar("Cut $n object${if (n == 1) "" else "s"}")
                                }
                            },
                            onClearSelection = { drawingView.value?.clearSelection() },
                            onPaste = {
                                val n = drawingView.value?.paste() ?: 0
                                if (n > 0) { dirty = true; undoTick++ }
                                else scope.launch { snackbar.showSnackbar("Nothing copied yet") }
                            },
                            canPaste = canPaste,
                            onInsertTable = { r, c ->
                                drawingView.value?.insertTable(r, c); dirty = true; undoTick++
                            },
                            onCycleMode = { held ->
                                tools.advanceMode(drawingView.value, held)
                            },
                            onSetButtonAction = { action ->
                                tools.setActionFor(tools.activeMode, action)
                                tools.applyTo(drawingView.value)
                            },
                            onInsertSymbol = { symbolPaletteOpen = true },
                            onEditPressureCurve = { pressureCurveOpen = true },
                            onInsertStamp = { stampPickerOpen = true },
                            onInsertPicture = {
                                runCatching { picturePicker.launch("image/*") }.onFailure {
                                    scope.launch {
                                        snackbar.showSnackbar("No picture picker on this device")
                                    }
                                }
                            },
                            onTakePhoto = { takePhoto() },
                            onSnapRuler = { drawingView.value?.snapRulerAngle() },
                            onRotateRuler = { d -> drawingView.value?.rotateRuler(d) },
                            onResetRuler = { drawingView.value?.placeRulerAcrossView() },
                            onPickCustomColour = { colourPickerOpen = true }
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                loadError != null -> LoadFailure(loadError!!, onClose)
                doc == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> AndroidView(
                    factory = { ctx ->
                        DrawingView(ctx).also { view ->
                            view.ids = StrokeIdGen(DeviceId.get(ctx))
                            view.onContentChanged = {
                                dirty = true
                                undoTick++
                                lastEditAt = System.currentTimeMillis()
                                pendingWrite = true
                                writeState = WriteState.UNSAVED
                            }
                            view.onInputModeChanged = { mode -> tools.onModeChanged(mode) }
                            view.onCanvasGrew = { grown ->
                                // Held in memory and autosaved; the document's page is only
                                // actually resized when the document is written.
                                doc?.let { d -> d.ink = d.ink.copy(canvas = grown) }
                                dirty = true
                            }
                            // Records only that the hardware exists. Revealing the profile is
                            // the deliberate gesture on the toolbar switch, not this.
                            view.onStylusButtonSeen = { index -> tools.noteButtonHardware(index) }
                            view.onStylusButtonPressed = { action ->
                                when (action) {
                                    StylusButtonAction.UNDO -> {
                                        view.undo(); dirty = true; undoTick++
                                    }
                                    StylusButtonAction.REDO -> {
                                        view.redo(); dirty = true; undoTick++
                                    }
                                    StylusButtonAction.NEXT_PRESET -> {
                                        tools.cyclePreset(); tools.applyTo(view)
                                    }
                                    StylusButtonAction.HIGHLIGHTER -> {
                                        tools.edit { it.toggleHighlighter() }
                                        tools.applyTo(view)
                                    }
                                    else -> Unit          // held actions are handled in the view
                                }
                            }
                            view.onDetailNeeded = { detailPage, rect, widthPx ->
                                scope.launch {
                                    val d = doc ?: return@launch
                                    val bmp = withContext(Dispatchers.IO) {
                                        runCatching {
                                            d.source.renderRegion(detailPage, rect, widthPx)
                                        }.getOrNull()
                                    }
                                    if (bmp != null) view.setDetail(detailPage, rect, bmp)
                                }
                            }
                            view.onPagesNeedRender = { missing ->
                                if (missing.isNotEmpty()) {
                                    wantedPages = (wantedPages + missing).distinct().sorted()
                                    renderNonce++
                                }
                            }
                            view.onVisiblePagesChanged = { visible ->
                                // pad by one page either side so scrolling does not reveal blanks
                                val lo = (visible.minOrNull() ?: 0) - 1
                                val hi = (visible.maxOrNull() ?: 0) + 1
                                wantedPages = (lo..hi).filter { it in 0 until (doc?.pageCount ?: 1) }
                            }
                            view.onCurrentPageChanged = { page = it }
                            StrokeRasteriser.imageResolver = { id -> imageStore.load(id) }
                            view.textSnapper = { p, pts ->
                                PdfText.wordsUnderPath(file, p, pts, 4f)
                            }
                            view.onRegionCaptured = { capturedPage, rect ->
                                // Capture what is on screen, store it, and drop it back on
                                // the page as a movable object.
                                val bmp = view.captureRegion(capturedPage, rect)
                                val id = bmp?.let { imageStore.put(it) }
                                if (id != null) {
                                    val target = android.graphics.RectF(rect)
                                    target.offset(rect.width() * 0.06f, rect.height() * 0.06f)
                                    view.addImage(capturedPage, target, id)
                                    dirty = true; undoTick++
                                    scope.launch {
                                        snackbar.showSnackbar("Captured. Drag to move it.")
                                    }
                                } else {
                                    scope.launch { snackbar.showSnackbar("Could not capture that area") }
                                }
                            }
                            view.onSelectionChanged = { selectionCount = it }
                            view.onPlacementChanged = { armedItem = it }
                            view.onTextRequested = { x, y, existing ->
                                textPrompt = TextPromptRequest(x, y, existing)
                            }
                            view.onCellRequested = { table, r, c ->
                                cellPrompt = CellPromptRequest(table, r, c)
                            }
                            tools.applyTo(view)
                            drawingView.value = view
                            // pages are declared by the effect above once geometry is known
                        }
                    },

                    modifier = Modifier.fillMaxSize()
                )
            }

            // A page that will not rasterise used to look identical to a genuinely blank one.
            // Say so, and record why, rather than leaving the user staring at white.
            if (renderFailed && doc != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "This page did not render",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "You can still draw on it, and anything you draw will save normally. " +
                                "Details are in Settings, Diagnostics.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Anything waiting to be put on the page says so, and says how. Stamps and symbols
            // both land here, because from the user's side they are the same action.
            armedItem?.let { item ->
                Card(
                    modifier = Modifier.align(Alignment.TopCenter).padding(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Tap to place, or drag to size  ·  ${item.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        TextButton(onClick = {
                            armedSymbol = null
                            drawingView.value?.disarmPlacement()
                        }) { Text("Done") }
                    }
                }
            }

            // The app bar is gone in focus mode, so this is the only way back out.
            if (immersive.isFullscreen) {
                FilledTonalIconButton(
                    onClick = { immersive.set(false) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(10.dp)
                ) { Icon(Icons.Default.FullscreenExit, "Leave fullscreen") }
            }
        }
    }

    // ---- dialogs -------------------------------------------------------------

    textPrompt?.let { req ->
        RichTextDialog(
            existing = req.existing,
            defaultSize = tools.active.textSize,
            defaultColor = tools.active.color,
            pageWidth = drawingView.value?.pageWidthPt ?: 612f,
            onDismiss = { textPrompt = null },
            onDelete = req.existing?.let { existing ->
                {
                    drawingView.value?.deleteStroke(existing)
                    dirty = true; textPrompt = null
                }
            },
            onConfirm = { spec ->
                val view = drawingView.value
                if (req.existing != null) {
                    view?.replaceStroke(
                        req.existing,
                        req.existing.copy(
                            text = spec.text, textSize = spec.size, color = spec.color,
                            bold = spec.bold, italic = spec.italic, font = spec.font,
                            align = spec.align, boxWidth = spec.boxWidth,
                            boxFillColor = spec.boxFillColor, boxBorder = spec.boxBorder,
                            updatedUtc = System.currentTimeMillis()
                        )
                    )
                } else {
                    view?.addText(
                        req.x, req.y, spec.text, spec.size, spec.color,
                        bold = spec.bold, italic = spec.italic, font = spec.font,
                        align = spec.align, boxWidth = spec.boxWidth,
                        boxFillColor = spec.boxFillColor, boxBorder = spec.boxBorder
                    )
                }
                // remember the size for the next text object placed
                tools.edit { it.textSize = spec.size }
                dirty = true
                textPrompt = null
            }
        )
    }

    cellPrompt?.let { req ->
        TextEntryDialog(
            initial = req.table.cells.getOrNull(req.row * req.table.cols + req.col).orEmpty(),
            title = "Cell ${req.row + 1}, ${req.col + 1}",
            singleLine = true,
            onDismiss = { cellPrompt = null },
            onConfirm = { value ->
                val cells = req.table.cells.toMutableList()
                val idx = req.row * req.table.cols + req.col
                while (cells.size <= idx) cells.add("")
                cells[idx] = value
                drawingView.value?.replaceStroke(
                    req.table,
                    req.table.copy(cells = cells, updatedUtc = System.currentTimeMillis())
                )
                dirty = true
                cellPrompt = null
            }
        )
    }

    if (navOpen) {
        doc?.let { d ->
            NavigationSheet(
                pageCount = d.pageCount,
                currentPage = page,
                outline = outline,
                bookmarks = bookmarks,
                onGoToPage = { target ->
                    navOpen = false
                    drawingView.value?.goToPage(target)
                },
                onRemoveBookmark = { p ->
                    d.ink = d.ink.withBookmarkRemoved(p)
                    bookmarks = d.ink.bookmarks
                    persistWorking()
                },
                onDismiss = { navOpen = false }
            )
        }
    }

    if (pagesOpen) {
        doc?.let { d ->
            val dim = remember(d) { d.source.pageDim(0) }
            PagesSheet(
                pageCount = d.pageCount,
                currentPage = page,
                layout = layout,
                canEditPages = PageSources.isPdf(d.file),
                defaultPageWidth = dim.width,
                defaultPageHeight = dim.height,
                pageSizeAt = { index ->
                    val d2 = d.source.pageDim(index)
                    d2.width to d2.height
                },
                thumbnailFor = { index ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                            d.source.renderPage(index, 240)
                        }.getOrNull()
                    }
                },
                importThumbnailFor = { path, index ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                            // Opened and closed per thumbnail. Holding a renderer per imported
                            // file open across the whole sheet would mean a file handle for
                            // every file the user browsed past and changed their mind about.
                            PageSources.open(java.io.File(path))?.use { it.renderPage(index, 240) }
                        }.getOrNull()
                    }
                },
                canvasPaper = d.ink.canvas?.let { c ->
                    com.inkslate.ui.PaperStyle(
                        background = runCatching {
                            com.inkslate.pdf.BlankDocumentFactory.Background.valueOf(c.background)
                        }.getOrDefault(com.inkslate.pdf.BlankDocumentFactory.Background.PLAIN),
                        paperColor = c.paperColor,
                        lineColor = c.lineColor,
                        spacing = c.spacing
                    )
                },
                onCanvasChange = { style ->
                    repo.setCanvasEnabled(
                        doc = d,
                        enabled = style != null,
                        background = (style?.background
                            ?: com.inkslate.pdf.BlankDocumentFactory.Background.PLAIN).name,
                        paperColor = style?.paperColor ?: android.graphics.Color.WHITE,
                        lineColor = style?.lineColor
                            ?: android.graphics.Color.parseColor("#8FA8C8"),
                        spacing = style?.spacing ?: 24f
                    )
                    drawingView.value?.canvas = d.ink.canvas
                    dirty = true
                },
                onLayoutChange = { option ->
                    layout = option
                    drawingView.value?.setLayout(option)
                },
                onGoToPage = { target -> drawingView.value?.goToPage(target) },
                onExport = { pages ->
                    // Through the same prompt as everything else. Picking three pages out of the
                    // page manager and having them silently appear as a file beside the original
                    // was the same missing question as exporting the whole document was.
                    pagesOpen = false
                    exportPreset = pages
                    exportOpen = true
                },
                onDismiss = { pagesOpen = false },
                onApply = { plan ->
                    pagesOpen = false
                    restructuring = true
                    scope.launch {
                        // The strokes on screen are the truth; get them into the document before
                        // anything reorders the pages under them.
                        syncPage()
                        val result = writingDocument {
                            withContext(Dispatchers.IO) {
                                repo.saveWorking(d)
                                repo.rearrangePages(d, plan)
                            }
                        }
                        restructuring = false
                        result.fold(
                            onSuccess = {
                                dirty = false
                                unbaked = false
                                fileRepo.invalidateThumb(d.file)
                                // Nothing the editor is holding survives this: page count, page
                                // sizes and every stroke id have changed. Reopen rather than
                                // patch.
                                reloadNonce++
                                snackbar.showSnackbar("Rearranged ${d.file.name}")
                            },
                            onFailure = {
                                snackbar.showSnackbar(
                                    it.message ?: "Could not rearrange the pages"
                                )
                            }
                        )
                    }
                }
            )
        }
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Saving") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "  Writing ${file.name}.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {}
        )
    }

    if (benchRunning) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Measuring") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "  Timing each part of a save against this document.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {}
        )
    }

    benchReport?.let { report ->
        AlertDialog(
            onDismissRequest = { benchReport = null },
            title = { Text("Save timings") },
            text = {
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = androidx.compose.ui.unit.TextUnit(
                            10f, androidx.compose.ui.unit.TextUnitType.Sp
                        )
                    ),
                    modifier = Modifier.verticalScroll(
                        androidx.compose.foundation.rememberScrollState()
                    ).heightIn(max = 420.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { benchReport = null }) { Text("Done") }
            }
        )
    }

    if (restructuring) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Rearranging pages") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "  Rewriting the document. This can take a moment on a large one.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {}
        )
    }

    bookmarkPrompt?.let { p ->
        BookmarkDialog(
            page = p,
            onDismiss = { bookmarkPrompt = null },
            onConfirm = { label ->
                doc?.let { d ->
                    d.ink = d.ink.withBookmarkAdded(p, label)
                    bookmarks = d.ink.bookmarks
                    persistWorking()
                }
                bookmarkPrompt = null
            }
        )
    }

    if (externalChange) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("This file changed elsewhere") },
            text = {
                Text(
                    "${file.name} was replaced on disk while you had it open - usually another " +
                        "device syncing over.\n\n" +
                        "Reloading folds both sets of handwriting together; nothing you have " +
                        "written here is lost, because it is already saved to this device. " +
                        "Carrying on regardless means the next save writes over what arrived."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    externalChange = false
                    reloadNonce++
                }) { Text("Reload and merge") }
            },
            dismissButton = {
                TextButton(onClick = {
                    externalChange = false
                    // Do not raise this exact arrival again; a later one still counts.
                    ignoredStamp = PageSources.fingerprint(file)
                }) { Text("Carry on") }
            }
        )
    }

    conflictNotice?.let { message ->
        AlertDialog(
            onDismissRequest = { conflictNotice = null },
            title = { Text("Synced changes") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { conflictNotice = null }) { Text("OK") } }
        )
    }

    if (colourPickerOpen) {
        ColorPickerDialog(
            initial = tools.active.color,
            title = "Pen colour",
            presets = ToolState.PALETTE,
            recents = tools.customColors,
            onDismiss = { colourPickerOpen = false },
            onPick = { picked ->
                tools.edit { it.color = picked }
                tools.rememberColor(picked)
                tools.activePreset = -1
                tools.applyTo(drawingView.value)
                colourPickerOpen = false
            }
        )
    }

    if (stampPickerOpen) {
        StampPickerDialog(
            inkColor = tools.active.color,
            recents = tools.recentStamps,
            optionsFor = { kind -> tools.stampOptionsFor(kind) },
            onDismiss = { stampPickerOpen = false },
            onPick = { kind, options ->
                stampPickerOpen = false
                tools.noteStampUsed(kind, options)
                drawingView.value?.insertStamp(kind, options)
                dirty = true; undoTick++
            }
        )
    }

    if (searchOpen) {
        doc?.let { d ->
            SearchSheet(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                searching = searching,
                progressPage = searchProgress,
                pageCount = d.pageCount,
                hits = searchHits,
                onSearch = {
                    searchHits = emptyList()
                    searching = true
                    searchProgress = 0
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            PdfText.search(
                                d.file, searchQuery, d.pageCount,
                                onProgress = { p -> searchProgress = p },
                                onHit = { hit -> searchHits = searchHits + hit }
                            )
                        }
                        searching = false
                        EventLog.info(
                            "search",
                            "\"" + searchQuery + "\" matched " + searchHits.size + " time(s)"
                        )
                    }
                },
                onGoToHit = { hit ->
                    searchOpen = false
                    drawingView.value?.goToPage(hit.page)
                    drawingView.value?.flashHighlight(hit.page, hit.rects)
                },
                onDismiss = { searchOpen = false }
            )
        }
    }

    if (pressureCurveOpen) {
        PressureCurveDialog(
            initialGamma = tools.active.pressureGamma,
            initialMin = tools.active.pressureMin,
            initialDynamics = tools.active.dynamics,
            onDismiss = { pressureCurveOpen = false },
            onReset = {
                tools.edit {
                    it.pressureGamma = 1f; it.pressureMin = 0.35f; it.dynamics = 1f
                }
                tools.applyTo(drawingView.value)
                pressureCurveOpen = false
            },
            onApply = { g, m, d ->
                tools.edit { it.pressureGamma = g; it.pressureMin = m; it.dynamics = d }
                tools.applyTo(drawingView.value)
                pressureCurveOpen = false
            }
        )
    }

    if (symbolPaletteOpen) {
        SymbolPaletteDialog(
            onDismiss = { symbolPaletteOpen = false },
            onInsert = { text ->
                symbolPaletteOpen = false
                // Arm it rather than placing it: you tap where it goes, as many times as you
                // need, which is how symbols actually get used in a line of working.
                armedSymbol = text
                drawingView.value?.let { view ->
                    tools.edit { it.tool = Tool.TEXT }
                    tools.applyTo(view)
                    // after applyTo, which does not know about armed items
                    view.armText(text)
                }
            }
        )
    }

    if (exportOpen) {
        doc?.let { d ->
            ExportDialog(
                documentName = d.file.name,
                pageCount = d.pageCount,
                currentPage = page,
                isPdf = PageSources.isPdf(d.file),
                presetPages = exportPreset,
                onDismiss = { exportOpen = false; exportPreset = null },
                onExport = { request ->
                    exportOpen = false
                    exportPreset = null
                    runExport(request)
                }
            )
        }
    }

    // Building a long document can take a moment, and an export that gives no sign of having
    // started is one the user taps again.
    if (exporting) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Exporting") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Text(
                        "Writing the file...",
                        modifier = Modifier.padding(start = 14.dp)
                    )
                }
            }
        )
    }

    inkRecovery?.let { (backup, count) ->
        AlertDialog(
            onDismissRequest = { inkRecovery = null },
            icon = { Icon(Icons.Default.Restore, null) },
            title = { Text("Missing handwriting?") },
            text = {
                Text(
                    "This document has ${doc?.ink?.totalStrokes ?: 0} marks, but a saved " +
                        "version from " + android.text.format.DateFormat
                        .format("d MMM, HH:mm", backup.lastModified()) +
                        " has $count. Restoring only adds back what is missing."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val f = backup
                    inkRecovery = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { repo.restoreInk(file, f) }
                        r.fold(
                            onSuccess = { n ->
                                val fresh = withContext(Dispatchers.IO) { repo.open(file) }
                                if (fresh != null) {
                                    drawingView.value?.setStrokes(fresh.allStrokes())
                                    doc?.ink = fresh.ink
                                    fresh.close()
                                }
                                dirty = false
                                snackbar.showSnackbar("Recovered $n mark(s)")
                            },
                            onFailure = {
                                snackbar.showSnackbar(it.message ?: "Recovery failed")
                            }
                        )
                    }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { inkRecovery = null }) { Text("Not now") }
            }
        )
    }

    if (versionsOpen) {
        VersionHistoryDialog(
            backups = remember(versionsOpen) { repo.backupsFor(file) },
            inkVersions = remember(versionsOpen) {
                repo.inkBackupsFor(file).map {
                    InkVersion(it, repo.peekInk(it)?.totalStrokes ?: 0)
                }
            },
            onDismiss = { versionsOpen = false },
            onRestoreInk = { backup ->
                versionsOpen = false
                scope.launch {
                    val r = withContext(Dispatchers.IO) { repo.restoreInk(file, backup) }
                    r.fold(
                        onSuccess = { n ->
                            // reload rather than merge into the live view: the view's snapshot
                            // is about to be written back, and it must not be the stale one
                            doc?.let { d ->
                                val fresh = withContext(Dispatchers.IO) { repo.open(file) }
                                if (fresh != null) {
                                    drawingView.value?.setStrokes(fresh.allStrokes())
                                    d.ink = fresh.ink
                                    fresh.close()
                                }
                            }
                            dirty = false
                            snackbar.showSnackbar(
                                if (n == 0) "That version had nothing extra to restore"
                                else "Recovered $n mark(s)"
                            )
                        },
                        onFailure = { snackbar.showSnackbar(it.message ?: "Recovery failed") }
                    )
                }
            },
            onRestore = { backup ->
                versionsOpen = false
                scope.launch {
                    val r = withContext(Dispatchers.IO) { repo.restoreBackup(backup, file) }
                    r.fold(
                        onSuccess = {
                            snackbar.showSnackbar("Restored ${backup.name}. Reopen to see it.")
                        },
                        onFailure = { snackbar.showSnackbar(it.message ?: "Restore failed") }
                    )
                }
            }
        )
    }




    if (showFileRules) {
        doc?.let { d ->
            FileRulesDialog(
                fileName = d.file.name,
                global = prefs.global(),
                current = prefs.overrideFor(d.file.absolutePath) ?: FileOverride(),
                onDismiss = { showFileRules = false },
                onApply = {
                    prefs.setOverride(d.file.absolutePath, it)
                    showFileRules = false
                }
            )
        }
    }
}


data class TextPromptRequest(val x: Float, val y: Float, val existing: Stroke?)
data class CellPromptRequest(val table: Stroke, val row: Int, val col: Int)

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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev, enabled = page > 0) {
            Icon(Icons.Default.ChevronLeft, "Previous page")
        }
        IconButton(onClick = onOpenPages) {
            Icon(Icons.Default.GridView, "Pages")
        }
        // The page indicator opens contents, bookmarks and page jump: it is already where you
        // look when you want to know where you are in a long document.
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

@Composable
private fun LoadFailure(message: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text("Cannot open this file", style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            TextButton(onClick = onClose, modifier = Modifier.padding(top = 12.dp)) {
                Text("Go back")
            }
        }
    }
}

/** Whether the file on disk has caught up with what is on screen. */
private enum class WriteState { SAVED, SAVING, UNSAVED }

/** How long the pen has to be still before the document is written out behind the scenes. */
private const val IDLE_BEFORE_WRITE_MS = 1200L
