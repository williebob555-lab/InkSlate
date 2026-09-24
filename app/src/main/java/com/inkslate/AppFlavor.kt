package com.inkslate

import androidx.compose.runtime.Composable
import java.io.File

/**
 * Which application this is: InkSlate, or InkSheets built on it.
 *
 * InkSheets is InkSlate with a music library where the Home screen was - the editor, tabs, pens,
 * sync and settings are all the same code. Its flavour installs its Home here at start-up (see
 * FlavorSetup in src/inksheets); InkSlate's installs nothing.
 */
object AppFlavor {
    /** Replaces the Home screen. Given a way to open a document in a tab and to open Settings. */
    var home: (@Composable (openFile: (File) -> Unit, openSettings: () -> Unit) -> Unit)? = null

    /** The finger moves the page rather than drawing, until changed in Settings. */
    var fingerPans: Boolean = false

    /** Drawn over the document in front, in the corner of its pane. */
    var paneOverlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null
}
