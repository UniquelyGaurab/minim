package com.minim.music.ui

import android.animation.ValueAnimator
import android.content.Context
import android.text.Layout
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.TextViewCompat
import com.minim.music.R
import com.minim.music.data.model.LyricsData
import com.minim.music.data.model.LyricsLine

/**
 * v4.9.9 part 1: the lyric line above the title on Now Playing.
 *
 * - **Flow:** the current line lights up word by word, exactly like the full screen lyrics (the same
 *   [LyricLineView], [FlowTiming] and [FlowClock]). Per frame while the slot can be seen and the
 *   song plays; frozen when paused; a seek resyncs at once; no frames when Now Playing is closed,
 *   covered by the full screen lyrics or the app is in the background. Follows the Lyrics style
 *   setting ([flowStyle]); untimed lyrics leave the slot empty as before.
 * - **Narrower lines:** centred, at most [NowPlayingLyricSlot.LINE_MAX_DP] wide and at least
 *   [NowPlayingLyricSlot.SIDE_MIN_DP] from the edges, with balanced line breaks. The slot's own
 *   height is fixed in the layout (68 dp), so nothing below it moves.
 * - **Line change:** two line views take turns: the old line slides up and fades out while the new
 *   one slides up into place and fades in. A seek or a song change fades in place instead.
 *   With Remove animations on, every change is instant.
 *
 * MainActivity keeps the tap (full screen lyrics) and TalkBack; the children are hidden from TalkBack.
 */
class NowPlayingLyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** One line view: [faint] for "Lyrics not available" (smaller, see-through). */
    private class SlotLine(context: Context) : LyricLineView(context) {
        var faint: Boolean = false
            private set

        fun setFaint(value: Boolean) {
            if (faint == value && sizedOnce) return
            faint = value
            sizedOnce = true
            if (value) {
                val sp = NowPlayingLyricSlot.UNAVAILABLE_TEXT_SP
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, sp, sp, 1, TypedValue.COMPLEX_UNIT_SP)
            } else {
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, MIN_SP, MAX_SP, 1, TypedValue.COMPLEX_UNIT_SP)
            }
        }

        private var sizedOnce = false
        val restAlpha: Float get() = if (faint) NowPlayingLyricSlot.UNAVAILABLE_ALPHA else 1f
    }

    /** What a line view shows. [generation] tells the same index of two songs apart. */
    private sealed class Content {
        object Empty : Content()
        data class Message(val text: CharSequence) : Content()
        data class Line(val index: Int, val generation: Int) : Content()
    }

    private var front: SlotLine
    private var back: SlotLine
    private var frontContent: Content = Content.Empty

    /** Android's "Remove animations" (animator duration scale 0): every change is instant. */
    var animationsOn: () -> Boolean = { ValueAnimator.areAnimatorsEnabled() }

    private var lines: List<LyricsLine> = emptyList()
    private var synced = false
    private var durations = LongArray(0)
    private var generation = 0
    /** Index of the line on show, -1 before the first one. */
    private var active = -1

    /** The Lyrics style setting: Flow (word by word) or Line. Untimed lyrics ignore it. */
    var flowStyle: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            front.flowOn = value && synced
            back.flowOn = value && synced
            if (flowRunning() && canShowMotion()) drawFlow(System.nanoTime())
            updateFrameLoop()
        }

    private val clock = FlowClock()
    private var hostVisible = true
    private var covered = false
    private var shownOnScreen = false
    private var frameQueued = false
    private var settleUntilNs = 0L
    private val ease = DecelerateInterpolator(1.5f)

    private val frameCallback = Choreographer.FrameCallback { nanos ->
        frameQueued = false
        if (!wantFrames()) return@FrameCallback
        drawFlow(nanos)
        updateFrameLoop()
    }

    init {
        clipToPadding = false
        front = makeLine()
        back = makeLine()
        addView(front)
        addView(back)
        back.alpha = 0f
    }

    @android.annotation.SuppressLint("WrongConstant") // Layout.BREAK_STRATEGY_* = LineBreaker's values
    private fun makeLine(): SlotLine = SlotLine(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        // The palette colours the text (Painter), like every other "text:fg" view
        tag = "text:fg"
        ResourcesCompat.getFont(context, R.font.poppins_semibold)?.let { typeface = it }
        includeFontPadding = false
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        // Balanced wrapping: a long line breaks into two even halves, not one long + one word
        breakStrategy = Layout.BREAK_STRATEGY_BALANCED
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, MAX_SP.toFloat())
        setFaint(false)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > 0) {
            val d = resources.displayMetrics.density
            val side = NowPlayingLyricSlot.sidePadding(
                w, (NowPlayingLyricSlot.SIDE_MIN_DP * d).toInt(), (NowPlayingLyricSlot.LINE_MAX_DP * d).toInt()
            )
            if (paddingLeft != side || paddingRight != side) setPadding(side, paddingTop, side, paddingBottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    // ------------------------------------------------------------------------------------------
    // Fed by MainActivity
    // ------------------------------------------------------------------------------------------

    /**
     * A new song's lyrics (or none yet). [unavailable] is the "Lyrics not available" text shown
     * for a song without lyrics. Whatever was on show fades out in place.
     */
    fun setLyrics(data: LyricsData?, unavailable: CharSequence, animate: Boolean) {
        val mode = NowPlayingLyricSlot.mode(data)
        generation++
        lines = if (mode == NowPlayingLyricSlot.Mode.LINES) data!!.lines else emptyList()
        synced = mode == NowPlayingLyricSlot.Mode.LINES && data!!.isSynced && lines.isNotEmpty()
        durations = if (synced) FlowTiming.lineDurations(lines) else LongArray(lines.size)
        active = -1
        clock.reset()
        settleUntilNs = 0L
        val content = if (mode == NowPlayingLyricSlot.Mode.NONE) Content.Message(unavailable) else Content.Empty
        // Always re-shown: the line views must drop the old song's line (and its Flow timing)
        show(content, NowPlayingLyricSlot.change(jump = true, animate = animate, canAnimate = canShowMotion()), force = true)
        updateFrameLoop()
    }

    /**
     * Every player tick (~4 a second) and on play / pause: [index] is the line that has started at
     * [shiftedMs] (the song position with the Lyrics timing offset applied).
     */
    fun onPosition(index: Int, shiftedMs: Long, playing: Boolean, animate: Boolean) {
        val now = System.nanoTime()
        val jump = clock.sample(shiftedMs, playing, now)
        if (!synced) return
        when {
            // A seek (or the first position of a song): straight to the right line, in place
            jump -> applyActive(index, NowPlayingLyricSlot.change(jump = true, animate = animate, canAnimate = canShowMotion()))
            index != active && !NowPlayingLyricSlot.ignoreTick(index, active, flowRunning(), clock.playing) ->
                applyActive(index, NowPlayingLyricSlot.change(jump = false, animate = animate, canAnimate = canShowMotion()))
        }
        if (flowRunning()) {
            if (canDraw()) drawFlow(now)
            // Paused: keep drawing a moment so a line still fading in gets its final look
            if (!playing) settleUntilNs = maxOf(settleUntilNs, now + SETTLE_NS)
            updateFrameLoop()
        }
    }

    /** The full screen lyrics cover Now Playing completely: no frames, no animations. */
    fun setCovered(value: Boolean) {
        if (covered == value) return
        covered = value
        onMotionInputsChanged()
    }

    /** The app went to the background (or came back): no frames while nobody can see them. */
    fun setHostVisible(visible: Boolean) {
        if (hostVisible == visible) return
        hostVisible = visible
        onMotionInputsChanged()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (shownOnScreen == isVisible) return
        shownOnScreen = isVisible
        onMotionInputsChanged()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (frameQueued) {
            frameQueued = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Lines
    // ------------------------------------------------------------------------------------------

    private fun applyActive(index: Int, change: NowPlayingLyricSlot.Change) {
        if (index == active) return
        active = index
        val content = if (index in lines.indices && lines[index].text.isNotBlank()) {
            Content.Line(index, generation)
        } else {
            Content.Empty
        }
        show(content, change)
        if (flowRunning()) {
            settleUntilNs = System.nanoTime() + SETTLE_NS
            updateFrameLoop()
        }
    }

    private fun bind(v: SlotLine, content: Content) {
        when (content) {
            is Content.Empty -> {
                v.setFaint(false)
                v.setActiveLine(false)
                v.flowOn = false
                v.bindLine("", 0L, 1L)
            }
            is Content.Message -> {
                v.setFaint(true)
                v.setActiveLine(false)
                v.flowOn = false
                v.bindLine(content.text, 0L, 1L)
            }
            is Content.Line -> {
                val l = lines[content.index]
                val end = if (synced) l.startMs + durations[content.index] else l.endMs
                v.setFaint(false)
                v.setActiveLine(false)
                v.flowOn = flowStyle && synced
                v.bindLine(l.text.trim(), l.startMs, end, l)
                v.setActiveLine(true)
            }
        }
    }

    /**
     * Puts [content] on show. SLIDE: the old line slides up and out, the new one slides up into
     * place. SWAP: both fade in place. A change while one is still running carries on from where
     * the old line is, so nothing jumps.
     */
    private fun show(content: Content, change: NowPlayingLyricSlot.Change, force: Boolean = false) {
        if (!force && content == frontContent) return
        frontContent = content
        front.animate().cancel()
        back.animate().cancel()
        if (change == NowPlayingLyricSlot.Change.INSTANT) {
            bind(front, content)
            front.alpha = front.restAlpha
            front.translationY = 0f
            back.alpha = 0f
            back.translationY = 0f
            back.setActiveLine(false)
            return
        }
        // The views take turns: the one on show goes out, the other one brings the new line in
        val out = front
        val inn = back
        front = inn
        back = out
        out.setActiveLine(false)
        bind(inn, content)
        val slide = NowPlayingLyricSlot.SLIDE_DP * resources.displayMetrics.density
        inn.alpha = 0f
        if (change == NowPlayingLyricSlot.Change.SLIDE) {
            inn.translationY = slide
            inn.animate().alpha(inn.restAlpha).translationY(0f)
                .setStartDelay(0L).setDuration(NowPlayingLyricSlot.SLIDE_IN_MS).setInterpolator(ease).start()
            out.animate().alpha(0f).translationY(out.translationY - slide)
                .setStartDelay(0L).setDuration(NowPlayingLyricSlot.SLIDE_OUT_MS).setInterpolator(ease)
                .withEndAction { out.translationY = 0f }.start()
        } else {
            inn.translationY = 0f
            inn.animate().alpha(inn.restAlpha).translationY(0f)
                .setStartDelay(0L).setDuration(NowPlayingLyricSlot.SWAP_MS).setInterpolator(ease).start()
            out.animate().alpha(0f)
                .setStartDelay(0L).setDuration(NowPlayingLyricSlot.SWAP_MS).setInterpolator(ease)
                .withEndAction { out.translationY = 0f }.start()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Flow: the per frame word highlight
    // ------------------------------------------------------------------------------------------

    /** Flow is what the slot shows: timed lyrics with the Flow style. */
    private fun flowRunning(): Boolean = flowStyle && synced

    /** Someone can see the slot move: on screen, not covered, app in the foreground, animations on. */
    private fun canShowMotion(): Boolean =
        isAttachedToWindow && shownOnScreen && hostVisible && !covered && animationsOn()

    /** Visible enough for Flow frames (with Remove animations on, words still light up). */
    private fun canDraw(): Boolean = isAttachedToWindow && shownOnScreen && hostVisible && !covered

    private fun wantFrames(): Boolean =
        flowRunning() && canDraw() && clock.hasSample &&
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

    private fun onMotionInputsChanged() {
        // Back on screen: bring the words up to date at once, before the first frame
        if (flowRunning() && canDraw() && clock.hasSample) drawFlow(System.nanoTime())
        updateFrameLoop()
    }

    /** One Flow frame: move to the next line when it's due, then light the words up to now. */
    private fun drawFlow(nowNs: Long) {
        if (!clock.hasSample) return
        val pos = clock.positionAt(nowNs)
        var index = active
        while (index + 1 < lines.size && lines[index + 1].startMs <= pos) index++
        if (index != active) {
            applyActive(index, NowPlayingLyricSlot.change(jump = false, animate = true, canAnimate = canShowMotion()))
        }
        if (frontContent is Content.Line) front.onLinePosition(pos)
    }

    private companion object {
        const val MIN_SP = 18
        const val MAX_SP = 22
        const val SETTLE_NS = NowPlayingLyricSlot.SETTLE_MS * 1_000_000L
    }
}
