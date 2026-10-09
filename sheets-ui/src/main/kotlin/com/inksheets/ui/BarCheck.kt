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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.unit.Dp

/**
 * Putting bars right, one at a time. The panel is docked along the foot of the screen and never
 * moves or changes size from bar to bar - it may cover the music; the bar itself is shown in it,
 * cut from the page. In it: the bar as printed, three readings of it to pick from with a tap (or
 * put right by hand), and along its foot the same buttons in the same places every time - Back,
 * None of these, Skip, Done - with a row of what can be wrong under them.
 */
@Composable
fun BoxScope.BarCheck(state: SheetsState, room: Dp = Dp.Infinity) {
    if (!ScoreTools.checking) return
    val m = ScoreTools.barUp(state) ?: return
    val paper = Color.White
    val printInk = Color(0xFF111111)
    val total = ScoreTools.checkBars.size
    // Sized by the screen alone - never by what is in it - so nothing jumps between bars.
    val short = room < 700.dp
    ScoreTools.compact = short
    // Putting a bar right by hand (or its clef, key, time, count, number) wants the room: the panel
    // grows for it. A phone on its side gives it nearly all of the screen.
    val handWork = ScoreTools.editing != null || ScoreTools.sigDraft != null || ScoreTools.numbering || m.bars > 1
    // As tall as what is in it needs - over the music if need be - so nothing in it has to be scrolled to.
    val panelHeight = if (room == Dp.Infinity) 900.dp else (room * 0.92f).coerceAtLeast(240.dp)
    Opened("Fix", if (short) 700.dp else 380.dp)
    BoxWithConstraints(Modifier.matchParentSize()) {
    // A phone gives it the whole width (the strips step aside); wider screens keep clear of them.
    val narrowScreen = maxWidth < 600.dp
    Surface(
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.99f),
        modifier = Modifier.align(Alignment.BottomCenter)
            .padding(start = if (narrowScreen) 0.dp else Overlays.left + 8.dp, end = if (narrowScreen) 0.dp else Overlays.right + 8.dp)
            .widthIn(max = 920.dp).fillMaxWidth().heightIn(max = panelHeight)
    ) {
        BoxWithConstraints {
            val wide = maxWidth >= 640.dp
            val pad = if (short) 10.dp else 14.dp
            val gap = if (short) 6.dp else 8.dp
            // Small on a phone either way up; large where there is room.
            val small = short || maxWidth < 600.dp
            val pictureHeight = if (small) 76.dp else 118.dp
            // (On a phone the strips' folded tabs sit at the edges: kept clear of them.)
            Column(Modifier.padding(start = if (narrowScreen) pad + 18.dp else pad, end = if (narrowScreen) pad + 18.dp else pad, top = 8.dp, bottom = pad)) {
                // Which bar, how far through, and what happens after a fix.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Bar ${m.number} · page ${m.page + 1}", style = if (short) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (total > 1) Text("${ScoreTools.checkAt + 1} of $total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    // What happens after a fix: one chip, tapped to change.
                    androidx.compose.material3.AssistChip(onClick = { ScoreTools.chooseGoOn(!ScoreTools.goOn) },
                        label = { Text(if (ScoreTools.goOn) "Then: next red" else "Then: close", maxLines = 1) })
                }
                LinearProgressIndicator(progress = { (ScoreTools.checkAt + 1f) / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 2.dp))
                Spacer(Modifier.height(gap))
                // The work: the print and the readings (or the bar put right by hand, or its clef,
                // key and time, or a rest's count) - in a space that scrolls, never pushing the
                // buttons below it about.
                val editing = ScoreTools.editing
                val picture: @Composable () -> Unit = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxWidth().height(pictureHeight).clip(RoundedCornerShape(10.dp)).background(paper)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    ) {
                        val pic = ScoreTools.barPicture
                        if (pic != null) Image(pic, "Bar ${m.number} as printed", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(pictureHeight).padding(4.dp))
                        else Text("Getting the bar from the page...", style = MaterialTheme.typography.bodySmall, color = Color(0xFF777777))
                    }
                }
                val printed: @Composable () -> Unit = { picture(); Doubts(m) }
                val work: @Composable () -> Unit = {
                    when {
                        m.bars > 1 || m.doubts.any { it.startsWith("rest of how many") } -> RestCount(state, m, short)
                        ScoreTools.sigDraft != null -> SignatureRow(state, m)
                        ScoreTools.numbering -> BarNumber(state, m, short)
                        editing != null -> BarEditor(state, m, editing, paper, printInk, short)
                        else -> {
                            Text(when {
                                ScoreTools.looking && ScoreTools.askedAgain -> "Looking at it further..."
                                ScoreTools.askedAgain -> "Looked further: is it one of these?"
                                else -> "Which matches the print? Tap it - or Edit the nearest."
                            }, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Readings(state, m, paper, printInk, if (small) 60.dp else 128.dp)
                        }
                    }
                }
                // The bar as printed on top, the choices under it - one column at every size.
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
                    picture(); Doubts(m); work()
                }
                Spacer(Modifier.height(gap))
                FixFoot(state, m, short)
            }
        }
    }
    }
}

