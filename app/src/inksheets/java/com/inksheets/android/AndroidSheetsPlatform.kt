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

    override fun openMobileSheets(db: File): com.inksheets.core.MobileSheetsImport.Tables? = runCatching {
        android.database.sqlite.SQLiteDatabase.openDatabase(db.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).close()
        com.inksheets.core.MobileSheetsImport.Tables { table ->
            runCatching {
                android.database.sqlite.SQLiteDatabase.openDatabase(db.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { sql ->
                    sql.rawQuery("SELECT * FROM \"" + table.replace("\"", "") + "\"", null).use { c ->
                        val rows = ArrayList<Map<String, Any?>>()
                        while (c.moveToNext()) {
                            rows += (0 until c.columnCount).associate { i ->
                                c.getColumnName(i) to when (c.getType(i)) {
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                    android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                    android.database.Cursor.FIELD_TYPE_STRING -> c.getString(i)
                                    else -> null
                                }
                            }
                        }
                        rows
                    }
                }
            }.getOrDefault(emptyList())
        }
    }.onFailure { EventLog.warn("sheets", "Could not open ${db.name}: ${it.message}") }.getOrNull()

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

    override fun audioPlayer(): com.inksheets.ui.AudioPlayer = MediaAudioPlayer()

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    override fun onMain(block: () -> Unit) { main.post(block) }

    override val deviceName: String =
        (android.provider.Settings.Global.getString(context.contentResolver, "device_name")
            ?: android.os.Build.MODEL).ifBlank { "Tablet" }

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

    override fun start(onChunk: (FloatArray) -> Unit): Boolean {
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
                AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, sampleRate / 5 * 4)
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull() ?: return false
        record = rec
        running = true
        rec.startRecording()
        Thread({
            val chunk = FloatArray(sampleRate / 100)       // 10 ms at a time
            while (running) {
                val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                onChunk(chunk.copyOf(n))
            }
        }, "microphone").apply { isDaemon = true; start() }
        return true
    }

    override fun stop() {
        running = false
        record?.let { runCatching { it.stop(); it.release() } }
        record = null
    }
}
