package com.inkslate.core

import kotlin.math.ceil

/** Bounded raster copies of static music notation, independent of the changing brush mask.
 * A mask preview clips one image instead of replaying thousands of glyph paths. Resolution
 * follows the actual canvas transform; deep zoom falls back to vectors rather than blurring.
 */
class MusicMarkRasterCache<T>(
    private val maxPixels:Long=4_000_000L,
    private val totalPixels:Long=8_000_000L,
    private val dispose:(T)->Unit={}
) {
    class Raster<T>(val image:T,val scale:Float,val width:Int,val height:Int)
    private class Entry<T>(val marks:List<PageMark>,val pageWidth:Float,val pageHeight:Float,val raster:Raster<T>)
    private val entries=ArrayList<Entry<T>>()
    fun get(marks:List<PageMark>,width:Float,height:Float,deviceScale:Float,
            render:(width:Int,height:Int,scale:Float)->T):Raster<T>? {
        if(!width.isFinite() || !height.isFinite() || width<=0f || height<=0f || !deviceScale.isFinite())return null
        val scale=(ceil(deviceScale.coerceAtLeast(1f)*2f)/2f)
        val w=ceil(width.toDouble()*scale).toLong();val h=ceil(height.toDouble()*scale).toLong()
        if(w<=0 || h<=0 || w>maxPixels || h>maxPixels || w*h>maxPixels)return null
        val index=entries.indexOfFirst { e -> e.pageWidth==width && e.pageHeight==height && e.raster.scale==scale &&
            e.marks.size==marks.size && e.marks.indices.all { i -> val a=e.marks[i];val b=marks[i]
                a.contours===b.contours && a.kind==b.kind && a.color==b.color && a.width==b.width } }
        if(index>=0)return entries.removeAt(index).also {entries.add(it)}.raster
        val raster=Raster(render(w.toInt(),h.toInt(),scale),scale,w.toInt(),h.toInt())
        entries.add(Entry(marks.toList(),width,height,raster))
        var pixels=entries.sumOf {it.raster.width.toLong()*it.raster.height}
        while(entries.size>4 || pixels>totalPixels && entries.size>1) {
            val old=entries.removeAt(0);pixels-=old.raster.width.toLong()*old.raster.height
            dispose(old.raster.image)
        }
        return raster
    }
    fun clear() {
        entries.forEach { dispose(it.raster.image) }
        entries.clear()
    }
}
