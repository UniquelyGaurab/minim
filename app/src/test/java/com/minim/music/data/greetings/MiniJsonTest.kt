package com.minim.music.data.greetings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class MiniJsonTest {

    @Test
    fun readsValues() {
        val v = MiniJson.parse("\uFEFF" + """ { "a": [1, -2.5, 3e2, true, false, null], "s": "x\"\n\u00e9\/" , "o": {} }""") as Map<*, *>
        assertEquals(listOf(1L, -2.5, 300.0, true, false, null), v["a"])
        assertEquals("x\"\n\u00e9/", v["s"])
        assertEquals(emptyMap<String, Any?>(), v["o"])
        assertNull(MiniJson.parse("null"))
    }

    @Test
    fun rejectsBrokenInput() {
        for (bad in listOf("", "{", "{\"a\"}", "{\"a\":}", "[1,]", "[01x]", "\"\\q\"", "\"\\u12\"", "tru", "{} {}", "\"a\u0001\"", "-", "1.", "[" .repeat(MiniJson.MAX_DEPTH + 2))) {
            try {
                MiniJson.parse(bad)
                fail("should fail: $bad")
            } catch (e: MiniJson.MiniJsonException) {
                // expected
            }
        }
    }
}
