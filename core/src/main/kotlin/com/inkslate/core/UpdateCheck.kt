package com.inkslate.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finding out whether a newer InkSlate has been published, and fetching it.
 *
 * InkSlate is sideloaded, so there is no store to notice a new version for us. Instead every
 * build is published as a GitHub release, and the app asks GitHub directly.
 *
 * This lives in `core` rather than in the Android app because the Windows build needs exactly
 * the same three answers - is there a newer one, what changed, and where do I download it -
 * and the only difference is which file it reaches for at the end. Nothing here touches
 * Android or Compose, and nothing here is added to the dependency list: `HttpURLConnection`
 * ships with the JVM and with Android.
 *
 * Every call blocks. Callers put it on a background thread themselves.
 */
object UpdateCheck {

    /** The repository the releases are published to. */
    const val OWNER = "williebob555-lab"
    const val REPO = "InkSlate"

    /** Where a person goes to read about the project or download a build by hand. */
    const val PROJECT_URL = "https://github.com/$OWNER/$REPO"
    const val RELEASES_URL = "$PROJECT_URL/releases"

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** Long enough for a slow phone on school wifi, short enough not to hang the dialog. */
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- platforms

    /**
     * Which downloadable file this build wants out of a release. A single release carries an
     * APK and, later, a Windows installer; each build picks its own and ignores the rest.
     */
    enum class Platform(internal val extensions: List<String>) {
        ANDROID(listOf(".apk")),
        // The desktop module already builds both, so accept either and prefer the .msi.
        WINDOWS(listOf(".msi", ".exe"))
    }

    // ---------------------------------------------------------------- results

    /** What the check found. Exactly one of these comes back from [check]. */
    sealed interface Result {
        /** A newer release exists and carries a file this platform can install. */
        data class Available(val release: Release, val download: Asset) : Result

        /** A newer release exists but has no file for this platform yet. */
        data class AvailableWithoutDownload(val release: Release) : Result

        /** Already on the newest published version. */
        data class UpToDate(val installed: Version) : Result

        /**
         * The check could not be completed. [message] is written to be shown to a person, not
         * logged - "No internet connection", not a stack trace.
         */
        data class Failed(val message: String) : Result
    }

    data class Release(
        val version: Version,
        val title: String,
        val notes: String,
        val pageUrl: String,
        val assets: List<Asset>
    )

    data class Asset(val name: String, val url: String, val sizeBytes: Long)

    // ---------------------------------------------------------------- the check

    /**
     * Asks GitHub for the newest release and compares it with [installedVersion], which is
     * whatever the running build calls itself ("1.0", "1.2.3").
     *
     * Never throws. Anything that goes wrong - no network, GitHub down, a rate limit, a release
     * tagged something unparseable - comes back as [Result.Failed] with a sentence to show.
     */
    fun check(installedVersion: String, platform: Platform): Result {
        val installed = Version.parse(installedVersion)
            ?: return Result.Failed("This build has no version number to compare against.")

        val body = try {
            fetchLatestReleaseJson()
        } catch (e: Exception) {
            return Result.Failed(describe(e))
        }

        val release = try {
            parseRelease(body)
        } catch (e: Exception) {
            return Result.Failed("GitHub sent back something unexpected.")
        } ?: return Result.Failed("The latest release has no version number in its tag.")

        if (release.version <= installed) return Result.UpToDate(installed)

        val download = release.assets.firstOrNull { asset ->
            platform.extensions.any { asset.name.endsWith(it, ignoreCase = true) }
        } ?: return Result.AvailableWithoutDownload(release)

        return Result.Available(release, download)
    }

    /** Visible for tests: turns a GitHub release payload into a [Release]. */
    internal fun parseRelease(body: String): Release? {
        val dto = json.decodeFromString<ReleaseDto>(body)
        val version = Version.parse(dto.tagName) ?: return null
        return Release(
            version = version,
            title = dto.name?.takeIf { it.isNotBlank() } ?: dto.tagName,
            notes = dto.body.orEmpty().trim(),
            pageUrl = dto.htmlUrl ?: RELEASES_URL,
            assets = dto.assets.map { Asset(it.name, it.downloadUrl, it.size) }
        )
    }

