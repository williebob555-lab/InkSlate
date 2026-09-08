package com.inkslate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.inkslate.core.UpdateCheck
import com.inkslate.data.AppUpdates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Check for updates", and the few steps that follow it.
 *
 * The flow is deliberately one button at a time - check, then download, then install - because
 * each step can fail for a reason the person needs to read: no wifi, no release published yet,
 * or Android not yet allowing this app to install anything. Doing all three behind one tap would
 * turn any of those into a spinner that stops.
 */

/** Where the section currently is. Only one of these is on screen at once. */
private sealed interface Phase {
    data object Idle : Phase
    data object Checking : Phase
    data class UpToDate(val version: String) : Phase
    data class Found(val result: UpdateCheck.Result) : Phase
    data class Downloading(val progress: Float, val asset: UpdateCheck.Asset) : Phase
    data class Downloaded(val apk: File, val version: String) : Phase
    data class Failed(val message: String) : Phase
}

@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { AppUpdates.installedVersion(context) }
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }

    // A download running when this screen goes away has nowhere to report to, so it is asked to
    // stop rather than left writing to a cache nobody will look at.
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
                    val result = withContext(Dispatchers.IO) { AppUpdates.check(context) }
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

        is Phase.Found -> FoundRelease(
            result = current.result,
            onDownload = { asset ->
                phase = Phase.Downloading(-1f, asset)
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            AppUpdates.download(
                                context,
                                asset,
                                shouldContinue = { alive },
                                onProgress = { fraction ->
                                    // Only the newest fraction matters; this is a repaint, not a log.
                                    phase = Phase.Downloading(fraction, asset)
                                }
                            )
                        }
                    }.onSuccess { apk ->
                        val version = (current.result as? UpdateCheck.Result.Available)
                            ?.release?.version?.toString() ?: ""
                        phase = Phase.Downloaded(apk, version)
                    }.onFailure { e ->
                        if (alive) phase = Phase.Failed(e.message ?: "The download failed.")
                    }
                }
            },
            onOpenPage = { AppUpdates.openInBrowser(context, it) }
        )

        is Phase.Downloading -> Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                if (current.progress >= 0f)
                    "Downloading... ${(current.progress * 100).toInt()}%"
                else "Downloading...",
                style = MaterialTheme.typography.bodyMedium
            )
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
                "Version ${current.version} is ready to install. Android will ask you to confirm, " +
                    "and your documents are untouched by the update.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)
            )
            if (!AppUpdates.canInstall(context)) {
                Text(
                    "Android needs permission to let InkSlate install this. Turn on \"Allow from " +
                        "this source\", then come back and press Install.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp)
                )
                TextButton(
                    onClick = { context.startActivity(AppUpdates.unknownSourcesIntent(context)) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) { Text("Open that setting") }
            }
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { AppUpdates.install(context, current.apk) }) { Text("Install") }
                TextButton(onClick = { phase = Phase.Idle }) { Text("Not now") }
            }
        }

        is Phase.Failed -> Column {
            Text(
                current.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)
            )
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { phase = Phase.Idle }) { Text("Try again") }
                TextButton(
                    onClick = { AppUpdates.openInBrowser(context, UpdateCheck.RELEASES_URL) }
                ) { Text("Open releases page") }
            }
        }
    }
}

@Composable
private fun FoundRelease(
    result: UpdateCheck.Result,
    onDownload: (UpdateCheck.Asset) -> Unit,
    onOpenPage: (String) -> Unit
) {
    val release = when (result) {
        is UpdateCheck.Result.Available -> result.release
        is UpdateCheck.Result.AvailableWithoutDownload -> result.release
        else -> return
    }

    Column {
        Text(
            "${release.title} is available.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)
        )

        if (release.notes.isNotBlank()) {
            Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
                // Release notes are written by whoever cut the release and can run long, so they
                // scroll in place rather than pushing the buttons off the screen.
                Text(
                    release.notes,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                )
            }
        }

        when (result) {
            is UpdateCheck.Result.Available -> {
                val megabytes = result.download.sizeBytes / 1_048_576.0
                Row(
                    Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { onDownload(result.download) }) {
                        Text(
                            if (megabytes > 0) "Download (%.0f MB)".format(megabytes)
                            else "Download"
                        )
                    }
                    TextButton(onClick = { onOpenPage(release.pageUrl) }) { Text("View on GitHub") }
                }
            }

            is UpdateCheck.Result.AvailableWithoutDownload -> Column {
                Text(
                    "That release has no Android build attached to it yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp)
                )
                TextButton(
                    onClick = { onOpenPage(release.pageUrl) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) { Text("View on GitHub") }
            }
        }
    }
}

@Composable
private fun Busy(label: String) {
    Row(
        Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("  $label", style = MaterialTheme.typography.bodyMedium)
    }
}