/**
 * Fix's foot: the same buttons in the same places for every bar, so a player in a rhythm never
 * has one move from under their finger - Back, None of these, Skip, Done; and under them what can
 * be wrong, each telling it what to look for.
 */
@Composable
private fun FixFoot(state: SheetsState, m: Measure, short: Boolean) {
    // Putting a bar right by hand (or its clef, key, time, count, number): that editor's own
    // Cancel / Use buttons are the only ones - never a second row of look-alikes under them.
    if (ScoreTools.editing != null || ScoreTools.sigDraft != null || ScoreTools.numbering || m.bars > 1 || m.doubts.any { it.startsWith("rest of how many") }) return
    val h = if (short) 40.dp else 46.dp
    val pad = PaddingValues(horizontal = 6.dp)
    val busy = ScoreTools.editing != null || ScoreTools.sigDraft != null || ScoreTools.numbering
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { ScoreTools.back(state) }, enabled = ScoreTools.canGoBack, contentPadding = pad, modifier = Modifier.weight(if (short) 0.7f else 1f).height(h)) { Text(if (short) "◀" else "◀ Back", maxLines = 1) }
            OutlinedButton(onClick = { ScoreTools.noneOfThese(state) }, enabled = !busy, contentPadding = pad, modifier = Modifier.weight(1.6f).height(h)) { Text("None of these", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { ScoreTools.skip(state) }, contentPadding = pad, modifier = Modifier.weight(1f).height(h)) { Text("Skip", maxLines = 1) }
            // Leaving Fix is not confirming anything: outlined, and named for what it does - the one
            // filled button in the panel is the one that keeps a bar ("Use this", "Use 4", a reading).
            OutlinedButton(onClick = { ScoreTools.endCheck() }, contentPadding = pad, modifier = Modifier.weight(1f).height(h)) { Text("Close Fix", maxLines = 1) }
        }
        // What's wrong, always here in the same order: every answer in a row where there is room;
        // on a phone one chip opening the list, so the readings above keep their room.
        val answers = listOf("Pitch", "Length", "Extra note", "Missing note", "Rests", "Grace note", "Clef, key, time", "Bar number", "Not one bar")
        fun choose(what: String) = when (what) {
            "Clef, key, time" -> ScoreTools.sigDraft = if (ScoreTools.sigDraft != null) null else com.inksheets.core.omr.SigFix(m.clef, m.key.fifths, m.time.beats, m.time.beatType)
            "Bar number" -> ScoreTools.numbering = !ScoreTools.numbering
            "Not one bar" -> ScoreTools.notOneBar(state)
            else -> ScoreTools.narrow(state, if (ScoreTools.focus == what) null else what)
        }
        fun isOn(what: String) = when (what) {
            "Clef, key, time" -> ScoreTools.sigDraft != null
            "Bar number" -> ScoreTools.numbering
            "Not one bar" -> false
            else -> ScoreTools.focus == what
        }
        fun enabled(what: String) = what == "Clef, key, time" || what == "Bar number" || !busy
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 560.dp || short) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Wrong:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    val chosen = answers.firstOrNull { isOn(it) }
                    androidx.compose.material3.FilterChip(selected = chosen != null, onClick = { open = true }, label = { Text((chosen ?: "What's wrong?") + "  ▾", maxLines = 1) })
                    androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }) {
                        for (what in answers) androidx.compose.material3.DropdownMenuItem(
                            text = { Text((if (isOn(what)) "✓  " else "") + what) }, enabled = enabled(what),
                            onClick = { open = false; choose(what) })
                    }
                }
            } else {
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Wrong:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                    for (what in answers) androidx.compose.material3.FilterChip(selected = isOn(what), enabled = enabled(what), onClick = { choose(what) }, label = { Text(what, maxLines = 1) })
                }
            }
        }
    }
}

