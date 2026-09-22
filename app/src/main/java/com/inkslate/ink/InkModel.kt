package com.inkslate.ink

import com.inkslate.data.StrokeIdGen

/**
 * One document's marks, and the history of changes to them.
 *
 * Held apart from [DrawingView] so that more than one view can show the same document - the same
 * document in both halves of a split, each half scrolled to its own place - and still be one
 * document: a stroke drawn in either is a stroke in both, and undo in either takes back the last
 * change made in either.
 *
 * The ids are here for the same reason. Two views each minting their own would both hand out
 * "this device, next number" and produce two marks with one id, which the merge treats as one mark
 * edited twice - the older of the two would simply vanish on the next sync.
 */
class InkModel(var ids: StrokeIdGen = StrokeIdGen("local")) {

    /** An undoable edit, as add/remove sets so erase-many is a single step. */
    data class Op(val added: List<Stroke>, val removed: List<Stroke>)

    val strokes = ArrayList<Stroke>()
    val undoStack = ArrayList<Op>()
    val redoStack = ArrayList<Op>()

    /** Bumped on every change to [strokes], so a view can tell its page index is stale. */
    var version = 0L
        private set

    private val views = LinkedHashSet<DrawingView>()

    fun attach(view: DrawingView) {
        views.add(view)
    }

    fun detach(view: DrawingView) {
        views.remove(view)
    }

    /** The marks changed. Every other view of them redraws; [from] already knows. */
    fun moved(from: DrawingView?) {
        version++
        for (v in views.toList()) if (v !== from) v.peerMoved()
    }

    /** Replace everything - a document opened, or read again - and start the history afresh. */
    fun setStrokes(list: List<Stroke>) {
        strokes.clear()
        strokes.addAll(list)
        ids.seedFrom(list.map { it.id })
        undoStack.clear()
        redoStack.clear()
        moved(null)
    }

    fun snapshot(): List<Stroke> = ArrayList(strokes)
    fun isEmpty(): Boolean = strokes.isEmpty()
    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()
}
