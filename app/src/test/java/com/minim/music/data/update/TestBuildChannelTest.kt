package com.minim.music.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cloud builds put every test APK on a rolling GitHub pre-release tagged "test-build". Minim must
 * never offer it as an update, whichever way the checker sees it.
 */
class TestBuildChannelTest {

    private fun releaseJson(tag: String, pre: Boolean, apk: String = "Minim-test.apk") =
        """{"tag_name":"$tag","name":"Test build","draft":false,"prerelease":$pre,
        "html_url":"https://github.com/UniquelyGaurab/minim/releases/tag/$tag","body":"Test build",
        "assets":[{"name":"$apk","size":8400000,"state":"uploaded",
        "content_type":"application/vnd.android.package-archive",
        "browser_download_url":"https://github.com/UniquelyGaurab/minim/releases/download/$tag/$apk"}]}"""

    @Test
    fun testBuildTagIsNotAVersion() {
        assertNull(AppVersion.parse("test-build"))
        assertNull(GitHubReleaseParser.parse(releaseJson("test-build", pre = true)))
    }

    @Test
    fun testBuildIsNeverOfferedFromTheApi() {
        val release = GitHubReleaseParser.parse(releaseJson("test-build", pre = true))
        assertNull(UpdateRules.availableUpdate("7.0.0-debug", release))
        assertNull(UpdateRules.availableUpdate("7.0.0", release))
    }

    @Test
    fun testBuildIsNeverOfferedFromTheWebRedirect() {
        val tag = UpdateChecker.tagFromLocation("https://github.com/UniquelyGaurab/minim/releases/tag/test-build")
        assertEquals("test-build", tag)
        assertNull(AppVersion.parse(tag))
    }

    @Test
    fun anyPreReleaseIsRefusedEvenWithAVersionTag() {
        val release = GitHubReleaseParser.parse(releaseJson("v99.0.0", pre = true, apk = "Minim-v99.0.0.apk"))
        assertNotNull(release)
        assertNull(UpdateRules.availableUpdate("7.0.0-debug", release))
    }

    @Test
    fun aRealReleaseIsStillOffered() {
        val release = GitHubReleaseParser.parse(releaseJson("v7.1.0", pre = false, apk = "Minim-v7.1.0.apk"))
        assertNotNull(UpdateRules.availableUpdate("7.0.0-debug", release))
    }
}
