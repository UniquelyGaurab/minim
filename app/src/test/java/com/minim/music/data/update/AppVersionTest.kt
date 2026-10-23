package com.minim.music.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    private fun v(s: String) = AppVersion.parse(s) ?: error("didn't parse $s")

    @Test
    fun vPrefixIsIgnored() {
        assertEquals(v("4.9.7"), v("v4.9.7"))
        assertEquals(v("4.9.7"), v("V4.9.7"))
        assertEquals(v("4.9.7"), v("  v4.9.7 "))
        assertEquals("4.9.7", v("v4.9.7").toString())
    }

    @Test
    fun numbersCompareAsNumbers() {
        assertTrue(v("4.9.10") > v("4.9.9"))
        assertTrue(v("v4.10.0") > v("v4.9.99"))
        assertTrue(v("5.0.0") > v("4.99.99"))
        assertTrue(v("4.9.6") > v("4.9.5"))
        assertTrue(v("4.9.5") < v("4.9.6"))
    }

    @Test
    fun equalVersions() {
        assertEquals(0, v("4.9.5").compareTo(v("v4.9.5")))
        assertEquals(0, v("4.9").compareTo(v("4.9.0")))
        assertEquals(0, v("4").compareTo(v("4.0.0")))
        assertEquals(0, v("4.9.5+build.7").compareTo(v("4.9.5")))
        assertEquals(v("4.9.5").hashCode(), v("v4.9.5").hashCode())
    }

    @Test
    fun prereleaseIsOlderThanTheRelease() {
        assertTrue(v("4.9.7-beta") < v("4.9.7"))
        assertTrue(v("4.9.7-beta") > v("4.9.6"))
        assertTrue(v("4.9.7-alpha") < v("4.9.7-beta"))
        assertTrue(v("4.9.7-beta.2") < v("4.9.7-beta.10"))
        assertTrue(v("4.9.7-beta") < v("4.9.7-beta.1"))
        assertTrue(v("4.9.7-1") < v("4.9.7-alpha"))
        assertTrue(v("4.9.7-rc.1").isPreRelease)
        assertFalse(v("4.9.7").isPreRelease)
    }

    @Test
    fun malformedGivesNull() {
        for (bad in listOf(
            "", " ", "v", "latest", "4.9.x", "4..9", "4.9.", ".4.9", "4.9.5.1", "-4.9.5",
            "4.09.5", "4.9.5-", "4.9.5-be ta", "4.9.5-beta..1", "vv4.9.5", "4,9,5",
            "99999999.0.0", "٤.٩.٥"
        )) {
            assertNull("\"$bad\" should not parse", AppVersion.parse(bad))
        }
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse("v" + "1.".repeat(40) + "1"))
    }

    @Test
    fun installedDropsTheDebugSuffix() {
        // Debug builds are named "4.9.5-debug"; that's not a prerelease of 4.9.5
        val installed = AppVersion.installed("4.9.5-debug")
        assertNotNull(installed)
        assertEquals(v("4.9.5"), installed)
        assertFalse(installed!!.isPreRelease)
        assertFalse(v("v4.9.5") > installed)
        assertTrue(v("v4.9.6") > installed)
        assertEquals(v("4.9.5"), AppVersion.installed("4.9.5"))
        assertNull(AppVersion.installed(null))
        assertNull(AppVersion.installed("debug"))
    }
}
