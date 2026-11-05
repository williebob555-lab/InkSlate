package com.inksheets.ui

import com.inksheets.core.omr.ScoreDocument
import com.inksheets.core.omr.StagePresets
import com.inksheets.core.omr.StageScene
import com.inksheets.core.omr.StageSceneCodec
import com.inksheets.core.omr.StageSceneEditing
import java.io.File
import java.nio.file.StandardCopyOption

/** Optional per-song sidecars. Nothing calls this until the stage editor is explicitly opened. */
internal object StageSceneFiles {
    fun load(state: SheetsState, songId: String, document: ScoreDocument): StageScene {
        val saved = file(state, songId).takeIf(File::isFile)?.let { runCatching { it.readText() }.getOrNull() }
            ?.let(StageSceneCodec::decodeOrNull)
        return if (saved == null) StagePresets.concertBand(document)
        else StageSceneEditing.reconcile(saved, document)
    }

    fun save(state: SheetsState, songId: String, scene: StageScene): Result<File> = runCatching {
        val target = file(state, songId)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(StageSceneCodec.encode(scene))
        runCatching {
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        target
    }

    private fun file(state: SheetsState, songId: String): File {
        require(songId.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid song id" }
        return File(File(state.platform.localFolder, "stages"), "$songId.json")
    }
}
