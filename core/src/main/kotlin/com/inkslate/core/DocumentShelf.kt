package com.inkslate.core

import java.io.File
import java.util.Calendar

/**
 * The list of documents on Home: what order it is in, what it is narrowed to, and the headings it
 * is broken up under - the way the music library's song list works, for coursework.
 *
 * Pure, so both apps sort and filter the same way and it can be tested without a screen.
 */
object DocumentShelf {

    /** One document, as far as the list is concerned. */
    data class Item(
        val file: File,
        val modified: Long,
        val size: Long,
        val annotated: Boolean
    ) {
        val name: String get() = file.name
        val isPdf: Boolean get() = file.extension.equals("pdf", true)
    }

    enum class Sort(val label: String) {
        RECENT("Recent"), NAME("A-Z"), FOLDER("Folder"), CLASS("Class"), SIZE("Size")
    }

    /** What the list can be narrowed to. Each is offered only while it would change something. */
    enum class Filter(val label: String) {
        ANNOTATED("Written on"),
        UNTOUCHED("Not started"),
        PDFS("PDFs"),
        PICTURES("Pictures"),
        THIS_WEEK("This week"),
        /** Sitting loose at the top of the library rather than in a folder of their own. */
        UNFILED("Not in a folder")
    }

    data class Query(
        val sort: Sort = Sort.RECENT,
        val filters: Set<Filter> = emptySet(),
        /** Only documents somewhere inside this folder. */
        val within: File? = null,
        val text: String = ""
    )

    data class Group(val title: String, val items: List<Item>)

    fun matches(item: Item, filter: Filter, roots: List<File>, now: Long): Boolean = when (filter) {
        Filter.ANNOTATED -> item.annotated
        Filter.UNTOUCHED -> !item.annotated
        Filter.PDFS -> item.isPdf
        Filter.PICTURES -> !item.isPdf
        Filter.THIS_WEEK -> now - item.modified < 7L * DAY
        Filter.UNFILED -> roots.any { it.absolutePath == item.file.parentFile?.absolutePath }
    }

    /**
     * [items] narrowed and ordered by [query]. Filters of one kind (pictures or PDFs, written on
     * or not) would together show nothing, so within a kind they widen and across kinds narrow.
     */
    fun apply(items: List<Item>, query: Query, roots: List<File>, now: Long = System.currentTimeMillis()): List<Item> {
        val words = query.text.trim().lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val kinds = query.filters.groupBy { kindOf(it) }
        val kept = items.filter { item ->
            (query.within == null || isInside(item.file, query.within)) &&
                kinds.values.all { same -> same.any { matches(item, it, roots, now) } } &&
                words.all { w -> relative(item.file, roots).lowercase().contains(w) }
        }
        return when (query.sort) {
            Sort.RECENT -> kept.sortedByDescending { it.modified }
            Sort.NAME -> kept.sortedWith(compareBy(NATURAL) { it.name })
            Sort.FOLDER -> kept.sortedWith(compareBy<Item, String>(NATURAL) { folderLabel(it.file, roots) }.thenByDescending { it.modified })
            Sort.CLASS -> kept.sortedWith(compareBy<Item, String>(NATURAL) { classLabel(it.file, roots) }.thenByDescending { it.modified })
            Sort.SIZE -> kept.sortedByDescending { it.size }
        }
    }

    /** How many of [items] each filter would leave, for offering only the useful ones. */
    fun counts(items: List<Item>, roots: List<File>, now: Long = System.currentTimeMillis()): Map<Filter, Int> =
        Filter.entries.associateWith { f -> items.count { matches(it, f, roots, now) } }

