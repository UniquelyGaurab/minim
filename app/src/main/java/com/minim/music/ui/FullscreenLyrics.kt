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
import com.minim.music.data.ai.LyricLanguage
import com.minim.music.data.ai.isAiLyrics
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
        /**
         * 5.1.0: a lyric line was tapped. Seeks to the moment line time [lineStartMs] shows (the
         * timing offsets taken back out) with a light tick, and puts that line in place.
         */
        fun seekToLyric(lineStartMs: Long)
        /** The mix, ink or state changed: status bar icons, nav bar colour, status cover. */
        fun onChromeChanged()
        /** 5.6.0: the phone's saved English translation of [data] (one entry per line), or null. Never calls Gemini. */
        suspend fun savedTranslation(data: LyricsData): List<String?>?
        /** 5.6.0: [data] translated by Gemini (one call per song, then saved). Throws when it fails. */
        suspend fun translateLyrics(data: LyricsData): List<String?>
        /** 5.6.0: the app's small tap haptic. */
        fun tapHaptic(v: View)
        /** 5.7.0: the song time at [fraction] of the bar, as the time pill shows it ("2:47"). */
        fun seekLabel(fraction: Float): String
        /** 5.7.0: the light tick when the dragged bar reaches either end. */
        fun edgeTick(v: View)
        /** 5.8.1: Gemini lyrics (the offer, the request and the saved copies). */
        val aiLyrics: FullscreenAiLyrics.Host
        /** 6.6.0: the Share pill in select mode: make a lyrics card from these lines. */
        fun shareLyrics(share: LyricShare)
    }

    /**
     * 6.6.0: lines picked for a lyrics card: [data]'s lines [first]..[last], and their English
     * translations when they're on screen right now (else null; the host can still find saved ones).
     */
    data class LyricShare(val data: LyricsData, val first: Int, val last: Int, val translations: List<String?>?)

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
    /** 5.7.0: the time pill above the finger while the bar is dragged (like Now Playing's). */
    private val seekPill: TextView = overlay.findViewById(R.id.fs_seek_pill)
    private val seekPillBg = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 40f * overlay.resources.displayMetrics.density
    }
    private var seekPillShown = false

    private val adapter = FullscreenLyricsAdapter(
        ResourcesCompat.getFont(overlay.context, R.font.poppins_semibold),
        ResourcesCompat.getFont(overlay.context, R.font.poppins_regular)
    )
    /** 5.6.0: AI translation button (top right) and its failure note (top middle). */
    private val translateBtn: ImageView = overlay.findViewById(R.id.fs_translate)
    /** 6.6.0: share lyrics as a card. */
    private val shareBtn: ImageView = overlay.findViewById(R.id.fs_share)
    private val aiBadge: View? = overlay.findViewById(R.id.fs_ai_badge)
    private val selectFont = runCatching { ResourcesCompat.getFont(overlay.context, R.font.poppins_semibold) }.getOrNull()
    /** 6.6.0: select mode's bottom row: "2/5" and the Share pill. */
    private val selectBar = android.widget.LinearLayout(overlay.context).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        visibility = View.GONE
        isClickable = true
    }
    private val selectCount = TextView(overlay.context).apply {
        textSize = 14f
        typeface = selectFont
        includeFontPadding = false
    }
    private val selectShare = TextView(overlay.context).apply {
        textSize = 15f
        typeface = selectFont
        includeFontPadding = false
        setText(R.string.lyrics_select_share)
        isClickable = true
        isFocusable = true
    }
    private val selectShareBg = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 999f }
    /** 6.6.0: the gentle line above the bar ("Tap the lines above or below to add them", limits). */
    private val selectHint = TextView(overlay.context).apply {
        textSize = 13f
        typeface = selectFont
        includeFontPadding = false
        gravity = android.view.Gravity.CENTER
        visibility = View.GONE
        alpha = 0f
    }
    private val selectHintBg = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 999f }
    private var selection: com.minim.music.data.share.LyricSelection? = null
    private val hideHint = Runnable { fadeHint(false) }
    /** 6.6.0: picking lines for a lyrics card. Back cancels it first. */
    val isSelecting: Boolean get() = selection != null
    private val translateNote: TextView = overlay.findViewById(R.id.fs_translate_note)
    /** 5.7.0 u46: switches between a stories upload's caption tracks (only with more than one). */
    private val captionPill: TextView = overlay.findViewById(R.id.fs_caption_pill)
    private val captionPillBg = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 999f }
    /** Tapped the caption pill (MainActivity moves to the next caption track). */
    var onCaptionPill: (() -> Unit)? = null

    /**
     * 5.7.0 u46: a stories upload is playing, so this view shows its YouTube captions: it can open
     * with none (and says so quietly), and there's no translate button.
     */
    var captionsMode: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            if (!v) setCaptionChoice(null)
            refreshMessage()
            onLyricsForTranslation(if (!v && NowPlayingLyricSlot.mode(lyrics) == NowPlayingLyricSlot.Mode.LINES) lyrics else null)
        }
    private val translateOnBg = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
    }
    private val translateNoteBg = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 40f * overlay.resources.displayMetrics.density
    }

    private val layoutManager = LinearLayoutManager(overlay.context)

    /** 5.8.1 (update 53): Gemini lyrics for songs without any. */
    val ai = FullscreenAiLyrics(overlay, scope, host.aiLyrics)

    /**
     * 5.8.1: the song the Gemini offer is for: set for a song (not a tape) whose lyrics lookup
     * found none, or that's showing saved AI lyrics. Null hides the offer.
     */
    fun setAiSong(id: String?) {
        ai.setSong(id)
        refreshMessage()
    }

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

    // 5.6.0: translation state
    /** Which lines are English, skipped or other, for the lyrics showing now. */
    private var langPlan: LyricLanguage.Plan? = null
    /** The translate button is on. Stays on for the next songs until it's tapped off or this screen closes. */
    private var translateOn = false
    private var translateJob: Job? = null
    private var revealAnim: ValueAnimator? = null
    private var fadeAnim: ValueAnimator? = null
    private var translatingAnim: ValueAnimator? = null
    /** While the space under the lines opens or closes, keep the active line in the middle. */
    private var keepCentred = false

    private val useRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val bigPx: Int get() = res.displayMetrics.widthPixels.coerceIn(720, 1440)
    private val enterEase = PathInterpolator(0.3f, 0f, 0.1f, 1f)
    private val scrollEase = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    private val resumeFollow = Runnable {
        userScrolling = false
        if (selection == null) scrollToActive(smooth = true)
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
        // 5.7.0: the animated bar from Now Playing (5.5.0): thickens and grows a thumb when
        // grabbed, a time pill above the finger, a light tick at either end, smooth fill and the
        // buffered part
        bar.expressive = true
        bar.onScrub = { f, shown -> showSeekPill(f, shown) }
        bar.onEdge = { host.edgeTick(bar) }
        seekPill.background = seekPillBg
        overlay.excludeView = bar
        overlay.moreExcluded = listOf(translateBtn, translateNote, captionPill, ai.panel, ai.redo, shareBtn, selectBar, selectHint)
        setUpSelect()
        ai.onListHidden = { hidden ->
            list.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            translateBtn.visibility = if (!hidden && langPlan?.showButton == true) View.VISIBLE else View.GONE
            if (hidden) hideTranslateNote()
            if (hidden) cancelSelect()
            refreshShareButton()
        }
        captionPill.setOnClickListener {
            if (state != State.OPEN || captionPill.visibility != View.VISIBLE) return@setOnClickListener
            host.tapHaptic(captionPill)
            onCaptionPill?.invoke()
        }
        translateBtn.setOnClickListener { onTranslateTapped() }
        translateNote.setOnClickListener { retryTranslation() }
        list.viewTreeObserver.addOnGlobalLayoutListener { if (keepCentred) keepActiveCentred() }
        overlay.onDoubleTap = { doubleTap() }
        // 5.1.0: tap a line to jump there
        overlay.tapBlocked = { list.scrollState != RecyclerView.SCROLL_STATE_IDLE }
        overlay.onSingleTap = { e -> if (selection != null) selectTapAt(e.rawX, e.rawY) else tapAt(e.rawX, e.rawY) }
        overlay.onLongPress = { e -> longPressAt(e.rawX, e.rawY) }
        adapter.lineActionLabel = res.getString(R.string.fs_lyrics_play_from_line)
        adapter.onLineAction = { seekToLine(it) }
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
        placeTranslateControls()
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
        placeTranslateControls()
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
        cancelSelect(animate = false)
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
            refreshMessage()
        }
        onLyricsForTranslation(if (mode == NowPlayingLyricSlot.Mode.LINES && !isCaptions()) data else null)
        ai.setShowingAi(mode == NowPlayingLyricSlot.Mode.LINES && data.isAiLyrics())
        refreshMessage()
        refreshShareButton()
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
        if (!userScrolling && selection == null) scrollToActive(smooth = animate)
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

    private fun activeLineView(): LyricLineView? = (layoutManager.findViewByPosition(active) as? LyricRow)?.line

    fun setProgress(fraction: Float) = bar.setFraction(fraction)

    /** 5.7.0: the latest playback position, so the bar fills smoothly and shows what's loaded. */
    fun setPlayback(positionMs: Long, durationMs: Long, isPlaying: Boolean, bufferedMs: Long) =
        bar.setPlayback(positionMs, durationMs, isPlaying, bufferedMs)

    /** 5.7.0: the time pill floating above the finger while the bar is dragged. */
    private fun showSeekPill(fraction: Float, shown: Boolean) {
        val pill = seekPill
        if (!shown) {
            if (seekPillShown) {
                seekPillShown = false
                pill.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).translationYBy(dp(4f))
                    .setDuration(140L).withEndAction { pill.visibility = View.INVISIBLE }.start()
            }
            return
        }
        // The cover's ink on the pill, the dark or light opposite for its text
        seekPillBg.setColor(ink)
        pill.setTextColor(if (inkIsDark) 0xFFFFFFFF.toInt() else 0xFF111111.toInt())
        pill.text = host.seekLabel(fraction)
        val barLoc = IntArray(2)
        val hostLoc = IntArray(2)
        bar.getLocationInWindow(barLoc)
        overlay.getLocationInWindow(hostLoc)
        val half = 2.5f * res.displayMetrics.density
        val left = bar.paddingLeft + half
        val right = bar.width - bar.paddingRight - half
        val xInBar = left + (right - left) * fraction
        val pw = if (pill.width > 0) pill.width.toFloat() else dp(52f)
        val ph = if (pill.height > 0) pill.height.toFloat() else dp(28f)
        val margin = dp(8f)
        val x = (barLoc[0] - hostLoc[0] + xInBar - pw / 2f)
            .coerceIn(margin, (overlay.width - pw - margin).coerceAtLeast(margin))
        val y = barLoc[1] - hostLoc[1] + bar.height / 2f - ph - dp(16f)
        pill.translationX = x
        if (!seekPillShown) {
            seekPillShown = true
            pill.animate().cancel()
            pill.translationY = y + dp(6f)
            pill.alpha = 0f
            pill.scaleX = 0.85f
            pill.scaleY = 0.85f
            pill.visibility = View.VISIBLE
            pill.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(y)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).setDuration(160L)
                .withEndAction(null).start()
        } else if (pill.animation == null) {
            pill.translationY = y
        }
    }

    /** The app palette changed (new song colours, dark mode): only the bar's start and the base. */
    fun onPalette(p: Pal) {
        backdrop.setBackgroundColor(p.bg)
        ai.setCoverColors(p)
        if (shownUrl == null && isShowing) applyBaseInk(animate = true)
        if (isShowing) updateBarColors()
    }

    // ------------------------------------------------------------------------------------------
    // Open / close
    // ------------------------------------------------------------------------------------------

    fun open() {
        if (isOpen) return
        // 5.7.0 u46: captions open even when there are none (the CC button says "show me").
        // 5.8.1: songs open with none too (5.8.0's tap on the lyric area): the Gemini offer shows.
        if (NowPlayingLyricSlot.mode(lyrics) != NowPlayingLyricSlot.Mode.LINES && !isCaptions() && ai.songId == null) return
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
        cancelSelect(animate = false)
        state = State.CLOSED
        resetTranslation()
        updateFrameLoop()
        mixAnim = null
        list.removeCallbacks(resumeFollow)
        userScrolling = false
        overlay.visibility = View.GONE
        bar.translationX = 0f
        bar.translationY = 0f
        seekPill.animate().cancel()
        seekPill.visibility = View.INVISIBLE
        seekPillShown = false
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
        translateBtn.alpha = a
        translateNote.alpha = a
        captionPill.alpha = a
        shareBtn.alpha = a
        selectBar.alpha = a
        ai.setAlpha(a, lift)
        if (selection != null) aiBadge?.alpha = 0f
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

    private fun isCaptions(): Boolean =
        captionsMode || com.minim.music.data.captions.CaptionLyrics.isCaptions(lyrics)

    /** The quiet line shown in place of lines: loading, or none for this song / upload. */
    private fun refreshMessage() {
        val mode = NowPlayingLyricSlot.mode(lyrics)
        // 5.8.1: a song with no lyrics gets the Gemini offer in place of the quiet line
        val offer = mode == NowPlayingLyricSlot.Mode.NONE && !isCaptions() && ai.songId != null
        ai.setPanelWanted(offer)
        if (mode == NowPlayingLyricSlot.Mode.LINES) return
        if (offer) {
            hideMessage()
            return
        }
        val caps = isCaptions()
        showMessage(when {
            // 6.1.1: captions switched off (CC on Now Playing turns them on)
            mode == NowPlayingLyricSlot.Mode.NONE && com.minim.music.data.captions.CaptionLyrics.isOff(lyrics) -> R.string.captions_off
            mode == NowPlayingLyricSlot.Mode.NONE && caps -> R.string.captions_none
            mode == NowPlayingLyricSlot.Mode.NONE -> R.string.lyrics_unavailable
            caps -> R.string.captions_loading
            else -> R.string.lyrics_loading
        })
    }

    /** 5.7.0 u46: the caption pill's text ("English \u00B7 auto"), or null to hide it. */
    fun setCaptionChoice(label: String?) {
        if (label == null || !captionsMode) {
            captionPill.visibility = View.GONE
            return
        }
        if (captionPill.text != label) captionPill.text = label
        captionPill.contentDescription = res.getString(R.string.captions_switch, label)
        if (captionPill.visibility != View.VISIBLE) {
            captionPill.visibility = View.VISIBLE
            captionPill.alpha = FullscreenLyricsLook.linesAlpha(mix)
        }
        styleCaptionPill()
    }

    private fun styleCaptionPill() {
        captionPill.setTextColor(ink)
        captionPillBg.setColor((ink and 0x00FFFFFF) or (TRANSLATE_NOTE_FILL shl 24))
        if (captionPill.background !== captionPillBg) captionPill.background = captionPillBg
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

    /** 5.1.0: a single tap at screen point [rawX], [rawY]: the synced line under it, if any. */
    // ------------------------------------------------------------------------------------------
    // 6.6.0: lyrics card. The share icon (next to translate) or holding a line picks lines.
    // ------------------------------------------------------------------------------------------

    private fun setUpSelect() {
        selectShare.background = selectShareBg
        val padH = dp(20f).toInt()
        val padV = dp(10f).toInt()
        selectShare.setPadding(padH, padV, padH, padV)
        selectCount.setPadding(0, 0, dp(14f).toInt(), 0)
        selectBar.addView(selectCount)
        selectBar.addView(selectShare)
        overlay.addView(selectBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        ))
        selectHint.background = selectHintBg
        val hp = dp(14f).toInt()
        selectHint.setPadding(hp, dp(7f).toInt(), hp, dp(7f).toInt())
        overlay.addView(selectHint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        ))
        shareBtn.setOnClickListener { onShareTapped() }
        selectShare.setOnClickListener { confirmSelect() }
        placeShareControls(insetTop + dp(TRANSLATE_TOP_DP).toInt())
        styleSelectControls()
    }

    /** Share sits just left of translate, or where translate is when that's hidden; the bar above the progress bar. */
    private fun placeShareControls(top: Int) {
        val transShown = translateBtn.visibility == View.VISIBLE
        val end = dp(14f).toInt() + if (transShown) dp(44f + 2f).toInt() else 0
        (shareBtn.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.topMargin != top || it.marginEnd != end) {
                it.topMargin = top
                it.marginEnd = end
                shareBtn.layoutParams = it
            }
        }
        val barBottom = insetBottom + dp(BAR_BOTTOM_DP + BAR_HEIGHT_DP + SELECT_BAR_GAP_DP).toInt()
        (selectBar.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.bottomMargin != barBottom) {
                it.bottomMargin = barBottom
                selectBar.layoutParams = it
            }
        }
        val hintBottom = barBottom + dp(SELECT_HINT_GAP_DP).toInt()
        (selectHint.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.bottomMargin != hintBottom) {
                it.bottomMargin = hintBottom
                selectHint.layoutParams = it
            }
        }
    }

    /** Shown while real lyric lines are on screen (not captions, not Gemini's panel). */
    private fun refreshShareButton() {
        val show = lyrics != null && adapter.itemCount > 0 && !isCaptions() && list.visibility == View.VISIBLE
        val v = if (show) View.VISIBLE else View.GONE
        if (shareBtn.visibility != v) {
            shareBtn.visibility = v
            shareBtn.alpha = FullscreenLyricsLook.linesAlpha(mix)
        }
        if (!show) cancelSelect()
        placeShareControls(insetTop + dp(TRANSLATE_TOP_DP).toInt())
    }

    private fun styleSelectControls() {
        shareBtn.imageTintList = ColorStateList.valueOf(ink)
        selectCount.setTextColor((ink and 0x00FFFFFF) or (0xB3 shl 24))
        selectShareBg.setColor(ink)
        // the pill's text in the backdrop's colour, so it reads on the ink fill
        val onInk = if (androidx.core.graphics.ColorUtils.calculateLuminance(ink) > 0.5) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        selectShare.setTextColor(onInk)
        selectHint.setTextColor(ink)
        selectHintBg.setColor((ink and 0x00FFFFFF) or (TRANSLATE_NOTE_FILL shl 24))
    }

    private fun onShareTapped() {
        if (state != State.OPEN || shareBtn.visibility != View.VISIBLE) return
        host.tapHaptic(shareBtn)
        if (selection != null) {
            cancelSelect()
            return
        }
        // The line playing now; else (untimed, or before the first line) the first line on screen
        val start = if (adapter.synced && active >= 0) active
        else layoutManager.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 } ?: 0
        enterSelect(start)
    }

    private fun longPressAt(rawX: Float, rawY: Float) {
        if (state != State.OPEN || shareBtn.visibility != View.VISIBLE) return
        val pos = lineAt(rawX, rawY) ?: return
        val sel = selection
        if (sel != null && pos in sel.range) return
        overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        enterSelect(pos)
    }

    private fun lineAt(rawX: Float, rawY: Float): Int? {
        if (!list.isShown) return null
        val loc = IntArray(2)
        list.getLocationOnScreen(loc)
        val child = list.findChildViewUnder(rawX - loc[0], rawY - loc[1]) ?: return null
        return list.getChildAdapterPosition(child).takeIf { it != RecyclerView.NO_POSITION }
    }

    private fun enterSelect(index: Int) {
        val sel = com.minim.music.data.share.LyricSelection(adapter.lines.map { it.text })
        val start = sel.nearestSelectable(index)
        if (start < 0 || !sel.start(start)) return
        val fresh = selection == null
        selection = sel
        list.removeCallbacks(resumeFollow)
        if (fresh) {
            selectBar.visibility = View.VISIBLE
            aiBadge?.alpha = 0f
            if (host.animationsOn()) {
                selectBar.translationY = dp(12f)
                selectBar.scaleX = 0.96f
                selectBar.scaleY = 0.96f
                selectBar.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(SELECT_ANIM_MS).setInterpolator(enterEase).start()
            }
            showHint(R.string.lyrics_select_hint)
        }
        applySelection()
    }

    private fun selectTapAt(rawX: Float, rawY: Float) {
        val sel = selection ?: return
        if (state != State.OPEN) return
        val pos = lineAt(rawX, rawY)
        if (pos == null) {
            // a tap outside the lines cancels
            cancelSelect()
            return
        }
        when (sel.tap(pos)) {
            com.minim.music.data.share.LyricSelection.Result.ADDED,
            com.minim.music.data.share.LyricSelection.Result.REMOVED,
            com.minim.music.data.share.LyricSelection.Result.STARTED -> {
                host.tapHaptic(overlay)
                applySelection()
            }
            com.minim.music.data.share.LyricSelection.Result.LIMIT_LINES -> {
                host.edgeTick(overlay)
                showHint(R.string.lyrics_select_max_lines)
            }
            com.minim.music.data.share.LyricSelection.Result.LIMIT_CHARS -> {
                host.edgeTick(overlay)
                showHint(R.string.lyrics_select_max_chars)
            }
            com.minim.music.data.share.LyricSelection.Result.NOTHING -> Unit
        }
    }

    private fun applySelection() {
        val sel = selection
        adapter.selection = sel?.range
        selectCount.text = res.getString(
            R.string.lyrics_select_count, sel?.count ?: 0, com.minim.music.data.share.LyricSelection.MAX_LINES
        )
        restyleVisible(host.animationsOn())
    }

    /** Back, a tap outside the lines, the share icon again, or the lyrics going away. */
    fun cancelSelect(animate: Boolean = true) {
        if (selection == null) return
        selection = null
        adapter.selection = null
        list.removeCallbacks(hideHint)
        selectBar.animate().cancel()
        if (animate && host.animationsOn() && state == State.OPEN) {
            selectBar.animate().translationY(dp(12f)).alpha(0f).setDuration(SELECT_ANIM_MS / 2)
                .withEndAction {
                    if (selection == null) selectBar.visibility = View.GONE
                    selectBar.translationY = 0f
                    selectBar.alpha = FullscreenLyricsLook.linesAlpha(mix)
                }.start()
            fadeHint(false)
        } else {
            selectBar.visibility = View.GONE
            selectHint.animate().cancel()
            selectHint.visibility = View.GONE
            selectHint.alpha = 0f
        }
        if (state != State.CLOSED) {
            applyContentAlpha()
            restyleVisible(animate && host.animationsOn())
            if (adapter.synced && !userScrolling) scrollToActive(smooth = animate && host.animationsOn())
        }
    }

    private fun confirmSelect() {
        val sel = selection ?: return
        val data = lyrics ?: return
        if (state != State.OPEN || !sel.isActive) return
        host.tapHaptic(selectShare)
        val translations = if (adapter.transMode == FullscreenLyricsAdapter.TransMode.READY) {
            (sel.first..sel.last).map { adapter.translationAt(it) }
        } else null
        val share = LyricShare(data, sel.first, sel.last, translations)
        cancelSelect()
        host.shareLyrics(share)
    }

    private fun showHint(text: Int) {
        selectHint.setText(text)
        list.removeCallbacks(hideHint)
        fadeHint(true)
        list.postDelayed(hideHint, SELECT_HINT_MS)
    }

    private fun fadeHint(show: Boolean) {
        selectHint.animate().cancel()
        if (show) {
            selectHint.visibility = View.VISIBLE
            if (host.animationsOn()) selectHint.animate().alpha(1f).setDuration(SELECT_ANIM_MS).start() else selectHint.alpha = 1f
        } else if (selectHint.visibility == View.VISIBLE) {
            if (host.animationsOn()) {
                selectHint.animate().alpha(0f).setDuration(SELECT_ANIM_MS).withEndAction { selectHint.visibility = View.GONE }.start()
            } else {
                selectHint.alpha = 0f
                selectHint.visibility = View.GONE
            }
        }
    }

    private fun tapAt(rawX: Float, rawY: Float) {
        if (state != State.OPEN || !adapter.synced || !list.isShown) return
        val loc = IntArray(2)
        list.getLocationOnScreen(loc)
        val child = list.findChildViewUnder(rawX - loc[0], rawY - loc[1]) ?: return
        val pos = list.getChildAdapterPosition(child)
        if (pos == RecyclerView.NO_POSITION) return
        seekToLine(pos)
    }

    private fun seekToLine(pos: Int) {
        if (state != State.OPEN || !adapter.synced) return
        val line = adapter.lines.getOrNull(pos) ?: return
        // Follow the song again straight away, so the tapped line slides to the middle
        list.removeCallbacks(resumeFollow)
        userScrolling = false
        host.seekToLyric(line.startMs)
        scrollToActive(smooth = host.animationsOn())
    }

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
        for (c in 0 until list.childCount) {
            val row = list.getChildAt(c) as? LyricRow ?: continue
            row.line.setTextColor(i)
            row.translation.ink = i
        }
        message.setTextColor(i)
        ai.setInk(i)
        styleTranslateControls()
        pulse.imageTintList = ColorStateList.valueOf(i)
        updateBarColors()
    }

    // ------------------------------------------------------------------------------------------
    // 5.6.0: AI translation (English), one Gemini call per song, saved on the phone
    // ------------------------------------------------------------------------------------------

    /** New lyrics (or none): show the button only when they aren't English, and keep translating if it's on. */
    private fun onLyricsForTranslation(data: LyricsData?) {
        translateJob?.cancel()
        translateJob = null
        revealAnim?.cancel()
        fadeAnim?.cancel()
        keepCentred = false
        setTranslating(false)
        hideTranslateNote()
        langPlan = data?.let { d -> LyricLanguage.plan(d.lines.map { it.text }) }
        val show = langPlan?.showButton == true
        translateBtn.visibility = if (show) View.VISIBLE else View.GONE
        refreshShareButton()
        styleTranslateControls()
        if (show && translateOn && state != State.CLOSED) startTranslation(animate = state == State.OPEN)
    }

    private fun onTranslateTapped() {
        if (state != State.OPEN || translateBtn.visibility != View.VISIBLE) return
        host.tapHaptic(translateBtn)
        if (translateOn) {
            translateOn = false
            stopTranslation(animate = host.animationsOn())
        } else {
            translateOn = true
            startTranslation(animate = host.animationsOn())
        }
        styleTranslateControls()
    }

    private fun retryTranslation() {
        if (state != State.OPEN || translateNote.visibility != View.VISIBLE) return
        host.tapHaptic(translateNote)
        translateOn = true
        startTranslation(animate = host.animationsOn())
        styleTranslateControls()
    }

    /**
     * The phone's copy shows straight away (no Gemini call). Otherwise shimmer bars appear under
     * the non-English lines as the space opens, and Gemini's translation fades in over them.
     */
    private fun startTranslation(animate: Boolean) {
        val data = lyrics ?: return
        val plan = langPlan ?: return
        hideTranslateNote()
        translateJob?.cancel()
        translateJob = scope.launch {
            val saved = runCatching { host.savedTranslation(data) }.getOrNull()
            if (lyrics !== data || !translateOn) return@launch
            if (saved != null && saved.size == data.lines.size) {
                showTranslations(saved, animate)
                return@launch
            }
            val needs = BooleanArray(plan.kinds.size) { plan.kinds[it] == LyricLanguage.Kind.OTHER }
            adapter.setLoading(needs)
            rebindTranslations()
            setTranslating(true)
            openSpace(animate)
            val result = try {
                host.translateLyrics(data)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (lyrics !== data || !translateOn) return@launch
            setTranslating(false)
            if (result == null || result.size != data.lines.size) failTranslation(animate = host.animationsOn())
            else showTranslations(result, host.animationsOn())
        }
    }

    private fun showTranslations(result: List<String?>, animate: Boolean) {
        val delays = if (animate) staggerDelays() else null
        adapter.setReady(result, delays)
        rebindTranslations()
        if (adapter.reveal < 1f) openSpace(animate)
        if (delays != null) runFade(delays.maxOrNull() ?: 0L)
        else list.post { if (!userScrolling) scrollToActive(smooth = false) }
    }

    private fun failTranslation(animate: Boolean) {
        closeSpace(animate) { showTranslateNote() }
    }

    private fun stopTranslation(animate: Boolean) {
        translateJob?.cancel()
        translateJob = null
        fadeAnim?.cancel()
        setTranslating(false)
        hideTranslateNote()
        closeSpace(animate) {}
    }

    /** Closing the screen turns translations off; the saved copy makes them instant next time. */
    private fun resetTranslation() {
        translateOn = false
        translateJob?.cancel()
        translateJob = null
        revealAnim?.cancel()
        fadeAnim?.cancel()
        keepCentred = false
        setTranslating(false)
        hideTranslateNote()
        adapter.setTranslationsOff()
        adapter.reveal = 0f
        rebindTranslations()
        styleTranslateControls()
    }

    /** Top to bottom: each visible row starts its fade a little after the one above. */
    private fun staggerDelays(): LongArray {
        val n = adapter.itemCount
        val first = layoutManager.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: 0
        val last = layoutManager.findLastVisibleItemPosition().takeIf { it >= first } ?: (first + 12)
        return LongArray(n) { i -> (i - first).coerceIn(0, last - first + 1) * TRANSLATION_STAGGER_MS }
    }

    private fun runFade(lastDelay: Long) {
        fadeAnim?.cancel()
        val total = lastDelay + FullscreenLyricsAdapter.TRANSLATION_FADE_MS
        fadeAnim = ValueAnimator.ofFloat(0f, total.toFloat()).apply {
            duration = total
            interpolator = null
            addUpdateListener {
                adapter.fadeElapsedMs = (it.animatedValue as Float).toLong()
                keepCentred = true
                rebindTranslations()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    adapter.fadeElapsedMs = Long.MAX_VALUE
                    rebindTranslations()
                    if (fadeAnim === animation) fadeAnim = null
                    settleCentre()
                }
            })
            start()
        }
    }

    /** Opens the space under every line (translations or shimmer bars slide in). */
    private fun openSpace(animate: Boolean) = animateReveal(1f, animate, REVEAL_OPEN_MS) {}

    /** Closes it again; [then] runs once the lines sit together like before. */
    private fun closeSpace(animate: Boolean, then: () -> Unit) = animateReveal(0f, animate, REVEAL_CLOSE_MS) {
        adapter.setTranslationsOff()
        rebindTranslations()
        then()
    }

    private fun animateReveal(to: Float, animate: Boolean, ms: Long, end: () -> Unit) {
        revealAnim?.cancel()
        val from = adapter.reveal
        if (!animate || from == to || state != State.OPEN) {
            adapter.reveal = to
            rebindTranslations()
            end()
            list.post { if (!userScrolling) scrollToActive(smooth = false) }
            return
        }
        revealAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = enterEase
            addUpdateListener {
                adapter.reveal = it.animatedValue as Float
                keepCentred = true
                rebindTranslations()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (revealAnim === animation) revealAnim = null
                    if (cancelled) return
                    end()
                    settleCentre()
                }
            })
            start()
        }
    }

    /** After the space has moved: stop correcting every frame and glide to the exact middle. */
    private fun settleCentre() {
        if (revealAnim != null || fadeAnim != null) return
        keepCentred = false
        list.post { if (!userScrolling && state == State.OPEN) scrollToActive(smooth = host.animationsOn()) }
    }

    private fun rebindTranslations() {
        for (i in 0 until list.childCount) {
            val holder = list.getChildViewHolder(list.getChildAt(i)) as? FullscreenLyricsAdapter.Holder ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) adapter.bindTranslation(holder, pos)
        }
    }

    /**
     * Rows grow from the top of the list down, which would push the active line off the
     * middle: after each layout, move it back (only while following the song).
     */
    private fun keepActiveCentred() {
        if (!adapter.synced || userScrolling || state != State.OPEN) return
        if (list.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        val target = FullscreenLyricsLook.scrollTarget(active, adapter.itemCount)
        val w = layoutManager.findViewByPosition(target) ?: return
        val dy = FullscreenLyricsLook.centreDelta(w.top, w.bottom, list.paddingTop, list.height - list.paddingBottom)
        if (dy != 0) list.scrollBy(0, dy)
    }

    /** The button gently pulses while Gemini works. */
    private fun setTranslating(on: Boolean) {
        translatingAnim?.cancel()
        translatingAnim = null
        if (!on || !host.animationsOn()) {
            translateBtn.imageAlpha = 255
            if (on) translateBtn.announceForAccessibility(res.getString(R.string.fs_translating))
            return
        }
        translateBtn.announceForAccessibility(res.getString(R.string.fs_translating))
        translatingAnim = ValueAnimator.ofFloat(1f, TRANSLATING_MIN_ALPHA).apply {
            duration = TRANSLATING_PULSE_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { translateBtn.imageAlpha = ((it.animatedValue as Float) * 255).toInt() }
            start()
        }
    }

    private fun showTranslateNote() {
        styleTranslateControls()
        translateNote.visibility = View.VISIBLE
        translateNote.alpha = 0f
        translateNote.animate().alpha(FullscreenLyricsLook.linesAlpha(mix)).setDuration(220L).start()
    }

    private fun hideTranslateNote() {
        translateNote.animate().cancel()
        translateNote.visibility = View.GONE
    }

    private fun placeTranslateControls() {
        val top = insetTop + dp(TRANSLATE_TOP_DP).toInt()
        placeShareControls(top)
        (translateBtn.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.topMargin != top) {
                it.topMargin = top
                translateBtn.layoutParams = it
            }
        }
        val noteTop = top + dp(TRANSLATE_NOTE_SHIFT_DP).toInt()
        (translateNote.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.topMargin != noteTop) {
                it.topMargin = noteTop
                translateNote.layoutParams = it
            }
        }
        ai.place(insetTop, insetBottom, TRANSLATE_TOP_DP, dp(BAR_BOTTOM_DP + BAR_HEIGHT_DP + AI_BADGE_GAP_DP).toInt())
        val pillTop = top + dp(CAPTION_PILL_SHIFT_DP).toInt()
        (captionPill.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.topMargin != pillTop) {
                it.topMargin = pillTop
                captionPill.layoutParams = it
            }
        }
    }

    /** Ink colours; a soft filled circle behind the icon while translations are on. */
    private fun styleTranslateControls() {
        translateBtn.imageTintList = ColorStateList.valueOf(ink)
        translateOnBg.setColor((ink and 0x00FFFFFF) or (TRANSLATE_ON_FILL shl 24))
        val bg = if (translateOn) translateOnBg else null
        if (translateBtn.background !== bg) translateBtn.background = bg
        val desc = res.getString(if (translateOn) R.string.fs_translate_hide else R.string.fs_translate_show)
        if (translateBtn.contentDescription != desc) translateBtn.contentDescription = desc
        styleSelectControls()
        translateNote.setTextColor(ink)
        translateNoteBg.setColor((ink and 0x00FFFFFF) or (TRANSLATE_NOTE_FILL shl 24))
        if (translateNote.background !== translateNoteBg) translateNote.background = translateNoteBg
        styleCaptionPill()
    }

    companion object {
        const val REVEAL_OPEN_MS = 460L
        const val REVEAL_CLOSE_MS = 360L
        const val TRANSLATION_STAGGER_MS = 60L
        const val TRANSLATING_PULSE_MS = 700L
        const val TRANSLATING_MIN_ALPHA = 0.4f
        const val TRANSLATE_TOP_DP = 6f
        /** 5.8.1: the AI line's gap above the progress bar. */
        const val AI_BADGE_GAP_DP = 2f
        const val TRANSLATE_NOTE_SHIFT_DP = 6f
        const val CAPTION_PILL_SHIFT_DP = 8f
        const val TRANSLATE_ON_FILL = 0x2E
        const val TRANSLATE_NOTE_FILL = 0x24
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
        /** 6.6.0: select mode's bar sits this far above the progress bar, the hint above it. */
        const val SELECT_BAR_GAP_DP = 6f
        const val SELECT_HINT_GAP_DP = 52f
        const val SELECT_ANIM_MS = 220L
        const val SELECT_HINT_MS = 2600L
        const val BAR_HEIGHT_DP = 20f
        const val BAR_GAP_DP = 18f
        const val LINE_HALF_DP = 30f
        const val MESSAGE_ALPHA = 0.6f
        const val PULSE_ALPHA = 0.9f
        const val BAR_REST_ALPHA = 0x4D
    }
}
