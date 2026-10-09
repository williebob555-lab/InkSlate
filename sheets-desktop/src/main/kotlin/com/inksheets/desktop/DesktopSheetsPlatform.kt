package com.inksheets.desktop

import com.inkslate.desktop.DesktopPrefs
import com.inkslate.desktop.EventLog
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsPlatform
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/** The desktop's side of [SheetsPlatform]: Java Sound for the metronome and tuner, PDFBox for text. */
class DesktopSheetsPlatform(private val openFile: (File) -> Unit) : SheetsPlatform {
    init {
        // The laptop draws two of the reader's pages side by side (the page ahead while this one is read); a tablet, one.
        com.inkslate.core.RenderGate.readerRenders = if (com.inksheets.core.omr.Workers.roomy) 2 else 1
    }


    override val deviceId: String =
        DesktopPrefs.get(K_DEVICE) ?: ("desktop-" + UUID.randomUUID().toString().take(8)).also {
            DesktopPrefs.put(K_DEVICE, it)
        }

    override val startFolder: File =
        File(System.getProperty("user.home")).let { home ->
            listOf("Sync", "Syncthing", "Music").map { File(home, it) }.firstOrNull { it.isDirectory } ?: home
        }

    override fun pref(key: String): String? = DesktopPrefs.get(key)
    override fun setPref(key: String, value: String?) = DesktopPrefs.put(key, value)

    override fun openPart(song: Song, part: Part, file: File) {
        if (!file.isFile) {
            EventLog.warn("sheets", "${song.title}: ${part.file} is not in the music folder on this device")
            return
        }
        openFile(file)
    }

    /**
     * The first lines of text on a page - where a part's instrument is printed. A scan has no
     * text of its own; for those see [recognise].
     */
    override fun pageCount(file: File): Int? = DesktopPages.count(file)

    override fun pageText(file: File, page: Int): String? = runCatching {
        Loader.loadPDF(file).use { pdf ->
            if (page > pdf.numberOfPages) return null
            val text = PDFTextStripper().apply {
                startPage = page
                endPage = page
                sortByPosition = true
            }.getText(pdf)
            text.lines().filter { it.isNotBlank() }.take(15).joinToString("\n").ifBlank { null }
        }
    }.getOrNull()

    /**
     * Tesseract, where it is installed: `sudo dnf install tesseract` on Fedora, the UB Mannheim
     * installer on Windows. Nothing is bundled - where it is missing, scans are left for a device
     * that can read them (the tablet always can), and what it finds reaches here through the
     * synced library.
     */
    private val tesseract: String? by lazy {
        val names = if (System.getProperty("os.name").startsWith("Windows")) listOf("tesseract.exe") else listOf("tesseract")
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .flatMap { dir -> names.map { File(dir, it) } }
        val usual = listOf(
            File("C:/Program Files/Tesseract-OCR/tesseract.exe"),
            File("/usr/bin/tesseract"),
            File("/usr/local/bin/tesseract")
        )
        (onPath + usual).firstOrNull { it.canExecute() }?.absolutePath
    }

    override val canRecognise: Boolean get() = tesseract != null

