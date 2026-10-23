package com.minim.music.data.update

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v4.9.6 part 4 crash checks: odd GitHub replies never throw, and a 4.9.6 install ("4.9.6-debug")
 * sees v4.9.7 as an update but never v4.9.6 itself.
 */
class UpdateCrashChecksTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    private fun checker() = UpdateChecker(client, server.url("/latest").toString(), "Minim/test")

    private fun release(tag: String, assets: String) =
        """{"tag_name":"$tag","draft":false,"prerelease":false,"body":"- Fixes","assets":$assets}"""

    private fun apk(name: String, state: String = "uploaded", size: Long = 7_200_000) =
        """{"name":"$name","size":$size,"state":"$state","browser_download_url":"https://github.com/x/$name"}"""

    // --- malformed or unexpected replies --------------------------------------------------------

    @Test
    fun malformedRepliesAreQuietFailuresNotCrashes() {
        val bodies = listOf(
            "",
            "null",
            "[]",
            "{}",
            "\"v4.9.7\"",
            """{"tag_name":5}""",
            """{"tag_name":null,"assets":[]}""",
            """{"tag_name":"latest","assets":[]}""",
            """{"tag_name":"v4.9.7""",               // cut off
            """{"tag_name":"v4.9.7",}""",              // trailing comma
            "[".repeat(100_000),                       // nested far too deep
            "\u0000\u0001\uFFFF binary",
            "<!DOCTYPE html><html>GitHub is down</html>"
        )
        for (b in bodies) {
            server.enqueue(MockResponse().setBody(b))
            val r = checker().check("4.9.6-debug")
            assertTrue("body <${b.take(30)}> gave $r", r is UpdateCheckResult.Failed)
        }
    }

    @Test
    fun wrongFieldTypesInAGoodReleaseAreSkipped() {
        // assets not a list, junk inside the list, size as text: still readable, just no APK
        val bodies = listOf(
            """{"tag_name":"v4.9.7","assets":"nope"}""",
            """{"tag_name":"v4.9.7","assets":[null,3,"x",{},[]]}""",
            """{"tag_name":"v4.9.7","body":42,"name":[],"draft":"yes","assets":null}"""
        )
        for (b in bodies) {
            server.enqueue(MockResponse().setBody(b))
            assertEquals(UpdateCheckResult.UpToDate("v4.9.7"), checker().check("4.9.6-debug"))
        }
        // A size that's text is read as "unknown" and the APK is still offered
        server.enqueue(MockResponse().setBody(release("v4.9.7",
            """[{"name":"Minim-v4.9.7.apk","size":"big","browser_download_url":"https://github.com/x/a.apk"}]""")))
        val r = checker().check("4.9.6-debug")
        assertTrue(r is UpdateCheckResult.Available)
        assertEquals(-1L, (r as UpdateCheckResult.Available).update.apk.size)
    }

    @Test
    fun releaseWithoutAnApkIsNotOffered() {
        // Only GitHub's source archives / other files, an APK still uploading, an APK over http
        val cases = listOf(
            "[]",
            "[" + apk("Minim-source.zip") + "," + apk("notes.txt") + "]",
            "[" + apk("Minim-v4.9.7.apk", state = "starter") + "]",
            "[" + apk("Minim-v4.9.7.apk", size = 0) + "]",
            """[{"name":"Minim-v4.9.7.apk","size":100,"browser_download_url":"http://insecure/a.apk"}]"""
        )
        for (assets in cases) {
            server.enqueue(MockResponse().setBody(release("v4.9.7", assets)))
            assertEquals(UpdateCheckResult.UpToDate("v4.9.7"), checker().check("4.9.6-debug"))
        }
    }

    @Test
    fun rateLimitAndNoReleases() {
        server.enqueue(MockResponse().setResponseCode(403)
            .addHeader("X-RateLimit-Remaining", "0")
            .setBody("""{"message":"API rate limit exceeded for 1.2.3.4."}"""))
        val limited = checker().check("4.9.6-debug")
        assertTrue(limited is UpdateCheckResult.Failed)
        assertFalse((limited as UpdateCheckResult.Failed).offline)
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        assertEquals(UpdateCheckResult.NoRelease, checker().check("4.9.6-debug"))
        // 200 with no body at all
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(checker().check("4.9.6-debug") is UpdateCheckResult.Failed)
    }

    @Test
    fun offlineNeverThrows() {
        val port = server.port
        server.shutdown()
        val r = UpdateChecker(client, "http://127.0.0.1:$port/latest").check("4.9.6-debug")
        assertTrue(r is UpdateCheckResult.Failed && r.offline)
        // A host that can't exist
        val r2 = UpdateChecker(client, "https://no-such-host.invalid/latest").check("4.9.6-debug")
        assertTrue(r2 is UpdateCheckResult.Failed)
    }

    // --- a 4.9.6 install --------------------------------------------------------------------------

    @Test
    fun installed496SeesV497ButNotV496() {
        for (installed in listOf("4.9.6-debug", "4.9.6")) {
            server.enqueue(MockResponse().setBody(release("v4.9.7", "[" + apk("Minim-v4.9.7.apk") + "]")))
            val newer = checker().check(installed)
            assertTrue("$installed should see v4.9.7", newer is UpdateCheckResult.Available)
            assertEquals("4.9.7", (newer as UpdateCheckResult.Available).update.version.toString())

            server.enqueue(MockResponse().setBody(release("v4.9.6", "[" + apk("Minim-v4.9.6.apk") + "]")))
            assertEquals(UpdateCheckResult.UpToDate("v4.9.6"), checker().check(installed))

            server.enqueue(MockResponse().setBody(release("v4.9.5", "[" + apk("Minim-v4.9.5.apk") + "]")))
            assertEquals(UpdateCheckResult.UpToDate("v4.9.5"), checker().check(installed))
        }
    }

    @Test
    fun installed496VersionRules() {
        val installed = AppVersion.installed("4.9.6-debug")!!
        assertEquals("4.9.6", installed.toString())
        assertFalse(installed.isPreRelease) // "-debug" is the build type, not a beta
        assertTrue(AppVersion.parse("v4.9.7")!! > installed)
        assertTrue(AppVersion.parse("v4.9.10")!! > installed)
        assertTrue(AppVersion.parse("v4.10.0")!! > installed)
        assertFalse(AppVersion.parse("v4.9.6")!! > installed)
        // A 4.9.7 beta isn't offered
        val beta = GitHubReleaseParser.parse(release("v4.9.7-beta.1", "[" + apk("Minim-v4.9.7.apk") + "]"))
        assertNull(UpdateRules.availableUpdate("4.9.6-debug", beta))
        // Old saved downloads are cleaned up once 4.9.6 is installed; a 4.9.7 download is kept
        assertTrue(UpdateRules.isStaleDownload("Minim-4.9.6.apk", installed))
        assertTrue(UpdateRules.isStaleDownload("Minim-4.9.5.apk", installed))
        assertFalse(UpdateRules.isStaleDownload("Minim-4.9.7.apk", installed))
        // The v4.9.6 APK (code 22) over 4.9.5 (code 21) is fine; v4.9.5 over 4.9.6 is "older"
        val pkg = "com.minim.music.debug"
        assertEquals(UpdateRules.ArchiveCheck.OK, UpdateRules.checkArchive(pkg, 22, pkg, 21))
        assertEquals(UpdateRules.ArchiveCheck.OLDER, UpdateRules.checkArchive(pkg, 21, pkg, 22))
    }
}
