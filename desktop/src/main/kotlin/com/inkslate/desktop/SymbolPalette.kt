package com.inkslate.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inkslate.core.MathSymbols

/**
 * Pick symbols and build a short string, then place it as a text object.
 *
 * Multi-select rather than one at a time, because these are usually wanted in combination -
 * "x₁²" is three clicks here and a fight with the keyboard otherwise.
 */
@OptIn(ExperimentalLayoutApi::class)
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
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {

                // What has been assembled so far, and the only way to take a character back off.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        buffer.ifEmpty { "Pick some characters" },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (buffer.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                MathSymbols.groups.forEach { group ->
                    Text(
                        group.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
                    )
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        group.symbols.forEach { symbol ->
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { buffer += symbol },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(symbol, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = buffer.isNotEmpty(), onClick = { onInsert(buffer) }) {
                Text("Insert")
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (buffer.isNotEmpty()) {
                    TextButton(onClick = { buffer = buffer.dropLast(1) }) { Text("Undo one") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
