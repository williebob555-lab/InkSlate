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

    /** Open a setlist's parts as tabs, in order and named by song, with [focus] in front. */
    fun openSet(parts: List<Pair<File, String>>, focus: Int) {}

    /** Put a setlist's tabs away. */
    fun closeSet() {}

    /** Hand a file to the system to send somewhere: the share sheet, or the file manager. */
    fun share(file: File) {}

    /** Run [block] on the UI thread - for things that arrive from the network. */
    fun onMain(block: () -> Unit)

    /** What this device is called to other tablets in companion mode. */
    val deviceName: String

    /** A player for a song's recordings; null where there is none. */
    fun audioPlayer(): AudioPlayer? = null

    /** A line in the event log (Settings), for things worth keeping but not worth a pop-up. */
    fun log(message: String) {}

    /**
     * Keep the network quick while playing together - on a tablet, Wi-Fi's power saving holds
     * back what arrives for seconds at a time. Calls pair up: true, then false.
     */
    fun holdNetwork(on: Boolean) {}

    /** Where downloads land: where a download of band music is looked for first. */
    val downloadsFolder: File get() = startFolder

    /** Whether [scanQr] can use a camera here. */
    val canScanQr: Boolean get() = false

    /** Scan a QR code with the camera; [onResult] hears its text, or null if cancelled. */
    fun scanQr(onResult: (String?) -> Unit) { onResult(null) }

    /**
     * What is on the clipboard, as text: its text, or the text of a QR code in a picture on it
     * (a screenshot of another device's code, say). Null when neither.
     */
    fun readClipboard(): String? = null

    /** Put the picture in [png] on the clipboard, to paste into a message. False where that cannot be done. */
    fun copyImage(png: File): Boolean = false

    /** Hand a picture to the share sheet (or show it in the file manager). */
    fun shareImage(png: File) = share(png)

    /** Whether [scanPages] can photograph pages here. */
    val canScanPages: Boolean get() = false

    /**
     * Photograph pages with the camera - the edges of each found and the page straightened - and
     * hand back one PDF of them, or null if cancelled. On the UI thread.
     */
    fun scanPages(onResult: (File?) -> Unit) { onResult(null) }

    /** Write ARGB pixels as a PNG file; false where that cannot be done. */
    fun writePng(width: Int, height: Int, argb: IntArray, to: File): Boolean = false

    /** Somewhere for files made only to be copied or shared, like a QR code's picture. */
    val cacheFolder: File get() = File(System.getProperty("java.io.tmpdir"))

    /** Whether the app can be closed from its own menu (a window with no title bar to close it by). */
    val canQuit: Boolean get() = false
    fun quit() {}
}

/**
 * Plays one recording at a time, slower or faster without changing pitch, shifted in pitch, and
 * round an A-B loop. [load] is slow and runs off the UI thread; the rest are quick.
 */
interface AudioPlayer {
    fun load(file: File): Boolean
    fun play()
    fun pause()
    val playing: Boolean
    fun seek(ms: Long)
    val positionMs: Long
    val durationMs: Long
    var speed: Double
    var pitch: Int
    /** Loop between two places; nulls for no loop. */
    fun setLoop(startMs: Long?, endMs: Long?)
    fun release()
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
     * Start delivering the sound as it arrives, in short chunks, to [onChunk] on a background
     * thread - the tuner windows them, a recording writes them out. Returns false when the
     * microphone could not be opened (or permission was refused).
     */
    fun start(onChunk: (FloatArray) -> Unit): Boolean
    fun stop()
}
