package com.inkslate.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What happens to the original file when you save. */
enum class SaveMode {
    OVERWRITE,   // write back over the file you opened
    COPY,        // leave the original alone, write a new file
    ASK;         // prompt every time

    val label: String
        get() = when (this) {
            OVERWRITE -> "Overwrite original"
            COPY -> "Save as a copy"
            ASK -> "Ask every time"
        }
}

/** How the ink is embedded in the saved PDF. */
enum class InkFormat {
    ANNOTATIONS,  // real PDF Ink annotations: still editable after reopening
    FLATTENED;    // burned into the page content: permanent, maximum compatibility

    val label: String
        get() = when (this) {
            ANNOTATIONS -> "Editable annotations"
            FLATTENED -> "Flattened into page"
        }

    val detail: String
        get() = when (this) {
            ANNOTATIONS -> "Strokes stay erasable when you reopen the file."
            FLATTENED -> "Strokes become part of the page and cannot be edited again."
        }
}

/** How a copy gets named. */
enum class CopyNaming {
    SUFFIX,     // homework.pdf -> homework_annotated.pdf
    TIMESTAMP,  // homework.pdf -> homework_2026-09-06_1432.pdf
    INCREMENT;  // homework.pdf -> homework (1).pdf

    val label: String
        get() = when (this) {
            SUFFIX -> "Add a suffix"
            TIMESTAMP -> "Add date and time"
            INCREMENT -> "Add a number"
        }
}

/** Where a copy is written. */
enum class CopyLocation {
    SAME_FOLDER,
    FIXED_FOLDER;

    val label: String
        get() = when (this) {
            SAME_FOLDER -> "Next to the original"
            FIXED_FOLDER -> "A folder I choose"
        }
}

/**
 * A fully resolved set of save rules.
 *
 * Never stored directly for a file - it is the result of layering that file's overrides on top of
 * the global defaults. Shared between the builds because these decide what happens to a document
 * on disk, and two machines disagreeing about whether saving overwrites the original is not a
 * difference anyone would want to discover afterwards.
 */
data class SaveSettings(
    val mode: SaveMode = SaveMode.COPY,
    val inkFormat: InkFormat = InkFormat.ANNOTATIONS,
    val copyNaming: CopyNaming = CopyNaming.SUFFIX,
    val copySuffix: String = "_annotated",
    val copyLocation: CopyLocation = CopyLocation.SAME_FOLDER,
    val copyFolder: String? = null,
    val autosave: Boolean = true,
    val autosaveSeconds: Int = 20,
    val confirmOverwrite: Boolean = true,
    val backupOnOverwrite: Boolean = true
)

/**
 * Per-file overrides.
 *
 * Every field is nullable, and null means "inherit the global value". That is what makes an
 * override genuinely partial: a file can be pinned to OVERWRITE while still picking up later
 * changes to, say, the autosave interval.
 */
data class FileOverride(
    val mode: SaveMode? = null,
    val inkFormat: InkFormat? = null,
    val copyNaming: CopyNaming? = null,
    val copySuffix: String? = null,
    val copyLocation: CopyLocation? = null,
    val copyFolder: String? = null,
    val autosave: Boolean? = null,
    val autosaveSeconds: Int? = null,
    val confirmOverwrite: Boolean? = null,
    val backupOnOverwrite: Boolean? = null
) {
    val isEmpty: Boolean
        get() = mode == null && inkFormat == null && copyNaming == null && copySuffix == null &&
            copyLocation == null && copyFolder == null && autosave == null &&
            autosaveSeconds == null && confirmOverwrite == null && backupOnOverwrite == null

    /** Human-readable list of what this file does differently, for the UI badge. */
    fun describe(global: SaveSettings): List<String> = buildList {
        mode?.let { if (it != global.mode) add(it.label) }
        inkFormat?.let { if (it != global.inkFormat) add(it.label) }
        autosave?.let { if (it != global.autosave) add(if (it) "Autosave on" else "Autosave off") }
        copyNaming?.let { if (it != global.copyNaming) add(it.label) }
        copyLocation?.let { if (it != global.copyLocation) add(it.label) }
        backupOnOverwrite?.let {
            if (it != global.backupOnOverwrite) add(if (it) "Keeps backups" else "No backups")
        }
    }

    /** The global defaults with this file's overrides layered on top. */
    fun appliedTo(global: SaveSettings): SaveSettings = global.copy(
        mode = mode ?: global.mode,
        inkFormat = inkFormat ?: global.inkFormat,
        copyNaming = copyNaming ?: global.copyNaming,
        copySuffix = copySuffix ?: global.copySuffix,
        copyLocation = copyLocation ?: global.copyLocation,
        copyFolder = copyFolder ?: global.copyFolder,
        autosave = autosave ?: global.autosave,
        autosaveSeconds = autosaveSeconds ?: global.autosaveSeconds,
        confirmOverwrite = confirmOverwrite ?: global.confirmOverwrite,
        backupOnOverwrite = backupOnOverwrite ?: global.backupOnOverwrite
    )
}

/** Naming a copy, which is the one rule that has to produce the same filename on both builds. */
object CopyNames {

    /**
     * Build the destination name for a copy.
     *
     * Always returns a name that does not yet exist, so a copy can never silently clobber an
     * earlier one - which on a folder of dated worksheets is the difference between two versions
     * and one.
     */
    fun copyTargetName(
        originalName: String,
        s: SaveSettings,
        exists: (String) -> Boolean
    ): String {
        val dot = originalName.lastIndexOf('.')
        val stem = if (dot > 0) originalName.substring(0, dot) else originalName
        val ext = if (dot > 0) originalName.substring(dot) else ""
        val first = when (s.copyNaming) {
            CopyNaming.SUFFIX -> "$stem${s.copySuffix}$ext"
            CopyNaming.TIMESTAMP -> {
                val f = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US)
                "${stem}_${f.format(Date())}$ext"
            }
            CopyNaming.INCREMENT -> "$stem (1)$ext"
        }
        if (!exists(first)) return first
        val firstDot = first.lastIndexOf('.')
        val firstStem = if (firstDot > 0) first.substring(0, firstDot) else first
        var n = 2
        while (n < 1000) {
            val candidate = "$firstStem ($n)$ext"
            if (!exists(candidate)) return candidate
            n++
        }
        return "$firstStem ${System.currentTimeMillis()}$ext"
    }
}
