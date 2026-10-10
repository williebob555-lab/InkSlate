package com.inksheets.desktop

import com.inksheets.core.Chroma
import com.inksheets.core.InstrumentReader
import com.inksheets.core.MusicPresence
import com.inksheets.core.ScoreFollower
import com.inksheets.core.TimeStretch
import com.inksheets.core.omr.BandAudio
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.PlayOrder
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.ScoreAudio
import com.inksheets.core.omr.Scores
import com.inksheets.core.omr.Strips
import com.inksheets.core.omr.Ink
import org.apache.pdfbox.Loader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.abs

/**
 * Turning pages by ear, measured on the Pep Band's songs: each song's recording played into the
 * follower as a room would hear it (slower or faster, quieter, with noise), and every page turn it
 * makes set against where that turn falls in the recording.
 *
 * Where a turn really falls is taken from lining the whole recording up with the whole band's
 * music at once (offline, both ends known) - far surer than following as it goes, which is what is
 * being measured. -Dinksheets.turns=1 [-Dinksheets.turns.songs=September,Hot Hot Hot]
 * [-Dinksheets.turns.mine=Electric Bass]
 */
class PageTurnBench {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/Imported/PEP BAND/Music")
    private val cache = File("build/turnbench").apply { mkdirs() }

    private fun decode(file: File): Pair<FloatArray, Int> {
        AudioSystem.getAudioInputStream(file).use { raw ->
            val src = raw.format
            val pcm = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.sampleRate, 16, src.channels, src.channels * 2, src.sampleRate, false)
            AudioSystem.getAudioInputStream(pcm, raw).use { d ->
                val bytes = d.readAllBytes()
                val ch = pcm.channels
                val frames = bytes.size / (2 * ch)
                return FloatArray(frames) { f ->
                    var s = 0f
                    for (c in 0 until ch) { val at = (f * ch + c) * 2; s += ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort() / 32768f }
                    s / ch
                } to pcm.sampleRate.toInt()
            }
        }
    }

    /** A part read as the app reads it (a PDF that states its notes, from them), kept between runs. */
    private fun read(file: File): Score? {
        val kept = File(cache, file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9]+"), "-") + "-${file.length()}.json")
        if (kept.isFile) Scores.decode(kept.readText())?.let { return it }
        val source = com.inkslate.desktop.DesktopSources.open(file, detached = true) ?: return null
        val pages = Loader.loadPDF(file).use { it.numberOfPages }
        val carry = Recognizer.Carry(); var number = 1
        val measures = ArrayList<Measure>(); val widths = ArrayList<Int>()
        for (p in 0 until pages) {
            fun draw(width: Int): Pair<IntArray, Ink> {
                val img = source.render(p, width)!!
                val px = IntArray(img.width * img.height); img.readPixels(px)
                return Strips.grey(px) to Ink.fromArgb(img.width, img.height, px)
            }
            val space = Recognizer().metrics(draw(1600).second)?.second
            val width = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
            val (grey, ink) = draw(width)
            val printed = runCatching { PdfPrinted.read(file, p) }.getOrNull()
            val r = Recognizer().read(ink, p, number, carry, printed, grey = grey, net = if (printed == null) com.inksheets.core.omr.Net.shipped else null)
            measures += r.measures; widths += ink.width
            r.measures.lastOrNull()?.let { number = it.number + it.bars }
        }
        val score = Score(measures, pages, widths)
        kept.writeText(Scores.encode(score))
        return score
    }

    /** What a part's instrument is: its written-above-sounding semitones, and whether it has pitches at all. */
    private fun instrumentOf(file: File): Pair<Int, Boolean> {
        val words = file.nameWithoutExtension.lowercase()
        if (listOf("drum", "snare", "cymbal", "percussion", "perc", "quads", "tenor drum", "bass drum", "score").any { words.contains(it) }) return 0 to false
        val inst = InstrumentReader.readFileName(file.name)?.instrument
        return (inst?.transpose ?: 0) to true
    }

    /** What a follower did through one run: its place a tenth of a second at a time, and whether it was trusting it. */
    class Trace(val path: List<Long>, val locked: BooleanArray, val trusted: BooleanArray)

    /** Whether the follower stands behind its place (the same rule Listen turns pages by). */
    private fun trustedOf(w: com.inksheets.core.WindowFollower) = w.locked && !w.suspect && w.turnSafe

    /**
     * Where [follow] says the music is, a tenth of a second at a time, as [clip] at [speed] is heard -
     * from sample [fromSample] (a start part way through), for at most [maxS] seconds heard.
     */
    private fun followed(reference: List<Chroma.Frame>, clip: FloatArray, rate: Int, speed: Double, mode: String,
                         fromSample: Int = 0, startMs: Long = 0, startEndMs: Long = startMs, maxS: Double = Double.MAX_VALUE, pageAt: ((Long) -> Int)? = null): Trace {
        val old = if (mode == "old") ScoreFollower(reference) else null
        val prior = (System.getProperty("inksheets.turns.prior") ?: "0.0002").toFloat()
        val back = (System.getProperty("inksheets.turns.back") ?: "6").toFloat()
        val win = if (mode == "new") com.inksheets.core.WindowFollower(reference, startMs, prior = prior, backCost = back, startEndMs = startEndMs, pageAt = pageAt) else null
        val leg = if (mode == "legacy") LegacyWindowFollower(reference, startMs, prior = prior, backCost = back, startEndMs = startEndMs) else null
        val follower = object { val positionMs get() = old?.positionMs ?: win?.positionMs ?: leg!!.positionMs
            fun hear(f: Chroma.Frame, q: Boolean) = old?.hear(f, q) ?: win?.hear(f, q) ?: leg!!.hear(f, q) }
        val presence = MusicPresence()
        val stream = Chroma.Stream(rate)
        val live = TimeStretch(if (fromSample == 0) clip else clip.copyOfRange(fromSample, clip.size), rate).apply { this.speed = speed }
        val random = java.util.Random(11)
        val block = FloatArray(rate / 10)
        val out = ArrayList<Long>()
        val trusted = ArrayList<Boolean>()
        val locked = ArrayList<Boolean>()
        var work = 0L
        while (!live.atEnd && out.size < maxS * 10) {
            live.read(block)
            for (i in block.indices) block[i] = block[i] * 0.4f + (random.nextGaussian() * 0.01).toFloat()
            var at = follower.positionMs
            val t0 = System.nanoTime()
            for (f in stream.feed(block)) { presence.hear(f); at = follower.hear(f, presence.quiet(f)) }
            work += System.nanoTime() - t0
            out += at
            locked += win?.locked ?: leg?.let { it.found && it.confidence >= LegacyWindowFollower.UNSURE } ?: true
            trusted += win?.let { trustedOf(it) } ?: leg?.let { it.found && it.confidence >= LegacyWindowFollower.UNSURE } ?: true
        }
        workMs += work / 1_000_000; heardS += out.size / 10.0
        return Trace(out, locked.toBooleanArray(), trusted.toBooleanArray())
    }

    /** One run's lock behaviour, against the music's true place ([musicAtFrom] gives it for a tenth heard). */
    class Lock(val label: String, val start: String, val lockS: Double, val firstTrustS: Double, val searchingPct: Double,
               val flips: Int, val wrongJumps: Int, val trustedWrongPct: Double, val heardS: Double, val turnWrongPct: Double = 0.0, val turnOkPct: Double = 100.0)

    private fun lockOf(label: String, start: String, t: Trace, toTurn: IntArray?, truthAt: (Int) -> Int?): Lock {
        val n = t.path.size
        fun err(k: Int): Double? = truthAt(k)?.let { abs(t.path[k] / 100.0 - it) / 10.0 }
        val firstTrust = (0 until n).firstOrNull { t.locked[it] } ?: -1
        val lock = (0 until n).firstOrNull { t.locked[it] && (err(it) ?: 99.0) <= 3.0 && (it until minOf(n, it + 20)).all { j -> (err(j) ?: 0.0) <= 3.0 } } ?: -1
        // From the first moment it stood behind a place: the time it did not (searching), how often it let go,
        // and how long it stood behind a place that was not where the music was.
        var turnOk = 0; var turnWrong = 0; var searching = 0; var counted = 0; var flips = 0; var wrongJumps = 0; var tw = 0
        if (firstTrust >= 0) for (k in firstTrust until n) {
            counted++
            if (!t.locked[k]) searching++
            if (k > firstTrust && t.locked[k - 1] && !t.locked[k]) flips++
            if (t.locked[k] && (err(k) ?: 0.0) > 3.0) tw++
            if (t.trusted[k]) turnOk++
            // Trusted for turning, but the next turn it would make is more than 3 s from the true one.
            if (toTurn != null && t.trusted[k]) { val tr = truthAt(k); val p = (t.path[k] / 100).toInt().coerceIn(0, toTurn.size - 1)
                if (tr != null && abs(toTurn[p] - toTurn[tr.coerceIn(0, toTurn.size - 1)]) > 30) turnWrong++ }
        }
        for (k in 1 until n) if (abs(t.path[k] - t.path[k - 1]) > 4000 && (err(k) ?: 0.0) > 3.0) wrongJumps++
        return Lock(label, start, if (lock < 0) Double.NaN else lock / 10.0, if (firstTrust < 0) Double.NaN else firstTrust / 10.0,
            if (counted == 0) 100.0 else searching * 100.0 / counted, flips, wrongJumps, if (counted == 0) 0.0 else tw * 100.0 / counted, n / 10.0, if (counted == 0) 0.0 else turnWrong * 100.0 / counted, if (counted == 0) 0.0 else turnOk * 100.0 / counted).also { println("    LOCKRUN $start ${"%-8s".format(label)} lock ${it.lockS}s first-trust ${it.firstTrustS}s searching ${"%.0f".format(it.searchingPct)}% flips ${it.flips} wrong-jumps ${it.wrongJumps} lost ${"%.0f".format(it.trustedWrongPct)}% turn-ok ${"%.0f".format(it.turnOkPct)}% turn-wrong ${"%.1f".format(it.turnWrongPct)}%") }
    }

    private val locks = ArrayList<Lock>()

    private fun printLocks() {
        if (locks.isEmpty()) return
        fun med(v: List<Double>) = v.filter { !it.isNaN() }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
        println("LOCK table: start | follower | runs | locked | median time to lock s | locked<8s | searching % after lock | flips/run | wrong jumps/run | locked-but-off % | turn-trusted % | turn-trusted-but-wrong %")
        for ((key, g) in locks.groupBy { it.start to it.label }) {
            val runs = g.size
            val locked = g.count { !it.lockS.isNaN() }
            println("LOCK ${key.first.padEnd(5)} | ${key.second.padEnd(8)} | $runs | $locked | ${"%.1f".format(med(g.map { it.lockS }))} | ${g.count { !it.lockS.isNaN() && it.lockS < 8.0 }}/$runs | " +
                "${"%.1f".format(g.map { it.searchingPct }.average())} | ${"%.2f".format(g.map { it.flips }.average())} | ${"%.2f".format(g.map { it.wrongJumps }.average())} | ${"%.1f".format(g.map { it.trustedWrongPct }.average())} | ${"%.1f".format(g.map { it.turnOkPct }.average())} | ${"%.2f".format(g.map { it.turnWrongPct }.average())}")
        }
    }

    private var workMs = 0L
    private var heardS = 0.0

    @Test
    fun `page turns by ear on the Pep Band's songs`() {
        assumeTrue(System.getProperty("inksheets.turns") != null)
        val wanted = System.getProperty("inksheets.turns.songs")?.split(",")?.map { it.trim() }
        val mineName = System.getProperty("inksheets.turns.mine") ?: "Electric Bass"
        val songs = music.listFiles().orEmpty().filter { it.isDirectory && (wanted == null || it.name in wanted) }.sortedBy { it.name }
        val all = ArrayList<Pair<String, Double>>()
        val allGated = ArrayList<Pair<String, Double>>()
        val posAll = LinkedHashMap<String, ArrayList<Double>>()
        for (dir in songs) {
            val audio = dir.listFiles { f -> f.extension.lowercase() in setOf("mp3", "wav") }.orEmpty().firstOrNull() ?: continue
            val pdfs = dir.listFiles { f -> f.extension.lowercase() == "pdf" }.orEmpty().sortedBy { it.name }
            // The part followed: the one asked for where it turns pages, else the first parts that do (two at most).
            val pageCount = pdfs.associateWith { f -> runCatching { Loader.loadPDF(f).use { it.numberOfPages } }.getOrDefault(0) }
            val turning = pdfs.filter { (pageCount[it] ?: 0) >= 2 && instrumentOf(it).second }
            val pitched = pdfs.filter { instrumentOf(it).second }
            val chosen = (turning.filter { it.name.contains(mineName, ignoreCase = true) }.ifEmpty { turning }.ifEmpty {
                pitched.filter { it.name.contains(mineName, ignoreCase = true) }.ifEmpty { pitched } }).take(if (turning.isEmpty()) 1 else 2)
            if (chosen.isEmpty()) { println("TURNS ${dir.name}: no pitched part"); continue }
            for (mineFile in chosen) {
            val mine = read(mineFile) ?: continue
            val (myTr, _) = instrumentOf(mineFile)
            val others = pdfs.filter { it != mineFile }.mapNotNull { f ->
                val (tr, pitched) = instrumentOf(f)
                if (!pitched) null else read(f)?.let { BandAudio.Voice(it, tr) }
            }
            val (samples, rate) = runCatching { decode(audio) }.getOrNull() ?: run { println("TURNS ${dir.name}: recording not decodable"); continue }
            val recording = Chroma.frames(Chroma.resample(samples, rate))
            // The tempo the band is at, from how long the recording is against how long the music is.
            val beatsMs = BandAudio.bars(mine, 60.0).let { b -> b.last().startMs + b.last().lengthMs }
            val bpm = (beatsMs / 1000.0 / (recording.size * 0.1)) * 60.0
            // The truth: the whole band lined up with the whole recording.
            val truth = BandAudio.changesIn(mine, myTr, others, recording, bpm).map { it.first }
            // Where in the music each tenth of the recording is, from the same line-up: the truth for following.
            val map = ScoreAudio.align(BandAudio.frames(mine, myTr, others, bpm), recording)
            val musicAt = IntArray(recording.size).also { inv -> var i = 0; for (r in inv.indices) { while (i + 1 < map.size && map[i + 1] <= r) i++; inv[r] = i } }
            val ownChanges = ScoreAudio.changesIn(PlayOrder.unrolled(mine), recording, bpm, myTr).map { it.first }
            println("TURNS ${dir.name} / ${mineFile.nameWithoutExtension}: ${mine.pages} pages, ${others.size} other parts, recording ${recording.size / 10}s, about ${"%.0f".format(bpm)} bpm; " +
                "turns (whole band, offline) ${truth.joinToString { "%.1f".format(it / 1000.0) }}s; own part offline ${ownChanges.joinToString { "%.1f".format(it / 1000.0) }}s")
            val changesRef = BandAudio.pageChanges(mine, bpm).map { it.first }
            val ownRef = ScoreAudio.frames(PlayOrder.unrolled(mine), bpm, myTr)
            val bandRef = BandAudio.frames(mine, myTr, others, bpm)
            // Which page the music is on at a time in it, for the band's reference and for the part alone.
            fun pagesFrom(changes: List<Pair<Long, Int>>): (Long) -> Int = { ms -> changes.lastOrNull { it.first <= ms }?.second ?: 0 }
            val bandPage = pagesFrom(BandAudio.pageChanges(mine, bpm))
            val ownPage = pagesFrom(ScoreAudio.pageChanges(PlayOrder.unrolled(mine), bpm))
            fun pageAtOf(label: String): (Long) -> Int = if (label.endsWith("own")) ownPage else bandPage
            fun toTurnOf(label: String): IntArray {
                val ref = if (label.endsWith("own")) ownRef else bandRef
                val pa = pageAtOf(label); val page = IntArray(ref.size) { pa(it * 100L) }
                return IntArray(ref.size) { 3000 }.also { t -> for (j in ref.size - 2 downTo 0) t[j] = if (page[j + 1] != page[j]) 0 else minOf(3000, t[j + 1] + 1) }
            }
            for (speed in (System.getProperty("inksheets.turns.speeds")?.split(",")?.map { it.toDouble() } ?: listOf(0.92, 1.08))) {
                val configs = listOf(Triple("old own", ownRef, "old"), Triple("old band", bandRef, "old"), Triple("new own", ownRef, "new"), Triple("new band", bandRef, "new"), Triple("win0 own", ownRef, "legacy"), Triple("win0 band", bandRef, "legacy"))
                    .filter { c -> System.getProperty("inksheets.turns.only")?.split(",")?.contains(c.first) ?: true }
                for ((label, reference, mode) in configs) {
                    val changes = changesRef
                    val trace = followed(reference, samples, rate, speed, mode, pageAt = pageAtOf(label))
                    val path = trace.path
                    // How far from the music's true place it is, every tenth of a second once the music has started.
                    val posErr = path.indices.filter { it > 50 }.mapNotNull { k ->
                        val r = (k * speed).toInt(); if (r >= musicAt.size) null else abs(path[k] / 100.0 - musicAt[r]) / 10.0 }
                    if (posErr.isNotEmpty()) { posAll.getOrPut(label) { ArrayList() } += posErr }
                    if (System.getProperty("inksheets.turns.dump") != null && speed < 1.0) println("    PATH $label: " + path.indices.filter { it % 30 == 0 }.joinToString(" ") { k ->
                        val r = (k * speed).toInt(); "${k / 10}:${path[k] / 1000}/${if (r < musicAt.size) musicAt[r] / 10 else -1}" })
                    if (mode != "old" && System.getProperty("inksheets.turns.lock") != null) {
                        locks += lockOf(label, "start", trace, toTurnOf(label)) { k -> val r = (k * speed).toInt(); if (r >= musicAt.size) null else musicAt[r] }
                        // Started part way down a page: three places, page by page.
                        val bR = listOf(0L) + truth + listOf(recording.size * 100L)
                        val bM = listOf(0L) + changesRef + listOf(ownRef.size * 100L)
                        if (bR.size == bM.size) for ((pick, frac) in listOf(0.25 to 0.5, 0.5 to 0.3, 0.75 to 0.7)) {
                            val p = (pick * (bR.size - 1)).toInt().coerceIn(0, bR.size - 2)
                            val tR = bR[p] + ((bR[p + 1] - bR[p]) * frac).toLong()
                            val from = (tR / 1000.0 * rate).toInt().coerceIn(0, samples.size - rate)
                            val t0 = from / 10 * 10 / (rate / 10).coerceAtLeast(1)
                            val base = (from.toDouble() / rate * 10).toInt()
                            val tr = followed(reference, samples, rate, speed, mode, from, bM[p], bM[p + 1], maxS = 120.0, pageAt = pageAtOf(label))
                            locks += lockOf(label, "mid", tr, toTurnOf(label)) { k -> val r = base + (k * speed).toInt(); if (r >= musicAt.size) null else musicAt[r] }
                        }
                    }
                    val within = posErr.count { it <= 2.0 } * 100 / maxOf(1, posErr.size)
                    // When each turn is made: the first moment the follower is past it, less a moment's lead.
                    val errs = changes.zip(truth).map { (refMs, trueMs) ->
                        val made = path.indexOfFirst { it >= refMs - 500 }
                        val madeS = if (made < 0) Double.NaN else made * 0.1
                        madeS - trueMs / 1000.0 / speed
                    }
                    // The same, but as Listen makes them: only while it stands behind its place.
                    val gated = changes.zip(truth).map { (refMs, trueMs) ->
                        val made = path.indices.firstOrNull { path[it] >= refMs - 500 && trace.trusted[it] } ?: -1
                        if (made < 0) Double.NaN else made * 0.1 - trueMs / 1000.0 / speed
                    }
                    gated.forEach { allGated += label to it }
                    val shown = errs.joinToString { if (it.isNaN()) "never" else "%+.1f".format(it) }
                    println("  ${"%-8s".format(label)} at ${(speed * 100).toInt()}%: place within 2 s $within%, median off ${"%.1f".format(posErr.sorted().getOrNull(posErr.size / 2) ?: Double.NaN)} s; turn errors (s) $shown")
                    errs.forEach { all += "$label" to it }
                }
            }
            }
        }
        printLocks()
        println("WORK following: ${workMs} ms for ${"%.0f".format(heardS)} s heard (${"%.1f".format(workMs / heardS)} ms a second, every follower together)")
        for ((label, e) in posAll) println("PLACE $label: within 2 s ${e.count { it <= 2.0 } * 100 / maxOf(1, e.size)}%, within 5 s ${e.count { it <= 5.0 } * 100 / maxOf(1, e.size)}%, median off ${"%.1f".format(e.sorted()[e.size / 2])} s")
        for (label in listOf("win0 band", "new band", "win0 own", "new own")) {
            val e = allGated.filter { it.first == label }.map { it.second }
            if (e.isEmpty()) continue
            println("GATED $label: ${e.size} turns made only while trusted; within 4 s early to 1 s late ${e.count { !it.isNaN() && it >= -4.0 && it <= 1.0 }}/${e.size}; never made ${e.count { it.isNaN() }}")
        }
        for (label in listOf("old own", "old band", "win0 own", "win0 band", "new own", "new band")) {
            val e = all.filter { it.first == label }.map { it.second }
            if (e.isEmpty()) continue
            val good = e.count { !it.isNaN() && it >= -4.0 && it <= 1.0 }
            println("SUMMARY $label: ${e.size} turns; within 4 s early to 1 s late ${good}/${e.size}; never made ${e.count { it.isNaN() }}; " +
                "median |error| ${"%.1f".format(e.filter { !it.isNaN() }.map { abs(it) }.sorted().let { it.getOrNull(it.size / 2) ?: Double.NaN })} s")
        }
    }
}
