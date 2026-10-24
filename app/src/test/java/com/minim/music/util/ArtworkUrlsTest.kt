package com.minim.music.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkUrlsTest {

    private val cover = "https://lh3.googleusercontent.com/AbC_dEf-123=w60-h60-l90-rj"
    private val avatar = "https://yt3.ggpht.com/xyz-Q=s88-c-k-c0x00ffffff-no-rj"

    @Test
    fun bucketsBySize() {
        assertEquals(ArtworkUrls.SMALL, ArtworkUrls.bucket(132))
        assertEquals(ArtworkUrls.SMALL, ArtworkUrls.bucket(200))
        assertEquals(ArtworkUrls.MEDIUM, ArtworkUrls.bucket(360))
        assertEquals(ArtworkUrls.MEDIUM, ArtworkUrls.bucket(544))
        assertEquals(ArtworkUrls.LARGE, ArtworkUrls.bucket(720))
        assertEquals(ArtworkUrls.LARGE, ArtworkUrls.bucket(1440))
    }

    @Test
    fun youtubeMusicCoverGetsHdForBigViews() {
        assertEquals(
            listOf("https://lh3.googleusercontent.com/AbC_dEf-123=w1080-h1080-l90-rj", cover),
            ArtworkUrls.candidates(cover, 1080)
        )
    }

    @Test
    fun smallRowsGetAMidSize() {
        assertEquals(
            "https://lh3.googleusercontent.com/AbC_dEf-123=w300-h300-l90-rj",
            ArtworkUrls.candidates(cover, 132).first()
        )
        assertEquals(
            "https://lh3.googleusercontent.com/AbC_dEf-123=w544-h544-l90-rj",
            ArtworkUrls.candidates(cover, 360).first()
        )
    }

    @Test
    fun alreadyBigCoversAreSizedDownForSmallViews() {
        val big = "https://lh3.googleusercontent.com/AbC=w544-h544-l90-rj"
        assertEquals("https://lh3.googleusercontent.com/AbC=w300-h300-l90-rj", ArtworkUrls.candidates(big, 100).first())
    }

    @Test
    fun channelPhotosUseTheSOption() {
        assertEquals(
            "https://yt3.ggpht.com/xyz-Q=s1080-c-k-c0x00ffffff-no-rj",
            ArtworkUrls.googleSized(avatar, 1080)
        )
        assertEquals(
            "https://yt3.googleusercontent.com/abc=s1080",
            ArtworkUrls.googleSized("https://yt3.googleusercontent.com/abc=s120", 1080)
        )
    }

    @Test
    fun otherOptionsAndQueriesAreKept() {
        assertEquals(
            "https://lh3.googleusercontent.com/a-b=w1080-h1080-p-l90-rj?x=1",
            ArtworkUrls.googleSized("https://lh3.googleusercontent.com/a-b=w120-h120-p-l90-rj?x=1", 1080)
        )
        // h-before-w order
        assertEquals(
            "https://lh3.googleusercontent.com/a=w544-h544-rj",
            ArtworkUrls.googleSized("https://lh3.googleusercontent.com/a=h90-w90-rj", 544)
        )
        // "-s" inside the image id isn't a size option
        assertEquals(
            "https://lh3.googleusercontent.com/ab-s12=w1080-h1080",
            ArtworkUrls.googleSized("https://lh3.googleusercontent.com/ab-s12=w60-h60", 1080)
        )
    }

    @Test
    fun googleUrlsWithoutASizeAreLeftAlone() {
        val plain = "https://lh3.googleusercontent.com/abcdef"
        assertEquals(plain, ArtworkUrls.googleSized(plain, 1080))
        assertEquals(listOf(plain), ArtworkUrls.candidates(plain, 1080))
    }

    @Test
    fun otherHostsAreUntouched() {
        val other = "https://example.com/cover=w60-h60.jpg"
        assertFalse(ArtworkUrls.isGoogleImage(other))
        assertEquals(listOf(other), ArtworkUrls.candidates(other, 1080))
        assertEquals(other, ArtworkUrls.googleSized(other, 1080))
    }

    @Test
    fun blankGivesNothing() {
        assertEquals(emptyList<String>(), ArtworkUrls.candidates(null, 300))
        assertEquals(emptyList<String>(), ArtworkUrls.candidates("  ", 300))
    }

    @Test
    fun videoThumbnailsAreRebuiltWithFallbacks() {
        val signed = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=-oaymwEbCKgBEF5&rs=AOn4CLD"
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                signed
            ),
            ArtworkUrls.candidates(signed, 1080)
        )
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                signed
            ),
            ArtworkUrls.candidates(signed, 400)
        )
        // Small: hqdefault, and the original isn't repeated when it's the same URL
        assertEquals(
            listOf("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"),
            ArtworkUrls.candidates("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", 132)
        )
    }

    @Test
    fun videoIdsFromTheUsualHosts() {
        assertEquals("dQw4w9WgXcQ", ArtworkUrls.videoThumbId("https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/mqdefault.webp"))
        assertEquals("dQw4w9WgXcQ", ArtworkUrls.videoThumbId("https://i9.ytimg.com/vi/dQw4w9WgXcQ/default.jpg"))
        assertEquals("dQw4w9WgXcQ", ArtworkUrls.videoThumbId("https://img.youtube.com/vi/dQw4w9WgXcQ/0.jpg"))
        assertEquals("a-b_c1234XY", ArtworkUrls.videoThumbId("https://i.ytimg.com/vi/a-b_c1234XY/hq720.jpg"))
        assertNull(ArtworkUrls.videoThumbId("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault_live.jpg"))
        assertNull(ArtworkUrls.videoThumbId("https://i.ytimg.com/sb/dQw4w9WgXcQ/storyboard3_L1/M0.jpg"))
        assertNull(ArtworkUrls.videoThumbId("https://i.ytimg.com/vi/short/hqdefault.jpg"))
        assertNull(ArtworkUrls.videoThumbId(cover))
        assertNull(ArtworkUrls.videoThumbId("not a url"))
        assertTrue(ArtworkUrls.isVideoThumb("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"))
    }

    @Test
    fun videoFramesCropToTheCentreSquareWithoutBars() {
        // maxresdefault 1280x720: no bars, centre 720 square
        assertEquals(ArtworkUrls.Square(280, 0, 720), ArtworkUrls.squareCrop(1280, 720, videoFrame = true))
        // hqdefault 480x360: 16:9 picture is the middle 480x270, square 270 inside it
        assertEquals(ArtworkUrls.Square(105, 45, 270), ArtworkUrls.squareCrop(480, 360, videoFrame = true))
        // sddefault 640x480: middle 640x360
        assertEquals(ArtworkUrls.Square(140, 60, 360), ArtworkUrls.squareCrop(640, 480, videoFrame = true))
        // Sampled down by 2 it's the same square, halved
        assertEquals(ArtworkUrls.Square(70, 30, 180), ArtworkUrls.squareCrop(320, 240, videoFrame = true))
    }

    @Test
    fun otherImagesCropToTheLargestSquare() {
        assertEquals(ArtworkUrls.Square(0, 0, 544), ArtworkUrls.squareCrop(544, 544, videoFrame = false))
        assertEquals(ArtworkUrls.Square(0, 100, 400), ArtworkUrls.squareCrop(400, 600, videoFrame = false))
        assertEquals(ArtworkUrls.Square(0, 0, 0), ArtworkUrls.squareCrop(0, 10, videoFrame = true))
    }
}
