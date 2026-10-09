package com.inkslate.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
     * How music pages are coloured (Night, Sepia...) - one choice for every song in every tab,
     * kept by InkSheets, rather than each editor's own that reset with every song.
     */
    val readingMode = androidx.compose.runtime.mutableStateOf(com.inkslate.core.ReadingMode.NONE)

    /**
     * Open a whole setlist as tabs, in order, each named by its song, with [focus] in front; and
     * put them all away again. Set by the workspace; used by InkSheets.
     */
    var openSet: ((parts: List<Pair<File, String>>, focus: Int) -> Unit)? = null
    var closeSet: (() -> Unit)? = null

    /** The tab showing the first file shows the second instead, in the same place in the row. */
    var swapTab: ((old: File, new: File) -> Unit)? = null

    /**
     * Bring the open tab of [file] to the front, as a turn to the next song in a set does. False
     * when no tab has it, and the set has to be opened again instead.
     */
    var focusFile: ((file: File) -> Boolean)? = null

    /** Told when Home is pressed in the tab row, before Home shows. */
    var onHome: (() -> Unit)? = null

    /** What an app built on this one adds at the top of Settings - InkSheets' one-off imports. */
    var settingsSection: (@Composable () -> Unit)? = null

    /**
     * Whether the window should cover the whole screen - taskbar and title bar too. Music does
     * whenever a song is in front; InkSlate does in its fullscreen mode.
     */
    var windowFullscreen by androidx.compose.runtime.mutableStateOf(false)

    /** The window covers the whole screen all the time, Home included - InkSheets on a music stand. */
    var alwaysFullscreen: Boolean = false

    /**
     * Extra actions in a document's Pages panel, for the pages picked there (0-based, in the
     * document as it is on disk). InkSheets makes a part, or a new song, of some pages.
     */
    var pagesActions: (@Composable (path: String, pages: List<Int>, close: () -> Unit) -> Unit)? = null

    /** Keep the open tabs, in order, as a setlist; null where the app has none. */
    var onSaveTabs: ((List<java.io.File>) -> Unit)? = null

    /** Told whether Home is what is on screen, whenever that changes. */
    var onHomeShown: ((Boolean) -> Unit)? = null

    /** Files dropped on the window; null where the app does nothing with them. */
    var onFilesDropped: ((List<File>) -> Unit)? = null

    /** The song in front fading out before another comes in (music): 1 shown, 0 gone. */
    var songShown by androidx.compose.runtime.mutableStateOf(1f)

    /** Close the app, and put its window away; set once the window is up. */
    var quit: (() -> Unit)? = null
    var minimise: (() -> Unit)? = null

    /**
     * For an app that covers the whole screen ([alwaysFullscreen]): shown in an ordinary window
     * instead, moved and sized by hand. Remembered.
     */
    var windowed by androidx.compose.runtime.mutableStateOf(DesktopPrefs.get(K_WINDOWED) == "true")
        private set

    fun chooseWindowed(on: Boolean) {
        windowed = on
        DesktopPrefs.put(K_WINDOWED, on.toString())
    }

    /** The screens there are, by name ("Screen 2 - 2560 x 1440"); more than one to choose between. */
    fun screens(): List<String> = WindowMemory.screenNames()

    /** Move the window to screen [index] of [screens]: covering it, or in the middle of it. */
    var moveToScreen: ((Int) -> Unit)? = null

    private const val K_WINDOWED = "window/windowed"

    /** The action strip over music sits down the left side rather than the right. */
    @Volatile
    var stripOnLeft: Boolean = false

    /** Room kept clear beside a page of music for the strip, in dp; none while it is folded away. */
    var stripLaneDp: Float = 64f

    /** The music tools' lane down the side away from the strip, while they are out; 0 when not. */
    @Volatile
    var musicLaneDp: Float = 0f

    /** How a page turn in music is shown: "slide", "fade" or "none". */
    @Volatile
    var turnAnimation: String = "slide"

    /** A finger tap at either side of a page of music turns it. */
    @Volatile
    var edgeTaps: Boolean = false

    /** Told the files in the tab row, in order, whenever a tab is dragged to a new place. */
    var onTabsMoved: ((List<File>) -> Unit)? = null

    /** Told the files of the open tabs whenever one opens or closes. */
    var onOpenTabs: ((List<File>) -> Unit)? = null

    /** Drawn over the document in front, in the corner of its pane. */
    var paneOverlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null
}
