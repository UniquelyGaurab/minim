package com.minim.music.ui

import android.app.Activity
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.minim.music.R
import com.minim.music.data.ai.GeminiChatSession
import com.minim.music.data.ai.GeminiClient
import com.minim.music.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** 5.2.1: after the Gemini chat closed, its layer could stay up and swallow every tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GeminiChatPanelTest {

    private class FakeHost(val session: GeminiChatSession) : GeminiChatPanel.Host {
        override fun palette() = Pal(0xFF101010.toInt(), 0xFFFFFFFF.toInt(), 0xFF888888.toInt())
        override fun session() = session
        override fun send(text: String, focus: Track?) = false
        override fun loadArt(view: ImageView, url: String?, sizePx: Int) {}
        override fun playTrack(track: Track, list: List<Track>) {}
        override fun showTrackOptions(anchor: View, track: Track) {}
        override suspend fun setLiked(track: Track, liked: Boolean) {}
        override suspend fun savePlaylist(name: String, tracks: List<Track>) = true
        override fun openArtist(name: String) {}
        override fun openGeminiSettings() {}
        override fun modelLabel() = "Gemini"
        override fun needsKeyUpFront() = false
        override fun note(text: String) {}
        override fun haptic(v: View, type: Int) {}
        override fun launch(block: suspend () -> Unit) {}
    }

    private fun setup(): Triple<Activity, FrameLayout, GeminiChatPanel> {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        activity.setTheme(R.style.Theme_Minim)
        val host = FrameLayout(activity)
        activity.setContentView(host)
        val root = LayoutInflater.from(activity).inflate(R.layout.view_gemini_chat, host, false) as FrameLayout
        host.addView(root)
        val session = GeminiChatSession(GeminiClient(OkHttpClient()), CoroutineScope(Dispatchers.Unconfined)) { it() }
        val panel = GeminiChatPanel(root, FakeHost(session))
        host.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, 1080, 2200)
        return Triple(activity, root, panel)
    }

    private fun idle() = ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS)

    private fun tapReaches(root: FrameLayout): Boolean {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 500f, 300f, 0)
        val handled = root.dispatchTouchEvent(down)
        down.recycle()
        return handled
    }

    @Test
    fun openThenCloseLeavesNothingBehind() {
        val (_, root, panel) = setup()
        panel.open(null, null)
        idle()
        assertEquals(View.VISIBLE, root.visibility)
        panel.close()
        idle()
        assertEquals(View.GONE, root.visibility)
        assertFalse(panel.isOpen)
    }

    @Test
    fun closingBeforeTheSheetGrewDoesNotLeaveItUp() {
        val (_, root, panel) = setup()
        panel.open(null, null)
        panel.close() // before the posted grow runs
        idle()
        assertEquals(View.GONE, root.visibility)
        assertFalse(tapReaches(root))
    }

    @Test
    fun closingMidwayThroughOpeningStillFinishes() {
        val (_, root, panel) = setup()
        panel.open(null, null)
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        panel.close()
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        panel.open(null, null)
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        panel.close()
        idle()
        assertEquals(View.GONE, root.visibility)
    }

    @Test
    fun aClosedChatNeverTakesTouches() {
        val (_, root, panel) = setup()
        panel.open(null, null)
        idle()
        panel.close()
        // mid-way through the close animation, taps already go through to the app
        assertFalse(tapReaches(root))
        // even if something left the layer showing, a tap goes through and tidies it away
        root.visibility = View.VISIBLE
        assertFalse(tapReaches(root))
        idle()
        assertEquals(View.GONE, root.visibility)
    }

    @Test
    fun closeRunsWhatComesNextExactlyOnce() {
        val (_, _, panel) = setup()
        var runs = 0
        panel.open(null, null)
        idle()
        panel.close { runs++ }
        idle()
        assertEquals(1, runs)
    }
}
