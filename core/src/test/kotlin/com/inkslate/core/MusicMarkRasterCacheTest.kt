package com.inkslate.core

import org.junit.Assert.*
import org.junit.Test

class MusicMarkRasterCacheTest {
    @Test fun `changing only brush masks reuses notation and does not allocate another image`() {
        val cache=MusicMarkRasterCache<Any>()
        val shapes=(0..999).map {i ->PageMark.rect((i%20)*10f,(i/20)*10f,(i%20)*10f+8,(i/20)*10f+8,0xff000000.toInt())}
        var renders=0
        fun frame(x:Float):MusicMarkRasterCache.Raster<Any>? {
            val mask=PageMark.Mask(listOf(PageMark.MaskStroke(listOf(floatArrayOf(x,0f,x+20,0f,x+20,800f,x,800f)),false)),width=800f,height=800f)
            return cache.get(shapes.map {PageMark(it.kind,it.contours,it.color,it.width,mask)},800f,800f,1f){_,_,_->renders++;Any()}
        }
        val image=frame(0f)!!.image
        repeat(60) {assertSame(image,frame(it.toFloat())!!.image)}
        assertEquals("Only the first live frame rasterizes static glyphs",1,renders)
    }
    @Test fun `zoom uses device resolution and falls back to vectors rather than blurry oversized bitmap`() {
        val cache=MusicMarkRasterCache<Any>()
        val marks=listOf(PageMark.rect(0f,0f,800f,800f,0xff000000.toInt()))
        val first=cache.get(marks,800f,800f,1f){w,h,_->assertEquals(800,w);assertEquals(800,h);Any()}!!
        val zoom=cache.get(marks,800f,800f,1.8f){w,h,_->assertEquals(1600,w);assertEquals(1600,h);Any()}!!
        assertNotSame(first.image,zoom.image)
        assertEquals(2f,zoom.scale,0f)
        assertNull(cache.get(marks,800f,800f,4f){_,_,_->fail("Deep zoom should stay vector");Any()})
    }
    @Test fun `correction or color changes cannot reuse stale notation`() {
        val cache=MusicMarkRasterCache<Any>()
        val mark=PageMark.rect(0f,0f,8f,8f,0xff000000.toInt())
        fun get(m:PageMark)=cache.get(listOf(m),100f,100f,1f){_,_,_->Any()}!!.image
        val original=get(mark)
        assertSame(original,get(PageMark(mark.kind,mark.contours,mark.color,mark.width)))
        assertNotSame(original,get(PageMark(mark.kind,mark.contours,0xffff0000.toInt(),mark.width)))
        assertNotSame(original,get(PageMark.rect(0f,0f,9f,8f,mark.color)))
    }
    @Test fun `evicted and cleared images are disposed`() {
        val disposed=ArrayList<Any>()
        val cache=MusicMarkRasterCache<Any>(maxPixels=100,totalPixels=150,dispose=disposed::add)
        val mark=PageMark.rect(0f,0f,8f,8f,0xff000000.toInt())
        val first=cache.get(listOf(mark),10f,10f,1f){_,_,_->Any()}!!.image
        val changed=PageMark(mark.kind,mark.contours,0xffff0000.toInt(),mark.width)
        val second=cache.get(listOf(changed),10f,10f,1f){_,_,_->Any()}!!.image
        assertEquals(listOf(first),disposed)
        cache.clear()
        assertEquals(listOf(first,second),disposed)
    }
}
