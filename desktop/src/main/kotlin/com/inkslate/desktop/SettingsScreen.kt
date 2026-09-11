package com.inkslate.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkslate.core.CopyNaming
import com.inkslate.core.InkFormat
import com.inkslate.core.SaveMode
import com.inkslate.core.SaveSettings
import com.inkslate.core.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings.
 *
 * The Android screen, heading for heading: the save rules first, because they are what decides
 * what happens to a document on disk; then autosave, the stylus, this device, any files pinned to
 * their own rules, and updates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, navigation: NavigationHooks) {
    navigation.back = onBack

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            SavingSection()
            HorizontalDivider(Modifier.padding(top = 14.dp))
            WhileOpenSection()
            HorizontalDivider(Modifier.padding(top = 14.dp))
            StylusSection()
            HorizontalDivider(Modifier.padding(top = 14.dp))
            DeviceSection()
            HorizontalDivider(Modifier.padding(top = 14.dp))
            StorageSection()
            HorizontalDivider(Modifier.padding(top = 14.dp))
            DiagnosticsSection()
            HorizontalDivider(Modifier.padding(top = 14.dp))
            CompanionFilesSection()
            HorizontalDivider(Modifier.padding(top = 14.dp))
            UpdateSection()
        }
    }
}


/**
 * The save rules, and the files that ignore them.
 *
 * These are the defaults. Any file can override them from the editor's own menu, and its rules
 * always win - which is why the overridden ones are listed here rather than being invisible.
 */
@Composable
private fun SavingSection() {
    val prefs = remember { SavePrefs() }
    var settings by remember { mutableStateOf(prefs.global()) }
    var overrides by remember { mutableStateOf(prefs.overriddenPaths()) }

    fun update(block: (SaveSettings) -> SaveSettings) {
        settings = block(settings).also { prefs.setGlobal(it) }
    }

    SectionHeader("Saving")
    Text(
        "These are the defaults. Any file can override them from the editor, and its own rules " +
            "always win.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )

    ChoiceRow(
        "When I save",
        settings.mode.label,
        SaveMode.entries.map { it.label to it },
        subtitle = "Your handwriting goes into the document as you work, which is what carries it to your other devices. This is what pressing Save leaves behind."
    ) { update { s -> s.copy(mode = it) } }
    ChoiceRow(
        "Ink in exported PDFs",
        settings.inkFormat.label,
        InkFormat.entries.map { it.label to it },
        subtitle = settings.inkFormat.detail
    ) { update { s -> s.copy(inkFormat = it) } }

    if (settings.mode != SaveMode.OVERWRITE) {
        ChoiceRow(
            "Name copies by",
            settings.copyNaming.label,
            CopyNaming.entries.map { it.label to it }
        ) { update { s -> s.copy(copyNaming = it) } }

        if (settings.copyNaming == CopyNaming.SUFFIX) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                OutlinedTextField(
                    value = settings.copySuffix,
                    onValueChange = { update { s -> s.copy(copySuffix = it) } },
                    label = { Text("Suffix") },
                    singleLine = true,
                    supportingText = {
                        Text("homework.pdf becomes homework${settings.copySuffix}.pdf")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (settings.mode != SaveMode.COPY) {
        SwitchRow(
            "Ask before overwriting",
            "Overwriting is the one action here that can destroy something this app did not " +
                "create, so it asks unless you say not to.",
            settings.confirmOverwrite
        ) { update { s -> s.copy(confirmOverwrite = it) } }

        SwitchRow(
            "Keep a backup when overwriting",
            "The original is copied into ${DocumentExport.BACKUP_DIR} beside it first, keeping " +
                "the last 20. If the backup fails, the overwrite is refused.",
            settings.backupOnOverwrite
        ) { update { s -> s.copy(backupOnOverwrite = it) } }
    }

    SectionHeader("Autosave")
    SwitchRow(
        "Autosave annotations",
        "Keeps a working copy as you draw, in this machine's own storage. Your document is only " +
            "written when you save or leave it.",
        settings.autosave
    ) { update { s -> s.copy(autosave = it) } }

    if (settings.autosave) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Every ${settings.autosaveSeconds} seconds",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = settings.autosaveSeconds.toFloat(),
                onValueChange = { update { s -> s.copy(autosaveSeconds = it.toInt()) } },
                valueRange = 5f..120f,
                steps = 22
            )
        }
    }

    if (overrides.isNotEmpty()) {
        SectionHeader("Files with their own rules")
        Text(
            "${overrides.size} file${if (overrides.size == 1) "" else "s"} ignore the defaults " +
                "above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        overrides.forEach { path ->
            val o = prefs.overrideFor(path)
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            File(path).name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            o?.describe(settings)?.joinToString(" · ")
                                ?.ifEmpty { "Same as defaults" } ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        prefs.clearOverride(path)
                        overrides = prefs.overriddenPaths()
                    }) { Icon(Icons.Default.Close, "Reset to defaults") }
                }
            }
        }
        TextButton(
            onClick = { prefs.clearAllOverrides(); overrides = prefs.overriddenPaths() },
            modifier = Modifier.padding(horizontal = 12.dp)
        ) { Text("Reset all to defaults") }
    }
}

