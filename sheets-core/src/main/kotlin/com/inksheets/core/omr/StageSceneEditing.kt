package com.inksheets.core.omr

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredStageScene(val version: Int = 1, val scene: StageScene)

/** Versioned, failure-isolated stage data. A bad or future scene returns null to its optional
 * editor; it is never part of loading a song, PDF, or score cache.
 */
object StageSceneCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(scene: StageScene): String = json.encodeToString(StoredStageScene(scene = scene))

    fun decodeOrNull(encoded: String): StageScene? = runCatching {
        json.decodeFromString<StoredStageScene>(encoded).takeIf { it.version == 1 }?.scene
    }.getOrNull()
}

/** Pure scene edits shared by pointer and accessible controls. Coordinates are clamped to the
 * room so a dragged item cannot become unreachable outside the editor.
 */
object StageSceneEditing {
    fun movePerformer(scene: StageScene, index: Int, x: Double, y: Double): StageScene {
        require(index in scene.performers.indices && x.isFinite() && y.isFinite())
        return scene.copy(performers = scene.performers.mapIndexed { at, performer ->
            if (at == index) performer.copy(x = clampX(scene, x), y = clampY(scene, y)) else performer
        })
    }

    fun moveMicrophone(scene: StageScene, index: Int, x: Double, y: Double): StageScene {
        require(index in scene.microphones.indices && x.isFinite() && y.isFinite())
        return scene.copy(microphones = scene.microphones.mapIndexed { at, microphone ->
            if (at == index) microphone.copy(x = clampX(scene, x), y = clampY(scene, y)) else microphone
        })
    }

    /** Preserve edits for known parts and add newly available score parts from a preset. */
    fun reconcile(scene: StageScene, document: ScoreDocument): StageScene {
        val ids = document.parts.filterNot { it.instrument == "score" }.map { it.id }.toSet()
        val existing = scene.performers.filter { it.partId in ids }
        val present = existing.map { it.partId }.toSet()
        val defaults = StagePresets.concertBand(document, scene.room).performers.filter { it.partId !in present }
        return scene.copy(performers = existing + defaults)
    }

    private fun clampX(scene: StageScene, x: Double) = x.coerceIn(-scene.room.width / 2.0, scene.room.width / 2.0)
    private fun clampY(scene: StageScene, y: Double) = y.coerceIn(0.0, scene.room.depth)
}
