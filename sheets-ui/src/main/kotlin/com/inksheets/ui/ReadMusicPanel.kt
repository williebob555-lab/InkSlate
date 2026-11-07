package com.inksheets.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.inkslate.core.Perform
import com.inksheets.core.omr.Engraver
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Midi
import com.inksheets.core.omr.MusicGlyphs
import com.inksheets.core.omr.Score
import java.io.File
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * The music read off a part (experimental): how much was read and how much of it adds up; each
 * bar that may be read wrong shown as it is on the page and, under it, drawn cleanly as read -
 * so a badly scanned bar can be read from the drawing, and a misread one seen at once; and the
 * whole saved as a MIDI file.
 */
@Composable
internal fun ReadMusicPanel(state: SheetsState, onClose: () -> Unit) {
    val shown = Transcriber.shown
    var all by remember { mutableStateOf(true) }
    var said by remember { mutableStateOf<String?>(null) }
    var ensembleMidiOpen by remember { mutableStateOf(false) }
    SheetDialog(title = "The music, read", onDismiss = onClose, wide = true, movable = true) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Transcriber.busy?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            if (shown == null) {
                if (Transcriber.busy == null) Text("Nothing read yet.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val (file, score) = shown
            val sure = score.measures.count { it.sure }
            Text(
                "${score.measures.sumOf { it.bars }} bars on " + (score.readPages?.let { r -> "page${if (r.size == 1) "" else "s"} ${r.joinToString(", ") { "${it + 1}" }} (of ${score.pages})" }
                    ?: "${score.pages} page${if (score.pages == 1) "" else "s"}") + "; " +
                    "$sure of ${score.measures.size} add up. Tap a bar to go to it." + (Transcriber.speedSaid()?.let { "\n$it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                OutlinedButton(onClick = { said = exportMidi(state, file, score) }) { Text("Save as MIDI") }
                state.current?.takeIf { it.parts.size > 1 }?.let { song ->
                    OutlinedButton(onClick = { ensembleMidiOpen = true }) { Text("Save ensemble MIDI") }
                }
                TextButton(onClick = { Transcriber.read(state, file, again = true, pages = score.readPages?.toSet()) { } }, enabled = Transcriber.busy == null) { Text("Read again") }
            }
            said?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !all, onClick = { all = false }, label = { Text("In doubt (${score.measures.size - sure})") })
                FilterChip(selected = all, onClick = { all = true }, label = { Text("Every bar") })
            }
            val list = if (all) score.measures else score.measures.filter { !it.sure }
            val pages = remember(file) { HashMap<Int, ImageBitmap?>() }
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                // (Keyed by where the bar is: a number can come twice - a part counting again, a misread.)
                items(list, key = { "${it.page}/${it.staff}/${it.box.left}" }) { m ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            Perform.jumpTo?.invoke(file.absolutePath, m.page)
                        }
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text(
                                "Bar ${m.number}${if (m.bars > 1) "-${m.number + m.bars - 1}" else ""} · page ${m.page + 1}" +
                                    (if (m.doubts.isNotEmpty()) " - " + m.doubts.joinToString("; ") else ""),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (m.sure) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                            )
                            ScanOf(state, file, score, m, pages, Modifier.fillMaxWidth().height(84.dp))
                            EngravedMeasure(m, Modifier.fillMaxWidth().height(84.dp))
                        }
                    }
                }
            }
        }
    }
    state.current?.takeIf { ensembleMidiOpen }?.let { song ->
        EnsembleMidiDialog(state, song, onClose = { ensembleMidiOpen = false }) { said = it }
    }
}

