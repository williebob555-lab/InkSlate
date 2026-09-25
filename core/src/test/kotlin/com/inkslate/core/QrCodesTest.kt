package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodesTest {

    @Test
    fun `a code drawn as pixels reads back`() {
        val text = "inksheets://join?name=Stand%201&hosts=192.168.1.4,100.70.1.2&port=47820"
        val (w, h, px) = QrCodes.pixels(text, scale = 6)
        assertEquals(text, QrCodes.decode(w, h, px))
    }

    @Test
    fun `a code in a bigger picture, off to one side, still reads`() {
        val text = "inkslate://pair?name=Tab&hosts=10.0.0.2&port=47810&code=123456"
        val (w, h, px) = QrCodes.pixels(text, scale = 5)
        val bigW = w * 3
        val bigH = h * 2
        val big = IntArray(bigW * bigH) { 0xFFEEEEEE.toInt() }
        for (y in 0 until h) for (x in 0 until w) big[(y + h / 2) * bigW + x + w * 2 - 10] = px[y * w + x]
        assertEquals(text, QrCodes.decode(bigW, bigH, big))
    }

    @Test
    fun `pairing links carry the code and every address`() {
        val link = PairLink.of("InkSheets", "Willie's Tab S9", listOf("192.168.1.4", "100.70.1.2"), 47810, "042317")
        val p = PairLink.parse(link)!!
        assertEquals("Willie's Tab S9", p.name)
        assertEquals(listOf("192.168.1.4", "100.70.1.2"), p.hosts)
        assertEquals(47810, p.port)
        assertEquals("042317", p.code)
        assertNull(PairLink.parse("inksheets://join?name=x&hosts=1.2.3.4&port=1"))
    }
}