/** The number printed at bar [m] (or the one it would have): it and every bar after it numbered on from it. */
@Composable
private fun BarNumber(state: SheetsState, m: Measure, short: Boolean) {
    var n by androidx.compose.runtime.remember(m.number) { androidx.compose.runtime.mutableIntStateOf(m.number) }
    val h = if (short) 40.dp else 48.dp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Which bar is this on the page? Every bar after it is numbered on from it.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = { n = (n - 1).coerceAtLeast(1) }, modifier = Modifier.height(h)) { Text("−") }
            Text("Bar $n", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { n += 1 }, modifier = Modifier.height(h)) { Text("+") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { ScoreTools.numbering = false }, modifier = Modifier.weight(1f).height(h)) { Text("Cancel") }
            Button(onClick = { ScoreTools.numbering = false; if (n != m.number) ScoreTools.setBarNumber(state, m.number, n) }, modifier = Modifier.weight(1.3f).height(h)) { Text("Number it $n", maxLines = 1) }
        }
    }
}

/** The readings offered for bar [m], drawn as the print is - black on white - [height] tall; a tap picks one. */
@Composable
private fun Readings(state: SheetsState, m: Measure, paper: Color, printInk: Color, height: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((i, c) in ScoreTools.offered.withIndex()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .clickable { ScoreTools.pick(state, c) }.padding(6.dp)
            ) {
                val drawing = remember(c) { Engraver.line(listOf(m.copy(events = c.events)), lineStart = false) }
                Canvas(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(8.dp)).background(paper)) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    // Nearly it: put right by hand from here.
                    TextButton(onClick = { ScoreTools.startEdit(c) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(30.dp)) {
                        Text("Edit", style = MaterialTheme.typography.labelMedium)
                    }
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
private fun BarEditor(state: SheetsState, m: Measure, events: List<Event>, paper: Color, printInk: Color, short: Boolean = false,
                      /** Which half: the bar itself (with what it comes to and which note is chosen), its buttons, or both. */
                      view: Boolean = true, buttons: Boolean = true) {
    val at = ScoreTools.editAt
    val drawing = remember(events) { Engraver.line(listOf(m.copy(events = events)), lineStart = false) }
    val chosen = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    // Where the drawing was last laid out: its space and the x and y of its top line - what a tap is measured by.
    val layout = androidx.compose.runtime.remember { FloatArray(3) }
    if (view) Canvas(
        Modifier.fillMaxWidth().height(if (short) 84.dp else 130.dp).clip(RoundedCornerShape(10.dp)).background(paper)
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
    if (view) Text("Comes to ${beats(q)} of ${beats(m.time.quarters)} beats" + if (adds) " - adds up" else "",
        style = if (short) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, color = if (adds) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
    val e = events.getOrNull(at)
    // (Small screens: smaller words and less padding, so every label fits its button whole.)
    val labels = if (short) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
    val inset = PaddingValues(horizontal = if (short) 2.dp else 6.dp)
    @Composable
    fun Btn(label: String, modifier: Modifier, enabled: Boolean = true, on: Boolean = false, onClick: () -> Unit) =
        if (on) Button(onClick = onClick, enabled = enabled, contentPadding = inset, modifier = modifier.height(if (short) 38.dp else 44.dp)) {
            Text(label, style = labels, maxLines = 1)
        } else FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = inset, modifier = modifier.height(if (short) 38.dp else 44.dp)) {
            Text(label, style = labels, maxLines = 1)
        }
    // Which is chosen, and the way to the one before or after it without a tap on a small note.
    if (view) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Btn("◀", Modifier.widthIn(min = 48.dp), at > 0) { ScoreTools.editAt = at - 1 }
        Text(when (e) {
            is Note -> "Note ${at + 1} of ${events.size} - drag it up or down"
            is Rest -> "Rest ${at + 1} of ${events.size}"
            null -> "Nothing in the bar - add a note"
        }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Btn("▶", Modifier.widthIn(min = 48.dp), at + 1 < events.size) { ScoreTools.editAt = at + 1 }
    }
    val isNote = e is Note
    if (!buttons) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Btn("Up", Modifier.weight(1f), isNote) { ScoreTools.edit { BarEdit.step(m, it, at, -1) } }
        Btn("Down", Modifier.weight(1f), isNote) { ScoreTools.edit { BarEdit.step(m, it, at, 1) } }
        // What is written before it: a flat, a natural, a sharp - tapped again, none.
        val acc = BarEdit.accidentalOf(events, at)
        for ((label, alter) in listOf("♭♭" to -2, "♭" to -1, "♮" to 0, "♯" to 1, "x" to 2))
            Btn(label, Modifier.weight(0.7f), isNote, on = acc == alter) { ScoreTools.edit { BarEdit.accidental(m, it, at, if (acc == alter) null else alter) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Btn(if (e?.duration?.dots ?: 0 > 0) "No dot" else "Dot", Modifier.weight(1f), e != null) { ScoreTools.edit { BarEdit.dot(it, at) } }
        Btn(if (isNote) "Make rest" else "Make note", Modifier.weight(1.3f), e != null) { ScoreTools.edit { BarEdit.restOrNote(m, it, at) } }
        Btn("Fill with rests", Modifier.weight(1.5f), !adds && q < m.time.quarters) { ScoreTools.edit { BarEdit.fillWithRests(m, it) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        // (Five to a row: their words a size smaller wherever that keeps "Quarter" whole.)
        for ((label, base) in listOf("Whole" to 1, "Half" to 2, "Quarter" to 4, "8th" to 8, "16th" to 16))
            run {
                val sel = e?.duration?.base == base
                val mod = Modifier.weight(1f).height(if (short) 38.dp else 44.dp)
                val pad = PaddingValues(horizontal = 2.dp)
                if (sel) Button(onClick = { ScoreTools.edit { BarEdit.length(it, at, base) } }, enabled = e != null, contentPadding = pad, modifier = mod) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                else FilledTonalButton(onClick = { ScoreTools.edit { BarEdit.length(it, at, base) } }, enabled = e != null, contentPadding = pad, modifier = mod) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Btn("Triplet", Modifier.weight(1f), at + 2 < events.size) { ScoreTools.edit { BarEdit.triplet(it, at) } }
        Btn("Add note after", Modifier.weight(1.4f)) { ScoreTools.edit { BarEdit.addAfter(m, it, if (events.isEmpty()) -1 else at) }; if (events.isNotEmpty()) ScoreTools.editAt = at + 1 }
        Btn("Remove", Modifier.weight(1f), e != null) { ScoreTools.edit { BarEdit.delete(it, at) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        val h = if (short) 40.dp else 48.dp
        OutlinedButton(onClick = { ScoreTools.cancelEdit() }, contentPadding = inset, modifier = Modifier.weight(1f).height(h)) { Text("Cancel", style = labels, maxLines = 1) }
        OutlinedButton(onClick = { ScoreTools.undoEdit() }, enabled = ScoreTools.canUndoEdit, contentPadding = inset, modifier = Modifier.weight(1f).height(h)) { Text("Undo", style = labels, maxLines = 1) }
        Button(onClick = { ScoreTools.finishEdit(state) }, enabled = events.isNotEmpty(), contentPadding = inset, modifier = Modifier.weight(1f).height(h)) { Text("Use this", style = labels, maxLines = 1) }
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
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Narrow (a side of the panel on a small screen): what it was read as over the way to change it.
            val stacked = maxWidth < 380.dp
            val read = "Read as ${clefName(m.clef)} clef · ${keyName(m.key.fifths)} · ${m.time.beats}/${m.time.beatType}" +
                if (ScoreTools.signatureAt(state, m.number) != null) " (put right here)" else ""
            val change: @Composable () -> Unit = {
                TextButton(onClick = { ScoreTools.sigDraft = com.inksheets.core.omr.SigFix(m.clef, m.key.fifths, m.time.beats, m.time.beatType) },
                    contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Clef, key or time wrong?", maxLines = 1) }
            }
            if (stacked) Column(Modifier.fillMaxWidth()) {
                Text(read, style = MaterialTheme.typography.bodySmall, color = dim, maxLines = 3)
                change()
            } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(read, style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.weight(1f), maxLines = 3)
                change()
            }
        }
        return
    }
    @Composable
    fun Choice(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
        if (on) Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 2.dp), modifier = modifier.height(if (ScoreTools.compact) 36.dp else 40.dp)) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        else OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 2.dp), modifier = modifier.height(if (ScoreTools.compact) 36.dp else 40.dp)) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
    @Composable
    fun Stepper(label: String, value: String, minus: () -> Unit, plus: () -> Unit) =
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 44.dp))
            FilledTonalButton(onClick = minus, modifier = Modifier.height(if (ScoreTools.compact) 36.dp else 40.dp), contentPadding = PaddingValues(horizontal = 14.dp)) { Text("−") }
            Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = plus, modifier = Modifier.height(if (ScoreTools.compact) 36.dp else 40.dp), contentPadding = PaddingValues(horizontal = 14.dp)) { Text("+") }
        }
    Column(verticalArrangement = Arrangement.spacedBy(if (ScoreTools.compact) 5.dp else 8.dp), modifier = Modifier.fillMaxWidth()
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
        if (!ScoreTools.compact) Text("Kept until the print changes it again; the notes take their pitches from it.", style = MaterialTheme.typography.labelSmall, color = dim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { ScoreTools.sigDraft = null }, modifier = Modifier.weight(1f).height(if (ScoreTools.compact) 40.dp else 48.dp)) { Text("Cancel") }
            if (ScoreTools.signatureAt(state, m.number) != null)
                OutlinedButton(onClick = { ScoreTools.setSignature(state, m.number, null) }, modifier = Modifier.weight(1f).height(if (ScoreTools.compact) 40.dp else 48.dp)) { Text("As read", maxLines = 1) }
            Button(onClick = {
                // Only what differs from the reading is kept: the rest still follows the print.
                ScoreTools.setSignature(state, m.number, com.inksheets.core.omr.SigFix(
                    clef = draft.clef?.takeIf { it != m.clef }, key = draft.key?.takeIf { it != m.key.fifths },
                    beats = if (draft.beats != m.time.beats || draft.beatType != m.time.beatType) draft.beats else null,
                    beatType = if (draft.beats != m.time.beats || draft.beatType != m.time.beatType) draft.beatType else null))
            }, modifier = Modifier.weight(1.3f).height(if (ScoreTools.compact) 40.dp else 48.dp)) { Text("Use from bar ${m.number}", maxLines = 1) }
        }
    }
}