/** A bar as it is on the page, cut from the page drawn as it was read. */
@Composable
private fun ScanOf(state: SheetsState, file: File, score: Score, m: Measure, pages: HashMap<Int, ImageBitmap?>, modifier: Modifier) {
    var img by remember(m.page) { mutableStateOf(pages[m.page]) }
    LaunchedEffect(m.page) {
        if (img == null && !pages.containsKey(m.page)) {
            val width = score.pageWidths.getOrNull(m.page) ?: return@LaunchedEffect
            img = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { state.platform.peek(file)?.use { it.render(m.page, width) } }.getOrNull()
            }
            pages[m.page] = img
        }
    }
    val paper = Color.White
    Canvas(modifier) {
        val bitmap = img ?: return@Canvas
        val pad = (m.space * 4).toInt()
        val left = (m.box.left - (m.space * 0.5f).toInt()).coerceAtLeast(0)
        val top = (m.box.top - pad).coerceAtLeast(0)
        val right = min(bitmap.width, m.box.right + (m.space * 0.5f).toInt())
        val bottom = min(bitmap.height, m.box.bottom + pad)
        if (right <= left || bottom <= top) return@Canvas
        val w = right - left; val h = bottom - top
        val scale = min(size.width / w, size.height / h)
        drawRect(paper, size = androidx.compose.ui.geometry.Size(w * scale, h * scale))
        drawImage(bitmap, IntOffset(left, top), IntSize(w, h), dstSize = IntSize((w * scale).toInt(), (h * scale).toInt()))
    }
}

/** A bar drawn cleanly, as read: the clef, key and time it is read in, and its notes. */
@Composable
internal fun EngravedMeasure(m: Measure, modifier: Modifier) {
    val drawing = remember(m) { Engraver.line(listOf(m.copy(showsClef = true, showsKey = true))) }
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        // Room for ledger lines: four spaces above the staff and four below.
        val space = min(size.height / 12f, size.width / max(1f, drawing.width + 1f))
        drawMarks(drawing, space, Offset(space * 0.5f, space * 4f), ink)
    }
}

/** [drawing]'s marks, [space] pixels to the staff space, the top staff line at [origin]. */
internal fun DrawScope.drawMarks(drawing: Engraver.Drawing, space: Float, origin: Offset, ink: Color) {
    fun p(x: Float, y: Float) = Offset(origin.x + x * space, origin.y + y * space)
    for (mark in drawing.marks) when (mark) {
        is Engraver.Stroke -> drawLine(ink, p(mark.x1, mark.y1), p(mark.x2, mark.y2), strokeWidth = max(1f, mark.w * space))
        is Engraver.Symbol -> {
            val path = Path()
            for (poly in MusicGlyphs[mark.name].polygons(space * mark.scale, origin.x + mark.x * space, origin.y + mark.y * space)) {
                path.moveTo(poly[0], poly[1])
                for (i in 2 until poly.size step 2) path.lineTo(poly[i], poly[i + 1])
                path.close()
            }
            drawPath(path, ink)
        }
        is Engraver.Slab -> {
            val path = Path()
            val q = mark.points
            path.moveTo(origin.x + q[0] * space, origin.y + q[1] * space)
            for (i in 2 until q.size step 2) path.lineTo(origin.x + q[i] * space, origin.y + q[i + 1] * space)
            path.close()
            drawPath(path, ink)
        }
    }
}

/** The read part as a MIDI file beside the app's shared files, handed to the system to send or open. */
internal fun exportMidi(state: SheetsState, file: File, score: Score): String {
    val song = state.current
    val part = state.partShown()
    val instrumentId = part?.instrument?.let { com.inksheets.core.PartChoice.seat(it).first }
    val instrument = instrumentId?.let { com.inksheets.core.Instruments.byId[it] }
    val bpm = song?.tempo?.toDouble()?.takeIf { it > 0 } ?: SharedMetronome.bpm
    val bytes = Midi.write(score, bpm, transpose = instrument?.transpose ?: 0, program = Midi.program(instrumentId), name = song?.title ?: file.nameWithoutExtension)
    val out = File(state.platform.cacheFolder, "${file.nameWithoutExtension}.mid")
    return runCatching {
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        state.platform.share(out)
        "Saved ${out.name} (${bytes.size / 1024 + 1} KB, at ${bpm.toInt()} bpm${instrument?.let { ", as a ${it.name} sounds" } ?: ""})"
    }.getOrElse { "Couldn't save the MIDI file: ${it.message}" }
}

internal data class EnsembleMidiExport(val file: File, val message: String)

internal fun ensembleCandidates(song: com.inksheets.core.Song): List<com.inksheets.core.Part> =
    song.parts.filterNot { part ->
        part.instrument?.substringBefore(':') == "score" || part.label?.contains("score", ignoreCase = true) == true
    }

/** Assemble only independent instrument parts. Full-score entries would duplicate their parts,
 * and unread/partial parts are reported rather than silently represented as empty tracks.
 */
