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

    /** The share sheet: email, a messaging app, Drive, Nearby Share - whatever the tablet has. */
    override fun share(file: File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(send, file.name).apply {
                if (context !is android.app.Activity) addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { EventLog.warn("sheets", "Could not share ${file.name}: ${it.message}") }
    }

    override fun openSet(parts: List<Pair<File, String>>, focus: Int) {
        com.inkslate.AppFlavor.openSet?.invoke(parts, focus) ?: parts.getOrNull(focus)?.let { openFile(it.first) }
    }

    override fun closeSet() {
        com.inkslate.AppFlavor.closeSet?.invoke()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    override fun onMain(block: () -> Unit) { main.post(block) }

    override val deviceName: String =
        (android.provider.Settings.Global.getString(context.contentResolver, "device_name")
            ?: android.os.Build.MODEL).ifBlank { "Tablet" }

    override fun log(message: String) = EventLog.info("sheets", message)

    override val downloadsFolder: File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).takeIf { it.isDirectory } ?: startFolder

    override val cacheFolder: File get() = context.cacheDir

    /** Google's code scanner: its own camera screen, no camera permission for this app to ask. */
    override val canScanQr: Boolean = true

    override fun scanQr(onResult: (String?) -> Unit) = com.inkslate.ui.QrBits.scan(context, onResult)

    /** The clipboard's text - or a QR code in a picture copied from a chat. */
    override fun readClipboard(): String? = com.inkslate.ui.QrBits.readClipboard(context)

    override fun copyImage(png: File): Boolean = runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", png)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, "InkSheets join code", uri))
        true
    }.onFailure { EventLog.warn("sheets", "Could not copy the picture: ${it.message}") }.getOrDefault(false)

    override fun shareImage(png: File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", png)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(send, "Send the join code").apply {
                if (context !is Activity) addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { EventLog.warn("sheets", "Could not share the picture: ${it.message}") }
    }

    override fun writePng(width: Int, height: Int, argb: IntArray, to: File): Boolean = runCatching {
        val bitmap = android.graphics.Bitmap.createBitmap(argb, width, height, android.graphics.Bitmap.Config.ARGB_8888)
        to.parentFile?.mkdirs()
        to.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        true
    }.getOrDefault(false)

    /** Google's document scanner: finds each page's edges, straightens it, and hands back a PDF. */
    override val canScanPages: Boolean get() = context is androidx.activity.ComponentActivity

    override fun scanPages(onResult: (File?) -> Unit) {
        val activity = context as? androidx.activity.ComponentActivity ?: return onResult(null)
        runCatching {
            val options = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            val scanner = com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(options)
            var launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>? = null
            launcher = activity.activityResultRegistry.register(
                "inksheets-scan-" + System.nanoTime(),
                androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                launcher?.unregister()
                val scan = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                val pdf = scan?.pdf?.uri
                if (result.resultCode != Activity.RESULT_OK || pdf == null) return@register onResult(null)
                val out = File(context.cacheDir, "scan-${System.currentTimeMillis()}.pdf")
                val copied = runCatching {
                    context.contentResolver.openInputStream(pdf)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                    out.isFile && out.length() > 0
                }.getOrDefault(false)
                onResult(if (copied) out else null)
            }
            scanner.getStartScanIntent(activity)
                .addOnSuccessListener { sender -> launcher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) }
                .addOnFailureListener {
                    EventLog.warn("sheets", "Scanner unavailable: ${it.message}")
                    launcher.unregister()
                    onResult(null)
                }
        }.onFailure { EventLog.warn("sheets", "Scanner failed: ${it.message}"); onResult(null) }
    }

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
