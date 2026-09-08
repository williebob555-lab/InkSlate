package com.inkslate.data

import android.content.Context
import org.json.JSONObject
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
    SUFFIX,     // homework.pdf     -> homework_annotated.pdf
    TIMESTAMP,  // homework.pdf     -> homework_2026-09-06_1432.pdf
    INCREMENT;  // homework.pdf     -> homework (1).pdf

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
 * A fully resolved set of save rules. Never stored directly for a file - it is the result of
 * layering that file's overrides on top of the global defaults.
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
 * Per-file overrides. Every field is nullable, and null means "inherit the global value".
 * That is what makes an override genuinely partial: you can pin one file to OVERWRITE while
 * still picking up later changes to, say, the autosave interval.
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
        backupOnOverwrite?.let { if (it != global.backupOnOverwrite) add(if (it) "Keeps backups" else "No backups") }
    }
}

/**
 * Two-tier save configuration: one global default set, plus per-file overrides keyed by
 * absolute path. [effectiveFor] is the only thing the editor ever asks for.
 */
class SavePrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("save_prefs", Context.MODE_PRIVATE)

    // ---- global --------------------------------------------------------------

    fun global(): SaveSettings = SaveSettings(
        mode = SaveMode.valueOf(sp.getString(K_MODE, SaveMode.COPY.name)!!),
        inkFormat = InkFormat.valueOf(sp.getString(K_FORMAT, InkFormat.ANNOTATIONS.name)!!),
        copyNaming = CopyNaming.valueOf(sp.getString(K_NAMING, CopyNaming.SUFFIX.name)!!),
        copySuffix = sp.getString(K_SUFFIX, "_annotated")!!,
        copyLocation = CopyLocation.valueOf(sp.getString(K_LOCATION, CopyLocation.SAME_FOLDER.name)!!),
        copyFolder = sp.getString(K_FOLDER, null),
        autosave = sp.getBoolean(K_AUTOSAVE, true),
        autosaveSeconds = sp.getInt(K_AUTOSAVE_SECS, 20),
        confirmOverwrite = sp.getBoolean(K_CONFIRM, true),
        backupOnOverwrite = sp.getBoolean(K_BACKUP, true)
    )

    fun setGlobal(s: SaveSettings) {
        sp.edit()
            .putString(K_MODE, s.mode.name)
            .putString(K_FORMAT, s.inkFormat.name)
            .putString(K_NAMING, s.copyNaming.name)
            .putString(K_SUFFIX, s.copySuffix)
            .putString(K_LOCATION, s.copyLocation.name)
            .putString(K_FOLDER, s.copyFolder)
            .putBoolean(K_AUTOSAVE, s.autosave)
            .putInt(K_AUTOSAVE_SECS, s.autosaveSeconds)
            .putBoolean(K_CONFIRM, s.confirmOverwrite)
            .putBoolean(K_BACKUP, s.backupOnOverwrite)
            .apply()
    }

    // ---- per-file ------------------------------------------------------------

    private fun overridesJson(): JSONObject =
        runCatching { JSONObject(sp.getString(K_OVERRIDES, "{}")!!) }.getOrElse { JSONObject() }

    fun overrideFor(path: String): FileOverride? {
        val o = overridesJson().optJSONObject(path) ?: return null
        fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
        fun bool(k: String) = if (o.has(k) && !o.isNull(k)) o.getBoolean(k) else null
        fun int(k: String) = if (o.has(k) && !o.isNull(k)) o.getInt(k) else null
        return FileOverride(
            mode = str(K_MODE)?.let { runCatching { SaveMode.valueOf(it) }.getOrNull() },
            inkFormat = str(K_FORMAT)?.let { runCatching { InkFormat.valueOf(it) }.getOrNull() },
            copyNaming = str(K_NAMING)?.let { runCatching { CopyNaming.valueOf(it) }.getOrNull() },
            copySuffix = str(K_SUFFIX),
            copyLocation = str(K_LOCATION)?.let { runCatching { CopyLocation.valueOf(it) }.getOrNull() },
            copyFolder = str(K_FOLDER),
            autosave = bool(K_AUTOSAVE),
            autosaveSeconds = int(K_AUTOSAVE_SECS),
            confirmOverwrite = bool(K_CONFIRM),
            backupOnOverwrite = bool(K_BACKUP)
        )
    }

    fun setOverride(path: String, o: FileOverride?) {
        val root = overridesJson()
        if (o == null || o.isEmpty) {
            root.remove(path)
        } else {
            val j = JSONObject()
            o.mode?.let { j.put(K_MODE, it.name) }
            o.inkFormat?.let { j.put(K_FORMAT, it.name) }
            o.copyNaming?.let { j.put(K_NAMING, it.name) }
            o.copySuffix?.let { j.put(K_SUFFIX, it) }
            o.copyLocation?.let { j.put(K_LOCATION, it.name) }
            o.copyFolder?.let { j.put(K_FOLDER, it) }
            o.autosave?.let { j.put(K_AUTOSAVE, it) }
            o.autosaveSeconds?.let { j.put(K_AUTOSAVE_SECS, it) }
            o.confirmOverwrite?.let { j.put(K_CONFIRM, it) }
            o.backupOnOverwrite?.let { j.put(K_BACKUP, it) }
            root.put(path, j)
        }
        sp.edit().putString(K_OVERRIDES, root.toString()).apply()
    }

    fun clearOverride(path: String) = setOverride(path, null)

    fun hasOverride(path: String): Boolean = overridesJson().has(path)

    /** Every path that currently pins its own rules, for the settings screen. */
    fun overriddenPaths(): List<String> = overridesJson().keys().asSequence().toList().sorted()

    fun clearAllOverrides() = sp.edit().remove(K_OVERRIDES).apply()

    /** Move an override with its file, so renaming does not silently reset the rules. */
    fun moveOverride(fromPath: String, toPath: String) {
        val o = overrideFor(fromPath) ?: return
        setOverride(toPath, o)
        clearOverride(fromPath)
    }

    // ---- resolution ----------------------------------------------------------

    /** Global defaults with this file's overrides layered on top. */
    fun effectiveFor(path: String): SaveSettings {
        val g = global()
        val o = overrideFor(path) ?: return g
        return g.copy(
            mode = o.mode ?: g.mode,
            inkFormat = o.inkFormat ?: g.inkFormat,
            copyNaming = o.copyNaming ?: g.copyNaming,
            copySuffix = o.copySuffix ?: g.copySuffix,
            copyLocation = o.copyLocation ?: g.copyLocation,
            copyFolder = o.copyFolder ?: g.copyFolder,
            autosave = o.autosave ?: g.autosave,
            autosaveSeconds = o.autosaveSeconds ?: g.autosaveSeconds,
            confirmOverwrite = o.confirmOverwrite ?: g.confirmOverwrite,
            backupOnOverwrite = o.backupOnOverwrite ?: g.backupOnOverwrite
        )
    }

    companion object {
        private const val K_MODE = "mode"
        private const val K_FORMAT = "format"
        private const val K_NAMING = "naming"
        private const val K_SUFFIX = "suffix"
        private const val K_LOCATION = "location"
        private const val K_FOLDER = "folder"
        private const val K_AUTOSAVE = "autosave"
        private const val K_AUTOSAVE_SECS = "autosave_secs"
        private const val K_CONFIRM = "confirm"
        private const val K_BACKUP = "backup"
        private const val K_OVERRIDES = "overrides"

        /**
         * Build the destination name for a copy. Always returns a path that does not yet
         * exist, so a copy can never silently clobber an earlier one.
         */
        fun copyTargetName(originalName: String, s: SaveSettings, exists: (String) -> Boolean): String {
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
}
