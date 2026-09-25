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

    /** Drawn over the document in front, in the corner of its pane. */
    var paneOverlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null
}
