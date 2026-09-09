package com.inkslate.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.inkslate.core.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings.
 *
 * The Android screen's headings, carrying the sections that mean something on Windows today.
 * Saving rules, the stylus profile and the render diagnostics belong with the parts of the editor
 * they configure, and follow when those arrive - a settings screen full of switches that control
 * nothing is worse than a short one.
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
            DeviceSection()
            HorizontalDivider(Modifier.padding(top = 14.dp))
            StorageSection()
            HorizontalDivider(Modifier.padding(top = 14.dp))
            UpdateSection()
        }
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

    when (val current = phase) {
        is Phase.Idle -> TextButton(
            onClick = {
                phase = Phase.Checking
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        UpdateCheck.check(installed, UpdateCheck.Platform.WINDOWS)
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
