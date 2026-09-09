package com.inkslate.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.inkslate.core.CopyNaming
import com.inkslate.core.FileOverride
import com.inkslate.core.InkFormat
import com.inkslate.core.SaveMode

/**
 * What this one file does differently.
 *
 * Every control has an "as set globally" position, and that is the default. An override that
 * cannot be partial is not much of an override: pinning one worksheet to overwrite should not
 * also freeze its autosave interval at whatever the global happened to be that day.
 */
@Composable
fun FileRulesDialog(
    path: String,
    name: String,
    prefs: SavePrefs,
    onDismiss: () -> Unit
) {
    val global = remember { prefs.global() }
    var override by remember(path) { mutableStateOf(prefs.overrideFor(path) ?: FileOverride()) }

    fun commit(next: FileOverride) {
        override = next
        prefs.setOverride(path, next)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rules for $name") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Anything left as \"as set globally\" keeps following the defaults, including " +
                        "when those change later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OptionLabel("When I save")
                OptionWrapRow {
                    OptionChip("As set globally (${global.mode.label})", override.mode == null) {
                        commit(override.copy(mode = null))
                    }
                    SaveMode.entries.forEach { m ->
                        OptionChip(m.label, override.mode == m) { commit(override.copy(mode = m)) }
                    }
                }

                OptionLabel("Ink in the saved PDF")
                OptionWrapRow {
                    OptionChip(
                        "As set globally (${global.inkFormat.label})",
                        override.inkFormat == null
                    ) { commit(override.copy(inkFormat = null)) }
                    InkFormat.entries.forEach { f ->
                        OptionChip(f.label, override.inkFormat == f) {
                            commit(override.copy(inkFormat = f))
                        }
                    }
                }
                Text(
                    override.inkFormat?.detail ?: global.inkFormat.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OptionLabel("Name copies by")
                OptionWrapRow {
                    OptionChip(
                        "As set globally (${global.copyNaming.label})",
                        override.copyNaming == null
                    ) { commit(override.copy(copyNaming = null)) }
                    CopyNaming.entries.forEach { n ->
                        OptionChip(n.label, override.copyNaming == n) {
                            commit(override.copy(copyNaming = n))
                        }
                    }
                }

                TriSwitch(
                    "Autosave",
                    override.autosave,
                    global.autosave
                ) { commit(override.copy(autosave = it)) }

                TriSwitch(
                    "Ask before overwriting",
                    override.confirmOverwrite,
                    global.confirmOverwrite
                ) { commit(override.copy(confirmOverwrite = it)) }

                TriSwitch(
                    "Keep a backup when overwriting",
                    override.backupOnOverwrite,
                    global.backupOnOverwrite
                ) { commit(override.copy(backupOnOverwrite = it)) }

                if (!override.isEmpty) {
                    Text(
                        "This file: " + override.describe(global).joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            if (!override.isEmpty) {
                TextButton(onClick = { commit(FileOverride()) }) { Text("Use the defaults") }
            }
        }
    )
}

/**
 * A switch with three positions: on, off, and inherited.
 *
 * A plain switch cannot express "follow the default", which is the position most files should be
 * in - so the chips say which of the three this file is on rather than pretending there are two.
 */
@Composable
private fun TriSwitch(
    label: String,
    value: Boolean?,
    globalValue: Boolean,
    onChange: (Boolean?) -> Unit
) {
    OptionLabel(label)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OptionWrapRow {
            OptionChip(
                "As set globally (${if (globalValue) "on" else "off"})",
                value == null
            ) { onChange(null) }
            OptionChip("On", value == true) { onChange(true) }
            OptionChip("Off", value == false) { onChange(false) }
        }
    }
}
