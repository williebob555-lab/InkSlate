package com.inkslate.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.inkslate.data.LocalDesignUpdates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foreground-only checks; no Android installer and no process/activity recreation. */
@Composable
fun DesignUpdatePrompt() {
    val updates = LocalDesignUpdates.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    LaunchedEffect(updates, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                updates.check()
                delay(5 * 60 * 1000L)
            }
        }
    }
    updates.pending?.let { design ->
        AlertDialog(
            onDismissRequest = { if (!updates.applying) updates.defer() },
            title = { Text("Quick design update") },
            text = {
                Column {
                    Text(design.summary)
                    Text("Reloads the interface while keeping your documents and undo history open. No app installation is needed.", Modifier.padding(top = 12.dp))
                    if (updates.applying) CircularProgressIndicator(Modifier.padding(top = 12.dp))
                    updates.error?.let { Text(it, Modifier.padding(top = 12.dp)) }
                }
            },
            confirmButton = {
                TextButton(enabled = !updates.applying, onClick = { scope.launch { updates.applyPending() } }) { Text("Apply and reload") }
            },
            dismissButton = { TextButton(enabled = !updates.applying, onClick = { updates.defer() }) { Text("Later") } }
        )
    }
}

@Composable
fun DesignUpdateSection(testBuildsEnabled: Boolean) {
    val updates = LocalDesignUpdates.current
    val scope = rememberCoroutineScope()
    val enabled = testBuildsEnabled || updates.enabled()
    SectionHeader("Design updates")
    Text("Design revision ${updates.active.revision}. Quick changes to the tabs, mixer, wheel, colors and scribble recognition.", Modifier.padding(horizontal = 16.dp))
    Text("A valid active design configuration takes priority over built-in defaults. Prefer changing the configuration for supported design adjustments; an app build is needed for new behavior.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    TextButton(enabled = enabled && !updates.checking && !updates.applying,
        onClick = { scope.launch { updates.check(manual = true) } }) {
        Text(if (updates.checking) "Checking design…" else "Check for design changes")
    }
    if (!enabled) Text("Enable Test builds to receive experimental designs.", Modifier.padding(horizontal = 16.dp))
    updates.error?.let { Text(it, Modifier.padding(horizontal = 16.dp)) }
    TextButton(enabled = !updates.applying && updates.prepareReload != null,
        onClick = { scope.launch { updates.reset() } }) { Text("Restore this build’s design") }
}