/**
 * The stylus, and where its button is actually configured.
 *
 * Deliberately not a dropdown here. A barrel button is a whole pen profile - tool, colour, brush
 * and width - which is the toolbar's job to edit, and a setting three screens away from the pen
 * it belongs to is a setting nobody finds.
 */
@Composable
private fun StylusSection() {
    SectionHeader("Stylus")
    Text(
        "If your pen has a barrel button, click the Pen/Finger switch in the toolbar while " +
            "holding it. That reveals a profile for the button - a second pen, with its own " +
            "tool, colour and width - and holding the button while drawing uses it.\n\n" +
            "The switch itself only ever moves between the pen and the finger, so a button " +
            "profile cannot turn up by accident.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    Text(
        "Pressure comes from the pen where the hardware reports it, and from speed where it does " +
            "not - which is also what a mouse gets, since a mouse reporting a constant full " +
            "pressure is the absence of the data rather than the data.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

// ---- shared rows -------------------------------------------------------------

@Composable
private fun <T> ChoiceRow(
    title: String,
    current: String,
    options: List<Pair<String, T>>,
    subtitle: String? = null,
    onPick: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(onClick = { open = true }) { Text(current) }
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (label, value) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onPick(value); open = false })
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DeviceSection() {
    SectionHeader("This device")
    Text(
        "Every mark carries the tag of the machine that made it, so two devices drawing offline " +
            "cannot mint the same identifier and a sync cannot silently drop one of them.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    Text(
        "Device tag  ${DocumentIO.deviceTag()}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp)
    )
}

@Composable
private fun StorageSection() {
    var tick by remember { mutableStateOf(0) }
    var summary by remember { mutableStateOf("Counting...") }

    LaunchedEffect(tick) {
        summary = withContext(Dispatchers.IO) {
            val dir = File(
                System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
                "InkSlate/working"
            )
            val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
            val bytes = files.sumOf { it.length() }
            "${files.size} working ${if (files.size == 1) "copy" else "copies"}  ·  " +
                "${(bytes / 1024.0).let { "%.0f".format(it) }} KB"
        }
    }

    SectionHeader("Storage")
    Text(
        "While a document is open its marks are also written to a scratch copy here, so a crash " +
            "between two saves does not take the afternoon with it. The documents themselves are " +
            "wherever you keep them; nothing is copied into the app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    Text(
        summary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp)
    )
    TextButton(onClick = { tick++ }, modifier = Modifier.padding(horizontal = 12.dp)) {
        Text("Refresh")
    }
}

// ---- while a document is open --------------------------------------------------

/**
 * The two things that only matter while you are actually working in a document.
 *
 * Both live in the tool state rather than the save rules: they are about how the app behaves at
 * your desk, not about what happens to the file.
 */
