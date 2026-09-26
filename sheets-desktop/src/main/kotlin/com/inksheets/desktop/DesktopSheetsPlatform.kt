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

    override fun audioPlayer(): com.inksheets.ui.AudioPlayer = JavaSoundPlayer()

    override fun onMain(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)

    override fun setEdgeTaps(on: Boolean) {
        com.inkslate.desktop.AppFlavor.edgeTaps = on
    }

    override fun setStripSide(left: Boolean) {
        com.inkslate.desktop.AppFlavor.stripOnLeft = left
    }

    override fun setStripLane(open: Boolean) {
        com.inkslate.desktop.AppFlavor.stripLaneDp = if (open) 64f else 0f
    }

    override fun setTurnStyle(style: String) {
        com.inkslate.desktop.AppFlavor.turnAnimation = style
    }

    override fun openSet(parts: List<Pair<File, String>>, focus: Int) {
        com.inkslate.desktop.AppFlavor.openSet?.invoke(parts, focus) ?: parts.getOrNull(focus)?.let { openFile(it.first) }
    }

    override fun closeSet() {
        com.inkslate.desktop.AppFlavor.closeSet?.invoke()
    }

    override fun peek(file: File): com.inksheets.ui.PagePeek? {
        val source = com.inkslate.desktop.DesktopSources.open(file, detached = true) ?: return null
        return object : com.inksheets.ui.PagePeek {
            override val pageCount = source.pageCount
            override fun render(index: Int, widthPx: Int) = runCatching { source.render(index, widthPx) }.getOrNull()
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
                val line = AudioSystem.getSourceDataLine(format)
                line.open(format, sampleRate / 50 * 2)
                line.start()
                val block = FloatArray(256)
                val bytes = ByteArray(block.size * 2)
                while (running) {
                    fill(block)
                    for (i in block.indices) {
                        val v = (block[i].coerceIn(-1f, 1f) * 32767).toInt()
                        bytes[2 * i] = v.toByte()
                        bytes[2 * i + 1] = (v shr 8).toByte()
                    }
                    line.write(bytes, 0, bytes.size)
                }
                line.stop()
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

/** The default microphone through Java Sound, delivered as it arrives. */
private class JavaSoundMic : Microphone {
    override val sampleRate = 48_000
    @Volatile private var line: TargetDataLine? = null
    @Volatile private var running = false

    override fun start(onChunk: (FloatArray) -> Unit): Boolean {
        stop()
        val opened = runCatching {
            val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            AudioSystem.getTargetDataLine(format).apply {
                open(format)
                start()
            }
        }.onFailure { EventLog.warn("sheets", "Microphone failed: ${it.message}") }.getOrNull() ?: return false
        line = opened
        running = true
        Thread({
            val bytes = ByteArray(sampleRate / 100 * 2)     // 10 ms at a time
            while (running) {
                val n = opened.read(bytes, 0, bytes.size)
                if (n <= 0) continue
                val chunk = FloatArray(n / 2) { i ->
                    ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f
                }
                onChunk(chunk)
            }
        }, "microphone").apply { isDaemon = true; start() }
        return true
    }

    override fun stop() {
        running = false
        line?.let { runCatching { it.stop(); it.close() } }
        line = null
    }
}
