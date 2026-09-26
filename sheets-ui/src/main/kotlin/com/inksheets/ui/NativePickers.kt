package com.inksheets.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The system's own file and folder pickers, where there are ones worth using (Windows), in place
 * of the list InkSheets draws: search, filters, Quick access and every shortcut the person already
 * knows. Each blocks until a choice is made - null for none - and is called off the UI thread.
 */
object NativePickers {
    /** Title, folder to start in, extensions wanted (empty for any file). */
    var file: ((title: String, start: File?, extensions: Set<String>) -> File?)? = null

    /** Title, folder to start in. */
    var folder: ((title: String, start: File?) -> File?)? = null
}

/** Show a system picker once, and hand back what it chose. */
@Composable
internal fun NativeChoice(pick: () -> File?, onResult: (File?) -> Unit) {
    LaunchedEffect(Unit) {
        val chosen = withContext(Dispatchers.IO) { runCatching { pick() }.getOrNull() }
        onResult(chosen)
    }
}

internal fun File.isInside(folder: File): Boolean =
    runCatching { canonicalPath.startsWith(folder.canonicalPath + File.separator) || canonicalPath == folder.canonicalPath }.getOrDefault(false)
