package com.inksheets.ui

import com.inksheets.core.AudioTrack
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Apply only what an open recording editor changed to the current synced list. */
internal fun mergeAudioEdits(before: List<AudioTrack>, edited: List<AudioTrack>, latest: List<AudioTrack>): List<AudioTrack> {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun fields(track: AudioTrack) = json.parseToJsonElement(json.encodeToString(track)) as JsonObject
    val old = before.associateBy { it.file }
    val next = edited.associateBy { it.file }
    val removed = old.keys - next.keys
    val result = latest.filter { it.file !in removed }.map { current ->
        val was = old[current.file]
        val now = next[current.file]
        if (was == null || now == null || was == now) current else {
            val original = fields(was); val changed = fields(now)
            val merged = fields(current).toMutableMap()
            changed.forEach { (key, value) -> if (value != original[key]) merged[key] = value }
            json.decodeFromString<AudioTrack>(JsonObject(merged).toString())
        }
    }.toMutableList()
    edited.filter { it.file !in old && result.none { current -> current.file == it.file } }.forEach(result::add)
    val beforeOrder = before.map { it.file }.filter { it in next }
    val afterOrder = edited.map { it.file }.filter { it in old }
    return if (beforeOrder != afterOrder) {
        edited.mapNotNull { wanted -> result.firstOrNull { it.file == wanted.file } } + result.filter { it.file !in next }
    } else result
}
