package com.inksheets.desktop

import com.inkslate.core.UpdateCheck
import com.inkslate.desktop.DesktopUpdates
import com.inkslate.desktop.SystemShell
import java.io.File
import java.util.zip.ZipFile

/** Explicit user action downloads only the watch bundle from the selected InkSheets channel. */
internal object DesktopWatchInstaller {
    internal val allowedEntries = setOf("InkSheets-Watch.apk", "Install-Watch.ps1", "Install-Watch.cmd", "README-Watch.txt")
    internal fun safeEntry(name: String, size: Long) = name in allowedEntries && size in 0..64_000_000
    internal fun asset(release: UpdateCheck.Release) = release.assets.firstOrNull {
        it.name.startsWith("InkSheets-Watch-") && it.name.endsWith(".zip") &&
            it.url.startsWith("https://github.com/${UpdateCheck.OWNER}/${UpdateCheck.REPO}/releases/download/")
    }
    fun open(done: (String?) -> Unit) {
        Thread({
            val failure = runCatching {
                val result = UpdateCheck.check("0.0.0", UpdateCheck.Platform.WINDOWS, DesktopUpdates.channel(), "InkSheets")
                val release = when (result) {
                    is UpdateCheck.Result.Available -> result.release
                    is UpdateCheck.Result.AvailableWithoutDownload -> result.release
                    is UpdateCheck.Result.Failed -> error(result.message)
                    else -> error("No watch release is available on this update channel.")
                }
                val download = asset(release) ?: error("This release has no watch setup bundle yet. Try again after the new build is published.")
                val folder = File(System.getProperty("user.home"), "Downloads/InkSheets-Watch-${release.version}")
                folder.mkdirs()
                val archive = UpdateCheck.download(download, File(folder, "watch-bundle.zip"))
                ZipFile(archive).use { zip ->
                    val entries = zip.entries().asSequence().toList()
                    require(entries.map { it.name }.toSet() == allowedEntries && entries.size == allowedEntries.size) { "The watch bundle is missing setup files or contains unexpected files." }
                    entries.forEach { entry ->
                        require(safeEntry(entry.name, entry.size)) { "Unsafe watch bundle entry." }
                        zip.getInputStream(entry).use { input -> File(folder, entry.name).outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                total += n; require(total <= 64_000_000) { "Watch bundle is too large." }
                                output.write(buffer, 0, n)
                            }
                        } }
                    }
                }
                if (System.getProperty("os.name").startsWith("Windows", true)) SystemShell.open(File(folder, "Install-Watch.cmd"))
                else SystemShell.open(File(folder, "README-Watch.txt"))
            }.exceptionOrNull()?.message
            javax.swing.SwingUtilities.invokeLater { done(failure) }
        }, "watch-setup-download").apply { isDaemon = true; start() }
    }
}
