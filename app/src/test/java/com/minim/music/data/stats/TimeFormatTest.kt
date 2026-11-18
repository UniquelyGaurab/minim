package com.minim.music.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test

/** 6.9.0 b78: the Stats format and the Reprise (minutes only) format, at their boundaries. */
class TimeFormatTest {
    private val min = 60_000L
    private val hr = 60 * min

    @Test fun statsUnderAnHour() {
        assertEquals("0 min", TimeFormat.stats(0))
        assertEquals("0 min", TimeFormat.stats(59_000))
        assertEquals("0 min", TimeFormat.stats(-5_000))
        assertEquals("1 min", TimeFormat.stats(min))
        assertEquals("45 min", TimeFormat.stats(45 * min))
        assertEquals("59 min", TimeFormat.stats(59 * min + 59_000))
    }

    @Test fun statsHoursAndMinutes() {
        assertEquals("1 hr", TimeFormat.stats(60 * min))
        assertEquals("1 hr 1 min", TimeFormat.stats(61 * min))
        assertEquals("2 hr", TimeFormat.stats(2 * hr))
        assertEquals("2 hr 15 min", TimeFormat.stats(2 * hr + 15 * min))
        assertEquals("99 hr", TimeFormat.stats(99 * hr))
        assertEquals("99 hr 59 min", TimeFormat.stats(99 * hr + 59 * min))
    }

    @Test fun statsBigTotalsAreHoursOnly() {
        assertEquals("100 hrs", TimeFormat.stats(100 * hr))
        assertEquals("100 hrs", TimeFormat.stats(100 * hr + 59 * min))
        assertEquals("142 hrs", TimeFormat.stats(142 * hr + 30 * min))
        assertEquals("1,250 hrs", TimeFormat.stats(1_250 * hr))
    }

    @Test fun theOldStatsHelpersUseIt() {
        assertEquals("2 hr 15 min", StatsFormat.duration(2 * hr + 15 * min))
        assertEquals("45 min", StatsPeriods.long(45 * min))
        assertEquals("142 hrs", StatsPeriods.long(142 * hr))
        assertEquals("1 hr 5 min", TapeStats.savedText(65 * min))
    }

    @Test fun repriseIsMinutesOnly() {
        assertEquals("0 minutes", TimeFormat.reprise(0))
        assertEquals("0 minutes", TimeFormat.reprise(59_000))
        assertEquals("1 minute", TimeFormat.reprise(min))
        assertEquals("59 minutes", TimeFormat.reprise(59 * min))
        assertEquals("60 minutes", TimeFormat.reprise(60 * min))
        assertEquals("61 minutes", TimeFormat.reprise(61 * min))
        assertEquals("5,940 minutes", TimeFormat.reprise(99 * hr))
        assertEquals("6,000 minutes", TimeFormat.reprise(100 * hr))
        assertEquals("4,380 minutes", TimeFormat.reprise(73 * hr))
        assertEquals("1,234,567 minutes", TimeFormat.reprise(1_234_567 * min))
    }

    @Test fun repriseShort() {
        assertEquals("0 min", TimeFormat.repriseShort(0))
        assertEquals("1 min", TimeFormat.repriseShort(min))
        assertEquals("61 min", TimeFormat.repriseShort(61 * min))
        assertEquals("4,380 min", TimeFormat.repriseShort(73 * hr))
        assertEquals("6,000 min", TimeFormat.repriseShort(100 * hr))
    }

    @Test fun repriseNeverSaysHours() {
        for (m in listOf(0L, 1, 59, 60, 61, 99 * 60, 100 * 60, 525_600)) {
            val a = TimeFormat.reprise(m * min)
            val b = TimeFormat.repriseShort(m * min)
            assert(!a.contains("hr") && !a.contains("hour") && !b.contains("hr")) { "$a / $b" }
        }
    }
}
