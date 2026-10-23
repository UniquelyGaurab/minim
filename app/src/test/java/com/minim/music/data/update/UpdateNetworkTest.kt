package com.minim.music.data.update

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The GitHub check and the APK download against a fake server. */
class UpdateNetworkTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    private fun release(tag: String) = """{"tag_name":"$tag","body":"- New things","assets":[
        {"name":"Minim-$tag.apk","size":1234,"state":"uploaded","browser_download_url":"https://example.com/Minim.apk"}]}"""

    private fun checker() = UpdateChecker(client, server.url("/repos/UniquelyGaurab/minim/releases/latest").toString(), "Minim/test")

    @Test
    fun newerReleaseIsAvailable() {
        server.enqueue(MockResponse().setBody(release("v4.9.7")))
        val r = checker().check("4.9.5-debug")
        assertTrue(r is UpdateCheckResult.Available)
        assertEquals("4.9.7", (r as UpdateCheckResult.Available).update.version.toString())
        val req = server.takeRequest()
        assertEquals("application/vnd.github+json", req.getHeader("Accept"))
        assertEquals("Minim/test", req.getHeader("User-Agent"))
        assertEquals("/repos/UniquelyGaurab/minim/releases/latest", req.path)
    }

    @Test
    fun sameReleaseIsUpToDate() {
        server.enqueue(MockResponse().setBody(release("v4.9.5")))
        val r = checker().check("4.9.5-debug")
        assertEquals(UpdateCheckResult.UpToDate("v4.9.5"), r)
    }

    @Test
    fun silentFailures() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        assertEquals(UpdateCheckResult.NoRelease, checker().check("4.9.5"))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}"""))
        assertTrue(checker().check("4.9.5") is UpdateCheckResult.Failed)
        server.enqueue(MockResponse().setResponseCode(429))
        assertTrue(checker().check("4.9.5") is UpdateCheckResult.Failed)
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(checker().check("4.9.5") is UpdateCheckResult.Failed)
        server.enqueue(MockResponse().setBody("<html>oops</html>"))
        assertTrue(checker().check("4.9.5") is UpdateCheckResult.Failed)
        server.enqueue(MockResponse().setBody("x".repeat(300_000)))
        assertTrue(checker().check("4.9.5") is UpdateCheckResult.Failed)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(checker().check("4.9.5") is UpdateCheckResult.Failed)
    }

    @Test
    fun offlineIsMarkedOffline() {
        val port = server.port
        server.shutdown()
        val r = UpdateChecker(client, "http://127.0.0.1:$port/latest").check("4.9.5")
        assertTrue(r is UpdateCheckResult.Failed)
        assertTrue((r as UpdateCheckResult.Failed).offline)
    }

    // --- download -------------------------------------------------------------------------------

    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun downloadsWithProgressAndRenames() {
        val data = bytes(300_000)
        server.enqueue(MockResponse().setBody(Buffer().write(data)))
        val dir = tmp.newFolder("updates")
        val progress = mutableListOf<Pair<Long, Long>>()
        val r = ApkDownloader(client).download(
            server.url("/Minim.apk").toString().replace("localhost", "127.0.0.1"),
            dir, "Minim-4.9.7.apk", data.size.toLong()
        ) { d, t -> progress += d to t }
        assertTrue(r.toString(), r is DownloadResult.Done)
        val f = (r as DownloadResult.Done).file
        assertEquals("Minim-4.9.7.apk", f.name)
        assertTrue(f.readBytes().contentEquals(data))
        assertFalse(java.io.File(dir, "Minim-4.9.7.apk.part").exists())
        assertEquals(0L to data.size.toLong(), progress.first())
        assertEquals(data.size.toLong() to data.size.toLong(), progress.last())
        assertTrue(progress.size >= 3)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.first >= a.first })
    }

    @Test
    fun wrongSizeServerErrorAndDropAreFailures() {
        val dir = tmp.newFolder("u2")
        val url = server.url("/a.apk").toString().replace("localhost", "127.0.0.1")
        server.enqueue(MockResponse().setBody(Buffer().write(bytes(1000))))
        val wrong = ApkDownloader(client).download(url, dir, "a.apk", 2000)
        assertTrue(wrong is DownloadResult.Failed && wrong.kind == DownloadResult.Kind.BROKEN)

        server.enqueue(MockResponse().setResponseCode(404))
        val missing = ApkDownloader(client).download(url, dir, "a.apk", 2000)
        assertTrue(missing is DownloadResult.Failed && missing.kind == DownloadResult.Kind.SERVER)

        server.enqueue(
            MockResponse().setBody(Buffer().write(bytes(200_000)))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val dropped = ApkDownloader(client).download(url, dir, "a.apk", 200_000)
        assertTrue(dropped.toString(), dropped is DownloadResult.Failed)
        assertFalse(java.io.File(dir, "a.apk").exists())
        assertFalse(java.io.File(dir, "a.apk.part").exists())

        val notHttps = ApkDownloader(client).download("http://example.com/a.apk", dir, "a.apk", 10)
        assertTrue(notHttps is DownloadResult.Failed)
    }

    @Test
    fun cancelStopsTheDownload() {
        val dir = tmp.newFolder("u3")
        val url = server.url("/a.apk").toString().replace("localhost", "127.0.0.1")
        server.enqueue(MockResponse().setBody(Buffer().write(bytes(2_000_000))).throttleBody(64 * 1024, 50, java.util.concurrent.TimeUnit.MILLISECONDS))
        val d = ApkDownloader(client)
        val r = d.download(url, dir, "a.apk", 2_000_000) { done, _ -> if (done > 100_000) d.cancel() }
        assertEquals(DownloadResult.Cancelled, r)
        assertFalse(java.io.File(dir, "a.apk").exists())
        assertFalse(java.io.File(dir, "a.apk.part").exists())
    }
}