    override fun recognise(file: File, page: Int): String? {
        val exe = tesseract ?: return null
        val image = TopOfPage.render(file, page) ?: return null
        val png = File.createTempFile("inksheets-ocr", ".png")
        return try {
            javax.imageio.ImageIO.write(image, "png", png)
            val process = ProcessBuilder(exe, png.absolutePath, "stdout", "--psm", "3")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val text = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else text.lines().filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }
        } catch (e: Exception) {
            EventLog.warn("sheets", "Could not read ${file.name}: ${e.message}")
            null
        } finally {
            png.delete()
        }
    }

    override fun openMobileSheets(db: File): com.inksheets.core.MobileSheetsImport.Tables? = runCatching {
        Class.forName("org.sqlite.JDBC")
        val url = "jdbc:sqlite:file:" + db.absolutePath.replace(File.separatorChar, '/') + "?mode=ro"
        java.sql.DriverManager.getConnection(url).close()
        com.inksheets.core.MobileSheetsImport.Tables { table ->
            runCatching {
                java.sql.DriverManager.getConnection(url).use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT * FROM \"" + table.replace("\"", "") + "\"").use { rs ->
                            val meta = rs.metaData
                            val rows = ArrayList<Map<String, Any?>>()
                            while (rs.next()) {
                                rows += (1..meta.columnCount).associate { meta.getColumnName(it) to rs.getObject(it) }
                            }
                            rows
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }.onFailure { EventLog.warn("sheets", "Could not open ${db.name}: ${it.message}") }.getOrNull()

    override val audioOut: AudioOut = JavaSoundOut()
    override val microphone: Microphone = JavaSoundMic()

    override val remoteBluetooth: com.inksheets.core.RemoteBluetooth? by lazy { DesktopRemoteBluetooth.forThisSystem() }

    override val controllers: com.inksheets.core.ControllerInput by lazy { com.inksheets.core.AllControllers(listOf(DesktopMidiInput(), DesktopPodGoInput())) }

    override fun audioPlayer(): com.inksheets.ui.AudioPlayer = JavaSoundPlayer()

    override fun decodeAudio(file: File, onChunk: (FloatArray, Int) -> Unit): Boolean = runCatching {
        AudioFiles.open(file).use { raw ->
            val src = raw.format
            val rate = src.sampleRate.takeIf { it > 0 } ?: 44_100f
            val ch = src.channels.coerceAtLeast(1)
            val pcm = javax.sound.sampled.AudioFormat(javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED, rate, 16, ch, ch * 2, rate, false)
            javax.sound.sampled.AudioSystem.getAudioInputStream(pcm, raw).use { d ->
                val bytes = ByteArray(ch * 2 * 8192)
                while (true) {
                    var got = 0
                    while (got < bytes.size) { val r = d.read(bytes, got, bytes.size - got); if (r <= 0) break; got += r }
                    val frames = got / (2 * ch)
                    if (frames == 0) break
                    onChunk(FloatArray(frames) { f ->
                        var s = 0f
                        for (c in 0 until ch) {
                            val at = (f * ch + c) * 2
                            s += ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort() / 32768f
                        }
                        s / ch
                    }, rate.toInt())
                    if (got < bytes.size) break
                }
            }
        }
        true
    }.onFailure { EventLog.warn("sheets", "Could not read ${file.name} to follow it: ${it.message}") }.getOrDefault(false)

    override fun onMain(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)

    override fun setEdgeTaps(on: Boolean) {
        com.inkslate.desktop.AppFlavor.edgeTaps = on
    }

    // The page is fitted beside the strip: a lane that opens, closes or changes side fits it again.
    override fun setStripSide(left: Boolean) {
        if (com.inkslate.desktop.AppFlavor.stripOnLeft == left) return
        com.inkslate.desktop.AppFlavor.stripOnLeft = left
        com.inkslate.core.Perform.recentre?.invoke()
    }

    override fun setMusicLane(open: Boolean) {
        com.inkslate.desktop.AppFlavor.musicLaneDp = if (open) 72f else 0f
    }

    override var readingMode: com.inkslate.core.ReadingMode
        get() = com.inkslate.desktop.AppFlavor.readingMode.value
        set(v) { com.inkslate.desktop.AppFlavor.readingMode.value = v }

    override fun setStripLane(open: Boolean) {
        val lane = if (open) 64f else 0f
        if (com.inkslate.desktop.AppFlavor.stripLaneDp == lane) return
        com.inkslate.desktop.AppFlavor.stripLaneDp = lane
        com.inkslate.core.Perform.recentre?.invoke()
    }

    override fun setTurnStyle(style: String) {
        com.inkslate.desktop.AppFlavor.turnAnimation = style
    }

    override fun openSet(parts: List<Pair<File, String>>, focus: Int) {
        com.inkslate.desktop.AppFlavor.openSet?.invoke(parts, focus) ?: parts.getOrNull(focus)?.let { openFile(it.first) }
    }

    override fun focusSetTab(file: File): Boolean = com.inkslate.desktop.AppFlavor.focusFile?.invoke(file) ?: false

    override fun closeSet() {
        com.inkslate.desktop.AppFlavor.closeSet?.invoke()
    }

    override fun peek(file: File): com.inksheets.ui.PagePeek? {
        val source = com.inkslate.desktop.DesktopSources.open(file, detached = true) ?: return null
        return object : com.inksheets.ui.PagePeek {
            override val pageCount = source.pageCount
            override fun render(index: Int, widthPx: Int) = runCatching { source.render(index, widthPx) }.getOrNull()
            override fun printed(index: Int) = if (file.extension.equals("pdf", true)) PdfPrinted.read(file, index) else null
            override fun close() = source.close()
        }
    }

    override fun swapPart(old: File, new: File) {
        com.inkslate.desktop.AppFlavor.swapTab?.invoke(old, new) ?: openFile(new)
    }

    override fun share(file: File) {
        runCatching { com.inkslate.desktop.SystemShell.reveal(file) }
    }

    override val deviceName: String =
        runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Laptop"

    override fun log(message: String) = EventLog.info("sheets", message)
    override val localFolder: File get() = com.inkslate.desktop.AppDirs.dir("library")

    override val downloadsFolder: File =
        File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory } ?: startFolder

    override fun readClipboard(): String? = com.inkslate.desktop.ClipboardQr.read()
    override fun copyImage(png: File): Boolean = com.inkslate.desktop.ClipboardQr.copyImage(png)
    override fun writePng(width: Int, height: Int, argb: IntArray, to: File): Boolean =
        com.inkslate.desktop.ClipboardQr.writePng(width, height, argb, to)

    override fun pickFiles(onResult: (List<File>) -> Unit) {
        if (com.inkslate.desktop.WindowsFileDialog.available) {
            val music = (com.inksheets.core.LibraryScan.MUSIC + com.inksheets.core.LibraryScan.SOUND + "zip")
            Thread({
                val files = com.inkslate.desktop.WindowsFileDialog.files(
                    "Add music", downloadsFolder,
                    listOf("Music, recordings and zips" to music.joinToString(";") { "*.$it" }, "All files" to "*.*"),
                    many = true
                )
                onMain { onResult(files) }
            }, "add-music").apply { isDaemon = true; start() }
            return
        }
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Add music", java.awt.FileDialog.LOAD).apply {
            isMultipleMode = true
            directory = downloadsFolder.absolutePath
        }
        dialog.isVisible = true
        onResult(dialog.files.orEmpty().toList())
    }

    override val canQuit: Boolean get() = com.inkslate.desktop.AppFlavor.quit != null
    override val canWindow: Boolean get() = true
    override val windowed: Boolean get() = com.inkslate.desktop.AppFlavor.windowed
    override fun setWindowed(on: Boolean) = com.inkslate.desktop.AppFlavor.chooseWindowed(on)
    override fun screens(): List<String> = com.inkslate.desktop.AppFlavor.screens()
    override fun moveToScreen(index: Int) { com.inkslate.desktop.AppFlavor.moveToScreen?.invoke(index) }
    override fun quit() { com.inkslate.desktop.AppFlavor.quit?.invoke() }
    override fun minimise() { com.inkslate.desktop.AppFlavor.minimise?.invoke() }

    private companion object {
        const val K_DEVICE = "sheets_device"
    }
}

