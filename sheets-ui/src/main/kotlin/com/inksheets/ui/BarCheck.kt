package com.inksheets.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inksheets.core.omr.Engraver
import com.inksheets.core.omr.Rest
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Event
import com.inksheets.core.omr.BarEdit
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues

/**
 * The bars in doubt, one at a time. At the top, the bar as it is printed (cut from the page, a
 * little of the bars either side dimmed, as high and low as its notes and slurs go), and
 * right under it three readings of it drawn the same way - black on white - to compare with it at
 * a glance and pick with a tap. "None of these" looks further; "Skip" leaves the bar as it is.
 * The panel sits in the half of the page away from the bar.
 */
@Composable
fun BoxScope.BarCheck(state: SheetsState) {
    if (!ScoreTools.checking) return
    val m = ScoreTools.barUp(state) ?: return
    val score = ScoreTools.scoreHere(state)
    // Where the bar is on its page: the panel goes to the other half.
    val pageHeight = score?.pageWidths?.getOrNull(m.page)?.let { it * 1.3f } ?: 1f
    val barLow = m.box.top > pageHeight * 0.5f
    val paper = Color.White
    val printInk = Color(0xFF111111)
    val total = ScoreTools.checkBars.size
    // A window over the music: between the strips where it fits, else they step aside (see Overlays).
    Opened("Fix", 380.dp)
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        modifier = Modifier.align(if (barLow) Alignment.TopCenter else Alignment.BottomCenter)
            .padding(start = Overlays.left + 8.dp, end = Overlays.right + 8.dp, top = 12.dp, bottom = 12.dp).widthIn(max = 760.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Which bar, and how far through.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bar ${m.number} · page ${m.page + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${ScoreTools.checkAt + 1} of $total to check", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { (ScoreTools.checkAt + 1f) / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().height(3.dp))
            // The bar as printed.
            Text("On the page", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(10.dp)).background(paper)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            ) {
                val pic = ScoreTools.barPicture
                if (pic != null) Image(pic, "Bar ${m.number} as printed", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(140.dp).padding(4.dp))
                else Text("Getting the bar from the page...", style = MaterialTheme.typography.bodySmall, color = Color(0xFF777777))
            }
            Text(when {
                    ScoreTools.editing != null -> "Tap a note to choose it, then change it below."
                    ScoreTools.looking && ScoreTools.askedAgain -> "Looking at it further..."
                    ScoreTools.askedAgain -> "Looked further: is it one of these?"
                    ScoreTools.looking -> "Which matches? Tap it. (Looking at it again more closely...)"
                    else -> "Which matches? Tap it - or Edit the nearest."
                }, style = MaterialTheme.typography.bodyMedium)
            val editing = ScoreTools.editing
            if (editing != null) { BarEditor(state, m, editing, paper, printInk); return@Column }
            // The readings, drawn as the print is: black on white.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((i, c) in ScoreTools.offered.withIndex()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .clickable { ScoreTools.pick(state, c) }.padding(6.dp)
                    ) {
                        val drawing = remember(c) { Engraver.line(listOf(m.copy(events = c.events)), lineStart = false) }
                        Canvas(Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(8.dp)).background(paper)) {
                            // The whole of it in the picture - notes high over the staff or low under it with their
                            // ledger lines, stems and beams - not just the staff: as large as that allows.
                            val (top, bottom) = drawing.extent
                            val tall = bottom - top + 0.6f
                            val space = minOf(size.height / maxOf(tall, 8f), size.width / (drawing.width + 1f))
                            val y0 = (size.height - (bottom - top) * space) / 2f - top * space
                            drawMarks(drawing, space, Offset(space * 0.3f, y0), printInk)
                        }
                        Text(
                            if (c.changes.isEmpty()) "As read" else c.changes.joinToString("; "),
                            style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        // Nearly it: put right by hand from here.
                        TextButton(onClick = { ScoreTools.startEdit(c) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(30.dp)) {
                            Text("Edit", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            // Four in a row where there is room for their words; two by two where there is not.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val none: @Composable (Modifier) -> Unit = { mod -> OutlinedButton(onClick = { ScoreTools.noneOfThese(state) }, modifier = mod) { Text("None of these", maxLines = 1) } }
                // The bright part of the picture is not one whole bar: a part of one, or two.
                val notOne: @Composable (Modifier) -> Unit = { mod -> OutlinedButton(onClick = { ScoreTools.notOneBar(state) }, modifier = mod) { Text("Not one bar", maxLines = 1) } }
                val skip: @Composable (Modifier) -> Unit = { mod -> OutlinedButton(onClick = { ScoreTools.next(state) }, modifier = mod) { Text("Skip", maxLines = 1) } }
                val done: @Composable (Modifier) -> Unit = { mod -> Button(onClick = { ScoreTools.endCheck() }, modifier = mod) { Text("Done", maxLines = 1) } }
                if (maxWidth >= 520.dp) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    none(Modifier.weight(1f).height(48.dp)); notOne(Modifier.weight(1f).height(48.dp)); skip(Modifier.weight(1f).height(48.dp)); done(Modifier.weight(1f).height(48.dp))
                } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { none(Modifier.weight(1f).height(44.dp)); notOne(Modifier.weight(1f).height(44.dp)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { skip(Modifier.weight(1f).height(44.dp)); done(Modifier.weight(1f).height(44.dp)) }
                }
            }
        }
    }
}

/**
 * Bar [m] put right by hand: [events] drawn large - a tap chooses the note or rest to change - and
 * what can be done to it: up or down a step, another length, a dot, a rest for a note, three as a
 * triplet, out, a note more after it. What the bar comes to is said as it changes; "Use this"
 * keeps it (and it is taught to the reader), "Cancel" goes back to the readings offered.
 */
@Composable
private fun BarEditor(state: SheetsState, m: Measure, events: List<Event>, paper: Color, printInk: Color) {
    val at = ScoreTools.editAt
    val drawing = remember(events) { Engraver.line(listOf(m.copy(events = events)), lineStart = false) }
    val chosen = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    // Where the drawing was last laid out: its space and the x and y of its top line - what a tap is measured by.
    val layout = androidx.compose.runtime.remember { FloatArray(3) }
    Canvas(
        Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(10.dp)).background(paper)
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
            .pointerInput(drawing) {
                detectTapGestures { tap ->
                    val space = layout[0]; val ox = layout[1]
                    if (space <= 0f) return@detectTapGestures
                    val xs = drawing.events.firstOrNull().orEmpty()
                    val sx = (tap.x - ox) / space
                    xs.indices.minByOrNull { kotlin.math.abs(xs[it] + 0.6f - sx) }?.let { ScoreTools.editAt = it }
                }
            }
    ) {
        // The whole of it, notes far over or under the staff too (see Engraver.Drawing.extent).
        val (top, bottom) = drawing.extent
        val space = minOf(size.height / maxOf(bottom - top + 0.6f, 8f), size.width / (drawing.width + 1f))
        val origin = Offset(space * 0.3f, (size.height - (bottom - top) * space) / 2f - top * space)
        layout[0] = space; layout[1] = origin.x; layout[2] = origin.y
        drawing.events.firstOrNull()?.getOrNull(at)?.let { ex ->
            drawRect(chosen, Offset(origin.x + (ex - 0.4f) * space, 0f), Size(space * 2f, size.height))
        }
        drawMarks(drawing, space, origin, printInk)
    }
    // What the bar comes to: green when it is its time.
    val q = BarEdit.quarters(events)
    val adds = kotlin.math.abs(q - m.time.quarters) < 1e-6
    fun beats(v: Double) = if (v == Math.floor(v)) "${v.toInt()}" else if (v * 2 == Math.floor(v * 2)) "${Math.floor(v).toInt().takeIf { it > 0 } ?: ""}½" else "%.2f".format(v)
    Text("Comes to ${beats(q)} of ${beats(m.time.quarters)} beats" + if (adds) " - adds up" else "",
        style = MaterialTheme.typography.bodyMedium, color = if (adds) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
    val e = events.getOrNull(at)
    Text(when (e) {
        is Note -> "Chosen: note ${at + 1} of ${events.size}"
        is Rest -> "Chosen: rest ${at + 1} of ${events.size}"
        null -> "Nothing in the bar - add a note"
    }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    @Composable
    fun Btn(label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) =
        FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp), modifier = modifier.height(44.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    val isNote = e is Note
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Btn("Up", Modifier.weight(1f), isNote) { ScoreTools.edit { BarEdit.step(m, it, at, -1) } }
        Btn("Down", Modifier.weight(1f), isNote) { ScoreTools.edit { BarEdit.step(m, it, at, 1) } }
        Btn(if (e?.duration?.dots ?: 0 > 0) "No dot" else "Dot", Modifier.weight(1f), e != null) { ScoreTools.edit { BarEdit.dot(it, at) } }
        Btn(if (isNote) "Make rest" else "Make note", Modifier.weight(1.3f), e != null) { ScoreTools.edit { BarEdit.restOrNote(m, it, at) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        for ((label, base) in listOf("Whole" to 1, "Half" to 2, "Quarter" to 4, "8th" to 8, "16th" to 16))
            Btn(label, Modifier.weight(1f), e != null && e.duration.base != base) { ScoreTools.edit { BarEdit.length(it, at, base) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Btn("Triplet", Modifier.weight(1f), at + 2 < events.size) { ScoreTools.edit { BarEdit.triplet(it, at) } }
        Btn("Add note after", Modifier.weight(1.4f)) { ScoreTools.edit { BarEdit.addAfter(m, it, if (events.isEmpty()) -1 else at) }; if (events.isNotEmpty()) ScoreTools.editAt = at + 1 }
        Btn("Remove", Modifier.weight(1f), e != null) { ScoreTools.edit { BarEdit.delete(it, at) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { ScoreTools.cancelEdit() }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel") }
        Button(onClick = { ScoreTools.finishEdit(state) }, enabled = events.isNotEmpty(), modifier = Modifier.weight(1f).height(48.dp)) { Text("Use this") }
    }
}

@Composable
private fun <T> remember(key: Any?, calc: () -> T): T = androidx.compose.runtime.remember(key, calc)
