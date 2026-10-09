package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What Listen is doing, as a card under its button in the strip - never over the music - to be
 * read at a glance from the stand: the microphone and how loud it hears (a red card, and said,
 * when it hears nothing at all); how long until the page turns, in big figures, over a bar filling
 * through this page towards the turn; and in words anything else (waiting, turned, guessed).
 */
@Composable
internal fun ListenGauge(state: SheetsState) {
    val text = Listener.summary(state) ?: return
    val scheme = MaterialTheme.colorScheme
    if (!Listener.active) {
        // Why it stopped, or why it would not start: for a moment, in words.
        Text(text, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, lineHeight = 11.sp, maxLines = 3,
            textAlign = TextAlign.Center, modifier = Modifier.width(72.dp).padding(bottom = 4.dp))
        return
    }
    val f = Listener.follow
    val deaf = Ears.deaf
    val soon = f?.turnMs != null && f.music && f.turnMs - f.atMs < 3_000
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = when {
            deaf -> scheme.errorContainer
            soon -> scheme.tertiaryContainer
            else -> scheme.secondaryContainer
        },
        modifier = Modifier.width(68.dp).padding(bottom = 4.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 6.dp)
        ) {
            // The microphone, and its level: moves with any sound at all.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (deaf) Icons.Default.MicOff else Icons.Default.Mic, "Microphone", Modifier.size(14.dp),
                    tint = if (deaf) scheme.error else scheme.onSecondaryContainer)
                Meter(if (deaf) 0f else Ears.level, if (f?.music == true) scheme.primary else scheme.outline, Modifier.padding(start = 3.dp).weight(1f))
            }
            when {
                deaf -> {
                    Text("Hears nothing", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = scheme.error, textAlign = TextAlign.Center, lineHeight = 13.sp)
                    Ears.device?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 2,
                            textAlign = TextAlign.Center, color = scheme.onErrorContainer)
                    }
                    Text("Make a sound, or pick another in Settings", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                        lineHeight = 10.sp, textAlign = TextAlign.Center, color = scheme.onErrorContainer)
                }
                f == null -> Text(text, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                else -> {
                    val turned = System.currentTimeMillis() - f.turnedAt < 2_000
                    if (f.turnMs != null && f.music && !turned && f.found && f.sure >= com.inksheets.core.WindowFollower.UNSURE) {
                        // The countdown, big: how long, and to which page.
                        val s = ((f.turnMs - f.atMs) / 1000).coerceAtLeast(0)
                        Text(if (s < 1) "Now" else "${s}s", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            color = if (soon) scheme.onTertiaryContainer else scheme.onSecondaryContainer, lineHeight = 24.sp)
                        Text("to page ${(f.nextPage ?: f.page + 1) + 1}", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, lineHeight = 11.sp)
                    } else {
                        Text(text.removeSuffix(" (guessed)"), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, lineHeight = 14.sp)
                    }
                    // This page, filling towards its turn.
                    if (f.turnMs != null) {
                        val span = (f.turnMs - f.pageFromMs).coerceAtLeast(1L)
                        Meter(((f.atMs - f.pageFromMs).toFloat() / span), if (soon) scheme.tertiary else scheme.primary, Modifier.fillMaxWidth(), tall = true)
                    }
                    // How sure it is of where the band is: a row of dots, empty to full.
                    if (f.music) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        val dots = if (!f.found) 0 else (f.sure * 4).toInt().coerceIn(0, 4) + 1
                        repeat(5) { i -> Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i < dots) scheme.primary else scheme.outlineVariant)) }
                    }
                    Text("Page ${f.page + 1} of ${f.pages}" + if (f.source == "guessed") "\nturns guessed" else "",
                        style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, lineHeight = 10.sp, textAlign = TextAlign.Center,
                        color = if (f.source == "guessed") scheme.error else Color.Unspecified)
                }
            }
        }
    }
}

@Composable
private fun Meter(fraction: Float, color: Color, modifier: Modifier, tall: Boolean = false) {
    Box(
        modifier.height(if (tall) 8.dp else 6.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}