    /** The headings the sorted list falls under. */
    fun group(sorted: List<Item>, sort: Sort, roots: List<File>, now: Long = System.currentTimeMillis()): List<Group> {
        val groups = LinkedHashMap<String, MutableList<Item>>()
        for (item in sorted) {
            val title = when (sort) {
                Sort.RECENT -> whenLabel(item.modified, now)
                Sort.NAME -> item.name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
                    ?.let { if (it.isDigit()) "#" else it.toString() } ?: "#"
                Sort.FOLDER -> folderLabel(item.file, roots)
                Sort.CLASS -> classLabel(item.file, roots)
                Sort.SIZE -> sizeLabel(item.size)
            }
            groups.getOrPut(title) { mutableListOf() }.add(item)
        }
        return groups.map { (t, list) -> Group(t, list) }
    }

    /** The folder a document is in, said from the top of the library: "PHYS 161 / Homework". */
    fun folderLabel(file: File, roots: List<File>): String {
        val parent = file.parentFile ?: return ""
        val root = roots.firstOrNull { parent.absolutePath == it.absolutePath || isInside(parent, it) }
            ?: return parent.name
        if (parent.absolutePath == root.absolutePath) return root.name.ifEmpty { root.absolutePath }
        val rel = parent.absolutePath.removePrefix(root.absolutePath + File.separator)
        return rel.replace(File.separator, " / ")
    }

    /**
     * The class a document belongs to: the course number in its folders, or in its own name
     * when it has not been filed yet, or failing both its top folder.
     */
    fun classLabel(file: File, roots: List<File>): String {
        val parent = file.parentFile
        val root = roots.firstOrNull { parent != null && (parent.absolutePath == it.absolutePath || isInside(parent, it)) }
        var at = parent
        while (at != null) {
            if (ClassCodes.codesIn(at.name).isNotEmpty()) return at.name
            if (root == null || at.absolutePath == root.absolutePath) break
            at = at.parentFile
        }
        ClassCodes.codesIn(file.name).firstOrNull()?.let { c -> return c.number }
        return "No class"
    }

    private fun whenLabel(at: Long, now: Long): String {
        val today = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return when {
            at >= today -> "Today"
            at >= today - DAY -> "Yesterday"
            at >= today - 6 * DAY -> "This week"
            at >= today - 30 * DAY -> "This month"
            else -> "Earlier"
        }
    }

    private fun sizeLabel(bytes: Long): String = when {
        bytes >= 50L shl 20 -> "Over 50 MB"
        bytes >= 10L shl 20 -> "10 - 50 MB"
        bytes >= 1L shl 20 -> "1 - 10 MB"
        else -> "Under 1 MB"
    }

    private fun kindOf(f: Filter) = when (f) {
        Filter.ANNOTATED, Filter.UNTOUCHED -> 0
        Filter.PDFS, Filter.PICTURES -> 1
        Filter.THIS_WEEK -> 2
        Filter.UNFILED -> 3
    }

    private fun relative(file: File, roots: List<File>): String {
        val root = roots.firstOrNull { isInside(file, it) } ?: return file.name
        return file.absolutePath.removePrefix(root.absolutePath)
    }

    fun isInside(child: File, parent: File): Boolean =
        child.absolutePath.startsWith(parent.absolutePath + File.separator)

    /** "Sheet 2" before "Sheet 10", the way a person counts. */
    val NATURAL: Comparator<String> = Comparator { a, b ->
        val x = a.lowercase(); val y = b.lowercase()
        var i = 0; var j = 0
        while (i < x.length && j < y.length) {
            val cx = x[i]; val cy = y[j]
            if (cx.isDigit() && cy.isDigit()) {
                var ei = i; while (ei < x.length && x[ei].isDigit()) ei++
                var ej = j; while (ej < y.length && y[ej].isDigit()) ej++
                val nx = x.substring(i, ei).trimStart('0'); val ny = y.substring(j, ej).trimStart('0')
                if (nx.length != ny.length) return@Comparator nx.length - ny.length
                val c = nx.compareTo(ny)
                if (c != 0) return@Comparator c
                i = ei; j = ej
            } else {
                if (cx != cy) return@Comparator cx - cy
                i++; j++
            }
        }
        (x.length - i) - (y.length - j)
    }

    private const val DAY = 24L * 60 * 60 * 1000
}