@Composable
private fun WhileOpenSection() {
    val tools = rememberToolState()

    SectionHeader("While a document is open")

    SwitchRow(
        title = "Pick up where I left off",
        subtitle = "Reopen each document at the same place and zoom. Off, and every document " +
            "opens at the top.",
        checked = tools.rememberView
    ) { tools.rememberView = it }

    SwitchRow(
        title = "Keep the screen awake",
        subtitle = "The display will not blank or lock while you have a document open. It still " +
            "sleeps everywhere else.",
        checked = tools.keepScreenOn
    ) { tools.keepScreenOn = it }
}

// ---- companion files -----------------------------------------------------------

/**
 * The `.inkdoc` files an earlier version left beside documents.
 *
 * Same tidy-up as the tablet's, and the same reason for offering it rather than doing it quietly:
 * it rewrites the user's documents, which is not something to do without being asked.
 */
@Composable
private fun CompanionFilesSection() {
    var found by remember { mutableStateOf<List<File>?>(null) }
    var absorbing by remember { mutableStateOf(false) }
    var absorbed by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(0) }
    var confirming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SectionHeader("Companion files")
    Text(
        "Handwriting is stored inside your documents now, so there is nothing to keep beside " +
            "them. Any .inkdoc files left over from an earlier version can be folded into their " +
            "documents and removed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp)
    )

    val list = found
    when {
        absorbing -> Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                "  Folding in $absorbed of ${list?.size ?: 0}...",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        list == null -> TextButton(
            onClick = {
                scope.launch {
                    found = withContext(Dispatchers.IO) {
                        LegacyCompanions.find(FileRepo().libraryFolders())
                    }
                }
            },
            modifier = Modifier.padding(horizontal = 12.dp)
        ) { Text("Check for leftover companion files") }

        list.isEmpty() -> Text(
            if (absorbed > 0) {
                "Done. $absorbed document(s) now carry their own handwriting" +
                    (if (failed > 0) ", $failed could not be changed." else ".")
            } else {
                "None found. Nothing to tidy up."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp)
        )

        else -> Column {
            Text(
                "${list.size} document(s) still have one.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp)
            )
            TextButton(
                onClick = { confirming = true },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) { Text("Fold them into the documents") }
        }
    }

    if (confirming) {
        val count = found?.size ?: 0
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Fold in $count companion file(s)?") },
            text = {
                Text(
                    "Each document will be rewritten to carry its own handwriting, and its " +
                        "companion file removed.\n\nThe handwriting is copied into the document " +
                        "and read back before anything is deleted, so a document that will not " +
                        "take it keeps its companion file.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    val targets = found.orEmpty()
                    absorbing = true
                    absorbed = 0
                    failed = 0
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            for (f in targets) {
                                if (LegacyCompanions.absorb(f).isSuccess) absorbed++ else failed++
                            }
                        }
                        absorbing = false
                        found = withContext(Dispatchers.IO) {
                            LegacyCompanions.find(FileRepo().libraryFolders())
                        }
                    }
                }) { Text("Fold them in") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        )
    }
}

// ---- diagnostics -------------------------------------------------------------

