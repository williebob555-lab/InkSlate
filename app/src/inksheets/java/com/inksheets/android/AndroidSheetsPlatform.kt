package com.inksheets.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Environment
import com.inkslate.data.EventLog
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsPlatform
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID

/** The tablet's side of [SheetsPlatform]. */
class AndroidSheetsPlatform(
    private val context: Context,
    private val openFile: (File) -> Unit
) : SheetsPlatform {

    private val prefs = context.getSharedPreferences("inksheets", Context.MODE_PRIVATE)

    override val deviceId: String =
        prefs.getString(K_DEVICE, null) ?: ("tablet-" + UUID.randomUUID().toString().take(8)).also {
            prefs.edit().putString(K_DEVICE, it).apply()
        }

    override val startFolder: File =
        Environment.getExternalStorageDirectory().let { storage ->
            listOf("Sync", "Syncthing", "Music").map { File(storage, it) }.firstOrNull { it.isDirectory } ?: storage
        }

    override fun pref(key: String): String? = prefs.getString(key, null)
    override fun setPref(key: String, value: String?) = prefs.edit().putString(key, value).apply()

    override fun openPart(song: Song, part: Part, file: File) {
        if (!file.isFile) {
            EventLog.warn("sheets", "${song.title}: ${part.file} is not in the music folder on this device")
            return
        }
        openFile(file)
    }

    override fun pageText(file: File, page: Int): String? = runCatching {
        PDDocument.load(file).use { pdf ->
            if (page > pdf.numberOfPages) return null
            val text = PDFTextStripper().apply {
                startPage = page
                endPage = page
                sortByPosition = true
            }.getText(pdf)
            text.lines().filter { it.isNotBlank() }.take(15).joinToString("\n").ifBlank { null }
        }
    }.getOrNull()

    override fun setEdgeTaps(on: Boolean) {
        com.inkslate.ink.DrawingView.edgeTapTurns = on
    }

    override val canRecognise: Boolean = true

    /**
     * Recognise the words at the top of a page. The instrument is printed in the top corner of a
     * part, and reading only the top third is both faster and keeps the notes themselves (which
     * recognition reads as a spray of letters) out of the answer.
     */
    override fun recognise(file: File, page: Int): String? = runCatching {
        val bitmap = TopOfPage.render(file, page) ?: return null
        try {
            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
            )
            try {
                val result = com.google.android.gms.tasks.Tasks.await(recognizer.process(image))
                result.textBlocks.flatMap { block -> block.lines.map { it.text } }
                    .joinToString("\n").ifBlank { null }
            } finally {
                recognizer.close()
            }
        } finally {
            bitmap.recycle()
        }
    }.onFailure { EventLog.warn("sheets", "Could not read ${file.name}: ${it.message}") }.getOrNull()

    override val audioOut: AudioOut = TrackOut()
    override val microphone: Microphone = RecordMic(context)

    private companion object {
        const val K_DEVICE = "sheets_device"
    }
}

/** The metronome's output: a low-latency float AudioTrack kept full by a writer thread. */
private class TrackOut : AudioOut {
    override val sampleRate = 48_000
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(fill: (FloatArray) -> Unit) {
        stop()
        running = true
        thread = Thread({
            runCatching {
                val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(min)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
                track.play()
                val block = FloatArray(256)
                while (running) {
                    fill(block)
                    track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
                }
                track.stop()
                track.release()
            }.onFailure { EventLog.warn("sheets", "Sound out failed: ${it.message}") }
        }, "metronome").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.join(200)
        thread = null
    }
}

/** The microphone for the tuner. Asks for permission the first time; false until it is given. */
private class RecordMic(private val context: Context) : Microphone {
    override val sampleRate = 48_000
    @Volatile private var running = false
    private var record: AudioRecord? = null

    override fun start(blockSize: Int, onBlock: (FloatArray) -> Unit): Boolean {
        stop()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7301)
            return false
        }
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = runCatching {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, blockSize * 4 * 2)
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull() ?: return false
        record = rec
        running = true
        rec.startRecording()
        Thread({
            val hop = blockSize / 4
            val window = FloatArray(blockSize)
            val chunk = FloatArray(hop)
            while (running) {
                val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                System.arraycopy(window, n, window, 0, window.size - n)
                System.arraycopy(chunk, 0, window, window.size - n, n)
                onBlock(window.copyOf())
            }
        }, "tuner").apply { isDaemon = true; start() }
        return true
    }

    override fun stop() {
        running = false
        record?.let { runCatching { it.stop(); it.release() } }
        record = null
    }
}
