package com.inkslate.core

/**
 * Things a pedal, a key or an on-screen action button can do while reading or performing.
 *
 * A Bluetooth page-turn pedal is a keyboard as far as either system is concerned - it sends Page
 * Down, an arrow, or a media key - so pedals, keys and buttons all come down to one of these, and
 * each platform only has to say which key is which.
 */
enum class PerformAction(val label: String) {
    NEXT_PAGE("Next page"),
    PREVIOUS_PAGE("Previous page"),
    HALF_PAGE_FORWARD("Half a page on"),
    HALF_PAGE_BACK("Half a page back"),
    FIRST_PAGE("First page"),
    LAST_PAGE("Last page"),
    NEXT_SONG("Next song in the setlist"),
    PREVIOUS_SONG("Previous song in the setlist"),
    METRONOME("Start or stop the metronome"),
    TUNER("Open the tuner");

    val turnsPages: Boolean get() = ordinal <= LAST_PAGE.ordinal
}

/**
 * Where performance actions go.
 *
 * Page actions go to the document in front ([document], set by whichever editor has focus);
 * song, metronome and tuner actions go to the app ([app], set by InkSheets - InkSlate has no
 * setlists). A page turn the document cannot make - the last page going forward, the first going
 * back - is passed on as a song turn, which is what a pedal under a musician's foot should do at
 * the end of a piece.
 */
object Perform {

    /** Returns false when it could not do it (already on the last page, say). */
    @Volatile
    var document: ((PerformAction) -> Boolean)? = null

    @Volatile
    var app: ((PerformAction) -> Boolean)? = null

    fun run(action: PerformAction): Boolean {
        if (!action.turnsPages) return app?.invoke(action) ?: false
        if (document?.invoke(action) == true) return true
        return when (action) {
            PerformAction.NEXT_PAGE, PerformAction.HALF_PAGE_FORWARD -> app?.invoke(PerformAction.NEXT_SONG) ?: false
            PerformAction.PREVIOUS_PAGE, PerformAction.HALF_PAGE_BACK -> app?.invoke(PerformAction.PREVIOUS_SONG) ?: false
            else -> false
        }
    }
}
