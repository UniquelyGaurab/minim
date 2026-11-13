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

    /** 5.10.1 (V6): the update checker still reads this build's own name. */
    @Test
    fun theV6TestBuildNameStillParses() {
        val installed = AppVersion.installed("5.10.1-debug")
        assertEquals(v("5.10.1"), installed)
        assertFalse(v("v5.10.0") > installed!!)
        assertFalse(v("v5.9.1") > installed)
        assertFalse(v("v5.10.1") > installed)
        assertTrue(v("v5.10.2") > installed)
        assertTrue(v("v6.0.0") > installed)
        assertTrue("5.10 is newer than 5.9, not older", v("5.10.1") > v("5.9.1"))
    }

    /** 6.0.0 (V6): this build reads its own name, and v6.0.0 is newer than every 5.x build. */
    @Test
    fun v600ParsesAndIsNewerThanThe5xBuilds() {
        val installed = AppVersion.installed("6.0.0-debug")
        assertEquals(v("6.0.0"), installed)
        assertFalse(installed!!.isPreRelease)
        assertFalse(v("v6.0.0") > installed)
        assertTrue(v("v6.0.1") > installed)
        assertTrue(v("v6.1.0") > installed)
        assertTrue("6.0.0 is newer than the 5.10.1 test", v("v6.0.0") > AppVersion.installed("5.10.1-debug")!!)
        assertTrue("6.0.0 is newer than 5.10.0", v("v6.0.0") > AppVersion.installed("5.10.0-debug")!!)
        assertTrue("6.0.0 is newer than 5.9.1", v("v6.0.0") > AppVersion.installed("5.9.1-debug")!!)
        assertTrue("6.0 is not read as 6.00 below 5.10", v("6.0.0") > v("5.99.99"))
    }

    @Test
    fun aPhoneOn610GetsThe611Update() {
        // 6.1.1: the review fixes after the 6.1.0 test build, so a phone on 6.1.0 sees the update.
        val installed = AppVersion.installed("6.1.0-debug")
        assertEquals(v("6.1.0"), installed)
        assertTrue(v("v6.1.1") > installed!!)
        assertFalse(v("v6.1.0") > installed)
        assertFalse(v("v6.1.1") > AppVersion.installed("6.1.1-debug")!!)
    }

    @Test
    fun aPhoneOn611GetsThe620Update() {
        // 6.2.0: lean backups, so phones on 6.1.1 and 6.1.0 see the update.
        val installed = AppVersion.installed("6.1.1-debug")
        assertEquals(v("6.1.1"), installed)
        assertTrue(v("v6.2.0") > installed!!)
        assertTrue(v("v6.2.0") > AppVersion.installed("6.1.0-debug")!!)
        assertFalse(v("v6.1.1") > installed)
        assertFalse(v("v6.2.0") > AppVersion.installed("6.2.0-debug")!!)
    }
}
