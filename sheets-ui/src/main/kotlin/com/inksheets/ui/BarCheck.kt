package com.inksheets.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inksheets.core.omr.Engraver

/**
 * The bars in doubt, one at a time: the bar lit on the page, and here three readings of it drawn
 * as music - the likeliest first - to pick from with a tap. "None of these" looks further; "Skip"
 * leaves the bar as it is. The panel sits in the half of the page away from the bar, so the bar
 * itself is never covered.
 */
@Composable
fun BoxScope.BarCheck(state: SheetsState) {
    if (!ScoreTools.checking) return
    val m = ScoreTools.barUp(state) ?: return
    val score = ScoreTools.scoreHere(state)
    // Where the bar is on its page: the panel goes to the other half.
    val pageHeight = score?.pageWidths?.getOrNull(m.page)?.let { it * 1.3f } ?: 1f
    val barLow = m.box.top > pageHeight * 0.5f
    val ink = MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        modifier = Modifier.align(if (barLow) Alignment.TopCenter else Alignment.BottomCenter).padding(12.dp).widthIn(max = 720.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bar ${m.number}  (${ScoreTools.checkAt + 1} of ${ScoreTools.checkBars.size} in doubt)", style = MaterialTheme.typography.titleSmall)
            Text(if (ScoreTools.askedAgain) "Looked further: is it one of these?" else "Which is it? Tap the one that matches the page.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((i, c) in ScoreTools.offered.withIndex()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .clickable { ScoreTools.pick(state, c) }.padding(6.dp)
                    ) {
                        val drawing = remember(c) { Engraver.line(listOf(m.copy(events = c.events, showsClef = true, showsKey = true, showsTime = false))) }
                        Canvas(Modifier.fillMaxWidth().height(86.dp)) {
                            val space = minOf(size.height / 10f, size.width / (drawing.width + 1f))
                            drawMarks(drawing, space, Offset(space * 0.3f, (size.height - space * 4f) / 2f), ink)
                        }
                        Text(
                            if (c.changes.isEmpty()) "As read" else c.changes.joinToString("; "),
                            style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, lineHeight = 11.sp, textAlign = TextAlign.Center, maxLines = 2
                        )
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { ScoreTools.noneOfThese(state) }) { Text("None of these") }
                TextButton(onClick = { ScoreTools.next(state) }) { Text("Skip") }
                TextButton(onClick = { ScoreTools.endCheck() }) { Text("Done") }
            }
        }
    }
}

@Composable
private fun <T> remember(key: Any?, calc: () -> T): T = androidx.compose.runtime.remember(key, calc)
