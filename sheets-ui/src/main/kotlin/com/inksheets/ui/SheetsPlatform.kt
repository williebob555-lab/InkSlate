package com.inksheets.ui

import com.inksheets.core.Part
import com.inksheets.core.Song
import java.io.File

/**
 * What InkSheets' screens need from the system they run on.
 *
 * The screens themselves are shared source, compiled into the Android app and the desktop app
 * alike (see sheets-ui/README.md), so everything that differs between a tablet and a laptop comes
 * through here: where things are kept, how a part is opened in the editor, sound in and out.
 */
interface SheetsPlatform {

    /** A stable name for this device, for the library's per-device log. */
    val deviceId: String

    /** Somewhere to start looking for a library folder: the synced folder, or home. */
    val startFolder: File

    fun pref(key: String): String?
    fun setPref(key: String, value: String?)

    /** Open [file] (a part of [song]) in the editor, at the part's first page. */
    fun openPart(song: Song, part: Part, file: File)

    /**
     * The words on the top part of a page of [file], from its own text or from recognising a
     * scan; null when neither can be had. Called off the UI thread.
     */
    fun pageText(file: File, page: Int = 1): String?

    /**
     * The words recognised in the top part of a page of [file] (a PDF or a picture) - for scans,
     * which have no text of their own. Null where this device cannot recognise text. Slow, and
     * called off the UI thread.
     */
    fun recognise(file: File, page: Int = 1): String? = null

    /** Whether [recognise] can do anything here. */
    val canRecognise: Boolean get() = false

    /**
     * Open a MobileSheets database (`mobilesheets.db`) read-only and hand its tables over, or null
     * when it cannot be opened. The caller closes nothing: each call reads what it needs.
     */
    fun openMobileSheets(db: File): com.inksheets.core.MobileSheetsImport.Tables? = null

    /** Turn tap-the-side-to-turn-the-page on or off in the editor, where it can do that. */
    fun setEdgeTaps(on: Boolean) {}

    /** Sound out, for the metronome; null where there is none. */
    val audioOut: AudioOut?

    /** The microphone, for the tuner; null where there is none. */
    val microphone: Microphone?
}

/** A mono float output the caller keeps filled. */
interface AudioOut {
    val sampleRate: Int
    /** Start pulling samples from [fill] on an audio thread until [stop]. */
    fun start(fill: (FloatArray) -> Unit)
    fun stop()
}

/** A mono float input delivered in blocks. */
interface Microphone {
    val sampleRate: Int
    /**
     * Start delivering blocks of at least [blockSize] samples to [onBlock] on a background
     * thread. Returns false when the microphone could not be opened (or permission was refused).
     */
    fun start(blockSize: Int, onBlock: (FloatArray) -> Unit): Boolean
    fun stop()
}
