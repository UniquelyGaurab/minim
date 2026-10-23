package com.minim.music.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }

    private fun releaseJson(
        tag: String = "v4.9.7",
        body: String = "- Faster search\\n- Round photos",
        assets: String = apk("Minim-v4.9.7.apk"),
        draft: Boolean = false,
        pre: Boolean = false
    ) = """{"tag_name":"$tag","name":"Minim $tag","draft":$draft,"prerelease":$pre,
        "html_url":"https://github.com/UniquelyGaurab/minim/releases/tag/$tag",
        "body":"$body","assets":[$assets],"author":{"login":"x","id":1}}"""

    private fun apk(name: String, size: Long = 7_400_000, state: String = "uploaded", url: String? = null) =
        """{"name":"$name","size":$size,"state":"$state","content_type":"application/vnd.android.package-archive",
        "browser_download_url":"${url ?: "https://github.com/UniquelyGaurab/minim/releases/download/v4.9.7/$name"}"}"""

    // --- parsing ------------------------------------------------------------------------------

    @Test
    fun parsesTheRealV495Release() {
        val r = GitHubReleaseParser.parse(resource("github_release_v495.json"))
        assertNotNull(r)
        r!!
        assertEquals("v4.9.5", r.tag)
        assertEquals(AppVersion.parse("4.9.5"), r.version)
        assertFalse(r.draft)
        assertFalse(r.preRelease)
        assertEquals(1, r.assets.size)
        assertEquals("Minim-v4.9.5.apk", r.assets[0].name)
        assertTrue(r.assets[0].url.startsWith("https://github.com/UniquelyGaurab/minim/releases/download/"))
        assertTrue(r.assets[0].size > 1_000_000)
        assertTrue(r.body.isNotBlank())
    }

    @Test
    fun realV495IsNotAnUpdateFor495ButIsFor494() {
        val r = GitHubReleaseParser.parse(resource("github_release_v495.json"))
        assertNull(UpdateRules.availableUpdate("4.9.5-debug", r))
        val u = UpdateRules.availableUpdate("4.9.4-debug", r)
        assertNotNull(u)
        assertEquals("Minim-v4.9.5.apk", u!!.apk.name)
        assertTrue(u.notes.isNotBlank())
    }

    @Test
    fun parsesFieldsAndAssets() {
        val r = GitHubReleaseParser.parse(releaseJson(assets = apk("Minim-v4.9.7.apk") + "," + apk("notes.txt", 10)))!!
        assertEquals("v4.9.7", r.tag)
        assertEquals("Minim v4.9.7", r.name)
        assertEquals("- Faster search\n- Round photos", r.body)
        assertEquals(2, r.assets.size)
        assertEquals(7_400_000L, r.assets[0].size)
        assertEquals("https://github.com/UniquelyGaurab/minim/releases/tag/v4.9.7", r.pageUrl)
    }

    @Test
    fun badJsonOrBadTagGivesNull() {
        assertNull(GitHubReleaseParser.parse(null))
        assertNull(GitHubReleaseParser.parse(""))
        assertNull(GitHubReleaseParser.parse("not json"))
        assertNull(GitHubReleaseParser.parse("[1,2,3]"))
        assertNull(GitHubReleaseParser.parse("""{"message":"Not Found"}"""))
        assertNull(GitHubReleaseParser.parse("""{"tag_name":"latest"}"""))
        assertNull(GitHubReleaseParser.parse("""{"tag_name":"v4.9.7","assets":["""))
        assertNull(GitHubReleaseParser.parse("[".repeat(5000)))
    }

    @Test
    fun oddAssetsAreSkippedNotFatal() {
        val r = GitHubReleaseParser.parse(
            releaseJson(assets = """{"name":"x.apk"}, 5, null, {"browser_download_url":"https://a/b"},""" + apk("Minim.apk"))
        )!!
        assertEquals(1, r.assets.size)
        assertEquals("Minim.apk", r.assets[0].name)
        // No assets field at all
        val none = GitHubReleaseParser.parse("""{"tag_name":"v4.9.7"}""")!!
        assertTrue(none.assets.isEmpty())
    }

    // --- picking the asset ----------------------------------------------------------------------

    private fun asset(name: String, size: Long = 100, state: String? = "uploaded", url: String = "https://x/$name") =
        ReleaseAsset(name, url, size, null, state)

    @Test
    fun picksTheApk() {
        val list = listOf(asset("checksums.txt"), asset("Minim-v4.9.7.apk"), asset("Source.zip"))
        assertEquals("Minim-v4.9.7.apk", UpdateRules.pickApk(list, installedIsDebug = true)?.name)
        assertEquals("MINIM.APK", UpdateRules.pickApk(listOf(asset("MINIM.APK")), true)?.name)
    }

    @Test
    fun noApkMeansNoPick() {
        assertNull(UpdateRules.pickApk(emptyList(), true))
        assertNull(UpdateRules.pickApk(listOf(asset("Minim.apk.sha256"), asset("Minim.aab")), true))
        // Still uploading, empty, too big or not https
        assertNull(UpdateRules.pickApk(listOf(asset("a.apk", state = "starter")), true))
        assertNull(UpdateRules.pickApk(listOf(asset("a.apk", size = 0)), true))
        assertNull(UpdateRules.pickApk(listOf(asset("a.apk", size = UpdateRules.MAX_APK_BYTES + 1)), true))
        assertNull(UpdateRules.pickApk(listOf(asset("a.apk", url = "http://x/a.apk")), true))
    }

    @Test
    fun prefersTheMatchingBuildType() {
        val list = listOf(asset("Minim-v4.9.7-release.apk"), asset("Minim-v4.9.7-debug.apk"))
        assertEquals("Minim-v4.9.7-debug.apk", UpdateRules.pickApk(list, installedIsDebug = true)?.name)
        assertEquals("Minim-v4.9.7-release.apk", UpdateRules.pickApk(list, installedIsDebug = false)?.name)
        // Only one APK: it's picked whatever its name says (today's releases: Minim-v4.9.x.apk)
        assertEquals("Minim-v4.9.7.apk", UpdateRules.pickApk(listOf(asset("Minim-v4.9.7.apk")), true)?.name)
        assertEquals("x-debug.apk", UpdateRules.pickApk(listOf(asset("x-debug.apk")), false)?.name)
    }

    // --- the update decision --------------------------------------------------------------------

    @Test
    fun newerReleaseWithApkIsOffered() {
        val u = UpdateRules.availableUpdate("4.9.5-debug", GitHubReleaseParser.parse(releaseJson()))
        assertNotNull(u)
        assertEquals("4.9.7", u!!.version.toString())
        assertEquals("v4.9.7", u.tag)
        assertEquals("• Faster search\n• Round photos", u.notes)
    }

    @Test
    fun sameOrOlderIsNotOffered() {
        assertNull(UpdateRules.availableUpdate("4.9.7-debug", GitHubReleaseParser.parse(releaseJson())))
        assertNull(UpdateRules.availableUpdate("4.9.8", GitHubReleaseParser.parse(releaseJson())))
        assertNull(UpdateRules.availableUpdate("4.9.10-debug", GitHubReleaseParser.parse(releaseJson(tag = "v4.9.9"))))
        assertNotNull(UpdateRules.availableUpdate("4.9.9-debug", GitHubReleaseParser.parse(releaseJson(tag = "v4.9.10"))))
    }

    @Test
    fun draftsPrereleasesAndNoApkAreNotOffered() {
        assertNull(UpdateRules.availableUpdate("4.9.5-debug", GitHubReleaseParser.parse(releaseJson(draft = true))))
        assertNull(UpdateRules.availableUpdate("4.9.5-debug", GitHubReleaseParser.parse(releaseJson(pre = true))))
        assertNull(UpdateRules.availableUpdate("4.9.5-debug", GitHubReleaseParser.parse(releaseJson(tag = "v4.9.7-beta.1"))))
        assertNull(UpdateRules.availableUpdate("4.9.5-debug", GitHubReleaseParser.parse(releaseJson(assets = ""))))
        assertNull(UpdateRules.availableUpdate("4.9.5-debug", GitHubReleaseParser.parse(releaseJson(assets = apk("Minim.zip")))))
        assertNull(UpdateRules.availableUpdate("4.9.5-debug", null))
        // An installed version that can't be read never gets offered anything
        assertNull(UpdateRules.availableUpdate(null, GitHubReleaseParser.parse(releaseJson())))
        assertNull(UpdateRules.availableUpdate("weird", GitHubReleaseParser.parse(releaseJson())))
    }

    // --- notes ----------------------------------------------------------------------------------

    @Test
    fun notesAreMadeShortAndPlain() {
        val body = "## What's new\r\n\r\n- **Round** artist photos\n* Search rows match [Stats](https://x.y)\n\n---\n1. `Fixed` a crash\n> quoted"
        assertEquals(
            "What's new\n• Round artist photos\n• Search rows match Stats\n• Fixed a crash\nquoted",
            UpdateRules.shortNotes(body)
        )
        assertEquals("", UpdateRules.shortNotes(null))
        assertEquals("", UpdateRules.shortNotes("  \n\n "))
    }

    @Test
    fun longNotesAreCut() {
        val many = (1..10).joinToString("\n") { "- line $it" }
        val short = UpdateRules.shortNotes(many)
        assertEquals(6, short.lines().size)
        assertTrue(short.endsWith("…"))
        val long = UpdateRules.shortNotes("word ".repeat(200))
        assertTrue(long.length <= 361)
        assertTrue(long.endsWith("…"))
    }

    // --- archive and old downloads ------------------------------------------------------------

    @Test
    fun archiveCheck() {
        val pkg = "com.minim.music.debug"
        assertEquals(UpdateRules.ArchiveCheck.OK, UpdateRules.checkArchive(pkg, 18, pkg, 17))
        assertEquals(UpdateRules.ArchiveCheck.OK, UpdateRules.checkArchive(pkg, 17, pkg, 17))
        assertEquals(UpdateRules.ArchiveCheck.OLDER, UpdateRules.checkArchive(pkg, 16, pkg, 17))
        assertEquals(UpdateRules.ArchiveCheck.OTHER_APP, UpdateRules.checkArchive("com.minim.music", 18, pkg, 17))
        assertEquals(UpdateRules.ArchiveCheck.BROKEN, UpdateRules.checkArchive(null, 0, pkg, 17))
    }

    @Test
    fun staleDownloads() {
        val installed = AppVersion.installed("4.9.6-debug")
        assertEquals("Minim-4.9.7.apk", UpdateRules.apkFileName(AppVersion.parse("v4.9.7")!!))
        assertFalse(UpdateRules.isStaleDownload("Minim-4.9.7.apk", installed))
        assertTrue(UpdateRules.isStaleDownload("Minim-4.9.6.apk", installed))
        assertTrue(UpdateRules.isStaleDownload("Minim-4.9.5.apk", installed))
        assertTrue(UpdateRules.isStaleDownload("Minim-4.9.7.apk.part", installed))
        assertTrue(UpdateRules.isStaleDownload("other.bin", installed))
    }
}
