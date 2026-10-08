package com.inkslate.core

import java.io.File
import java.util.UUID

/**
 * Where the pictures on a page live: captured regions, inserted photos, pasted images.
 *
 * They used to be written beside the document, as `<name>.inkassets/<id>.png` - a folder and a
 * file per capture, sitting in the person's coursework where every file explorer, every sync and
 * every home-screen listing could see them. Nothing about a capture asks for a file of its own:
 * it is part of the page, like a stroke.
 *
 * So the pictures are kept in the app's own storage, by id, and travel *inside* the document the
 * same way the handwriting does - [InkPayload] carries the pictures a document uses after its
 * handwriting. A document that arrives from another device brings its pictures with it, and
 * opening it puts them here ([keep]). Nothing is written next to the document; a picture becomes
 * a file of its own only when someone asks for one, and puts it where they choose.
 *
 * Ids are random and global, so one store serves every document, and a picture copied from one
 * document into another is the same picture in both.
 */
object Pictures {

    /** Set at startup by each app; until it is, nothing is kept and nothing is found. */
    @Volatile var dir: File? = null

    /** Ids are hex, so a name can never climb out of the folder. */
    private val ID = Regex("^[0-9a-zA-Z_-]{1,64}$")

    fun isId(id: String) = ID.matches(id)

    /** Keep [png] and return its new id, or null when there is nowhere to keep it. */
    fun put(png: ByteArray): String? {
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        return if (keep(id, png)) id else null
    }

    /** The picture's file, when this device has it. */
    fun file(id: String): File? {
        if (!isId(id)) return null
        return dir?.let { File(it, "$id.png") }?.takeIf { it.isFile && it.length() > 0 }
    }

    fun bytes(id: String): ByteArray? = file(id)?.let { runCatching { it.readBytes() }.getOrNull() }

    /**
     * Keep a picture under an id it already has - from a document, the link, or an older build's
     * folder. A picture already here is left alone: the same id is the same picture.
     */
    fun keep(id: String, png: ByteArray): Boolean = runCatching {
        if (!isId(id) || png.isEmpty()) return false
        val d = dir ?: return false
        val target = File(d, "$id.png")
        if (target.isFile && target.length() > 0) return true
        d.mkdirs()
        val tmp = File(d, ".$id.${System.nanoTime()}.tmp")
        tmp.writeBytes(png)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    /** Every picture [doc] shows, by id. */
    fun idsIn(doc: InkDocument): Set<String> =
        doc.pages.values.asSequence().flatten().mapNotNull { it.imageId }.toSortedSet()

    /** The folder an older build kept [document]'s pictures in, beside it. */
    fun legacyFolder(document: File): File = File(document.parentFile, "${document.name}$LEGACY_SUFFIX")

    /** Folders named like this are an older build's pictures, never the person's own folders. */
    const val LEGACY_SUFFIX = ".inkassets"

    fun isLegacyFolder(f: File) = f.name.endsWith(LEGACY_SUFFIX, ignoreCase = true)

    /**
     * Take in the pictures an older build left beside [document], so they can travel inside it
     * from its next save on. The folder itself stays until [retireLegacyFolder] says it may go.
     */
    fun adoptLegacy(document: File) {
        val folder = legacyFolder(document)
        val files = folder.listFiles { f -> f.isFile && f.extension.equals("png", true) } ?: return
        for (f in files) {
            if (file(f.nameWithoutExtension) == null) {
                runCatching { keep(f.nameWithoutExtension, f.readBytes()) }
            }
        }
    }

    /**
     * Remove the folder of pictures an older build left beside [document], once the document
     * itself carries every picture it uses - [payload] being what was just written into it and
     * read back. Pictures in the folder that nothing uses any more were garbage already. A folder
     * holding anything else at all is left exactly as it is.
     */
    fun retireLegacyFolder(document: File, doc: InkDocument, payload: ByteArray?) {
        val folder = legacyFolder(document)
        if (!folder.isDirectory) return
        val carried = InkPayload.pictureIds(payload)
        if (!carried.containsAll(idsIn(doc))) return
        val contents = folder.listFiles() ?: return
        if (contents.any { !it.isFile || !(it.extension.equals("png", true) || it.name.endsWith(".tmp")) }) return
        // Kept here before the only other copy goes, in case this device has not seen one yet.
        adoptLegacy(document)
        contents.forEach { it.delete() }
        folder.delete()
    }
}
