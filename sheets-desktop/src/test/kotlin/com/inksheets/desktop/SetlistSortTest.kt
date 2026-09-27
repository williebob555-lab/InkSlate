package com.inksheets.desktop

import com.inksheets.core.Setlist
import com.inksheets.ui.SetlistSort
import com.inksheets.ui.readDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetlistSortTest {

    @Test
    fun `a concert date is read however it is typed`() {
        assertEquals("2026-10-03", readDate("2026-10-03"))
        assertEquals("2026-10-03", readDate("10/3/2026"))
        assertEquals("2026-10-03", readDate("3 Oct 2026"))
        assertEquals("2026-10-03", readDate("October 3, 2026"))
        assertNull(readDate(""))
        assertNull(readDate("soon"))
    }

    @Test
    fun `by concert date, the next one first, past ones after, undated last`() {
        val today = java.time.LocalDate.now()
        fun s(name: String, date: String?) = Setlist(name, name, date = date)
        val list = listOf(
            s("undated", null), s("last year", today.minusYears(1).toString()), s("next month", today.plusMonths(1).toString()),
            s("next week", today.plusWeeks(1).toString()), s("last week", today.minusWeeks(1).toString())
        )
        assertEquals(listOf("next week", "next month", "last week", "last year", "undated"), SetlistSort.DATE.apply(list).map { it.name })
    }
}