/**
 * Sound out through Java Sound. The line's buffer is kept short - about 20 ms - because the
 * metronome writes its clicks into the samples themselves, so the only delay is how far ahead
 * the line is filled.
 */
private class JavaSoundOut : AudioOut {
    override val sampleRate = 48_000
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false

    override fun start(fill: (FloatArray) -> Unit) {
        stop()
        running = true
        thread = Thread({
            runCatching {
                val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
                // 100 ms held in the card's buffer: a pause elsewhere (a page drawn, memory tidied, a busy
                // machine) no longer cuts the sound. (The instrument is also rendered ahead of this: see Sound.)
                val line = OutLine(format, sampleRate / 10 * 2)
                val block = FloatArray(480)
                val bytes = ByteArray(block.size * 2)
                while (running) {
                    fill(block)
                    for (i in block.indices) {
                        val v = (block[i].coerceIn(-1f, 1f) * 32767).toInt()
                        bytes[2 * i] = v.toByte()
                        bytes[2 * i + 1] = (v shr 8).toByte()
                    }
                    line.write(bytes, bytes.size)
                }
                line.close()
            }.onFailure { EventLog.warn("sheets", "Sound out failed: ${it.message}") }
        }, "metronome").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    }

    override fun stop() {
        running = false
        thread?.join(200)
        thread = null
    }
}

/**
 * A microphone through Java Sound, delivered as it arrives: the one chosen in Settings, else the
 * system's own. An input that gives nothing but digital silence (a headset's unplugged microphone
 * left as the default, a muted interface) is passed over for the loudest one that hears.
 */
private class JavaSoundMic : Microphone {
    override val sampleRate = 48_000
    @Volatile private var line: TargetDataLine? = null
    @Volatile private var running = false
    @Volatile override var inUse: String? = null
        private set