/**
 * A rest of many bars: how many - as read, its figure in doubt - chosen by the figure printed over
 * it on the picture above, and kept; the bars after it numbered on from it.
 */
@Composable
private fun RestCount(state: SheetsState, m: Measure, short: Boolean) {
    var count by androidx.compose.runtime.remember(m.number) { androidx.compose.runtime.mutableIntStateOf(if (m.bars > 1) m.bars else 2) }
    val h = if (short) 40.dp else 48.dp
    Column(verticalArrangement = Arrangement.spacedBy(if (short) 6.dp else 10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("A rest of how many bars? (the figure over it on the page)", style = if (short) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = { count = (count - 1).coerceAtLeast(2) }, modifier = Modifier.height(h)) { Text("−") }
            Text("$count bars", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { count = (count + 1).coerceAtMost(999) }, modifier = Modifier.height(h)) { Text("+") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            for (d in listOf(-10, 10)) OutlinedButton(onClick = { count = (count + d).coerceIn(2, 999) }, modifier = Modifier.weight(1f).height(h)) { Text(if (d < 0) "−10" else "+10", maxLines = 1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val pad = PaddingValues(horizontal = if (short) 4.dp else 12.dp)
            val words = if (short) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
            OutlinedButton(onClick = { ScoreTools.skip(state) }, contentPadding = pad, modifier = Modifier.weight(1f).height(h)) { Text("Skip", style = words, maxLines = 1) }
            OutlinedButton(onClick = { ScoreTools.endCheck() }, contentPadding = pad, modifier = Modifier.weight(1f).height(h)) { Text("Done", style = words, maxLines = 1) }
            Button(onClick = { ScoreTools.setRestCount(state, m.number, count) }, contentPadding = pad, modifier = Modifier.weight(1.3f).height(h)) { Text("Use $count", style = words, maxLines = 1) }
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
