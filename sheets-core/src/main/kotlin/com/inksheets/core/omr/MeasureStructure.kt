package com.inksheets.core.omr

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/** Source-page coordinates identify a corrected region independently of its changing bar number. */
@Serializable
data class MeasureSplit(val page: Int,val staff: Int,val left: Float,val right: Float,val cuts: List<Float>)

object MeasureStructure {
    private val json=Json { ignoreUnknownKeys=true }
    fun encode(splits: List<MeasureSplit>)=json.encodeToString(ListSerializer(MeasureSplit.serializer()),splits)
    fun decode(text: String?)=runCatching { json.decodeFromString(ListSerializer(MeasureSplit.serializer()),text.orEmpty()) }.getOrDefault(emptyList())
    fun correction(measure: Measure,pageWidth: Int,cuts: List<Float>): MeasureSplit =
        MeasureSplit(measure.page,measure.staff,measure.box.left.toFloat()/pageWidth,measure.box.right.toFloat()/pageWidth,
            cuts.filter { it>measure.box.left && it<measure.box.right }.distinct().sorted().map { it/pageWidth })

    fun apply(score: Score,splits: List<MeasureSplit>): Score {
        if(splits.isEmpty()) return score
        return splits.fold(score) { current,correction -> applyOne(current,correction) }
    }
    private fun applyOne(score: Score,stored: MeasureSplit): Score {
        var shift=0
        val measures=score.measures.flatMap { original ->
            val width=score.pageWidths.getOrNull(original.page)?.takeIf { it>0 }
            val correction=width?.let { w -> stored.takeIf {
                it.page==original.page && it.staff==original.staff &&
                    kotlin.math.abs(it.left-original.box.left.toFloat()/w)<.008f &&
                    kotlin.math.abs(it.right-original.box.right.toFloat()/w)<.008f
            } }
            val numbered=original.copy(number=original.number+shift)
            val parts=if(correction!=null && width!=null && original.bars==1) split(numbered,correction.cuts.map { it*width }) else listOf(numbered)
            shift+=parts.size-1
            parts
        }
        return score.copy(measures=measures)
    }

    /** Partition at printed barlines. Musical events and their source anchors are never rebuilt. */
    fun split(measure: Measure,cuts: List<Float>): List<Measure> {
        val boundaries=listOf(measure.box.left.toFloat())+cuts.filter { it.isFinite() }.map { it.roundToInt() }
            .filter { it>measure.box.left+1 && it<measure.box.right-1 }.distinct().sorted().map { it.toFloat() }+measure.box.right.toFloat()
        if(boundaries.size<3 || measure.bars!=1) return listOf(measure)
        fun owner(x: Float)=boundaries.drop(1).indexOfFirst { x<it }.let { if(it<0) boundaries.size-2 else it }
        return (0 until boundaries.size-1).map { index ->
            val left=boundaries[index];val right=boundaries[index+1]
            val events=measure.events.filter { owner(it.x)==index }
            val lines=if(measure.lines.size==10) {
                fun at(x: Float)=List(5) { n ->
                    val t=(x-measure.box.left)/measure.box.width.coerceAtLeast(1)
                    measure.lines[n]+(measure.lines[n+5]-measure.lines[n])*t
                }
                at(left)+at(right)
            } else measure.lines
            val outlines=measure.kept.indices.filter { n ->
                val points=measure.kept[n];val xs=points.indices.filter { it%2==0 }.map { points[it] }
                owner(xs.average().toFloat())==index
            }
            val found=events.sumOf { it.duration.quarters }
            val doubts=measure.doubts.filterNot { it.contains("beats found") || it=="nothing read" || it=="not one bar" }+
                if(events.isEmpty()) listOf("nothing read") else if(kotlin.math.abs(found-measure.time.quarters)>.01) listOf("$found beats found, ${measure.time.quarters} expected") else emptyList()
            measure.copy(number=measure.number+index,box=measure.box.copy(left=left.toInt(),right=right.toInt()),events=events,
                showsClef=index==0 && measure.showsClef,showsKey=index==0 && measure.showsKey,showsTime=index==0 && measure.showsTime,
                repeatStart=index==0 && measure.repeatStart,repeatEnd=index==boundaries.size-2 && measure.repeatEnd,
                segno=index==0 && measure.segno,coda=index==0 && measure.coda,start=if(index==0) measure.start else left.toInt(),
                lines=lines,doubts=doubts,maybe=measure.maybe.filter { owner(it.x)==index },graces=measure.graces.filter { owner(it.x)==index },
                kept=outlines.map { measure.kept[it] },keptShade=outlines.map { measure.keptShade.getOrElse(it) { 0 } },
                sourceSymbols=measure.sourceSymbols.filter { owner(it.origin.x)==index },
                sourceDecorations=measure.sourceDecorations.filter { d ->
                    val xs=d.contours.flatMap { points -> points.indices.filter { it%2==0 }.map { points[it] } }
                    owner(d.noteX ?: d.direction?.x ?: xs.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: measure.box.left.toFloat())==index
                },
                directions=measure.directions.filter { d ->
                    if(d.x==d.x2) owner(d.x)==index else maxOf(d.x,d.x2)>left && minOf(d.x,d.x2)<right
                }.map { d -> if(d.x==d.x2) d else d.copy(x=d.x.coerceIn(left,right),x2=d.x2.coerceIn(left,right)) })
        }
    }
}
