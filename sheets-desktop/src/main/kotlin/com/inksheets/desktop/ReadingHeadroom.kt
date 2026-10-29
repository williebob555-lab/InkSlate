package com.inksheets.desktop

import java.lang.management.ManagementFactory

/** Cheap, cached system-wide checks. Hysteresis prevents repeated stop/start under load. */
internal class ReadingHeadroom(
    private val sample: () -> Sample = ::systemSample,
    private val clock: () -> Long = { System.nanoTime()/1_000_000 }
) {
    data class Sample(val cpu: Double?,val freeMemory: Long?,val totalMemory: Long?,val heapHeadroom: Long,val heapMax: Long)
    private var nextCheck=Long.MIN_VALUE
    private var quietSince: Long?=null
    private var blocked=false
    @Synchronized fun allowed(): Boolean {
        val now=clock()
        if(now<nextCheck)return !blocked
        nextCheck=now+2000
        val s=runCatching(sample).getOrNull()
        if(s==null) {blocked=true;quietSince=null;return false}
        val physicalFloor=maxOf(512*MIB,(s.totalMemory ?: 0)/10)
        val pressure=(s.cpu?.let {it>=.80}==true) ||
            (s.freeMemory?.let {it<physicalFloor}==true) ||
            s.heapHeadroom<maxOf(128*MIB,s.heapMax/10) ||
            (s.cpu==null && s.freeMemory==null)
        if(pressure) {blocked=true;quietSince=null}
        else if(blocked) {
            val recovered=(s.cpu==null || s.cpu<.60) &&
                (s.freeMemory==null || s.freeMemory>maxOf(768*MIB,(s.totalMemory ?: 0)*15/100)) &&
                s.heapHeadroom>maxOf(192*MIB,s.heapMax/5)
            if(!recovered)quietSince=null
            else {
                val since=quietSince ?: now.also {quietSince=it}
                if(now-since>=10_000)blocked=false
            }
        }
        return !blocked
    }
    companion object {
        private const val MIB=1024L*1024
        private fun systemSample(): Sample {
            val base=ManagementFactory.getOperatingSystemMXBean()
            val os=base as? com.sun.management.OperatingSystemMXBean
            val cpu=os?.cpuLoad?.takeIf {it.isFinite() && it>=0}
                ?: base.systemLoadAverage.takeIf {it.isFinite() && it>=0}?.div(base.availableProcessors.coerceAtLeast(1))
            val runtime=Runtime.getRuntime()
            return Sample(cpu,os?.freeMemorySize?.takeIf {it>=0},os?.totalMemorySize?.takeIf {it>0},
                runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory()),runtime.maxMemory())
        }
    }
}
