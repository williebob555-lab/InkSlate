package com.inkslate.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.inkslate.library.LibraryBackend
import com.inkslate.library.LibraryHome
import com.inkslate.library.LibraryPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The landing screen. The screen itself is shared with the tablet - see library-ui/README.md -
 * and this is where it meets the computer: its files, Escape, and a scrollbar to drag.
 */
@Composable
fun HomeScreen(
    onOpenFile: (File) -> Unit,
    onBrowse: () -> Unit,
    onOpenSettings: () -> Unit,
    /** A new document, into the folder being looked at (null: wherever new documents go). */
    onNewDocument: (File?) -> Unit,
    refreshKey: Int,
    navigation: NavigationHooks
) {
    val backend = remember { DesktopLibrary(FileRepo()) }
    val platform = remember(navigation) {
        LibraryPlatform(
            // Escape walks back out before it does anything else, as Android's back button does.
            backHandler = { enabled, onBack -> navigation.back = if (enabled) onBack else null },
            verticalScrollbar = { state ->
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(state),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp)
                )
            },
            linkIndicator = { LinkIndicator() },
            pointerIsMouse = true
        )
    }
    LibraryHome(
        backend = backend,
        platform = platform,
        appName = "InkSlate",
        refreshKey = refreshKey,
        onOpenFile = onOpenFile,
        onBrowse = onBrowse,
        onOpenSettings = onOpenSettings,
        onNewDocument = onNewDocument
    )
}

/** The computer's files, as Home asks for them. */
internal class DesktopLibrary(private val repo: FileRepo) : LibraryBackend {
    override fun libraryFolders() = repo.libraryFolders()
    override fun removeLibraryFolder(dir: File) = repo.removeLibraryFolder(dir)
    override fun recents(limit: Int) = repo.recents(limit)
    override fun pinned() = repo.pinned()
    override fun isPinned(f: File) = repo.isPinned(f)
    override fun togglePin(f: File) = repo.togglePin(f)
    override fun isDocument(f: File) = DesktopSources.isSupported(f)
    override fun hasInk(f: File) = repo.hasInk(f)
    override var defaultNewFolder: File?
        get() = repo.defaultNewFolder
        set(v) { repo.defaultNewFolder = v }
    override fun isDefaultNewFolder(dir: File) = repo.isDefaultNewFolder(dir)
    override fun createFolder(parent: File, name: String) = repo.createFolder(parent, name)
    override fun rename(f: File, name: String) = repo.rename(f, name)
    override fun move(f: File, destination: File) = repo.move(f, destination)
    override fun delete(f: File) = repo.delete(f)
    override fun thumbnail(f: File): ImageBitmap? = repo.thumbnail(f)
    override fun removeRecent(f: File) = repo.removeRecent(f)
    override fun forget(f: File) = repo.forgetRemoved(f)
    override fun pref(key: String) = DesktopPrefs.get(key)
    override fun setPref(key: String, value: String?) = DesktopPrefs.put(key, value)
}

/** A document's first page with its handwriting, for the file browser's rows. */
@Composable
internal fun Thumb(file: File, repo: FileRepo) {
    var bmp by remember(file.absolutePath, file.lastModified()) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(file.absolutePath) { mutableStateOf(false) }

    LaunchedEffect(file.absolutePath, file.lastModified()) {
        val result = withContext(Dispatchers.IO) { runCatching { repo.thumbnail(file) }.getOrNull() }
        if (result == null) failed = true else bmp = result
    }

    val current = bmp
    when {
        current != null -> Image(
            bitmap = current,
            contentDescription = file.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        failed -> Icon(
            if (file.extension.equals("pdf", true)) Icons.Default.PictureAsPdf
            else Icons.Default.Image,
            null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
    }
}
