package com.minim.music.data.greetings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DateRangeTest {

    @Test
    fun simpleRangeAnyYear() {
        val r = DateRange(12, 24, 12, 26, null, 12, 25)
        assertFalse(r.matches(2026, 12, 23))
        assertTrue(r.matches(2026, 12, 24))
        assertTrue(r.matches(2031, 12, 26))
        assertFalse(r.matches(2026, 12, 27))
        assertTrue(r.isMainDay(2026, 12, 25))
        assertFalse(r.isMainDay(2026, 12, 24))
    }

    @Test
    fun singleDay() {
        val r = DateRange(8, 15, 8, 15)
        assertTrue(r.matches(2027, 8, 15))
        assertTrue(r.isMainDay(2027, 8, 15))
        assertFalse(r.matches(2027, 8, 14))
        assertFalse(r.matches(2027, 8, 16))
    }

    @Test
    fun rangeCrossingTheNewYear() {
        val r = DateRange(12, 31, 1, 1, null, 1, 1)
        assertTrue(r.crossesNewYear)
        assertTrue(r.matches(2026, 12, 31))
        assertTrue(r.matches(2027, 1, 1))
        assertFalse(r.matches(2026, 12, 30))
        assertFalse(r.matches(2027, 1, 2))
        assertFalse(r.matches(2027, 6, 1))
        assertTrue(r.isMainDay(2027, 1, 1))
        assertFalse(r.isMainDay(2026, 12, 31))
    }

    @Test
    fun yearSpecific() {
        val r = DateRange(11, 6, 11, 10, 2026, 11, 8)
        assertTrue(r.matches(2026, 11, 8))
        assertFalse(r.matches(2027, 11, 8))
        assertFalse(r.matches(2025, 11, 8))
        assertTrue(r.isMainDay(2026, 11, 8))
        assertFalse(r.isMainDay(2027, 11, 8))
    }

    @Test
    fun yearSpecificCrossingTheNewYearEndsInTheNextYear() {
        val r = DateRange(12, 30, 1, 2, 2026)
        assertTrue(r.matches(2026, 12, 30))
        assertTrue(r.matches(2026, 12, 31))
        assertTrue(r.matches(2027, 1, 1))
        assertTrue(r.matches(2027, 1, 2))
        assertFalse(r.matches(2026, 1, 1))   // January before the range started
        assertFalse(r.matches(2027, 12, 31)) // a year too late
        assertFalse(r.matches(2028, 1, 1))
    }

    @Test
    fun calendarOverloadUsesOneBasedMonths() {
        val r = DateRange(1, 26, 1, 26)
        val c = Calendar.getInstance().apply { set(2027, Calendar.JANUARY, 26, 10, 0) }
        assertTrue(r.matches(c))
        assertTrue(r.isMainDay(c))
        c.set(Calendar.MONTH, Calendar.FEBRUARY)
        assertFalse(r.matches(c))
    }
}
