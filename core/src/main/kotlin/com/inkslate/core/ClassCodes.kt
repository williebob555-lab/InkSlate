package com.inkslate.core

import java.io.File

/**
 * Filing documents into class folders by the course number in their names.
 *
 * Worksheets arrive named the way the course names them - `PHYS161 Weekly Sheet 3.pdf`,
 * `HW4_222.pdf`, `Math 222 - Quiz.pdf` - and the folders they belong in are named for the same
 * course: `PHYS 161`, `MATH222`. Matching the two by the number is what a person does by eye, one
 * document at a time; this does it for the whole library at once and lets them look it over.
 *
 * Only numbers that some folder is named with are ever looked for, so a stray "2026" or a page
 * number in a document's name matters only if there is a folder called that too.
 */
object ClassCodes {

    /** One course number found in a name, with the letters written just before it, if any. */
    data class Code(val number: String, val subject: String?)

    /** Three or four digits standing on their own - not part of a longer number. */
    private val CODE = Regex("""(?:(?<![A-Za-z])([A-Za-z]{2,5})[\s_\-]?)?(?<!\d)(\d{3,4})(?!\d)""")

    /** Every course number in [name], in order. Years (1900-2099) are not course numbers. */
    fun codesIn(name: String): List<Code> {
        val base = name.substringBeforeLast('.').takeIf { '.' in name && name.substringAfterLast('.').length <= 4 } ?: name
        return CODE.findAll(base).mapNotNull { m ->
            val number = m.groupValues[2]
            if (number.length == 4 && number.toInt() in 1900..2099) return@mapNotNull null
            Code(number, m.groupValues[1].takeIf { it.isNotEmpty() }?.uppercase())
        }.toList()
    }

    /** A folder that stands for a course: it has a course number in its name. */
    data class ClassFolder(val folder: File, val codes: List<Code>) {
        val depth: Int get() = folder.absolutePath.count { it == File.separatorChar }
    }

    /** A move worth suggesting: [document] into [target], because of [code]. */
    data class Suggestion(
        val document: File,
        val target: File,
        val code: String,
        /** Other folders it could equally have gone in - the person picks. */
        val alternatives: List<File> = emptyList(),
        /** Something of that name is already in [target], so moving would need a rename. */
        val clash: Boolean = false
    )

    fun classFolders(folders: List<File>): List<ClassFolder> =
        folders.map { ClassFolder(it, codesIn(it.name)) }.filter { it.codes.isNotEmpty() }

    /**
     * Where each of [documents] belongs, among [folders].
     *
     * A document already somewhere inside a folder for its course is filed and left alone - a
     * homework folder inside the class folder is still the class. Where two folders carry the
     * number (MATH 161 and PHYS 161), the letters before it decide; failing that the outermost
     * folder wins, which is the class rather than something inside it, and the others are offered
     * as [Suggestion.alternatives].
     */
    fun suggest(documents: List<File>, folders: List<File>): List<Suggestion> {
        val classes = classFolders(folders)
        if (classes.isEmpty()) return emptyList()
        val byNumber = HashMap<String, MutableList<ClassFolder>>()
        for (c in classes) for (code in c.codes) byNumber.getOrPut(code.number) { mutableListOf() }.add(c)

        val out = ArrayList<Suggestion>()
        for (doc in documents) {
            val codes = codesIn(doc.name).filter { it.number in byNumber }
            if (codes.isEmpty()) continue
            val numbers = codes.map { it.number }.toSet()
            // Already inside a folder for one of its courses.
            if (ancestors(doc).any { a -> codesIn(a.name).any { it.number in numbers } }) continue

            var candidates = codes.flatMap { byNumber[it.number].orEmpty() }.distinctBy { it.folder.absolutePath }
            val subjects = codes.mapNotNull { it.subject }.toSet()
            if (candidates.size > 1 && subjects.isNotEmpty()) {
                val bySubject = candidates.filter { c -> c.codes.any { it.subject != null && it.subject in subjects && it.number in numbers } }
                if (bySubject.isNotEmpty()) candidates = bySubject
            }
            // Inside a candidate is inside the class: keep only the outermost of nested ones.
            candidates = candidates.filter { c ->
                candidates.none { o -> o !== c && isInside(c.folder, o.folder) }
            }.sortedWith(compareBy<ClassFolder> { it.depth }.thenBy { it.folder.name.lowercase() })
            val best = candidates.firstOrNull() ?: continue
            if (best.folder.absolutePath == doc.parentFile?.absolutePath) continue
            val code = codes.first { c -> best.codes.any { it.number == c.number } }
            out.add(
                Suggestion(
                    document = doc,
                    target = best.folder,
                    code = listOfNotNull(code.subject, code.number).joinToString(" "),
                    alternatives = candidates.drop(1).map { it.folder },
                    clash = File(best.folder, doc.name).exists()
                )
            )
        }
        return out.sortedWith(compareBy({ it.target.name.lowercase() }, { it.document.name.lowercase() }))
    }

    private fun ancestors(f: File): Sequence<File> = generateSequence(f.parentFile) { it.parentFile }

    private fun isInside(child: File, parent: File): Boolean =
        child.absolutePath.startsWith(parent.absolutePath + File.separator)
}
