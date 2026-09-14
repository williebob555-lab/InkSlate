package com.inkslate.desktop

import org.junit.Test
import java.io.File

/**
 * Prints what kinds of marks a real document holds. Does nothing unless asked:
 * `INKSLATE_INSPECT=C:/path/doc.pdf gradlew :desktop:test --tests *InspectDocumentTest* --rerun`.
 */
class InspectDocumentTest {
    @Test
    fun inspect() {
        val paths = System.getenv("INKSLATE_INSPECT") ?: return
        val out = StringBuilder()
        for (path in paths.split(';')) {
            val ink = DesktopEmbedder.read(File(path))
            out.appendLine("== $path")
            if (ink == null) { out.appendLine("no handwriting"); continue }
            val strokes = ink.pages.values.flatten()
            out.appendLine("${strokes.size} marks, ${ink.deleted.size} tombstones")
            strokes.groupBy { it.kind }.forEach { (kind, list) ->
                out.appendLine("  $kind: ${list.size}  (by device: ${list.groupBy { it.id.substringBefore('-') }.mapValues { it.value.size }})")
            }
            strokes.filter { it.kind.name != "FREEHAND" }.take(12).forEach {
                out.appendLine("    ${it.id} ${it.kind} points=${it.points.size} brush=${it.brush}")
            }
            strokes.filter { it.kind.name == "FREEHAND" }.take(6).forEach {
                out.appendLine("    ${it.id} FREEHAND points=${it.points.size}")
            }
        }
        File(System.getenv("INKSLATE_INSPECT_OUT") ?: "inspect.txt").writeText(out.toString())
    }
}

/**
 * Builds the link messages opening a real document would send, and checks each survives the wire.
 * Does nothing unless asked: `INKSLATE_INSPECT=C:/path/doc.pdf ... --tests *LinkMessagesForDocumentTest*`.
 */
class LinkMessagesForDocumentTest {
    @Test
    fun roundTrip() {
        val path = System.getenv("INKSLATE_INSPECT")?.split(';')?.first() ?: return
        val ink = DesktopEmbedder.read(File(path)) ?: return
        val out = StringBuilder()
        val messages = listOf(
            "digest" to com.inkslate.core.peer.PeerSync.digestOf(ink),
            "answer" to com.inkslate.core.peer.PeerSync.answerFor(
                ink, com.inkslate.core.peer.PeerMessage.Digest(ink.docId)
            ),
            "editing" to com.inkslate.core.peer.PeerMessage.Editing(ink.docId, File(path).name)
        )
        for ((name, m) in messages) {
            val text = runCatching { com.inkslate.core.peer.PeerMessage.encode(m) }
            if (text.isFailure) {
                out.appendLine("$name: ENCODE FAILED ${text.exceptionOrNull()}")
                continue
            }
            val back = com.inkslate.core.peer.PeerMessage.decode(text.getOrThrow())
            out.appendLine("$name: ${text.getOrThrow().length} chars, decode ${if (back == null) "FAILED" else "ok"}")
            if (back == null) {
                val err = runCatching {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true; classDiscriminator = "t" }
                        .decodeFromString<com.inkslate.core.peer.PeerMessage>(text.getOrThrow())
                }.exceptionOrNull()
                out.appendLine("   because: $err")
            }
        }
        File(System.getenv("INKSLATE_INSPECT_OUT") ?: "inspect.txt").writeText(out.toString())
    }
}
