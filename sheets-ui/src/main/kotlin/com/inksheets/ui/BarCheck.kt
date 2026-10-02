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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
            // Why it is asked about, and how to read the marks on the picture.
            Doubts(m)
            // The clef, key and time it was read in - put right here where they are wrong.
            SignatureRow(state, m)
            if (ScoreTools.sigDraft != null) return@Column
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
                            // Behind each note: amber where it was read unclearly (as read), blue where
                            // this reading changes it - what to look at first on the print.
                            val xs = drawing.events.firstOrNull().orEmpty()
                            for ((k, e) in c.events.withIndex()) {
                                val x = xs.getOrNull(k) ?: continue
                                val tint = when {
                                    c.changes.isNotEmpty() && m.events.none { it == e } -> CHANGED
                                    c.changes.isEmpty() && e is Note && com.inksheets.core.omr.Learned.unclear(e) -> UNCLEAR
                                    else -> null
                                } ?: continue
                                drawRoundRect(tint, Offset(space * 0.3f + (x - 0.35f) * space, 0f), Size(space * 1.9f, size.height),
                                    androidx.compose.ui.geometry.CornerRadius(space * 0.5f))
                            }
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
                val skip: @Composable (Modifier) -> Unit = { mod -> OutlinedButton(onClick = { ScoreTools.skip(state) }, modifier = mod) { Text("Skip", maxLines = 1) } }
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
            // A note dragged up or down: a step for each half space it is moved.
            .pointerInput(Unit) {
                var moved = 0f
                detectVerticalDragGestures(
                    onDragStart = { start ->
                        moved = 0f
                        val space = layout[0]; val ox = layout[1]
                        val xs = ScoreTools.editing?.let { evs -> Engraver.line(listOf(m.copy(events = evs)), lineStart = false).events.firstOrNull() }.orEmpty()
                        if (space > 0f) xs.indices.minByOrNull { kotlin.math.abs(xs[it] + 0.6f - (start.x - ox) / space) }?.let { ScoreTools.editAt = it }
                    }
                ) { change, dy ->
                    change.consume()
                    val half = layout[0] / 2f
                    if (half <= 0f) return@detectVerticalDragGestures
                    moved += dy
                    while (moved >= half) { moved -= half; ScoreTools.edit { BarEdit.step(m, it, ScoreTools.editAt, 1) } }
                    while (moved <= -half) { moved += half; ScoreTools.edit { BarEdit.step(m, it, ScoreTools.editAt, -1) } }
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
    @Composable
    fun Btn(label: String, modifier: Modifier, enabled: Boolean = true, on: Boolean = false, onClick: () -> Unit) =
        if (on) Button(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp), modifier = modifier.height(44.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        } else FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp), modifier = modifier.height(44.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    // Which is chosen, and the way to the one before or after it without a tap on a small note.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Btn("◀", Modifier.widthIn(min = 48.dp), at > 0) { ScoreTools.editAt = at - 1 }
        Text(when (e) {
            is Note -> "Note ${at + 1} of ${events.size} - drag it up or down"
            is Rest -> "Rest ${at + 1} of ${events.size}"
            null -> "Nothing in the bar - add a note"
        }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Btn("▶", Modifier.widthIn(min = 48.dp), at + 1 < events.size) { ScoreTools.editAt = at + 1 }
    }
    val isNote = e is Note
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Btn("Up", Modifier.weight(1f), isNote) { ScoreTools.edit { BarEdit.step(m, it, at, -1) } }
        Btn("Down", Modifier.weight(1f), isNote) { ScoreTools.edit { BarEdit.step(m, it, at, 1) } }
        // What is written before it: a flat, a natural, a sharp - tapped again, none.
        val acc = BarEdit.accidentalOf(events, at)
        for ((label, alter) in listOf("♭" to -1, "♮" to 0, "♯" to 1))
            Btn(label, Modifier.weight(0.8f), isNote, on = acc == alter) { ScoreTools.edit { BarEdit.accidental(m, it, at, if (acc == alter) null else alter) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Btn(if (e?.duration?.dots ?: 0 > 0) "No dot" else "Dot", Modifier.weight(1f), e != null) { ScoreTools.edit { BarEdit.dot(it, at) } }
        Btn(if (isNote) "Make rest" else "Make note", Modifier.weight(1.3f), e != null) { ScoreTools.edit { BarEdit.restOrNote(m, it, at) } }
        Btn("Fill with rests", Modifier.weight(1.5f), !adds && q < m.time.quarters) { ScoreTools.edit { BarEdit.fillWithRests(m, it) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        for ((label, base) in listOf("Whole" to 1, "Half" to 2, "Quarter" to 4, "8th" to 8, "16th" to 16))
            Btn(label, Modifier.weight(1f), e != null, on = e?.duration?.base == base) { ScoreTools.edit { BarEdit.length(it, at, base) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Btn("Triplet", Modifier.weight(1f), at + 2 < events.size) { ScoreTools.edit { BarEdit.triplet(it, at) } }
        Btn("Add note after", Modifier.weight(1.4f)) { ScoreTools.edit { BarEdit.addAfter(m, it, if (events.isEmpty()) -1 else at) }; if (events.isNotEmpty()) ScoreTools.editAt = at + 1 }
        Btn("Remove", Modifier.weight(1f), e != null) { ScoreTools.edit { BarEdit.delete(it, at) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { ScoreTools.cancelEdit() }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel") }
        OutlinedButton(onClick = { ScoreTools.undoEdit() }, enabled = ScoreTools.canUndoEdit, modifier = Modifier.weight(1f).height(48.dp)) { Text("Undo") }
        Button(onClick = { ScoreTools.finishEdit(state) }, enabled = events.isNotEmpty(), modifier = Modifier.weight(1f).height(48.dp)) { Text("Use this") }
    }
}

@Composable
private fun <T> remember(key: Any?, calc: () -> T): T = androidx.compose.runtime.remember(key, calc)

private fun clefName(c: com.inksheets.core.omr.Clef) = when (c) {
    com.inksheets.core.omr.Clef.TREBLE -> "Treble"; com.inksheets.core.omr.Clef.BASS -> "Bass"
    com.inksheets.core.omr.Clef.ALTO -> "Alto"; com.inksheets.core.omr.Clef.TENOR -> "Tenor"
}

private fun keyName(fifths: Int) = when {
    fifths == 0 -> "no sharps or flats"
    fifths > 0 -> "$fifths sharp${if (fifths > 1) "s" else ""}"
    else -> "${-fifths} flat${if (fifths < -1) "s" else ""}"
}

/**
 * The clef, key and time bar [m] was read in, and - where one of them is wrong (a key misread, a
 * time not seen) - each chosen anew, from this bar on until the print changes it: the notes take
 * their pitches from it, and what the bar comes to is said against it.
 */
@Composable
private fun SignatureRow(state: SheetsState, m: Measure) {
    val draft = ScoreTools.sigDraft
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    if (draft == null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Read as ${clefName(m.clef)} clef · ${keyName(m.key.fifths)} · ${m.time.beats}/${m.time.beatType}" +
                if (ScoreTools.signatureAt(state, m.number) != null) " (put right here)" else "",
                style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.weight(1f), maxLines = 2)
            TextButton(onClick = { ScoreTools.sigDraft = com.inksheets.core.omr.SigFix(m.clef, m.key.fifths, m.time.beats, m.time.beatType) },
                contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Clef, key or time wrong?", maxLines = 1) }
        }
        return
    }
    @Composable
    fun Choice(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
        if (on) Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp), modifier = modifier.height(40.dp)) { Text(label, maxLines = 1) }
        else OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp), modifier = modifier.height(40.dp)) { Text(label, maxLines = 1) }
    @Composable
    fun Stepper(label: String, value: String, minus: () -> Unit, plus: () -> Unit) =
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 44.dp))
            FilledTonalButton(onClick = minus, modifier = Modifier.height(40.dp), contentPadding = PaddingValues(horizontal = 14.dp)) { Text("−") }
            Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = plus, modifier = Modifier.height(40.dp), contentPadding = PaddingValues(horizontal = 14.dp)) { Text("+") }
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()
        .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text("What is printed from bar ${m.number} on", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Clef", style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 44.dp))
            for (c in com.inksheets.core.omr.Clef.entries) Choice(clefName(c), draft.clef == c, Modifier.weight(1f)) { ScoreTools.sigDraft = draft.copy(clef = c) }
        }
        val k = draft.key ?: 0
        Stepper("Key", keyName(k), { ScoreTools.sigDraft = draft.copy(key = (k - 1).coerceAtLeast(-7)) }, { ScoreTools.sigDraft = draft.copy(key = (k + 1).coerceAtMost(7)) })
        val beats = draft.beats ?: 4; val type = draft.beatType ?: 4
        Stepper("Beats", "$beats", { ScoreTools.sigDraft = draft.copy(beats = (beats - 1).coerceAtLeast(1)) }, { ScoreTools.sigDraft = draft.copy(beats = (beats + 1).coerceAtMost(16)) })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Beat", style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 44.dp))
            for (t in listOf(2, 4, 8, 16)) Choice("$beats/$t", type == t, Modifier.weight(1f)) { ScoreTools.sigDraft = draft.copy(beatType = t) }
        }
        Text("Kept until the print changes it again; the notes take their pitches from it.", style = MaterialTheme.typography.labelSmall, color = dim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { ScoreTools.sigDraft = null }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel") }
            if (ScoreTools.signatureAt(state, m.number) != null)
                OutlinedButton(onClick = { ScoreTools.setSignature(state, m.number, null) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("As read", maxLines = 1) }
            Button(onClick = {
                // Only what differs from the reading is kept: the rest still follows the print.
                ScoreTools.setSignature(state, m.number, com.inksheets.core.omr.SigFix(
                    clef = draft.clef?.takeIf { it != m.clef }, key = draft.key?.takeIf { it != m.key.fifths },
                    beats = if (draft.beats != m.time.beats || draft.beatType != m.time.beatType) draft.beats else null,
                    beatType = if (draft.beats != m.time.beats || draft.beatType != m.time.beatType) draft.beatType else null))
            }, modifier = Modifier.weight(1.3f).height(48.dp)) { Text("Use from bar ${m.number}", maxLines = 1) }
        }
    }
}

