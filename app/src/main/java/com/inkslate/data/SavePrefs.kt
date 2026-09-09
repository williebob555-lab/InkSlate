package com.inkslate.data

import android.content.Context
import org.json.JSONObject

/**
 * The save rules themselves live in `:core`, shared with the Windows build.
 *
 * These decide what happens to a document on disk - whether saving overwrites the original,
 * whether the ink stays editable, what a copy is called - and two machines disagreeing about
 * that is not a difference anyone would want to discover afterwards. What stays here is only how
 * this platform stores them.
 */
typealias SaveMode = com.inkslate.core.SaveMode
typealias InkFormat = com.inkslate.core.InkFormat
typealias CopyNaming = com.inkslate.core.CopyNaming
typealias CopyLocation = com.inkslate.core.CopyLocation
typealias SaveSettings = com.inkslate.core.SaveSettings
typealias FileOverride = com.inkslate.core.FileOverride

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
        return overrideFor(path)?.appliedTo(g) ?: g
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
         * Build the destination name for a copy.
         *
         * The rule is in `core/CopyNames`, shared, so a copy made on the laptop and one made on
         * the tablet are named the same way. Kept here as well because every caller already has
         * a `SavePrefs` in hand.
         */
        fun copyTargetName(
            originalName: String,
            s: SaveSettings,
            exists: (String) -> Boolean
        ): String = com.inkslate.core.CopyNames.copyTargetName(originalName, s, exists)
    }
}
