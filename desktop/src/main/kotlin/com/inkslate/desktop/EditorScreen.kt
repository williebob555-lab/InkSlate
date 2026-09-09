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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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

    var source by remember(file) { mutableStateOf<DesktopSource?>(null) }
    var ink by remember(file) { mutableStateOf(InkDocument.create("", "pdf", 0, 0L, "")) }
    val strokes = remember(file) { mutableStateListOf<Stroke>() }
    val undo = remember(file) { mutableStateListOf<Op>() }
    val redo = remember(file) { mutableStateListOf<Op>() }

    var selection by remember(file) { mutableStateOf<Set<String>>(emptySet()) }
    var zoom by remember { mutableStateOf(1.15f) }
    var dirty by remember(file) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var status by remember(file) { mutableStateOf("Opening ${file.name}...") }
    var busy by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf<Stroke?>(null) }
    var newTextAt by remember { mutableStateOf<Stroke?>(null) }
    var pickingColour by remember { mutableStateOf(false) }
    var shapesOpen by remember { mutableStateOf(false) }

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
            withContext(Dispatchers.IO) { DocumentIO.saveWorking(file, doc) }
            if (ok) {
                dirty = false
                status = "Saved into ${file.name}  ·  ${doc.totalStrokes} mark(s)"
            } else {
                // The working copy still has it, which is the whole reason that copy exists.
                status = "Could not write into ${file.name}. Your work is kept locally."
                snackbar.showSnackbar(status)
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
    shortcuts.zoomIn = { zoom = (zoom * 1.15f).coerceAtMost(6f) }
    shortcuts.zoomOut = { zoom = (zoom / 1.15f).coerceAtLeast(0.2f) }
    shortcuts.resetZoom = { zoom = 1.15f }
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
                                text = { Text("Zoom in") },
                                onClick = { menuOpen = false; shortcuts.zoomIn?.invoke() }
                            )
                            DropdownMenuItem(
                                text = { Text("Zoom out") },
                                onClick = { menuOpen = false; shortcuts.zoomOut?.invoke() }
                            )
                            DropdownMenuItem(
                                text = { Text("Reset zoom") },
                                onClick = { menuOpen = false; shortcuts.resetZoom?.invoke() }
                            )
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
                        onInsertStamp = { shapesOpen = true },
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentPadding = PaddingValues(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items((0 until src.pageCount).toList()) { index ->
                        PageCanvas(
                            source = src,
                            index = index,
                            strokes = strokes,
                            selection = selection,
                            onSelection = { selection = it },
                            zoom = zoom,
                            tools = tools,
                            textMeasurer = textMeasurer,
                            newId = ::nextId,
                            onCommitted = ::pushOp,
                            onEditText = { editingText = it },
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

    if (shapesOpen) {
        ShapePicker(
            current = tools.active.tool,
            onDismiss = { shapesOpen = false },
            onPick = { tool ->
                tools.edit { it.tool = tool }
                shapesOpen = false
            }
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

/**
 * Line, arrow, box and oval.
 *
 * They live behind one toolbar button rather than four, exactly as they do on the tablet: four
 * permanent slots in front of the tools used constantly was the wrong trade.
 */
@Composable
private fun ShapePicker(current: Tool, onDismiss: () -> Unit, onPick: (Tool) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shapes") },
        text = {
            Column {
                Text(
                    "Drag one out on the page. Fill, dashes and colour come from the toolbar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                OptionWrapRow {
                    listOf(
                        Tool.LINE to "Line",
                        Tool.ARROW to "Arrow",
                        Tool.RECT to "Rectangle",
                        Tool.ELLIPSE to "Ellipse"
                    ).forEach { (tool, label) ->
                        OptionChip(label, current == tool) { onPick(tool) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
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