    private fun fetchLatestReleaseJson(): String {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // GitHub pins the response shape to this header rather than to a URL version.
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub rejects requests with no User-Agent outright.
            setRequestProperty("User-Agent", "InkSlate")
        }
        try {
            when (val code = connection.responseCode) {
                200 -> return connection.inputStream.bufferedReader().use { it.readText() }
                404 -> throw IOException("No releases have been published yet.")
                403, 429 -> throw IOException("GitHub is rate-limiting this device. Try later.")
                else -> throw IOException("GitHub replied $code.")
            }
        } finally {
            connection.disconnect()
        }
    }

    // ---------------------------------------------------------------- downloading

    /**
     * Downloads [asset] to [destination], reporting progress as a 0..1 fraction, or as -1 when
     * the server declines to say how big the file is.
     *
     * Writes to a neighbouring `.part` file and renames only on success, so an interrupted
     * download can never be mistaken for a complete installer. Returns the file it wrote.
     *
     * [shouldContinue] is polled as the bytes arrive; returning false aborts and cleans up.
     */
    fun download(
        asset: Asset,
        destination: File,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {}
    ): File {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        partial.delete()

        val connection = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "InkSlate")
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("The download failed (${connection.responseCode}).")
            }
            // Prefer the size the release listed: GitHub serves assets from a redirect whose
            // own Content-Length is occasionally absent.
            val total = if (asset.sizeBytes > 0) asset.sizeBytes else connection.contentLengthLong

            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        if (!shouldContinue()) throw IOException("Download cancelled.")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(if (total > 0) written.toFloat() / total else -1f)
                    }
                }
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }

        destination.delete()
        if (!partial.renameTo(destination)) {
            partial.delete()
            throw IOException("Could not put the download in place.")
        }
        onProgress(1f)
        return destination
    }

    // ---------------------------------------------------------------- plumbing

    private fun describe(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection."
        is java.net.SocketTimeoutException -> "GitHub did not answer in time."
        else -> e.message ?: "The check could not be completed."
    }

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        val assets: List<AssetDto> = emptyList()
    )

    @Serializable
    private data class AssetDto(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
        val size: Long = 0
    )
}

/**
 * A dotted version number, compared the way a person would read it: 1.10 is newer than 1.9,
 * and 1.2 and 1.2.0 are the same version.
 *
 * A trailing label - the `beta` in `1.3.0-beta` - marks a pre-release, which by the usual
 * convention is *older* than the finished 1.3.0. That keeps someone testing a beta from being
 * told they are up to date once the real release lands.
 */
data class Version(val parts: List<Int>, val preRelease: String? = null) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        val size = maxOf(parts.size, other.parts.size)
        for (i in 0 until size) {
            // A missing component reads as zero, so 1.2 and 1.2.0 compare equal.
            val diff = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return when {
            preRelease == other.preRelease -> 0
            preRelease == null -> 1   // a finished release beats any pre-release of it
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    override fun toString(): String =
        parts.joinToString(".") + (preRelease?.let { "-$it" } ?: "")

    companion object {
        /**
         * Reads "1.2.3", "v1.2.3", "1.2.3-beta.1" and the like. Returns null when there is no
         * number to be found, rather than guessing.
         */
        fun parse(raw: String): Version? {
            var text = raw.trim()
            if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1)

            val dash = text.indexOf('-')
            val preRelease = if (dash >= 0) text.substring(dash + 1).takeIf { it.isNotEmpty() }
            else null
            if (dash >= 0) text = text.substring(0, dash)

            val parts = text.split(".").map { part ->
                // Tolerate a stray suffix such as "3b" by reading the leading digits only.
                val digits = part.takeWhile { it.isDigit() }
                digits.toIntOrNull() ?: return null
            }
            return if (parts.isEmpty()) null else Version(parts, preRelease)
        }
    }
}
