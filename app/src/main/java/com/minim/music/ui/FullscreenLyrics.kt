package com.minim.music.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minim.music.R
import com.minim.music.data.model.LyricsData
import com.minim.music.util.ArtworkPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * v4.9.8: the full screen lyrics mode, opened by tapping the lyric line on Now Playing (or the
 * Lyrics long press). Everything on Now Playing fades away except the progress bar, which slides
 * down to the bottom and keeps working; the cover fills the screen, heavily blurred, under a
 * scrim; the lines are big and centred, the active one bright and kept near the middle. Double
 * tap anywhere plays or pauses; back reverses the whole animation.
 *
 * MainActivity owns the layout and feeds this class: the song's cover ([onTrack]), its lyrics
 * ([setLyrics]), the active line ([setActive]) and the playback position ([setProgress],
 * [onLinePosition]). The look rules live in [FullscreenLyricsLook].
 */
class FullscreenLyrics(
    private val overlay: FullscreenLyricsLayout,
    private val artwork: ArtworkPipeline,
    private val scope: CoroutineScope,
    private val host: Host
) {

    interface Host {
        /** Everything on Now Playing (the screen's colour stays; only this fades). */
        val nowPlayingContent: View
        /** Now Playing's own progress bar; the full screen one takes over from where it is. */
        val nowPlayingBar: ProgressLine
        fun palette(): Pal
        /** The app's existing cover brightness rule (average luminance of what shows on screen). */
        fun coverLuminance(bitmap: Bitmap, viewW: Int, viewH: Int): Double
        fun animationsOn(): Boolean
        /** Plays or pauses; returns true when it was playing (so it is pausing now). */
        fun togglePlayPause(): Boolean
        fun seek(fraction: Float, finished: Boolean)
        /** The mix, ink or state changed: status bar icons, nav bar colour, status cover. */
        fun onChromeChanged()
    }

    enum class State { CLOSED, OPENING, OPEN, CLOSING }

    private val res = overlay.resources
    private fun dp(v: Float): Float = v * res.displayMetrics.density

    private val backdrop: View = overlay.findViewById(R.id.fs_backdrop)
    private val images: FrameLayout = overlay.findViewById(R.id.fs_images)
    private val bgA: ImageView = overlay.findViewById(R.id.fs_bg_a)
    private val bgB: ImageView = overlay.findViewById(R.id.fs_bg_b)
    private val scrim: View = overlay.findViewById(R.id.fs_scrim)
    private val list: RecyclerView = overlay.findViewById(R.id.fs_lines)
    private val message: TextView = overlay.findViewById(R.id.fs_message)
    private val pulse: ImageView = overlay.findViewById(R.id.fs_pulse)
    val bar: ProgressLine = overlay.findViewById(R.id.fs_bar)

    private val adapter = FullscreenLyricsAdapter(ResourcesCompat.getFont(overlay.context, R.font.poppins_semibold))
    private val layoutManager = LinearLayoutManager(overlay.context)

    var state: State = State.CLOSED
        private set
    /** Open (or opening): what the back gesture and the rest of the app treat as "on screen". */
    val isOpen: Boolean get() = state == State.OPENING || state == State.OPEN
    /** Still showing at all, closing included. */
    val isShowing: Boolean get() = state != State.CLOSED
    /** 0 = Now Playing, 1 = full screen lyrics. */
    var mix: Float = 0f
        private set
    val backdropMix: Float get() = FullscreenLyricsLook.backdropAlpha(mix)
    /** Dark text on a light cover: the status bar icons go dark too. */
    var inkIsDark: Boolean = false
        private set

    private var insetTop = 0
    private var insetBottom = 0
    private var trackUrl: String? = null
    private var lyrics: LyricsData? = null
    private var active = -1
    private var userScrolling = false

    private class Look(val url: String, val display: Bitmap, val inkDark: Boolean, val scrimAlpha: Float)
    private var cachedLook: Look? = null
    private var shownUrl: String? = null
    private var lookJob: Job? = null
    /** The cover [lookJob] is working on, so a preload for the same cover is reused, not restarted. */
    private var lookJobUrl: String? = null
    private var front: ImageView = bgA

    private var ink = FullscreenLyricsLook.WHITE_INK
    private var scrimColor = 0
    private var inkAnim: ValueAnimator? = null
    private var mixAnim: ValueAnimator? = null
    private var swapAnim: ValueAnimator? = null
    private var swapAlpha = 1f
    private var barStartX = 0f
    private var barStartY = 0f

    private val useRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val bigPx: Int get() = res.displayMetrics.widthPixels.coerceIn(720, 1440)
    private val enterEase = PathInterpolator(0.3f, 0f, 0.1f, 1f)
    private val scrollEase = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    private val resumeFollow = Runnable {
        userScrolling = false
        scrollToActive(smooth = true)
    }

    init {
        list.layoutManager = layoutManager
        list.adapter = adapter
        list.itemAnimator = null
        list.addItemDecoration(EndSpace())
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        // Reading ahead or back: stop following for a moment
                        userScrolling = true
                        list.removeCallbacks(resumeFollow)
                    }
                    RecyclerView.SCROLL_STATE_IDLE ->
                        if (userScrolling) list.postDelayed(resumeFollow, RESUME_FOLLOW_MS)
                }
            }
        })
        if (useRenderEffect) applyRenderBlur()
        bar.onSeek = { f, finished -> host.seek(f, finished) }
        overlay.excludeView = bar
        overlay.onDoubleTap = { doubleTap() }
        overlay.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) onSizeChanged()
        }
        // TalkBack's own double tap activates; give it a play/pause action instead
        ViewCompat.replaceAccessibilityAction(
            overlay, AccessibilityActionCompat.ACTION_CLICK, res.getString(R.string.fs_lyrics_play_pause)
        ) { _, _ ->
            host.togglePlayPause()
            true
        }
        applyInk(ink, FullscreenLyricsLook.scrimColor(false, FullscreenLyricsLook.SCRIM_MIN))
    }

    // ------------------------------------------------------------------------------------------
    // Inputs from MainActivity
    // ------------------------------------------------------------------------------------------

    /** System bar sizes; the overlay itself reaches behind both bars. */
    fun setInsets(top: Int, bottom: Int) {
        if (top == insetTop && bottom == insetBottom) return
        insetTop = top
        insetBottom = bottom
        applyPadding()
    }

    /** The playing song's cover URL (null when nothing is playing). */
    fun onTrack(url: String?) {
        if (url == trackUrl) return
        trackUrl = url
        if (isOpen) loadLook(crossfade = true)
    }

    /**
     * Gets the blurred cover ready before the user taps, so opening needs no wait: when Now
     * Playing opens, and (part 4) as soon as the song changes while Now Playing is open. Cheap to
     * call often. Never runs while this mode is showing: then [loadLook] owns the cover loading,
     * and a preload must not cancel it.
     */
    fun prefetch() {
        if (isShowing) return
        val url = trackUrl ?: return
        if (cachedLook?.url == url) return
        if (lookJob?.isActive == true && lookJobUrl == url) return
        lookJob?.cancel()
        lookJobUrl = url
        lookJob = scope.launch { computeLook(url) }
    }

    fun setLyrics(data: LyricsData?) {
        if (data === lyrics) return
        lyrics = data
        active = -1
        clock.reset()
        userScrolling = false
        list.removeCallbacks(resumeFollow)
        val mode = NowPlayingLyricSlot.mode(data)
        if (mode == NowPlayingLyricSlot.Mode.LINES) {
            adapter.submit(data!!.lines, data.isSynced)
            hideMessage()
        } else {
            adapter.submit(emptyList(), false)
            // Only seen when the song changes while this mode is open; part 2 blocks opening it
            showMessage(if (mode == NowPlayingLyricSlot.Mode.NONE) R.string.lyrics_unavailable else R.string.lyrics_loading)
        }
        if (state == State.CLOSED) return
        jumpToStart()
        if (state == State.OPEN && mode == NowPlayingLyricSlot.Mode.LINES) fadeInNewLines()
    }

    /** The active line (Lyrics timing offset already applied by the caller), -1 before the first. */
    fun setActive(index: Int, animate: Boolean) {
        // Flow moves to the next line itself, per frame, a little before the player's next tick
        // says so. That tick's (now older) line is not a step back; real seeks come in through
        // onLinePosition's jump check instead.
        if (index < active && flowRunning() && clock.playing) return
        applyActive(index, animate)
    }

    private fun applyActive(index: Int, animate: Boolean) {
        if (index == active) return
        active = index
        adapter.active = index
        if (!adapter.synced || state == State.CLOSED) return
        restyleVisible(animate && host.animationsOn())
        if (!userScrolling) scrollToActive(smooth = animate)
        if (flowRunning()) {
            // The row fades up to full opacity: keep the frames coming until it has
            settleUntilNs = System.nanoTime() + SETTLE_NS
            updateFrameLoop()
        }
    }

    /**
     * Song position with the offset applied, from the player's ~4 per second tick. Feeds the
     * Flow clock (even while closed, so it's in sync when this opens). A seek (a jump) puts the
     * right line in place straight away.
     */
    fun onLinePosition(shiftedMs: Long, playing: Boolean) {
        val jump = clock.sample(shiftedMs, playing, System.nanoTime())
        if (!adapter.synced) return
        if (jump && adapter.flow) applyActive(FlowTiming.activeIndex(adapter.lines, shiftedMs), animate = true)
        if (state == State.CLOSED) return
        if (flowRunning()) {
            drawFlow(System.nanoTime())
            if (!playing) settleUntilNs = System.nanoTime() + SETTLE_NS
            updateFrameLoop()
        } else if (active >= 0) {
            activeLineView()?.onLinePosition(shiftedMs)
        }
    }

    /** v4.9.8 part 4: the Lyrics style setting. Applies live, also while this is open. */
    var flowStyle: Boolean
        get() = adapter.flow
        set(value) {
            if (adapter.flow == value) return
            adapter.flow = value
            if (state == State.CLOSED) return
            restyleVisible(false)
            if (flowRunning()) drawFlow(System.nanoTime())
            updateFrameLoop()
        }

    /** The app went to the background (or came back): no frames while nobody can see them. */
    fun setHostVisible(visible: Boolean) {
        if (hostVisible == visible) return
        hostVisible = visible
        updateFrameLoop()
    }

    // ------------------------------------------------------------------------------------------
    // Flow: the per frame word highlight (part 4)
    // ------------------------------------------------------------------------------------------

    private val clock = FlowClock()
    private var hostVisible = true
    private var frameQueued = false
    private var settleUntilNs = 0L
    private val frameCallback = Choreographer.FrameCallback { nanos ->
        frameQueued = false
        if (!wantFrames()) return@FrameCallback
        drawFlow(nanos)
        updateFrameLoop()
    }

    /** Flow is what the open lyrics show right now (timed lyrics, Flow style, showing). */
    private fun flowRunning(): Boolean = adapter.flow && adapter.synced && state != State.CLOSED

    /** Frames only while Flow is visible and something moves: playing, or a line settling. */
    private fun wantFrames(): Boolean =
        flowRunning() && hostVisible && overlay.isAttachedToWindow &&
            (clock.playing || System.nanoTime() < settleUntilNs)

    private fun updateFrameLoop() {
        val want = wantFrames()
        if (want && !frameQueued) {
            frameQueued = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else if (!want && frameQueued) {
            frameQueued = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    /** One Flow frame: move to the next line when it's due, then light the words up to now. */
    private fun drawFlow(nowNs: Long) {
        if (!clock.hasSample) return
        val pos = clock.positionAt(nowNs)
        val lines = adapter.lines
        var index = active
        while (index + 1 < lines.size && lines[index + 1].startMs <= pos) index++
        if (index != active) applyActive(index, animate = true)
        if (active >= 0) activeLineView()?.onLinePosition(pos)
    }

    private fun activeLineView(): LyricLineView? = layoutManager.findViewByPosition(active) as? LyricLineView

    fun setProgress(fraction: Float) = bar.setFraction(fraction)

    /** The app palette changed (new song colours, dark mode): only the bar's start and the base. */
    fun onPalette(p: Pal) {
        backdrop.setBackgroundColor(p.bg)
        if (shownUrl == null && isShowing) applyBaseInk(animate = true)
        if (isShowing) updateBarColors()
    }

    // ------------------------------------------------------------------------------------------
    // Open / close
    // ------------------------------------------------------------------------------------------

    fun open() {
        if (isOpen) return
        if (NowPlayingLyricSlot.mode(lyrics) != NowPlayingLyricSlot.Mode.LINES) return
        val fromClosed = state == State.CLOSED
        if (fromClosed && adapter.flow && adapter.synced && clock.hasSample) {
            // Flow may have moved lines on its own clock: start on the line that's really playing
            val index = FlowTiming.activeIndex(adapter.lines, clock.positionAt(System.nanoTime()))
            active = index
            adapter.active = index
        }
        state = State.OPENING
        list.removeCallbacks(resumeFollow)
        userScrolling = false
        if (!fromClosed) {
            // Re-opened while closing: just turn the animation around
            animateMix(1f)
            return
        }
        overlay.visibility = View.VISIBLE
        host.nowPlayingContent.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        backdrop.setBackgroundColor(host.palette().bg)
        syncBarWidth()
        applyPadding()
        showLookNow()
        setMix(0f)
        overlay.doOnPreDraw {
            if (state != State.OPENING) return@doOnPreDraw
            measureBarStart()
            setMix(0f)
            host.nowPlayingBar.visibility = View.INVISIBLE
            restyleVisible(false)
            jumpToStart()
            if (flowRunning()) drawFlow(System.nanoTime())
            updateFrameLoop()
            animateMix(1f)
        }
        host.onChromeChanged()
    }

    /** Back: reverse everything. [animate] false for jumps (song ended, going to an artist). */
    fun close(animate: Boolean = true) {
        if (state == State.CLOSED) return
        if (!animate || !host.animationsOn() || (mixAnim == null && mix == 0f)) {
            mixAnim?.cancel()
            state = State.CLOSING
            setMix(0f)
            finishClose()
            return
        }
        if (state == State.CLOSING) return
        state = State.CLOSING
        list.removeCallbacks(resumeFollow)
        measureBarStart()
        animateMix(0f)
    }

    private fun animateMix(to: Float) {
        mixAnim?.cancel()
        val from = mix
        val full = if (to > from) OPEN_MS else CLOSE_MS
        val dur = (full * abs(to - from)).toLong()
        if (!host.animationsOn() || dur < 16L) {
            mixAnim = null
            setMix(to)
            onMixEnd(to)
            return
        }
        mixAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = dur
            interpolator = enterEase
            addUpdateListener { setMix(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || mixAnim !== animation) return
                    mixAnim = null
                    onMixEnd(to)
                }
            })
            start()
        }
    }

    private fun onMixEnd(to: Float) {
        if (to >= 1f) {
            state = State.OPEN
            host.onChromeChanged()
        } else {
            finishClose()
        }
    }

    private fun finishClose() {
        state = State.CLOSED
        updateFrameLoop()
        mixAnim = null
        list.removeCallbacks(resumeFollow)
        userScrolling = false
        overlay.visibility = View.GONE
        bar.translationX = 0f
        bar.translationY = 0f
        pulse.animate().cancel()
        pulse.alpha = 0f
        host.nowPlayingContent.alpha = 1f
        host.nowPlayingContent.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        host.nowPlayingBar.visibility = View.VISIBLE
        host.onChromeChanged()
    }

    /** One value drives the whole transition, so a reversed back gesture mid-way just works. */
    private fun setMix(t: Float) {
        mix = t
        host.nowPlayingContent.alpha = FullscreenLyricsLook.nowPlayingAlpha(t)
        backdrop.alpha = FullscreenLyricsLook.backdropAlpha(t)
        applyContentAlpha()
        val k = 1f - t
        bar.translationX = barStartX * k
        bar.translationY = barStartY * k
        updateBarColors()
        host.onChromeChanged()
    }

    private fun applyContentAlpha() {
        val a = FullscreenLyricsLook.linesAlpha(mix)
        val lift = FullscreenLyricsLook.linesLift(mix) * dp(LIFT_DP)
        list.alpha = a * swapAlpha
        list.translationY = lift
        message.alpha = a * MESSAGE_ALPHA
        message.translationY = lift
    }

    /** Where Now Playing's bar is, relative to where this bar rests at the bottom. */
    private fun measureBarStart() {
        val np = host.nowPlayingBar
        if (np.width == 0 || bar.width == 0) {
            barStartX = 0f
            barStartY = 0f
            return
        }
        val a = IntArray(2)
        val b = IntArray(2)
        np.getLocationInWindow(a)
        bar.getLocationInWindow(b)
        val restX = b[0] - bar.translationX
        val restY = b[1] - bar.translationY
        barStartX = a[0] - restX + (np.width - bar.width) / 2f
        barStartY = a[1] - restY + (np.height - bar.height) / 2f
    }

    private fun updateBarColors() {
        val p = host.palette()
        val t = mix
        bar.setColors(
            FullscreenLyricsLook.lerpColor(p.fg, ink, t),
            FullscreenLyricsLook.lerpColor(p.muted, restInk(ink), t)
        )
    }

    private fun restInk(i: Int): Int = (i and 0x00FFFFFF) or (BAR_REST_ALPHA shl 24)

    // ------------------------------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------------------------------

    private fun onSizeChanged() {
        applyPadding()
        if (isShowing) {
            syncBarWidth()
            list.post { scrollToActive(smooth = false) }
        }
    }

    private fun syncBarWidth() {
        val w = host.nowPlayingBar.width.takeIf { it > 0 } ?: (overlay.width * 0.73f).toInt()
        if (w <= 0) return
        val lp = bar.layoutParams as FrameLayout.LayoutParams
        val bottom = insetBottom + dp(BAR_BOTTOM_DP).toInt()
        if (lp.width != w || lp.bottomMargin != bottom) {
            lp.width = w
            lp.bottomMargin = bottom
            bar.layoutParams = lp
        }
    }

    private fun applyPadding() {
        val width = overlay.width.takeIf { it > 0 } ?: res.displayMetrics.widthPixels
        val side = FullscreenLyricsLook.sidePadding(width, dp(SIDE_MIN_DP).toInt(), dp(LINE_MAX_DP).toInt())
        val top = insetTop + dp(TOP_GAP_DP).toInt()
        val bottom = insetBottom + dp(BAR_BOTTOM_DP + BAR_HEIGHT_DP + BAR_GAP_DP).toInt()
        if (list.paddingLeft != side || list.paddingTop != top || list.paddingBottom != bottom) {
            list.setPadding(side, top, side, bottom)
            list.invalidateItemDecorations()
        }
        syncBarWidth()
    }

    /** Lets the first and last line reach the middle (synced) or sit a little in (untimed). */
    private inner class EndSpace : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(out: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val pos = parent.getChildAdapterPosition(view)
            if (pos == RecyclerView.NO_POSITION) return
            val inner = parent.height - parent.paddingTop - parent.paddingBottom
            if (inner <= 0) return
            val gap = if (adapter.synced) max(0, inner / 2 - dp(LINE_HALF_DP).toInt()) else (inner * 0.14f).toInt()
            if (pos == 0) out.top = gap
            if (pos == state.itemCount - 1) out.bottom = gap
        }
    }

    // ------------------------------------------------------------------------------------------
    // Lines
    // ------------------------------------------------------------------------------------------

    private fun restyleVisible(animate: Boolean) {
        for (i in 0 until list.childCount) {
            val holder = list.getChildViewHolder(list.getChildAt(i)) as? FullscreenLyricsAdapter.Holder ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) adapter.style(holder.line, pos, animate)
        }
    }

    /** Synced: the active line in the middle. Untimed: the top. No animation. */
    private fun jumpToStart() {
        list.stopScroll()
        if (adapter.itemCount == 0) return
        if (adapter.synced) scrollToActive(smooth = false) else layoutManager.scrollToPositionWithOffset(0, 0)
    }

    private fun scrollToActive(smooth: Boolean) {
        if (!adapter.synced) return
        val target = FullscreenLyricsLook.scrollTarget(active, adapter.itemCount)
        if (target < 0) return
        val areaTop = list.paddingTop
        val areaBottom = list.height - list.paddingBottom
        val v = layoutManager.findViewByPosition(target)
        if (v != null && list.height > 0) {
            val dy = FullscreenLyricsLook.centreDelta(v.top, v.bottom, areaTop, areaBottom)
            if (dy == 0) return
            if (smooth && host.animationsOn()) list.smoothScrollBy(0, dy, scrollEase, SCROLL_MS)
            else list.scrollBy(0, dy)
        } else {
            // Not laid out yet, or far away: jump close, then centre exactly after the next layout
            val half = max(0, (areaBottom - areaTop) / 2 - dp(LINE_HALF_DP).toInt())
            layoutManager.scrollToPositionWithOffset(target, half)
            list.post {
                val w = layoutManager.findViewByPosition(target) ?: return@post
                val dy = FullscreenLyricsLook.centreDelta(w.top, w.bottom, list.paddingTop, list.height - list.paddingBottom)
                if (dy != 0) list.scrollBy(0, dy)
            }
        }
    }

    /** A new song's lyrics arrive while open: they fade in instead of popping. */
    private fun fadeInNewLines() {
        swapAnim?.cancel()
        if (!host.animationsOn()) {
            swapAlpha = 1f
            applyContentAlpha()
            return
        }
        swapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWAP_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                swapAlpha = it.animatedValue as Float
                applyContentAlpha()
            }
            start()
        }
    }

    private fun showMessage(textRes: Int) {
        message.setText(textRes)
        message.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        message.visibility = View.GONE
    }

    // ------------------------------------------------------------------------------------------
    // Double tap: play / pause with a small pulse, since there are no controls on screen
    // ------------------------------------------------------------------------------------------

    private fun doubleTap() {
        if (state != State.OPEN) return
        val wasPlaying = host.togglePlayPause()
        overlay.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        pulse.setImageResource(if (wasPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        pulse.animate().cancel()
        if (!host.animationsOn()) return
        pulse.alpha = 0f
        pulse.scaleX = 0.7f
        pulse.scaleY = 0.7f
        pulse.animate().alpha(PULSE_ALPHA).scaleX(1f).scaleY(1f).setDuration(160L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                pulse.animate().alpha(0f).scaleX(1.12f).scaleY(1.12f).setStartDelay(220L).setDuration(360L).start()
            }
            .start()
    }

    // ------------------------------------------------------------------------------------------
    // The blurred cover, the scrim and the ink
    // ------------------------------------------------------------------------------------------

    private fun applyRenderBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val r = FullscreenLyricsLook.blurRadiusPx(res.displayMetrics.widthPixels)
        val effect = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
        bgA.setRenderEffect(effect)
        bgB.setRenderEffect(effect)
    }

    /** On open: the ready cover right away, else the app colour until it arrives. */
    private fun showLookNow() {
        val url = trackUrl
        val c = cachedLook
        when {
            url == null -> showBase(crossfade = false)
            shownUrl == url && c?.url == url -> setInk(c.inkDark, c.scrimAlpha, animate = false)
            c != null && c.url == url -> applyLook(c, crossfade = false)
            else -> {
                showBase(crossfade = false)
                loadLook(crossfade = true)
            }
        }
    }

    private fun loadLook(crossfade: Boolean) {
        val url = trackUrl
        // A preload of this same cover still running: wait for it instead of starting over
        val preload = lookJob?.takeIf { it.isActive && lookJobUrl == url }
        if (preload == null) lookJob?.cancel()
        if (url == null) {
            lookJob = null
            showBase(crossfade)
            return
        }
        cachedLook?.let { if (it.url == url) { applyLook(it, crossfade); return } }
        lookJobUrl = url
        lookJob = scope.launch {
            preload?.join()
            val look = computeLook(url)
            if (url != trackUrl || !isShowing) return@launch
            if (look == null) showBase(crossfade) else applyLook(look, crossfade)
        }
    }

    /** Loads the cover at Now Playing's size (same cache entry) and works out its look. */
    private suspend fun computeLook(url: String): Look? {
        cachedLook?.let { if (it.url == url) return it }
        val bmp = artwork.loadBitmap(url, bigPx) ?: return null
        val vw = overlay.width.takeIf { it > 0 } ?: res.displayMetrics.widthPixels
        val vh = overlay.height.takeIf { it > 0 } ?: res.displayMetrics.heightPixels
        val look = withContext(Dispatchers.Default) {
            runCatching {
                val sw = FullscreenLyricsLook.SOFT_BLUR_WIDTH
                val sh = max(1, sw * bmp.height / max(1, bmp.width))
                val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
                val px = IntArray(sw * sh)
                small.getPixels(px, 0, sw, 0, 0, sw, sh)
                if (small !== bmp) small.recycle()
                val blurred = SoftwareBlur.blur(px, sw, sh, FullscreenLyricsLook.SOFT_BLUR_RADIUS)
                val inkDark = FullscreenLyricsLook.inkIsDark(host.coverLuminance(bmp, vw, vh))
                val visible = FullscreenLyricsLook.crop(blurred, sw, FullscreenLyricsLook.centerCropRect(sw, sh, vw, vh))
                val alpha = FullscreenLyricsLook.scrimAlpha(FullscreenLyricsLook.worstPixel(visible, inkDark), inkDark)
                val display = if (useRenderEffect) bmp else Bitmap.createBitmap(blurred, sw, sh, Bitmap.Config.ARGB_8888)
                Look(url, display, inkDark, alpha)
            }.getOrNull()
        } ?: return null
        cachedLook = look
        return look
    }

    private fun applyLook(look: Look, crossfade: Boolean) {
        shownUrl = look.url
        showImage(look.display, crossfade)
        setInk(look.inkDark, look.scrimAlpha, animate = crossfade)
    }

    /** No cover (or it failed): the app colour, with ink to match it. */
    private fun showBase(crossfade: Boolean) {
        shownUrl = null
        showImage(null, crossfade)
        applyBaseInk(crossfade)
    }

    private fun applyBaseInk(animate: Boolean) {
        val bg = host.palette().bg
        val dark = Painter.isLight(bg)
        setInk(dark, FullscreenLyricsLook.scrimAlpha(bg, dark), animate)
    }

    private fun showImage(bitmap: Bitmap?, crossfade: Boolean) {
        bgA.animate().cancel()
        bgB.animate().cancel()
        val old = front
        val next = if (front === bgA) bgB else bgA
        if (!crossfade || !host.animationsOn() || mix == 0f && state != State.OPEN) {
            old.setImageBitmap(bitmap)
            old.alpha = 1f
            next.setImageDrawable(null)
            next.alpha = 0f
            return
        }
        old.alpha = 1f
        next.setImageBitmap(bitmap)
        next.alpha = 0f
        next.bringToFront()
        images.invalidate()
        front = next
        next.animate().alpha(1f).setDuration(CROSSFADE_MS).setInterpolator(DecelerateInterpolator())
            .withEndAction { old.setImageDrawable(null) }
            .start()
    }

    private fun setInk(dark: Boolean, scrimAlpha: Float, animate: Boolean) {
        val toInk = FullscreenLyricsLook.ink(dark)
        val toScrim = FullscreenLyricsLook.scrimColor(dark, scrimAlpha)
        inkIsDark = dark
        inkAnim?.cancel()
        if (!animate || !host.animationsOn()) {
            applyInk(toInk, toScrim)
        } else {
            val fromInk = ink
            val fromScrim = scrimColor
            inkAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = CROSSFADE_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    applyInk(FullscreenLyricsLook.lerpColor(fromInk, toInk, f), FullscreenLyricsLook.lerpColor(fromScrim, toScrim, f))
                }
                start()
            }
        }
        host.onChromeChanged()
    }

    private fun applyInk(i: Int, s: Int) {
        ink = i
        scrimColor = s
        scrim.setBackgroundColor(s)
        adapter.ink = i
        for (c in 0 until list.childCount) (list.getChildAt(c) as? TextView)?.setTextColor(i)
        message.setTextColor(i)
        pulse.imageTintList = ColorStateList.valueOf(i)
        updateBarColors()
    }

    companion object {
        const val OPEN_MS = 560L
        const val CLOSE_MS = 460L
        const val CROSSFADE_MS = 700L
        const val SCROLL_MS = 650
        const val SWAP_MS = 360L
        const val STYLE_MS = FullscreenLyricsAdapter.STYLE_MS
        /** Flow keeps drawing this long after a line change while paused, while its row fades up. */
        const val SETTLE_NS = (STYLE_MS + 120L) * 1_000_000L
        const val RESUME_FOLLOW_MS = 2600L
        const val LIFT_DP = 28f
        const val SIDE_MIN_DP = 36f
        const val LINE_MAX_DP = 560f
        const val TOP_GAP_DP = 20f
        const val BAR_BOTTOM_DP = 28f
        const val BAR_HEIGHT_DP = 20f
        const val BAR_GAP_DP = 18f
        const val LINE_HALF_DP = 30f
        const val MESSAGE_ALPHA = 0.6f
        const val PULSE_ALPHA = 0.9f
        const val BAR_REST_ALPHA = 0x4D
    }
}