    private val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)

    /** Every input that can be opened as a microphone, the system's own first. */
    private fun inputs(): List<javax.sound.sampled.Mixer.Info> = AudioSystem.getMixerInfo().filter { info ->
        runCatching { AudioSystem.getMixer(info).isLineSupported(javax.sound.sampled.DataLine.Info(TargetDataLine::class.java, format)) }.getOrDefault(false)
    }

    override val devices: List<String> get() = inputs().map { it.name }.filter { it != SYSTEM }

    override var device: String?
        get() = DesktopPrefs.get(K_MIC)
        set(value) = DesktopPrefs.put(K_MIC, value)

    private fun open(info: javax.sound.sampled.Mixer.Info?): TargetDataLine? = runCatching {
        (if (info == null) AudioSystem.getTargetDataLine(format) else AudioSystem.getTargetDataLine(format, info)).apply { open(format); start() }
    }.onFailure { EventLog.warn("sheets", "Microphone ${info?.name ?: "(the system's)"} failed: ${it.message}") }.getOrNull()

    /** The loudest sound in [ms] from [l]; -1 if it could not be read. */
    private fun peak(l: TargetDataLine, ms: Int): Int {
        val bytes = ByteArray(sampleRate * ms / 1000 * 2)
        var got = 0
        val until = System.currentTimeMillis() + ms + 500
        while (got < bytes.size && System.currentTimeMillis() < until) { val n = l.read(bytes, got, bytes.size - got); if (n > 0) got += n else if (n < 0) return -1 }
        var peak = 0
        for (i in 0 until got / 2) peak = maxOf(peak, kotlin.math.abs(((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort().toInt()))
        return peak
    }

    /** The input that hears, trying each a moment - a microphone before a line in, then the loudest; null when none hears. */
    private fun loudest(except: String?): Pair<javax.sound.sampled.Mixer.Info, Int>? =
        inputs().filter { it.name != except && it.name != SYSTEM }.mapNotNull { info ->
            val l = open(info) ?: return@mapNotNull null
            val p = try { peak(l, 400) } finally { runCatching { l.stop(); l.close() } }
            (info to p).takeIf { p > HEARD }
        }.maxWithOrNull(compareBy({ mic(it.first.name) }, { it.second }))

    /** Named as a microphone - hears the room - rather than a line in, which carries whatever is plugged into it. */
    private fun mic(name: String) = name.contains("mic", ignoreCase = true)

    override fun start(onChunk: (FloatArray) -> Unit): Boolean {
        stop()
        val chosen = device?.let { name -> inputs().firstOrNull { it.name == name } }
        val opened = open(chosen) ?: (if (chosen != null) open(null) else null) ?: return false
        line = opened
        inUse = chosen?.name ?: defaultName()
        running = true
        Thread({
            var current = opened
            val bytes = ByteArray(sampleRate / 100 * 2)     // 10 ms at a time
            // A good microphone in a quiet room gives near-silence too: an input is only taken as
            // dead while it has heard nothing at all since it was opened, and until then the
            // others are tried every few seconds, so the first sound anyone makes finds one.
            var everHeard = false
            var lookAt = System.currentTimeMillis() + 1_500
            while (running) {
                val n = current.read(bytes, 0, bytes.size)
                if (n <= 0) continue
                val chunk = FloatArray(n / 2) { i ->
                    ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f
                }
                if (!everHeard) for (v in chunk) if (kotlin.math.abs(v * 32768) > HEARD) { everHeard = true; break }
                if (!everHeard && System.currentTimeMillis() >= lookAt) {
                    val was = inUse
                    val better = loudest(except = was)
                    if (better != null && running) {
                        val l = open(better.first)
                        if (l != null) {
                            runCatching { current.stop(); current.close() }
                            current = l; line = l; inUse = better.first.name
                            EventLog.info("sheets", "Microphone: $was hears nothing; using ${better.first.name}")
                        }
                    }
                    lookAt = System.currentTimeMillis() + 4_000
                }
                onChunk(chunk)
            }
        }, "microphone").apply { isDaemon = true; start() }
        return true
    }

    /** What the system's own input is called, where Java Sound says (Windows: the capture driver). */
    private fun defaultName(): String = SYSTEM

    override fun stop() {
        running = false
        line?.let { runCatching { it.stop(); it.close() } }
        line = null
    }

    companion object {
        private const val K_MIC = "sheets_microphone"
        /** Windows' name for "whatever the system's default input is". */
        const val SYSTEM = "Primary Sound Capture Driver"
        /** A sample louder than this (of 32768) is something heard, not digital silence and its dither. */
        const val HEARD = 24
    }
}