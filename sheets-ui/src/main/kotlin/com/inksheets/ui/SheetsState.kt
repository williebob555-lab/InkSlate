package com.inksheets.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inksheets.core.InstrumentProfile
import com.inksheets.core.Instruments
import com.inksheets.core.Library
import com.inksheets.core.LibraryLog
import java.io.File

/**
 * What the InkSheets screens share: the open library, the instrument being played, and a counter
 * that moves whenever the library changes so the screens know to look again.
 */
class SheetsState(val platform: SheetsPlatform) {

    var root by mutableStateOf<File?>(null)
        private set

    var library by mutableStateOf<Library?>(null)
        private set

    /** Bumped on every change, from this device or from another through the synced folder. */
    var version by mutableStateOf(0L)
        private set

    val profiles: List<InstrumentProfile> = Instruments.defaultProfiles

    var profileId by mutableStateOf(platform.pref(K_PROFILE))
        private set

    val profile: InstrumentProfile?
        get() = profiles.firstOrNull { it.id == profileId }

    init {
        platform.pref(K_LIBRARY)?.let(::File)?.takeIf { it.isDirectory }?.let(::open)
    }

    fun open(folder: File) {
        val lib = runCatching { Library(LibraryLog(folder, platform.deviceId)) }.getOrNull() ?: return
        root = folder
        library = lib
        platform.setPref(K_LIBRARY, folder.absolutePath)
        version = lib.version
    }

    fun chooseProfile(id: String?) {
        profileId = id
        platform.setPref(K_PROFILE, id)
    }

    /** Take in edits from other devices. Called off the UI thread on a timer. */
    fun refresh() {
        val lib = library ?: return
        if (lib.refresh()) version = lib.version
    }

    /** Make a change and let the screens know. */
    fun change(block: Library.() -> Unit) {
        val lib = library ?: return
        lib.block()
        version = lib.version
    }

    /** A library-relative path turned into the file on this device. */
    fun fileOf(relative: String): File? = root?.let { File(it, relative) }

    /** A file on this device as the library records it: relative, forward slashes. */
    fun relative(file: File): String? {
        val base = root?.canonicalFile ?: return null
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(base.path)) return null
        return canonical.path.removePrefix(base.path).trimStart(File.separatorChar).replace(File.separatorChar, '/')
    }

    companion object {
        private const val K_LIBRARY = "sheets_library"
        private const val K_PROFILE = "sheets_profile"
    }
}
