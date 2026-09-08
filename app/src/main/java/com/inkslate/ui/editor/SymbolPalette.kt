package com.inkslate.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Quick insertion of characters that are painful to reach on a tablet keyboard.
 *
 * Everything here is a real Unicode character placed as a text object, not an image, so it stays
 * selectable, restyleable and searchable once exported.
 */
object MathSymbols {

    data class Group(val name: String, val symbols: List<String>)

    val groups = listOf(
        Group(
            "Operators",
            listOf("×", "÷", "±", "∓", "·", "√", "∛", "∞", "∝", "≈", "≠", "≡", "≤", "≥", "≪", "≫")
        ),
        Group(
            "Calculus",
            listOf("∫", "∬", "∭", "∮", "∂", "∇", "Δ", "δ", "lim", "Σ", "Π", "→", "↦", "′", "″", "…")
        ),
        Group(
            "Greek",
            listOf(
                "α", "β", "γ", "δ", "ε", "θ", "λ", "μ", "π", "ρ", "σ", "τ", "φ", "χ", "ψ", "ω",
                "Γ", "Θ", "Λ", "Ξ", "Φ", "Ψ", "Ω"
            )
        ),
        Group(
            "Sets and logic",
            listOf("∈", "∉", "⊂", "⊆", "⊃", "⊇", "∪", "∩", "∅", "∀", "∃", "¬", "∧", "∨", "⇒", "⇔")
        ),
        Group(
            "Superscript",
            listOf("⁰", "¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹", "ⁿ", "⁺", "⁻", "ᵀ", "°", "∠")
        ),
        Group(
            "Subscript",
            listOf("₀", "₁", "₂", "₃", "₄", "₅", "₆", "₇", "₈", "₉", "ₐ", "ₑ", "ᵢ", "ⱼ", "ₙ", "ₓ")
        ),
        Group(
            "Fractions",
            listOf("½", "⅓", "⅔", "¼", "¾", "⅕", "⅙", "⅛", "⅜", "⅝", "⅞", "⁄")
        ),
        Group(
            "Chemistry and units",
            listOf("⇌", "↑", "↓", "Å", "µ", "Ω", "℃", "℉", "‰", "∴", "∵", "⊥", "∥", "≅", "≜", "∎")
        )
    )
}

/**
 * Pick symbols and build a short string, then place it as a text object.
 *
 * Multi-select rather than one-at-a-time because these are usually wanted in combination -
 * "x₁²" is three taps here and a fight with the keyboard otherwise.
 */
@Composable
fun SymbolPaletteDialog(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit
) {
    var buffer by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert symbol") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp)
                ) {
                    Text(
                        buffer.ifEmpty { "Tap symbols to build a string" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (buffer.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                if (buffer.isNotEmpty()) {
                    TextButton(onClick = { buffer = buffer.dropLast(1) }) { Text("Backspace") }
                }

                MathSymbols.groups.forEach { group ->
                    Text(
                        group.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        group.symbols.forEach { sym ->
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { buffer += sym },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    sym,
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = buffer.isNotEmpty(), onClick = { onInsert(buffer) }) {
                Text("Place")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
