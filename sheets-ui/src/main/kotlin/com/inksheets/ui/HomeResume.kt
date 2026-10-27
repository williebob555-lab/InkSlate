package com.inksheets.ui

import com.inksheets.core.Setlist
import com.inksheets.core.Song
import kotlinx.serialization.json.*

internal data class HomeSetResume(val setlist: Setlist, val song: Song, val index: Int, val page: Int, val partId: String?)

private fun SheetsState.lastSetKey() = "sheets.lastSetlist." + root?.absolutePath.orEmpty()

/** Device-local reading place; entry IDs survive reorders and repeated songs in the same set. */
internal fun SheetsState.rememberSetlistPosition(entryId: String?, page: Int, path: String?) {
    val list = playing?.first?.let { library?.setlist(it) } ?: return
    val entry = list.entries.firstOrNull { it.id == entryId } ?: return
    val song = library?.song(entry.songId) ?: return
    val part = song.parts.firstOrNull { fileOf(it.file)?.absolutePath == path } ?: return
    if (current?.id != song.id) return
    val value = buildJsonObject {
        put("set", list.id); put("entry", entry.id); put("part", part.id); put("page", page.coerceAtLeast(0))
    }.toString()
    if (platform.pref(lastSetKey()) != value) platform.setPref(lastSetKey(), value)
}

internal fun SheetsState.homeSetResume(): HomeSetResume? {
    return runCatching {
    val saved = Json.parseToJsonElement(platform.pref(lastSetKey()) ?: return@runCatching null).jsonObject
    val set = saved["set"]?.jsonPrimitive?.content?.let { library?.setlist(it) } ?: return@runCatching null
    val index = set.entries.indexOfFirst { it.id == saved["entry"]?.jsonPrimitive?.content }
    val entry = set.entries.getOrNull(index) ?: return@runCatching null
    val song = library?.song(entry.songId) ?: return@runCatching null
    HomeSetResume(set, song, index, saved["page"]?.jsonPrimitive?.intOrNull ?: 0, saved["part"]?.jsonPrimitive?.content)
    }.getOrNull()
}

internal fun SheetsState.resumeHomeSetlist() {
    val saved = homeSetResume() ?: return
    val part = partFor(saved.song)
    val file = part?.let { partFile(saved.song, it) }
    if (part == null || file == null) {
        edgeNotice = "Cannot resume ${saved.setlist.name}: ${saved.song.title}'s part is unavailable"
        edgeNoticeAt = System.currentTimeMillis()
        return
    }
    val first = (part.firstPage ?: 1) - 1
    val last = minOf(part.lastPage?.minus(1) ?: Int.MAX_VALUE, platform.pageCount(file)?.minus(1) ?: Int.MAX_VALUE)
    val page = if (part.id == saved.partId) saved.page.coerceIn(first, last.coerceAtLeast(first)) else first
    playSetlist(saved.setlist.id, saved.index, page)
}
