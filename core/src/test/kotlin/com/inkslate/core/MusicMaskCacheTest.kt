package com.inkslate.core
import org.junit.Assert.*
import org.junit.Test

class MusicMaskCacheTest {
    @Test fun `changing a live stroke recomputes only that stroke and preserves erasures`() {
        var operations=0
        val cache=MusicMaskCache<MutableSet<Int>>(empty={mutableSetOf()},copy={it.toMutableSet()},apply={p,s->operations++;val n=s.contours[0][0].toInt();p.apply{if(s.erase)remove(n)else add(n)}})
        fun stroke(n:Int,erase:Boolean=false)=PageMark.MaskStroke(listOf(floatArrayOf(n.toFloat(),0f,1f,1f,2f,2f)),erase)
        val committed=(1..100).map{stroke(it)}
        cache.path(PageMark.Mask(committed))
        val before=operations
        repeat(40){i->assertTrue(cache.path(PageMark.Mask(committed+stroke(101+i))).contains(1))}
        assertEquals("Historical strokes must not be unioned for every pointer movement",40,operations-before)
        val erased=cache.path(PageMark.Mask(committed+stroke(1,true)))
        assertFalse(erased.contains(1));assertTrue(erased.contains(100))
        assertEquals(committed.size,cache.path(PageMark.Mask(committed)).size)
        assertEquals(0,cache.path(PageMark.Mask(emptyList(),sourceKey="another page")).size)
    }
}
