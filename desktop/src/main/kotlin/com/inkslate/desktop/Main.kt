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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.inkslate.core.BrushType
import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Windows build.
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
fun main() = application {
    val state = rememberWindowState(width = 1200.dp, height = 900.dp)
    // Set by the app so the window's key handler can reach it. A desktop user reaches for Ctrl+S
    // long before they reach for a toolbar, and Ctrl+Z is not optional anywhere.
    val shortcuts = remember { Shortcuts() }

    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "InkSlate",
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) false
            else when (event.key) {
                Key.S -> { shortcuts.save?.invoke(); true }
                Key.O -> { shortcuts.open?.invoke(); true }
                Key.Z -> { shortcuts.undo?.invoke(); true }
                Key.Y -> { shortcuts.redo?.invoke(); true }
                else -> false
            }
        }
    ) {
        InkSlateTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                App(shortcuts)
            }
        }
    }
}

/** Actions the window's key handler can invoke, filled in once the app has composed. */
class Shortcuts {
    var save: (() -> Unit)? = null
    var open: (() -> Unit)? = null
    var undo: (() -> Unit)? = null
    var redo: (() -> Unit)? = null
}

private enum class DesktopTool(val label: String) { PEN("Pen"), HIGHLIGHTER("Highlighter"), ERASER("Eraser") }

/** One reversible edit, mirroring the Android undo model. */
private data class Op(val added: List<Stroke>, val removed: List<Stroke>)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun App(shortcuts: Shortcuts = Shortcuts()) {
    val scope = rememberCoroutineScope()

    var file by remember { mutableStateOf<File?>(null) }
    var source by remember { mutableStateOf<DesktopSource?>(null) }
    var ink by remember { mutableStateOf(InkDocument.create("", "pdf", 0, 0L, "")) }
    val strokes = remember { mutableStateListOf<Stroke>() }
    val undo = remember { mutableStateListOf<Op>() }
    val redo = remember { mutableStateListOf<Op>() }

    var tool by remember { mutableStateOf(DesktopTool.PEN) }
    var colour by remember { mutableStateOf(0xFF1B1B1BL.toInt()) }
    var width by remember { mutableStateOf(2.4f) }
    var zoom by remember { mutableStateOf(1.15f) }
    var dirty by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Open a PDF or image to begin") }
    var busy by remember { mutableStateOf(false) }

    val ids = remember { mutableStateOf(0) }
    val deviceTag = remember { DocumentIO.deviceTag() }
    fun nextId(): String = "$deviceTag-d${++ids.value}"

    fun pushOp(op: Op) {
        undo.add(op)
        redo.clear()
        dirty = true
    }

    // ---- opening -------------------------------------------------------------

