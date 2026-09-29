package com.inksheets.core.omr

import org.junit.Assume.assumeTrue
import org.junit.Test

/** A look inside the reader on the rough page: run with -Dinksheets.omr=debug. */
class RecognizerDebug {
    @Test
    fun `barlines on the rough page`() {
        assumeTrue(System.getProperty("inksheets.omr") == "debug")
        val page = TestPages.page(2)
        val m = RecognizerTest::class.java.getDeclaredMethod("roughen", Ink::class.java, Long::class.javaPrimitiveType, Double::class.javaPrimitiveType)
        m.isAccessible = true
        val rough = m.invoke(RecognizerTest(), page.ink, 2L, 0.4) as Ink
        val r = Recognizer(debug = true)
        val (t, sp) = r.metrics(rough)!!
        val staves = r.staves(rough, t, sp)
        val clean = r.withoutLines(rough, staves, t)
        println("t=$t space=$sp")
        for ((i, s) in staves.withIndex()) { println("staff $i ${s.left}..${s.right}"); r.barlines(rough, clean, s, t) }
    }
}
