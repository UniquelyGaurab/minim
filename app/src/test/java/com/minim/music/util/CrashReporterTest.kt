package com.minim.music.util

import com.minim.music.data.update.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashReporterTest {

    @get:Rule val tmp = TemporaryFolder()

    private val boom = IllegalArgumentException(
        "Maximum auto-size text size (15.0px) is less or equal to minimum auto-size text size (15.0px)"
    )

    @Test
    fun reportHasVersionDeviceThreadAndTrace() {
        val r = CrashReporter.format(boom, "main", "4.9.10-debug (26)", "Pixel 7, Android 14 (API 34)", 0L)
        assertTrue(r.contains("Version: 4.9.10-debug (26)"))
        assertTrue(r.contains("Device: Pixel 7, Android 14 (API 34)"))
        assertTrue(r.contains("Thread: main"))
        assertTrue(r.contains("java.lang.IllegalArgumentException: Maximum auto-size"))
        assertTrue(r.contains("at com.minim.music.util.CrashReporterTest"))
    }

    @Test
    fun headlineIsTheShortExceptionLine() {
        val r = CrashReporter.format(boom, "main", "v", "d", 0L)
        assertEquals(
            "IllegalArgumentException: Maximum auto-size text size (15.0px) is less or equal to minimum auto-size text size (15.0px)",
            CrashReporter.headline(r)
        )
        assertEquals("", CrashReporter.headline(""))
    }

    @Test
    fun hugeTracesAreCut() {
        fun deep(n: Int): Nothing = if (n == 0) throw IllegalStateException("deep") else deep(n - 1)
        val e = try { deep(3000); null } catch (t: Throwable) { t }!!
        val r = CrashReporter.format(e, "main", "v", "d", 0L)
        assertTrue(r.length <= CrashReporter.MAX_CHARS + 20)
    }

    @Test
    fun saveReadClear() {
        val dir = tmp.newFolder()
        assertNull(CrashReporter.pending(dir))
        CrashReporter.save(dir, "report")
        assertEquals("report", CrashReporter.pending(dir))
        CrashReporter.clear(dir)
        assertNull(CrashReporter.pending(dir))
        // A missing folder never throws
        assertNull(CrashReporter.pending(java.io.File(dir, "nope/nope")))
        CrashReporter.clear(java.io.File(dir, "nope/nope"))
    }

    @Test
    fun handlerSavesThenCallsThePreviousOne() {
        val dir = tmp.newFolder()
        var previousGot: Throwable? = null
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> previousGot = e }
            CrashReporter.install(dir, "4.9.10", "test")
            val t = Thread { throw boom }
            t.start()
            t.join()
            assertEquals(boom, previousGot)
            assertTrue(CrashReporter.pending(dir)!!.contains("Maximum auto-size"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun hotfixVersionSitsBetween499And500() {
        val hotfix = AppVersion.installed("4.9.10-debug")!!
        assertTrue(hotfix > AppVersion.parse("4.9.9")!!)
        assertTrue(hotfix > AppVersion.parse("4.9.8")!!)
        assertTrue(AppVersion.parse("5.0.0")!! > hotfix)
    }
}