    fun openFile(chosen: File) {
        busy = true
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                source?.close()
                val src = DesktopSources.open(chosen) ?: return@withContext null
                val doc = DocumentIO.load(chosen, src.pageCount)
                // Anything autosaved here since the last explicit save is folded back in.
                val working = DocumentIO.loadWorking(chosen)
                val merged = if (working == null) doc.ink else doc.ink.mergeWith(working)
                Triple(src, doc, merged)
            }
            if (loaded == null) {
                status = "Could not open ${chosen.name}"
                busy = false
                return@launch
            }
            val (src, doc, merged) = loaded
            source = src
            file = chosen
            ink = merged
            strokes.clear()
            strokes.addAll((0 until src.pageCount).flatMap { p ->
                merged.strokesOn(p).map { if (it.pageIndex == p) it else it.copy(pageIndex = p) }
            })
            // Never mint an id that a synced-in edit is already using.
            ids.value = strokes.mapNotNull {
                it.id.substringAfter("-d", "").toIntOrNull()
            }.maxOrNull() ?: 0
            undo.clear(); redo.clear()
            dirty = false
            status = buildString {
                append("${chosen.name}  ·  ${src.pageCount} page(s)  ·  ")
                append("${merged.totalStrokes} mark(s)")
                if (doc.mergedConflicts > 0) {
                    append("  ·  merged ${doc.mergedConflicts} sync conflict(s)")
                }
                if (doc.sourceChanged) append("  ·  the file changed since these were saved")
            }
            busy = false
        }
    }

    fun chooseFile() {
        // The AWT dialog is the native one on Windows, which is what people expect here.
        val dialog = FileDialog(null as Frame?, "Open a document", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> DesktopSources.isSupported(File(name)) }
        dialog.isVisible = true
        val dir = dialog.directory
        val name = dialog.file
        if (dir != null && name != null) openFile(File(dir, name))
    }

    // ---- saving --------------------------------------------------------------

    fun currentInk(): InkDocument {
        var next = ink
        val byPage = strokes.groupBy { it.pageIndex }
        for (page in 0 until (source?.pageCount ?: 0)) {
            val existing = next.strokesOn(page)
            val updated = byPage[page].orEmpty()
            val same = existing.size == updated.size &&
                existing.indices.all { existing[it] === updated[it] }
            if (!same) next = next.withPage(page, updated, deviceTag)
        }
        return next
    }

    fun save() {
        val f = file ?: return
        busy = true
        scope.launch {
            val doc = currentInk()
            ink = doc
            val ok = withContext(Dispatchers.IO) { DocumentIO.save(f, doc) }
            if (ok) {
                dirty = false
                withContext(Dispatchers.IO) { DocumentIO.saveWorking(f, doc) }
                status = "Saved into ${f.name}  ·  ${doc.totalStrokes} mark(s)"
            } else {
                // The working copy still has it, which is the whole reason that copy exists.
                status = "Could not write into ${f.name}. Your work is kept locally."
            }
            busy = false
        }
    }

    fun exportFlattened() {
        val f = file ?: return
        busy = true
        scope.launch {
            val doc = currentInk()
            val result = withContext(Dispatchers.IO) { DocumentIO.exportFlattened(f, doc) }
            status = result.fold(
                onSuccess = { "Exported ${it.name}" },
                onFailure = { "Export failed: ${it.message}" }
            )
            busy = false
        }
    }

    // Autosave to the local working copy. The document itself is only written on an explicit
    // save, so this is what stands between a crash and a lost afternoon.
    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        while (true) {
            delay(20_000)
            if (!dirty) continue
            val doc = currentInk()
            withContext(Dispatchers.IO) { DocumentIO.saveWorking(f, doc) }
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

    // ---- layout --------------------------------------------------------------

    // Hand the window's key handler something to call. Re-assigned on each composition so the
    // captured lambdas always see current state rather than the state at first composition.
    shortcuts.save = ::save
    shortcuts.open = ::chooseFile
    shortcuts.undo = ::undoOnce
    shortcuts.redo = ::redoOnce

    Column(Modifier.fillMaxSize()) {
        Toolbar(
            hasFile = file != null,
            tool = tool, onTool = { tool = it },
            colour = colour, onColour = { colour = it },
            width = width, onWidth = { width = it },
            zoom = zoom, onZoom = { zoom = it.coerceIn(0.3f, 5f) },
            dirty = dirty,
            canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(),
            onOpen = ::chooseFile,
            onSave = ::save,
            onExport = ::exportFlattened,
            onUndo = ::undoOnce,
            onRedo = ::redoOnce
        )

        val src = source
        if (src == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("InkSlate", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Open a document from a folder your tablet syncs to, and its " +
                            "handwriting comes with it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = ::chooseFile) { Text("Open a document") }
                }
            }
        } else {
            PageList(
                source = src,
                strokes = strokes,
                zoom = zoom,
                tool = tool,
                colour = colour,
                width = width,
                newId = ::nextId,
                onCommitted = ::pushOp
            )
        }

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
}

// ---- pages -------------------------------------------------------------------

@Composable
private fun PageList(
    source: DesktopSource,
    strokes: MutableList<Stroke>,
    zoom: Float,
    tool: DesktopTool,
    colour: Int,
    width: Float,
    newId: () -> String,
    onCommitted: (Op) -> Unit
) {
    val listState = rememberLazyListState()
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
private fun Toolbar(
    hasFile: Boolean,
    tool: DesktopTool, onTool: (DesktopTool) -> Unit,
    colour: Int, onColour: (Int) -> Unit,
    width: Float, onWidth: (Float) -> Unit,
    zoom: Float, onZoom: (Float) -> Unit,
    dirty: Boolean,
    canUndo: Boolean, canRedo: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onOpen) { Text("Open") }
            TextButton(onClick = onSave, enabled = hasFile) {
                Text(if (dirty) "Save *" else "Save")
            }
            TextButton(onClick = onExport, enabled = hasFile) { Text("Export flattened") }

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
