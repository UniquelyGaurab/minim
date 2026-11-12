package com.minim.music.data.fermata

import org.junit.Assert.assertEquals
import org.junit.Test

/** 6.1.1: backup event counts as 3.8K, 1.3M, 2B instead of long numbers. */
class CompactCountTest {

    @Test
    fun underAThousandStaysAsItIs() {
        assertEquals("0", CompactCount.format(0))
        assertEquals("1", CompactCount.format(1))
        assertEquals("42", CompactCount.format(42))
        assertEquals("999", CompactCount.format(999))
    }

    @Test
    fun thousandsMillionsAndBillionsWithOneDecimal() {
        assertEquals("3.8K", CompactCount.format(3_785))
        assertEquals("3K", CompactCount.format(3_023))
        assertEquals("1K", CompactCount.format(1_000))
        assertEquals("1K", CompactCount.format(1_049))
        assertEquals("1.1K", CompactCount.format(1_050))
        assertEquals("12.3K", CompactCount.format(12_345))
        assertEquals("100K", CompactCount.format(100_000))
        assertEquals("999.9K", CompactCount.format(999_949))
        assertEquals("1M", CompactCount.format(1_000_000))
        assertEquals("1.3M", CompactCount.format(1_250_000))
        assertEquals("12.3M", CompactCount.format(12_345_678))
        assertEquals("1B", CompactCount.format(1_000_000_000))
        assertEquals("2.5B", CompactCount.format(2_460_000_000))
        assertEquals("1500B", CompactCount.format(1_500_000_000_000))
    }

    @Test
    fun neverAThousandOfALetter() {
        assertEquals("1M", CompactCount.format(999_950))
        assertEquals("1M", CompactCount.format(999_999))
        assertEquals("1B", CompactCount.format(999_950_000))
        assertEquals("1B", CompactCount.format(999_999_999))
    }

    @Test
    fun pluralForm() {
        assertEquals(1, CompactCount.quantity(1))
        assertEquals(2, CompactCount.quantity(0))
        assertEquals(2, CompactCount.quantity(3_785))
    }
}
