package com.minim.music.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.minim.music.R
import com.minim.music.data.stats.ChannelItem
import com.minim.music.data.stats.StatsPeriods
import com.minim.music.data.stats.TopArtist
import com.minim.music.util.ArtworkPipeline
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 75: Top 5 artists and channels bind their real round photos; the letter is only the fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class StatsTopPhotosTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext<Context>().apply {
        setTheme(R.style.Theme_Minim)
    }

    private val asked = mutableListOf<String>()
    private val later = mutableMapOf<String, (String) -> Unit>()

    private fun view(host: LinearLayout, artistUrl: (String) -> String?) = StatsPeriodView(
        host, ArtworkPipeline(ctx, OkHttpClient()),
        StatsPeriodView.Callbacks(
            onPeriod = {}, onPlaySong = { _, _ -> }, onArtist = {}, onChannel = {},
            onDay = {}, onHeatMonth = {}, onTeaserDive = {}, onTeaserCard = {},
            tick = {}, softHaptic = {},
            artistPhoto = { name, put -> asked += name; artistUrl(name)?.let(put) ?: run { later[name] = put } },
            channelPhoto = { c, put -> asked += "ch:" + c.name; put("https://yt3/" + c.channelId) }
        )
    ).also { it.restyle(Pal(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF8C8C8C.toInt())) }

    private fun model(artists: List<String>) = StatsPeriodView.Model(
        StatsPeriods.Period.TODAY, "1h 2m", null, emptyList(), emptyList(),
        artists.mapIndexed { i, n -> TopArtist(n, 60_000L * (10 - i), 3) },
        listOf(ChannelItem("k", "Mr. Nightmare", "UCabcdefghijkl", 90_000L, 2)),
        emptyList(), emptyList()
    )

    private fun photos(g: ViewGroup, out: MutableList<ImageView> = mutableListOf()): List<ImageView> {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c is ImageView && c.id == R.id.stats_period_row_photo) out += c
            if (c is ViewGroup) photos(c, out)
        }
        return out
    }

    private fun url(iv: ImageView) = iv.getTag(R.id.tag_artwork_url) as? String

    @Test fun artistAndChannelRowsBindRealPhotos() {
        val host = LinearLayout(ctx)
        val names = listOf("Alan Walker", "Sanah Moidutty", "Anindita Paul", "Anirudh Ravichander", "JEET")
        val v = view(host) { "https://lh3/" + it.replace(' ', '_') }
        v.setModel(model(names))
        assertEquals(names + "ch:Mr. Nightmare", asked)
        val iv = photos(host)
        assertEquals(6, iv.size)
        for ((i, n) in names.withIndex()) assertEquals("https://lh3/" + n.replace(' ', '_'), url(iv[i]))
        assertEquals("https://yt3/UCabcdefghijkl", url(iv[5]))
        // the photo sits round above the letter fallback
        assertTrue(iv.all { it.clipToOutline && it.visibility == View.VISIBLE })
    }

    @Test fun letterStaysUntilAPhotoArrivesAndOldRowsIgnoreLatePhotos() {
        val host = LinearLayout(ctx)
        val v = view(host) { null }
        v.setModel(model(listOf("Alan Walker")))
        val first = photos(host)[0]
        assertEquals(null, url(first))
        // the letter is still there as the fallback
        val frame = first.parent as ViewGroup
        assertEquals("A", (frame.getChildAt(0) as android.widget.TextView).text.toString())
        // a late photo for a row that has since been rebuilt is dropped
        val stale = later.remove("Alan Walker")!!
        v.setModel(model(listOf("Alan Walker", "JEET")))
        stale("https://lh3/old")
        assertEquals(null, url(first))
        val now = photos(host)[0]
        later.remove("Alan Walker")!!("https://lh3/alan")
        assertEquals("https://lh3/alan", url(now))
    }

    @Test fun repriseLinkOpensAndHides() {
        val host = LinearLayout(ctx)
        val v = view(host) { null }
        var opened = 0
        v.setRepriseLink("Open your weekly Reprise · Nov 8–14") { opened++ }
        val link = host.findViewById<android.widget.TextView>(R.id.stats_reprise_link)
        assertEquals(View.VISIBLE, link.visibility)
        assertEquals("Open your weekly Reprise · Nov 8–14", link.text.toString())
        link.performClick()
        assertEquals(1, opened)
        v.setRepriseLink(null, null)
        assertEquals(View.GONE, link.visibility)
    }

    @Test fun liveRefreshUpdatesInPlaceWithoutRebuilding() {
        val host = LinearLayout(ctx)
        val v = view(host) { "https://lh3/x" }
        val names = listOf("Alan Walker", "JEET")
        v.setModel(model(names))
        assertEquals(1, v.rebuilds)
        val rowsBefore = photos(host)
        // same rows, a bit more listening: words change in place, no views are rebuilt
        val more = model(names).let { m ->
            StatsPeriodView.Model(m.period, "1h 3m", m.compare, m.bars, m.songs,
                m.artists.map { TopArtist(it.name, it.ms + 120_000L, it.plays) }, m.channels, m.numbers, m.insights)
        }
        v.setModel(more)
        assertEquals(1, v.rebuilds)
        assertEquals(1, v.inPlaceUpdates)
        assertTrue(rowsBefore.zip(photos(host)).all { (a, b) -> a === b })
        // the same model again does nothing at all
        v.setModel(more)
        assertEquals(1, v.inPlaceUpdates)
        // a new artist in the Top 5 rebuilds the rows
        v.setModel(model(listOf("JEET", "Alan Walker")))
        assertEquals(2, v.rebuilds)
    }

    @Test fun calendarRefreshRedrawsTheSameGrid() {
        val host = LinearLayout(ctx)
        val v = view(host) { null }
        val ym = java.time.YearMonth.now()
        val k = com.minim.music.data.stats.Days.key(ym.atDay(1))
        v.setHeat(ym, mapOf(k to 60_000L))
        v.setHeat(ym, mapOf(k to 60_000L, k + 1 to 600_000L))
        assertEquals(1, v.heatInPlace)
    }
}
