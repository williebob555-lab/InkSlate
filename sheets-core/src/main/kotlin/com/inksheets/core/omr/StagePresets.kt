package com.inksheets.core.omr

import kotlin.math.atan2

/** Cheap, deterministic starting layouts. They create scene data only: no audio engine, UI, or
 * platform service is initialized until a caller explicitly asks for stage playback.
 */
object StagePresets {
    private data class Seat(val part: ScorePart, val row: Int, val order: Int)

    /** A broad concert-band layout, with woodwinds at the front and percussion at the rear.
     * Unknown instruments stay playable and are placed with the middle voices. A full-score
     * library entry is deliberately omitted because it duplicates the extracted playable parts.
     */
    fun concertBand(document: ScoreDocument, room: StageRoom = StageRoom()): StageScene {
        val seats = document.parts
            .filterNot { it.instrument == "score" }
            .mapIndexed { index, part ->
                val row = rowFor(part.instrument)
                Seat(part, row, orderFor(part.instrument) * 10_000 + (part.chair ?: 0) * 100 + index)
            }
        val performers = seats.groupBy { it.row }.toSortedMap().flatMap { (row, rowSeats) ->
            val ordered = rowSeats.sortedBy { it.order }
            val spacing = minOf(1.5, (room.width - 2.0).coerceAtLeast(1.0) / maxOf(ordered.size, 1))
            val centre = (ordered.size - 1) / 2.0
            ordered.mapIndexed { index, seat ->
                val x = ((index - centre) * spacing).coerceIn(-room.width / 2.0, room.width / 2.0)
                val y = minOf(room.depth - minOf(1.0, room.depth / 4.0), 3.0 + row * 2.0)
                StagePerformer(
                    partId = seat.part.id,
                    x = x,
                    y = y,
                    facing = atan2(-x, -y),
                    spread = if (seat.part.instrument in sectionInstruments) 0.8 else 0.0
                )
            }
        }
        return StageScene(
            podium = StagePoint(0.0, 0.0),
            listener = StagePoint(0.0, -2.0),
            room = room,
            performers = performers,
            microphones = listOf(
                StageMicrophone("main-left", x = -1.5, y = 1.0, height = 2.5,
                    pattern = MicrophonePattern.CARDIOID, pan = -0.65),
                StageMicrophone("main-right", x = 1.5, y = 1.0, height = 2.5,
                    pattern = MicrophonePattern.CARDIOID, pan = 0.65)
            )
        )
    }

    private fun rowFor(instrument: String?): Int = when (instrument) {
        "piccolo", "flute", "oboe", "english-horn", "bassoon" -> 0
        "clarinet", "alto-clarinet", "bass-clarinet", "contra-clarinet",
        "soprano-sax", "alto-sax", "tenor-sax", "bari-sax" -> 1
        "trumpet", "horn", "mellophone" -> 2
        "trombone", "bass-trombone", "baritone-tc", "baritone-bc", "euphonium-tc",
        "euphonium", "tuba", "string-bass", "bass-guitar" -> 3
        "percussion", "drumline", "steel-pan", "drums" -> 4
        else -> 2
    }

    private fun orderFor(instrument: String?): Int = concertBandOrder[instrument] ?: Int.MAX_VALUE / 10_000

    private val concertBandOrder = listOf(
        "piccolo", "flute", "oboe", "english-horn", "bassoon",
        "clarinet", "alto-clarinet", "bass-clarinet", "contra-clarinet",
        "soprano-sax", "alto-sax", "tenor-sax", "bari-sax",
        "trumpet", "horn", "mellophone", "trombone", "bass-trombone",
        "baritone-tc", "baritone-bc", "euphonium-tc", "euphonium", "tuba",
        "string-bass", "bass-guitar", "percussion", "drumline", "steel-pan", "drums"
    ).withIndex().associate { it.value to it.index }

    private val sectionInstruments = setOf(
        "flute", "clarinet", "alto-sax", "tenor-sax", "trumpet", "horn", "mellophone",
        "trombone", "baritone-bc", "euphonium", "tuba", "violin", "viola", "cello"
    )
}