/**
 * What the app has actually been doing, and anything that killed it.
 *
 * There is no crash reporting service behind a sideloaded build, so without this a bad afternoon is
 * just "it went wrong" with nothing to act on. The same three cards as Android, reading from the
 * same-shaped log, so a report from the tablet and one from the laptop can be compared line for
 * line.
 *
 * Problems are shown by default and the full log is a click away: a hundred routine "opened,
 * saved" lines are exactly what buries the one line that matters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsSection() {
    var tick by remember { mutableStateOf(0) }
    var showAllEvents by remember { mutableStateOf(false) }
    var crashes by remember { mutableStateOf(EventLog.crashReports()) }
    var viewingCrash by remember { mutableStateOf<String?>(null) }

    SectionHeader("Diagnostics")

    // ---- live rendering stats ----
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Rendering", style = MaterialTheme.typography.labelLarge)
            Text(
                remember(tick) { RenderStats.summary() },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                "From the last document you had open. Anything consistently over 16ms is a " +
                    "dropped frame.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            TextButton(onClick = { RenderStats.reset(); tick++ }) { Text("Reset counters") }
        }
    }

    // ---- activity log ----
    val counts = remember(tick) { EventLog.counts() }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Activity log  (" + counts.third + " errors, " + counts.second +
                " warnings, " + counts.first + " info)",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { showAllEvents = !showAllEvents }) {
            Text(if (showAllEvents) "Problems only" else "Show all")
        }
    }

    val events = remember(tick, showAllEvents) {
        if (showAllEvents) EventLog.recent(80)
        else EventLog.recentOfAtLeast(EventLog.Level.WARN, 80)
    }
    if (events.isEmpty()) {
        Text(
            if (showAllEvents) "Nothing recorded yet." else "No warnings or errors recorded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    } else {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column(Modifier.padding(10.dp)) {
                events.forEach { e ->
                    Text(
                        e.format(),
                        style = MaterialTheme.typography.labelSmall,
                        color = when (e.level) {
                            EventLog.Level.ERROR -> MaterialTheme.colorScheme.error
                            EventLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
    Row(Modifier.padding(horizontal = 12.dp)) {
        TextButton(onClick = { tick++; crashes = EventLog.crashReports() }) { Text("Refresh") }
        TextButton(onClick = { EventLog.clear(); tick++ }) { Text("Clear log") }
    }

    // ---- crash reports ----
    Text(
        "Crash reports",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp)
    )
    if (crashes.isEmpty()) {
        Text(
            "No crashes recorded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    } else {
        crashes.forEach { f ->
            Card(
                onClick = { viewingCrash = runCatching { f.readText() }.getOrNull() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(f.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        runCatching {
                            f.readText().lineSequence().firstOrNull {
                                it.contains("Exception") || it.contains("Error")
                            }?.trim().orEmpty()
                        }.getOrDefault(""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        TextButton(
            onClick = { EventLog.clearCrashReports(); crashes = EventLog.crashReports() },
            modifier = Modifier.padding(horizontal = 12.dp)
        ) { Text("Clear crash reports") }
    }

    Text(
        "Logs are also written to " + EventLog.fileLocation().parent + ".",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )

    viewingCrash?.let { text ->
        AlertDialog(
            onDismissRequest = { viewingCrash = null },
            title = { Text("Crash report") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(onClick = { viewingCrash = null }) { Text("Close") } }
        )
    }
}

// ---- updates -----------------------------------------------------------------

/** Where the update section currently is. Only one of these is on screen at once. */
private sealed interface Phase {
    data object Idle : Phase
    data object Checking : Phase
    data class UpToDate(val version: String) : Phase
    data class Found(val result: UpdateCheck.Result) : Phase
    data class Downloading(val progress: Float) : Phase
    data class Downloaded(val installer: File, val version: String) : Phase
    data class Failed(val message: String) : Phase
}

/**
 * "Check for updates", and the few steps that follow it.
 *
 * One button at a time, exactly as on Android and for the same reason: each step can fail for a
 * reason the person needs to read - no network, no release published yet, or an installer Windows
 * declines to run. Doing all three behind one button turns any of those into a spinner that stops.
 *
 * The version comparison and the download come from `:core`, unchanged. All this build does
 * differently is ask for the `.msi` instead of the `.apk`, and hand the file to Windows rather
 * than to Android's package installer.
 */
