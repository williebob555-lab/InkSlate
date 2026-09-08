package com.inkslate

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inkslate.data.FileRepo
import com.inkslate.pdf.PageSources
import com.inkslate.ui.AppRoot
import com.inkslate.ui.theme.InkSlateTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private var pendingOpen by mutableStateOf<File?>(null)
    private var storageGranted by mutableStateOf(false)

    private val legacyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            storageGranted = hasStorageAccess()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ ignores the old opt-out and always draws edge to edge, so insets have to
        // be handled rather than avoided; screens pad themselves against the system bars.
        enableEdgeToEdge()
        storageGranted = hasStorageAccess()
        handleIncoming(intent)

        setContent {
            InkSlateTheme {
                AppRoot(
                    storageGranted = storageGranted,
                    onRequestStorage = { requestStorageAccess() },
                    openRequest = pendingOpen,
                    onOpenHandled = { pendingOpen = null }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // returning from the system all-files-access screen
        storageGranted = hasStorageAccess()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    // ---- storage permission --------------------------------------------------

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // All-files access is a Settings screen, not a runtime dialog. Try the app-specific
            // page first and fall back to the global list if the OEM does not honour it.
            val direct = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            runCatching { startActivity(direct) }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            legacyPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    // ---- open-with from other apps -------------------------------------------

    private fun handleIncoming(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        } ?: return

        resolveToFile(uri)?.let { pendingOpen = it }
    }

    /**
     * Turn an incoming URI into a real file.
     *
     * A `file://` URI can be edited where it sits. Anything else (Gmail attachments, Drive,
     * another app's private storage) is only readable through a stream, so it is copied into a
     * working folder first. Editing in place is not possible there, and silently doing nothing
     * would be worse than importing a copy the user can actually find again.
     */
    private fun resolveToFile(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf { it.isFile }

        return runCatching {
            val name = displayNameOf(uri) ?: "imported-${System.currentTimeMillis()}.pdf"
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "InkSlate"
            ).apply { mkdirs() }

            var target = File(dir, name)
            var n = 1
            while (target.exists()) {
                target = File(dir, "${File(name).nameWithoutExtension} ($n).${File(name).extension}")
                n++
            }
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            if (PageSources.isSupported(target)) {
                FileRepo(this).noteOpened(target)
                target
            } else {
                target.delete(); null
            }
        }.getOrNull()
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()
}
