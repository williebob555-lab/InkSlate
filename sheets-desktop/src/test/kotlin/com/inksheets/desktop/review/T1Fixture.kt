package com.inksheets.desktop.review

import com.inksheets.core.Library
import com.inksheets.core.LibraryLog
import com.inksheets.core.LibraryScan
import java.io.File

/** Shared helpers for tester T1: a library of copies of real files under build/, and a findings log. */
object T1 {
    val master = File("../build/t1lib")
    val pool = File("../build/t1pool/small.pdf")

    /** A fresh copy of the master test library (files over [maxMb] left out). */
    fun copyLib(name: String, maxMb: Int = 6): File {
        val dst = File("build/t1work/$name").apply { deleteRecursively(); mkdirs() }
        master.walkTopDown().filter { it.isFile }.forEach { f ->
            if (f.length() > maxMb * 1_000_000L) return@forEach
            val to = File(dst, f.relativeTo(master).path)
            to.parentFile.mkdirs()
            f.copyTo(to)
        }
        return dst
    }

    class Lib(val root: File, device: String = "t1-laptop") {
        val log = LibraryLog(root, device)
        val library = Library(log)
        val scan = LibraryScan(root, library, File(root.parentFile, root.name + "-memory-$device.json"))
        fun titles() = library.songs.map { it.title }
        fun parts() = library.songs.sumOf { it.parts.size }
    }

    fun say(line: String) { println("T1> $line") }

    fun timed(label: String, block: () -> Unit): Double {
        val t = System.nanoTime(); block()
        val ms = (System.nanoTime() - t) / 1e6
        say("TIME $label: ${"%.1f".format(ms)} ms")
        return ms
    }

    fun touch(f: File, text: String = "x") { f.parentFile.mkdirs(); f.writeText(text) }
}
