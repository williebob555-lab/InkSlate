package com.inksheets.core.omr
import kotlin.math.abs

/** A tie hugs adjacent heads; a phrase curve can pass over stems or intervening notes. */
internal fun Printed.tieEndpoints(arc:Printed.Arc):Pair<Printed.Symbol,Printed.Symbol>? {
    for(a in heads.sortedBy{abs(arc.x0-(it.x+it.width))}) {
        val w=a.width.coerceAtLeast(1f)
        val dy=arc.y0-a.y
        if(arc.x0-(a.x+w) !in -w*.7f..w*.8f || abs(dy) !in w*.15f..w*1.05f)continue
        val b=heads.filter{it.x>a.x+w*.8f && abs(it.y-a.y)<w*.35f}
            .minByOrNull{abs(it.x-arc.x1)} ?:continue
        if(b.x-arc.x1 !in -w*.7f..w*.8f || (arc.y1-b.y)*dy<=0f || abs(arc.y1-b.y)>w*1.05f)continue
        // Notes on other systems can share these x coordinates. They do not
        // interrupt a tie on this staff.
        if(heads.any{abs(it.y-a.y)<w*4.5f && it.x>a.x+w*1.4f && it.x<b.x-w*.4f})continue
        return a to b
    }
    return null
}
