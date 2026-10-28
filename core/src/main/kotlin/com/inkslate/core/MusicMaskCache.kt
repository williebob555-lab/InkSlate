package com.inkslate.core

/** Reuse the completed stroke prefix while the current brush stroke changes. */
class MusicMaskCache<P>(private val empty:(PageMark.Mask)->P,private val copy:(P)->P,
                        private val apply:(P,PageMark.MaskStroke)->P) {
    private data class Key(val full:Boolean,val width:Float,val height:Float,val source:Any?)
    private class State<P>(val strokes:List<PageMark.MaskStroke>,val path:P,val beforeLast:P?)
    private val pages=object:LinkedHashMap<Key,State<P>>(4,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Key,State<P>>?)=size>8
    }
    @Synchronized fun path(mask:PageMark.Mask):P {
        val key=Key(mask.baseFull,mask.width,mask.height,mask.sourceKey)
        val old=pages[key]
        fun matches(count:Int)=old!=null && count<=mask.strokes.size && (0 until count).all{old.strokes[it]===mask.strokes[it]}
        if(old!=null && old.strokes.size==mask.strokes.size && matches(old.strokes.size))return old.path
        var start=0
        var result=when {
            old!=null && matches(old.strokes.size)->{start=old.strokes.size;copy(old.path)}
            old!=null && old.beforeLast!=null && matches((old.strokes.size-1).coerceAtLeast(0))->{start=(old.strokes.size-1).coerceAtLeast(0);copy(old.beforeLast)}
            else->empty(mask)
        }
        var beforeLast:P?=null
        for(i in start until mask.strokes.size) {
            if(i==mask.strokes.lastIndex)beforeLast=copy(result)
            result=apply(result,mask.strokes[i])
        }
        pages[key]=State(mask.strokes,result,beforeLast)
        return result
    }
}
