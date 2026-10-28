package com.inksheets.core.omr
import org.junit.Assert.*
import org.junit.Test
class ArcConnectionTest {
    @Test fun `native PDF ties may start inside heads and ignore notes on other systems`() {
        val p=Printed(700f,600f,listOf(
            Printed.Symbol(Printed.Kind.HEAD_BLACK,527.96f,111.92f,8.31f),
            Printed.Symbol(Printed.Kind.HEAD_WHOLE,555.55f,111.92f,10.63f),
            Printed.Symbol(Printed.Kind.HEAD_BLACK,542f,210f,8.31f)),emptyList(),emptyList())
        val pair=p.tieEndpoints(Printed.Arc(531.99f,108.20f,558.34f,108.20f,-3.49f))
        assertNotNull(pair)
        assertEquals(527.96f,pair!!.first.x,.01f)
        assertEquals(555.55f,pair.second.x,.01f)
    }
    private fun printed(vararg x:Float)=Printed(300f,100f,x.map{Printed.Symbol(Printed.Kind.HEAD_BLACK,it,40f,12f)},emptyList(),emptyList())
    @Test fun `low adjacent-head tie has evidence and high or spanning phrase slurs do not`() {
        val p=printed(20f,90f)
        assertNotNull(p.tieEndpoints(Printed.Arc(33f,46f,89f,46f,5f)))
        assertNull(p.tieEndpoints(Printed.Arc(33f,18f,89f,18f,-10f)))
        assertNull(printed(20f,55f,90f).tieEndpoints(Printed.Arc(33f,46f,89f,46f,5f)))
        assertNull(p.tieEndpoints(Printed.Arc(33f,46f,89f,34f,5f)))
    }
}