@Composable
private fun UpdateSection() {
    val scope = rememberCoroutineScope()
    val installed = remember { DesktopUpdates.installedVersion() }
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }

    // A download running when this screen goes away has nowhere to report to, so it is asked to
    // stop rather than left writing to a folder nobody will look at.
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { alive = false } }

    SectionHeader("Updates")
    Text(
        "InkSlate is installed by hand, so it cannot update itself in the background. This asks " +
            "GitHub whether a newer build has been published.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    Text(
        "Installed version $installed",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp)
    )

    var testBuilds by remember { mutableStateOf(DesktopUpdates.testChannelEnabled()) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text("Test builds", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Offers the build from the newest change as well as the published releases. It " +
                    "has had less use than a release.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = testBuilds,
            onCheckedChange = {
                testBuilds = it
                DesktopUpdates.setTestChannel(it)
                // The last answer was for the other channel, so it is no longer an answer.
                phase = Phase.Idle
            }
        )
    }

    when (val current = phase) {
        is Phase.Idle -> TextButton(
            onClick = {
                phase = Phase.Checking
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        UpdateCheck.check(installed, UpdateCheck.Platform.WINDOWS, DesktopUpdates.channel())
                    }
                    phase = when (result) {
                        is UpdateCheck.Result.UpToDate -> Phase.UpToDate(result.installed.toString())
                        is UpdateCheck.Result.Failed -> Phase.Failed(result.message)
                        else -> Phase.Found(result)
                    }
                }
            },
            modifier = Modifier.padding(horizontal = 12.dp)
        ) { Text("Check for updates") }

        is Phase.Checking -> Busy("Asking GitHub...")

        is Phase.UpToDate -> Column {
            Text(
                "This is the newest published build.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            TextButton(
                onClick = { phase = Phase.Idle },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) { Text("Check again") }
        }

        is Phase.Found -> when (val found = current.result) {
            is UpdateCheck.Result.Available -> Column {
                Text(
                    "Version ${found.release.version} is available.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                )
                Text(
                    "${found.download.name}  ·  " +
                        "${found.download.sizeBytes / (1024 * 1024)} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )
                TextButton(
                    onClick = {
                        phase = Phase.Downloading(-1f)
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    UpdateCheck.download(
                                        asset = found.download,
                                        destination = File(
                                            DesktopUpdates.downloadDir(), found.download.name
                                        ),
                                        shouldContinue = { alive },
                                        onProgress = { p -> phase = Phase.Downloading(p) }
                                    )
                                }
                            }.fold(
                                onSuccess = {
                                    phase = Phase.Downloaded(
                                        it, found.release.version.toString()
                                    )
                                },
                                onFailure = {
                                    phase = Phase.Failed(it.message ?: "The download failed.")
                                }
                            )
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) { Text("Download the installer") }
            }

            is UpdateCheck.Result.AvailableWithoutDownload -> Column {
                Text(
                    "Version ${found.release.version} is published, but that release does not " +
                        "carry a Windows installer.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                )
                TextButton(
                    onClick = { phase = Phase.Idle },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) { Text("Check again") }
            }

            else -> TextButton(
                onClick = { phase = Phase.Idle },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) { Text("Check again") }
        }

        is Phase.Downloading ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("Downloading...", style = MaterialTheme.typography.bodyMedium)
                if (current.progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { current.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }

        is Phase.Downloaded -> Column {
            Text(
                "Version ${current.version} has been downloaded.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            Text(
                "Opening it hands the installer to Windows, which will ask for its own " +
                    "confirmation. Close InkSlate first: an installer cannot replace files the " +
                    "running application still has open.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Row {
                TextButton(
                    onClick = {
                        runCatching { DesktopUpdates.open(current.installer) }.onFailure {
                            phase = Phase.Failed(
                                it.message ?: "Windows would not open the installer."
                            )
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) { Text("Open the installer") }
                TextButton(
                    onClick = {
                        runCatching {
                            DesktopUpdates.reveal(current.installer)
                        }
                    }
                ) { Text("Show in Explorer") }
            }
        }

        is Phase.Failed -> Column {
            Text(
                current.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            TextButton(
                onClick = { phase = Phase.Idle },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) { Text("Try again") }
        }
    }
}

@Composable
private fun Busy(label: String) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// Shared by every heading on this screen, so they are styled once - and styled the same as the
// Android screen's, which is the whole point.
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
    )
}
