package com.inkslate.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.inkslate.core.UpdateCheck
import java.io.File

/**
 * The Android half of "check for updates": everything the shared [UpdateCheck] deliberately does
 * not know about - which version is installed, where a downloaded APK may live, and how to ask
 * Android to install it.
 *
 * InkSlate is sideloaded, so installing is a request, not a right. Android will only let the app
 * hand over an APK once the person has ticked "allow from this source" for InkSlate specifically,
 * which is a Settings screen rather than a prompt. [canInstall] and [unknownSourcesIntent] exist
 * to take them there instead of failing silently.
 */
object AppUpdates {

    /** Downloads are kept out of the way, and swept up on the next check. */
    private const val CACHE_DIR = "updates"

    /**
     * The version string of the running build, as `versionName` in the app's own manifest, so
     * there is exactly one place a version number is written down.
     */
    fun installedVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0"

    /** Blocking. Callers move it off the main thread. */
    fun check(context: Context): UpdateCheck.Result =
        UpdateCheck.check(installedVersion(context), UpdateCheck.Platform.ANDROID)

    /**
     * Downloads [asset] into the cache and returns the file. Blocking, and reports progress as a
     * 0..1 fraction (or -1 when the size is unknown) so a bar can follow it.
     */
    fun download(
        context: Context,
        asset: UpdateCheck.Asset,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {}
    ): File {
        val dir = File(context.cacheDir, CACHE_DIR)
        // A part-downloaded or already-installed APK from a previous run is dead weight - tens of
        // megabytes of it - so the directory is cleared rather than accumulated.
        dir.listFiles()?.forEach { it.delete() }
        // The name is ours, not the server's: an asset name is attacker-controlled text and has
        // no business deciding a path.
        return UpdateCheck.download(asset, File(dir, "InkSlate-update.apk"), shouldContinue, onProgress)
    }

    /**
     * Whether Android will currently let InkSlate start an install. False means the person has
     * not granted "install unknown apps" to this app yet; send them to [unknownSourcesIntent].
     */
    fun canInstall(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** The Settings screen where "allow from this source" is turned on for InkSlate. */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Hands [apk] to Android's package installer. What happens next is entirely the system's
     * decision: it shows its own confirmation, and it will refuse an APK signed with a different
     * key than the installed one rather than replacing it.
     */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Opens the release page in a browser, for when the download is better done by hand. */
    fun openInBrowser(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
