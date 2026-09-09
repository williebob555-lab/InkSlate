package com.inkslate.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.inkslate.core.BrushType
import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

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
 *
 * The chrome is the Android editor's: the document's name and its saved state in the bar at the
 * top, the drawing tools along the bottom. The tools themselves are still the short list this
 * build started with - selection, shapes, text and stamps are the Android editor's and have not
 * been brought across yet.
 */
private enum class DesktopTool(val label: String) {
    PEN("Pen"), HIGHLIGHTER("Highlighter"), ERASER("Eraser")
}

/** One reversible edit, mirroring the Android undo model. */
private data class Op(val added: List<Stroke>, val removed: List<Stroke>)

/** What the title bar says about the document's relationship to the disk. */
private enum class WriteState(val label: String) {
    SAVED("saved"), UNSAVED("unsaved"), SAVING("saving...")
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    file: File,
    shortcuts: Shortcuts,
    navigation: NavigationHooks,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var source by remember(file) { mutableStateOf<DesktopSource?>(null) }
    var ink by remember(file) { mutableStateOf(InkDocument.create("", "pdf", 0, 0L, "")) }
    val strokes = remember(file) { mutableStateListOf<Stroke>() }
    val undo = remember(file) { mutableStateListOf<Op>() }
    val redo = remember(file) { mutableStateListOf<Op>() }

    var tool by remember { mutableStateOf(DesktopTool.PEN) }
    var colour by remember { mutableStateOf(0xFF1B1B1BL.toInt()) }
    var width by remember { mutableStateOf(2.4f) }
    var zoom by remember { mutableStateOf(1.15f) }
    var dirty by remember(file) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var status by remember(file) { mutableStateOf("Opening ${file.name}...") }
    var busy by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val page by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    val ids = remember(file) { mutableStateOf(0) }
    val deviceTag = remember { DocumentIO.deviceTag() }
    fun nextId(): String = "$deviceTag-d${++ids.value}"

    fun pushOp(op: Op) {
        undo.add(op)
        redo.clear()
        dirty = true
    }

    // ---- opening -------------------------------------------------------------

