package com.inkslate.desktop

import androidx.compose.runtime.Composable
import java.io.File

/**
 * Which application this is: InkSlate, or an app built on it.
 *
 * InkSheets is InkSlate with a music library where the Home screen was. Everything else - the
 * editor, the tabs, pens, sync, settings - is the same code, so the one thing it changes is set
 * here before the window opens: its name (which also names its own settings folder, so the two
 * apps never share preferences) and what the Home tab shows.
 */
object AppFlavor {

    /** Shown in the title bar and used for the app's own folder; set before anything else runs. */
    val name: String
        get() = System.getProperty("inkslate.appName")?.takeIf { it.isNotBlank() } ?: "InkSlate"

    /** Replaces the Home screen. Given a way to open a document in a tab and to open Settings. */
    var home: (@Composable (openFile: (File) -> Unit, openSettings: () -> Unit) -> Unit)? = null
}
