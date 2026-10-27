package com.inksheets.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A device-local choice, not a requirement that devices share Wi-Fi. */
internal enum class ConnectionTransport(val label: String) {
    AUTO("Auto"), WIFI("Wi-Fi only"), BLUETOOTH("Bluetooth only");
    companion object {
        fun decode(value: String?) = entries.firstOrNull { it.name == value } ?: AUTO
    }
}

@Composable
internal fun TransportChoice(value: ConnectionTransport, bluetooth: Boolean, onChange: (ConnectionTransport) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ConnectionTransport.entries.filter { bluetooth || it != ConnectionTransport.BLUETOOTH }.forEach { option ->
            FilterChip(selected = value == option, onClick = { onChange(option) }, label = { Text(option.label) })
        }
    }
}