internal fun ensembleDocument(song: com.inksheets.core.Song, includedPartIds: Set<String>? = null,
                              scoreFor: (com.inksheets.core.Part) -> Score?): Pair<com.inksheets.core.omr.ScoreDocument, Int> {
    val candidates = ensembleCandidates(song).filter { includedPartIds == null || it.id in includedPartIds }
    val readings = candidates.mapNotNull { part ->
        scoreFor(part)?.takeIf { it.measures.isNotEmpty() }?.let { part.id to listOf(it) }
    }.toMap(LinkedHashMap())
    val document = com.inksheets.core.omr.ScoreDocument.fromSong(song, readings)
    return document to (candidates.size - document.parts.size)
}

/** Complete readings already cached on this device. Shared by ensemble export and the optional
 * stage editor; inspecting them never queues recognition.
 */
internal fun cachedEnsembleDocument(state: SheetsState, song: com.inksheets.core.Song,
                                    includedPartIds: Set<String>? = null): Pair<com.inksheets.core.omr.ScoreDocument, Int> =
    ensembleDocument(song, includedPartIds) score@{ part ->
        val file = state.partFile(song, part)?.takeIf { it.isFile } ?: return@score null
        val whole = Transcriber.known(file) ?: return@score null
        val first = (part.firstPage ?: 1) - 1
        val last = (part.lastPage ?: whole.pages) - 1
        if (first < 0 || last < first || last >= whole.pages || !(first..last).all(whole::hasRead)) null
        else Transcriber.partOf(whole, part).first
    }

/** Build a type-1 MIDI from readings already on this device. This never starts OMR: an explicit
 * export cannot make the ordinary reader or the screen wait on every other part.
 */
internal fun prepareEnsembleMidi(state: SheetsState, song: com.inksheets.core.Song,
                                 includedPartIds: Set<String>? = null): Result<EnsembleMidiExport> = runCatching {
    val (document, missing) = cachedEnsembleDocument(state, song, includedPartIds)
    require(document.parts.isNotEmpty()) { "No complete part readings are available yet" }
    val bpm = song.tempo?.toDouble()?.takeIf { it > 0 } ?: SharedMetronome.bpm
    val bytes = Midi.write(document, bpm)
    val safeTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "-").ifBlank { "Score" }
    val out = File(state.platform.cacheFolder, "$safeTitle-ensemble.mid")
    out.parentFile?.mkdirs()
    out.writeBytes(bytes)
    EnsembleMidiExport(out, "Saved ${out.name} with ${document.parts.size} part${if (document.parts.size == 1) "" else "s"}" +
        if (missing > 0) " ($missing unread part${if (missing == 1) "" else "s"} omitted)" else "")
}

/** Choose the exact instrument tracks for an export. It inspects cached readings only after Save. */
@Composable
internal fun EnsembleMidiDialog(state: SheetsState, song: com.inksheets.core.Song,
                                onClose: () -> Unit, onMessage: (String) -> Unit) {
    val candidates = remember(song) { ensembleCandidates(song) }
    var selected by remember(song.id) { mutableStateOf(candidates.map { it.id }.toSet()) }
    var working by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SheetDialog("Ensemble MIDI", onDismiss = onClose, buttons = {
        TextButton(onClick = onClose, enabled = !working) { Text("Cancel") }
        TextButton(onClick = {
            working = true
            scope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    prepareEnsembleMidi(state, song, selected)
                }
                working = false
                result.fold(
                    onSuccess = { export -> state.platform.share(export.file); onMessage(export.message); onClose() },
                    onFailure = { onMessage("Couldn't save the ensemble MIDI: ${it.message}") }
                )
            }
        }, enabled = selected.isNotEmpty() && !working) { Text(if (working) "Preparing..." else "Save") }
    }) {
        Column(Modifier.fillMaxWidth()) {
            Text("Each selected instrument becomes its own MIDI track. Parts not completely read on this device are reported and omitted.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(candidates, key = { it.id }) { part ->
                    val checked = part.id in selected
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = if (checked) selected - part.id else selected + part.id
                    }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { on ->
                            selected = if (on) selected + part.id else selected - part.id
                        })
                        Text(com.inksheets.core.Instruments.partName(part), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
