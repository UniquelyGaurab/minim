package com.minim.music.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.minim.music.R
import com.minim.music.data.stories.StoryChannel
import com.minim.music.ui.adapter.StoriesAdapter
import com.minim.music.util.ArtworkPipeline
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 5.7.0 u45: "+ Add Channel" left the stories top row. The channel row now starts with a big +
 * tile (a ring the size of the channel photos, "Add" under it) that opens the add channel sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StoriesAddTileTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext<Context>().apply {
        setTheme(R.style.Theme_Minim)
    }
    private val artwork = ArtworkPipeline(ctx, OkHttpClient())
    private val channels = listOf(
        StoryChannel("UC1", "Kaushik STORIES", "https://www.youtube.com/@kaushik"),
        StoryChannel("UC2", "SR PAY STORIES", "https://www.youtube.com/@srpay")
    )

    private fun adapter(onAdd: (View) -> Unit, onChannel: (StoryChannel) -> Unit = {}) = StoriesAdapter(
        artwork = artwork,
        onUpload = { _, _ -> },
        onUploadLong = { _, _ -> },
        onChannel = onChannel,
        onChannelLong = {},
        onAdd = onAdd,
        meta = { "" }
    )

    /** The channel row as the stories list shows it, laid out on a 1080 px wide screen. */
    private fun channelRow(a: StoriesAdapter): RecyclerView {
        val parent = FrameLayout(ctx)
        assertEquals("the channel row comes first in the list", 0, a.getItemViewType(0))
        val holder = a.onCreateViewHolder(parent, a.getItemViewType(0))
        a.onBindViewHolder(holder, 0)
        val rv = holder.itemView as RecyclerView
        rv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST))
        rv.layout(0, 0, 1080, rv.measuredHeight)
        return rv
    }

    @Test
    fun `the plus tile is first in the channel row, before every channel`() {
        val a = adapter(onAdd = {})
        a.submit(channels, null, "Latest uploads", null, emptyList())
        val row = a.channelRow
        assertEquals(channels.size + 1, row.itemCount)
        assertEquals(StoriesAdapter.TYPE_ADD_TILE, row.getItemViewType(0))
        for (i in 1..channels.size) assertEquals(StoriesAdapter.TYPE_CHANNEL_TILE, row.getItemViewType(i))

        val rv = channelRow(a)
        assertTrue(rv.childCount >= 3)
        val first = rv.getChildAt(0)
        assertEquals(R.id.story_add, first.id)
        assertEquals(ctx.getString(R.string.add_channel), first.contentDescription)
        assertEquals("Add", first.findViewById<TextView>(R.id.story_add_label).text.toString())
        // Then the channels, in order, by name
        assertEquals("Kaushik STORIES", rv.getChildAt(1).findViewById<TextView>(R.id.channel_name).text.toString())
        assertEquals("SR PAY STORIES", rv.getChildAt(2).findViewById<TextView>(R.id.channel_name).text.toString())
        assertNull(first.findViewById<View>(R.id.channel_name))
    }

    @Test
    fun `the plus is a circle the size of the channel photos`() {
        val a = adapter(onAdd = {})
        a.submit(channels, null, "Latest uploads", null, emptyList())
        val rv = channelRow(a)
        val circle = rv.getChildAt(0).findViewById<View>(R.id.story_add_circle)
        val photoBox = rv.getChildAt(1).findViewById<View>(R.id.channel_photo).parent as View
        assertEquals(photoBox.width, circle.width)
        assertEquals(photoBox.height, circle.height)
        assertEquals(circle.width, circle.height)
        assertEquals(rv.getChildAt(1).width, rv.getChildAt(0).width)
    }

    @Test
    fun `tapping the plus opens add channel and picks no channel`() {
        var adds = 0
        var picked: StoryChannel? = null
        val a = adapter(onAdd = { adds++ }, onChannel = { picked = it })
        a.submit(channels, null, "Latest uploads", null, emptyList())
        val rv = channelRow(a)
        assertTrue(rv.getChildAt(0).performClick())
        assertEquals(1, adds)
        assertNull(picked)
        // A channel tile still picks its channel and doesn't open the sheet
        assertTrue(rv.getChildAt(1).performClick())
        assertEquals(1, adds)
        assertEquals("UC1", picked?.id)
    }

    @Test
    fun `the plus stays bright while one channel is picked`() {
        val a = adapter(onAdd = {})
        a.submit(channels, "UC2", "SR PAY STORIES", null, emptyList())
        val rv = channelRow(a)
        assertEquals(1f, rv.getChildAt(0).alpha)
        assertTrue(rv.getChildAt(1).alpha < 1f)
        assertEquals(1f, rv.getChildAt(2).alpha)
    }

    @Test
    fun `the stories screen opens the same add sheet from the plus, and the top row has no add pill`() {
        val screen = read("java/com/minim/music/ui/StoriesScreen.kt")
        val wiring = screen.substringAfter("onAdd = { v ->").substringBefore("},")
        assertTrue(wiring.contains("showAddSheet()"))
        assertNotNull(read("res/layout/item_story_add.xml").takeIf { it.contains("@drawable/ic_add") })
        val top = read("res/layout/view_top_tabs.xml")
        assertFalse(top.contains("top_add_channel"))
        assertFalse(top.contains("@string/add_channel"))
        val activity = read("java/com/minim/music/ui/MainActivity.kt")
        assertFalse(activity.contains("top_add_channel"))
        assertFalse(activity.contains("addChannel"))
        // 5.8.5 u57: the left area is as wide as its item (logo or round search button), both
        // starting at the same place, and the tabs slide when it changes (was one fixed width)
        assertTrue(activity.contains("private fun slideTopRow(from: Tab, to: Tab)"))
        assertTrue(top.contains("android:id=\"@+id/top_left\""))
    }

    private fun read(path: String): String {
        val candidates = listOf(File("src/main/$path"), File("app/src/main/$path"))
        return candidates.first { it.exists() }.readText()
    }
}
