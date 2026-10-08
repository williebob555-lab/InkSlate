package com.inkslate.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.inkslate.data.FileRepo
import com.inkslate.data.SavePrefs
import com.inkslate.library.LibraryBackend
import com.inkslate.library.LibraryHome
import com.inkslate.library.LibraryPlatform
import com.inkslate.pdf.PageSources
import java.io.File

/**
 * The landing screen. The screen itself is shared with the desktop build - see
 * library-ui/README.md - and this is where it meets the tablet: its files, its back button.
 */
@Composable
fun HomeScreen(
    onOpenFile: (File) -> Unit,
    onBrowse: () -> Unit,
    onOpenSettings: () -> Unit,
    /** A new document, into the folder being looked at (null: wherever new documents go). */
    onNewDocument: (File?) -> Unit,
    refreshKey: Int
) {
    val context = LocalContext.current
    val backend = remember { AndroidLibrary(FileRepo(context), SavePrefs(context)) }
    val platform = remember {
        LibraryPlatform(
            backHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) },
            linkIndicator = { com.inkslate.ui.LinkIndicator() }
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

/** The tablet's files, as Home asks for them. */
private class AndroidLibrary(private val repo: FileRepo, private val savePrefs: SavePrefs) : LibraryBackend {
    override fun libraryFolders() = repo.libraryFolders()
    override fun removeLibraryFolder(dir: File) = repo.removeLibraryFolder(dir)
    override fun recents(limit: Int) = repo.recents(limit)
    override fun pinned() = repo.pinned()
    override fun isPinned(f: File) = repo.isPinned(f)
    override fun togglePin(f: File) = repo.togglePin(f)
    override fun isDocument(f: File) = PageSources.isSupported(f)
    override fun hasInk(f: File) = repo.hasInk(f)
    override var defaultNewFolder: File?
        get() = repo.defaultNewFolder
        set(v) { repo.defaultNewFolder = v }
    override fun isDefaultNewFolder(dir: File) = repo.isDefaultNewFolder(dir)
    override fun createFolder(parent: File, name: String) = repo.createFolder(parent, name)
    override fun rename(f: File, name: String) = repo.rename(f, name, savePrefs)
    override fun move(f: File, destination: File) = repo.move(f, destination, savePrefs)
    override fun delete(f: File) = repo.delete(f, savePrefs)
    override fun thumbnail(f: File): ImageBitmap? = repo.thumbnail(f)?.asImageBitmap()
    override fun pref(key: String) = repo.pref(key)
    override fun setPref(key: String, value: String?) = repo.setPref(key, value)
}
