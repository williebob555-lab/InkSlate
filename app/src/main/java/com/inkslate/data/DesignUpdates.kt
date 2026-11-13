package com.inkslate.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.inkslate.core.DesignConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

val LocalDesignConfig = staticCompositionLocalOf { DesignConfig.current }
val LocalDesignUpdates = staticCompositionLocalOf<DesignUpdates> { error("Design updates not provided") }

/** A separate, data-only update channel. The bundled file is the minimum supported revision. */
class DesignUpdates(
    private val context: Context,
    bundledDesign: DesignConfig? = null,
    private val fetchText: () -> String = { downloadText() }
) {
    companion object {
        const val URL = "https://raw.githubusercontent.com/williebob555-lab/InkSlate/main/config/inkslate-test.json"

        private fun downloadText(): String {
            val connection = URI(URL).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Cache-Control", "no-cache")
                require(connection.responseCode == 200) { "GitHub returned ${connection.responseCode}." }
                return connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 65_536) { "Design file is too large." }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
            } finally { connection.disconnect() }
        }
    }
    private val bundled = (bundledDesign ?: DesignConfig.parse(
        context.assets.open("inkslate-test.json").bufferedReader().use { it.readText() }
    )).validated()
    private val cache = File(context.filesDir, "design-overrides.json")
    private val mutex = Mutex()
    var active by mutableStateOf(load())
        private set
    var pending by mutableStateOf<DesignConfig?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var applying by mutableStateOf(false)
        private set
    private var deferredRevision: Int? = null
    /** Workspace supplies a flush of every open document before replacing design values. */
    var prepareReload: (suspend () -> Unit)? = null

    init { DesignConfig.install(active) }

    private fun load(): DesignConfig = runCatching {
        val atomic = android.util.AtomicFile(cache)
        if (!cache.isFile && !File(cache.path + ".bak").isFile) bundled
        else DesignConfig.parse(atomic.openRead().bufferedReader().use { it.readText() })
            .takeIf { it.revision > bundled.revision } ?: bundled
    }.getOrElse { bundled }

    fun enabled(): Boolean = context.packageName == "com.inkslate" &&
        (AppUpdates.testChannelEnabled(context) || AppUpdates.installedVersion(context).contains("-test."))

    suspend fun check(manual: Boolean = false) = mutex.withLock {
        if (!enabled()) return@withLock
        checking = true
        error = null
        try {
            val candidate = withContext(Dispatchers.IO) {
                DesignConfig.parse(fetchText())
            }
            pending = candidate.takeIf { it.revision > active.revision && (manual || it.revision != deferredRevision) }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not check for design changes."
        } finally { checking = false }
    }

    fun defer() { deferredRevision = pending?.revision; pending = null }

    suspend fun applyPending() = mutex.withLock {
        val candidate = pending ?: return@withLock
        applying = true
        error = null
        try {
            checkNotNull(prepareReload) { "The workspace is not ready to reload. Try again in a moment." }.invoke()
            withContext(Dispatchers.IO) {
                // AtomicFile preserves the previous valid cache if writing is interrupted.
                val atomic = android.util.AtomicFile(cache)
                val output = atomic.startWrite()
                try {
                    output.write(DesignConfig.json.encodeToString(candidate).toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(output)
                } catch (e: Exception) { atomic.failWrite(output); throw e }
            }
            DesignConfig.install(candidate)
            active = candidate
            pending = null
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not apply the design. Your current design is still active."
        } finally { applying = false }
    }

    suspend fun reset() = mutex.withLock {
        applying = true
        error = null
        try {
            checkNotNull(prepareReload) { "The workspace is not ready to reload. Try again in a moment." }.invoke()
            withContext(Dispatchers.IO) {
                android.util.AtomicFile(cache).delete()
            }
            DesignConfig.install(bundled)
            deferredRevision = active.revision.takeIf { it > bundled.revision }
            active = bundled
            pending = null
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = e.message ?: "Could not reset the design." }
        finally { applying = false }
    }
}
