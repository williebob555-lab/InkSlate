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

    /**
     * Music, not homework: a document opens with the bars hidden (the tab row stays, and the
     * strip over the page brings the tools back), one page at a time, the whole page fitted to the
     * screen and centred - and kept that way as pages turn and the window changes size.
     */
    var musicView: Boolean = false

    /**
     * Open a whole setlist as tabs, in order, each named by its song, with [focus] in front; and
     * put them all away again. Set by the workspace; used by InkSheets.
     */
    var openSet: ((parts: List<Pair<File, String>>, focus: Int) -> Unit)? = null
    var closeSet: (() -> Unit)? = null

    /** Told when Home is pressed in the tab row, before Home shows. */
    var onHome: (() -> Unit)? = null

    /** What an app built on this one adds at the top of Settings - InkSheets' one-off imports. */
    var settingsSection: (@Composable () -> Unit)? = null

    /** Told the files in the tab row, in order, whenever a tab is dragged to a new place. */
    var onTabsMoved: ((List<File>) -> Unit)? = null

    /** Drawn over the document in front, in the corner of its pane. */
    var paneOverlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null

    /** Told whether Home is what is on screen, whenever that changes. */
    var onHomeShown: ((Boolean) -> Unit)? = null

    /** A link for the app itself arrived (a join code scanned with the camera app). */
    var onLink: ((String) -> Unit)? = null
}