    LaunchedEffect(file.absolutePath) {
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

    fun save(then: (() -> Unit)? = null) {
        busy = true
        saving = true
        scope.launch {
            val doc = currentInk()
            ink = doc
            val ok = withContext(Dispatchers.IO) { DocumentIO.save(file, doc) }
            if (ok) {
                dirty = false
                withContext(Dispatchers.IO) { DocumentIO.saveWorking(file, doc) }
                status = "Saved into ${file.name}  ·  ${doc.totalStrokes} mark(s)"
            } else {
                // The working copy still has it, which is the whole reason that copy exists.
                withContext(Dispatchers.IO) { DocumentIO.saveWorking(file, doc) }
                status = "Could not write into ${file.name}. Your work is kept locally."
                snackbar.showSnackbar("Could not write into ${file.name}. Your work is kept locally.")
            }
            saving = false
            busy = false
            if (ok) then?.invoke()
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
            delay(20_000)
            if (!dirty) continue
            val doc = currentInk()
            withContext(Dispatchers.IO) { DocumentIO.saveWorking(file, doc) }
        }
    }

    fun undoOnce() {
        val op = undo.removeLastOrNull() ?: return
        strokes.removeAll { s -> op.added.any { it.id == s.id } }
        strokes.addAll(op.removed)
        redo.add(op)
        dirty = true
    }

    fun redoOnce() {
        val op = redo.removeLastOrNull() ?: return
        strokes.removeAll { s -> op.removed.any { it.id == s.id } }
        strokes.addAll(op.added)
        undo.add(op)
        dirty = true
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
    navigation.back = ::leave

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
                                text = { Text("Export flattened copy...") },
                                onClick = { menuOpen = false; exportFlattened() }
                            )
                            DropdownMenuItem(
                                text = { Text("Fit width") },
                                onClick = { menuOpen = false; zoom = 1.15f }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                ToolBar(
                    tool = tool, onTool = { tool = it },
                    colour = colour, onColour = { colour = it },
                    width = width, onWidth = { width = it },
                    zoom = zoom, onZoom = { zoom = it.coerceIn(0.3f, 5f) },
                    dirty = dirty,
                    canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(),
                    onSave = { save() },
                    onUndo = ::undoOnce,
                    onRedo = ::redoOnce
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
                PageList(
                    source = src,
                    listState = listState,
                    strokes = strokes,
                    zoom = zoom,
                    tool = tool,
                    colour = colour,
                    width = width,
                    newId = ::nextId,
                    onCommitted = ::pushOp
                )
            }
        }
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

// ---- pages -------------------------------------------------------------------

@Composable
private fun PageList(
    source: DesktopSource,
    listState: androidx.compose.foundation.lazy.LazyListState,
    strokes: MutableList<Stroke>,
    zoom: Float,
    tool: DesktopTool,
    colour: Int,
    width: Float,
    newId: () -> String,
    onCommitted: (Op) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items((0 until source.pageCount).toList()) { index ->
            PageCanvas(
                source = source,
                index = index,
                strokes = strokes,
                zoom = zoom,
                tool = tool,
                colour = colour,
                width = width,
                newId = newId,
                onCommitted = onCommitted
            )
        }
    }
}

@Composable
private fun PageCanvas(
    source: DesktopSource,
    index: Int,
    strokes: MutableList<Stroke>,
    zoom: Float,
    tool: DesktopTool,
    colour: Int,
    width: Float,
    newId: () -> String,
    onCommitted: (Op) -> Unit
) {
    val dim = remember(index) { source.pageDim(index) }
    val pxWidth = (dim.width * zoom).roundToInt().coerceAtLeast(80)
    val pxHeight = (dim.height * zoom).roundToInt().coerceAtLeast(80)

    // Rendered lazily and re-rendered when the zoom moves enough to matter. Rounding to a bucket
    // stops a slider drag from queueing a full-page render for every intermediate value.
    val bucket = remember(pxWidth) { (pxWidth / 120) * 120 }
    var bitmap by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(index, bucket) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { source.render(index, max(120, bucket)) }.getOrNull()
        }
    }

    // Live stroke, kept separate so the committed list is not rewritten on every mouse move.
    var live by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    val scale = pxWidth / dim.width

    Card(
        shape = RoundedCornerShape(3.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        val density = LocalDensity.current.density
        Box(
            Modifier
                .size((pxWidth / density).dp, (pxHeight / density).dp)
                .pointerInput(index, tool, colour, width, scale) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val brush = when (tool) {
                            DesktopTool.HIGHLIGHTER -> BrushType.HIGHLIGHTER
                            else -> BrushType.BALLPOINT
                        }
                        val collected = ArrayList<InkPoint>()
                        var lastAt = System.nanoTime()
                        var lastPos = down.position

                        fun sample(pos: Offset, pressure: Float) {
                            val now = System.nanoTime()
                            // Speed stands in for pressure when the device does not report it.
                            // A dead constant width reads as a machine drawing; this at least
                            // thins on fast strokes the way a pen does.
                            val dt = ((now - lastAt) / 1_000_000f).coerceAtLeast(0.5f)
                            val speed = (pos - lastPos).getDistance() / dt
                            val fromSpeed = (1f - (speed / 3.2f)).coerceIn(0.25f, 1f)
                            val p = if (pressure in 0.02f..0.98f) pressure else fromSpeed
                            lastAt = now
                            lastPos = pos
                            collected.add(
                                InkPoint(pos.x / scale, pos.y / scale, brush.widthFor(width, p))
                            )
                        }

                        if (tool == DesktopTool.ERASER) {
                            val removed = ArrayList<Stroke>()
                            fun eraseAt(pos: Offset) {
                                val px = pos.x / scale
                                val py = pos.y / scale
                                val hit = strokes.filter {
                                    it.pageIndex == index && it.hitTest(px, py, 9f)
                                }
                                if (hit.isNotEmpty()) {
                                    removed.addAll(hit)
                                    strokes.removeAll(hit)
                                }
                            }
                            eraseAt(down.position)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (event.type == PointerEventType.Move) eraseAt(change.position)
                                if (!change.pressed) break
                            }
                            if (removed.isNotEmpty()) onCommitted(Op(emptyList(), removed))
                            return@awaitEachGesture
                        }

                        sample(down.position, down.pressure)
                        live = ArrayList(collected)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (event.type == PointerEventType.Move) {
                                sample(change.position, change.pressure)
                                live = ArrayList(collected)
                            }
                            if (!change.pressed) break
                        }

                        live = emptyList()
                        if (collected.size >= 2) {
                            val s = Stroke(
                                id = newId(),
                                kind = Stroke.Kind.FREEHAND,
                                color = colour,
                                baseWidth = width,
                                points = collected,
                                brush = brush,
                                opacity = brush.defaultAlpha,
                                pageIndex = index,
                                updatedUtc = System.currentTimeMillis()
                            )
                            strokes.add(s)
                            onCommitted(Op(listOf(s), emptyList()))
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                bitmap?.let {
                    drawImage(
                        it,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                    )
                }
                // Ink is stored in page points; one scale puts the whole layer in the right place
                // rather than converting every point on every frame.
                scale(scale, scale, pivot = Offset.Zero) {
                    strokes.forEach { if (it.pageIndex == index) drawStroke(it) }
                    if (live.size >= 2) {
                        drawStroke(
                            Stroke(
                                id = "live",
                                kind = Stroke.Kind.FREEHAND,
                                color = colour,
                                baseWidth = width,
                                points = live,
                                brush = if (tool == DesktopTool.HIGHLIGHTER) BrushType.HIGHLIGHTER
                                else BrushType.BALLPOINT,
                                opacity = if (tool == DesktopTool.HIGHLIGHTER) 0.38f else 1f,
                                pageIndex = index
                            )
                        )
                    }
                }
            }

            if (bitmap == null) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        "Page ${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF999999)
                    )
                }
            }
        }
    }
}

