package com.inksheets.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** InkSheets' part of Settings: things done once, kept out of the way of every day. */
@Composable
fun SheetsSettings(state: SheetsState) {
    var importing by remember { mutableStateOf(false) }
    Text(
        "Library",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
    )
    TextButton(
        onClick = { importing = true },
        enabled = state.library != null,
        modifier = Modifier.padding(horizontal = 12.dp)
    ) { Text("Import from MobileSheets...") }
    if (importing) MobileSheetsDialog(state, onClose = { importing = false })
}