/** Behind a note read unclearly, and one a reading changes. */
private val UNCLEAR = Color(0x40E08A00)
private val CHANGED = Color(0x332962FF)

/**
 * Why bar [m] is asked about - each of its doubts, as the reader put it - and what the marks along
 * the foot of its picture say: what was read under each note (green clear, amber unclear, grey a
 * rest), a ring round a note seen and let go. A note on the print with no mark under it was missed.
 */
@Composable
private fun Doubts(m: Measure) {
    val dark = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (m.doubts.isNotEmpty()) Text("Unsure: " + m.doubts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable fun key(c: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.padding(top = 1.dp).height(5.dp).widthIn(min = 14.dp).clip(RoundedCornerShape(3.dp)).background(c))
                Text(label, style = MaterialTheme.typography.labelSmall, color = dark, maxLines = 1)
            }
            val notes = m.events.count { it is Note }; val unclear = m.events.count { it is Note && com.inksheets.core.omr.Learned.unclear(it) }
            key(Color(0xFF2E7D32), "read ${notes - unclear}")
            if (unclear > 0) key(Color(0xFFE08A00), "unclear $unclear")
            if (m.maybe.isNotEmpty()) Text("◯ let go ${m.maybe.size}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB06C00), maxLines = 1)
            Text("no mark: missed", style = MaterialTheme.typography.labelSmall, color = dark, maxLines = 1)
        }
    }
}