// ---- toolbar -----------------------------------------------------------------

@Composable
private fun ToolBar(
    tool: DesktopTool, onTool: (DesktopTool) -> Unit,
    colour: Int, onColour: (Int) -> Unit,
    width: Float, onWidth: (Float) -> Unit,
    zoom: Float, onZoom: (Float) -> Unit,
    dirty: Boolean,
    canUndo: Boolean, canRedo: Boolean,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onSave) { Text(if (dirty) "Save *" else "Save") }

            Spacer(Modifier.width(10.dp))
            DesktopTool.entries.forEach { t ->
                FilterChip(
                    selected = tool == t,
                    onClick = { onTool(t) },
                    label = { Text(t.label) }
                )
            }

            Spacer(Modifier.width(10.dp))
            listOf(
                0xFF1B1B1BL.toInt(), 0xFFD32F2FL.toInt(), 0xFF1976D2L.toInt(),
                0xFF388E3CL.toInt(), 0xFFF9A825L.toInt()
            ).forEach { c ->
                Box(
                    Modifier
                        .size(if (colour == c) 26.dp else 22.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .clickable { onColour(c) }
                )
            }

            Spacer(Modifier.width(10.dp))
            Text("Size", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = width,
                onValueChange = onWidth,
                valueRange = 0.8f..18f,
                modifier = Modifier.width(120.dp)
            )

            Text("Zoom", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = zoom,
                onValueChange = onZoom,
                valueRange = 0.3f..4f,
                modifier = Modifier.width(120.dp)
            )

            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onUndo, enabled = canUndo) { Text("Undo") }
            TextButton(onClick = onRedo, enabled = canRedo) { Text("Redo") }
        }
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
