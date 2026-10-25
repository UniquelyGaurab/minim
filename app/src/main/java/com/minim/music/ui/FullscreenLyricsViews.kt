package com.minim.music.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.minim.music.data.model.LyricsLine
import kotlin.math.abs
import kotlin.math.max

/**
 * v4.9.8: the full screen lyrics' root. It watches every touch for a double tap (play/pause)
 * without taking the touches away from the lyric list underneath, so scrolling still works.
 * Touches that start on [excludeView] (the progress bar) never count toward a double tap.
 * 5.1.0: a single tap (one that didn't become a double tap) goes to [onSingleTap], unless
 * [tapBlocked] said no when the finger went down (the list was still scrolling: that tap only
 * stops the fling).
 */
class FullscreenLyricsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onDoubleTap: (() -> Unit)? = null
    var onSingleTap: ((MotionEvent) -> Unit)? = null
    var tapBlocked: (() -> Boolean)? = null
    var excludeView: View? = null

    private var tracking = false
    private var blockedTap = false

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!blockedTap) onSingleTap?.invoke(e)
            return true
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            blockedTap = tapBlocked?.invoke() == true
            tracking = !isOnExcluded(ev)
            if (!tracking) {
                // Forget any half-finished double tap, so a tap on the bar can't complete one
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                detector.onTouchEvent(cancel)
                cancel.recycle()
            }
        }
        if (tracking) detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun isOnExcluded(ev: MotionEvent): Boolean {
        val v = excludeView ?: return false
        if (v.visibility != View.VISIBLE) return false
        val slop = 12f * resources.displayMetrics.density
        val l = v.left + v.translationX
        val t = v.top + v.translationY
        return ev.x >= l - slop && ev.x <= l + v.width + slop && ev.y >= t - slop && ev.y <= t + v.height + slop
    }
}

/**
 * v4.9.8 part 4: one word's brightness in a Flow line. A colour span that only scales the text
 * colour's alpha, read every time the line draws: changing [level] and invalidating redraws the
 * line with no new text, no new layout and no change to the line breaks.
 */
class FlowWordSpan : CharacterStyle(), UpdateAppearance {
    var level: Float = 1f
    override fun updateDrawState(tp: TextPaint) {
        if (level < 1f) tp.alpha = (tp.alpha * level + 0.5f).toInt().coerceIn(0, 255)
    }
}

/**
 * v4.9.8: one lyric line in the full screen mode.
 *
 * Line style: the whole line lights up at once; the adapter sets its opacity and scale from how
 * far it is from the active line.
 *
 * Flow style (part 4, [flowOn]): while this is the active line, its words light up in turn.
 * [bindLine] splits the text into words ([FlowTiming.splitWords]) and spreads the line's time
 * ([lineStartMs]..[lineEndMs]) over them by length; every frame [onLinePosition] gets the song
 * position (Lyrics timing offset applied) and sets each word's [FlowWordSpan] level. Only the
 * spans' alpha changes, so a frame costs one redraw of this line and nothing else.
 */
open class LyricLineView(context: Context) : AppCompatTextView(context) {

    var lineStartMs: Long = 0L
        private set
    var lineEndMs: Long = 0L
        private set
    var isActiveLine: Boolean = false
        private set
    /** Flow style on (and the lyrics are timed). Set by the adapter. */
    var flowOn: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value || !isActiveLine) setAllLevels(1f)
        }

    private var spans: Array<FlowWordSpan> = emptyArray()
    private var bounds: LongArray = LongArray(1)

    fun bindLine(text: CharSequence, startMs: Long, endMs: Long, source: LyricsLine? = null) {
        lineStartMs = startMs
        lineEndMs = endMs
        val words = FlowTiming.splitWords(text)
        if (words.isEmpty()) {
            spans = emptyArray()
            bounds = LongArray(1)
            this.text = text
        } else {
            val duration = max(1L, endMs - startMs)
            bounds = source?.let { FlowTiming.realWordBounds(it, words, duration) }
                ?: FlowTiming.wordBounds(IntArray(words.size) { words[it].weight }, duration)
            val sp = SpannableString(text)
            spans = Array(words.size) { FlowWordSpan() }
            for (i in words.indices) {
                sp.setSpan(spans[i], words[i].start, words[i].end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // NORMAL: TextView keeps a SpannedString (a static layout) holding these same span objects
            setText(sp, BufferType.NORMAL)
        }
        onLineBound()
    }

    fun setActiveLine(active: Boolean) {
        if (active == isActiveLine) return
        isActiveLine = active
        // A line that stops being sung shows whole; a new Flow line waits for its first position
        if (!active || !flowOn) setAllLevels(1f)
        onActiveChanged(active)
    }

    /** Called after a new line was bound to this view (it may be a recycled view). */
    protected open fun onLineBound() {}

    /** Called when this line becomes the active one, or stops being it. */
    protected open fun onActiveChanged(active: Boolean) {}

    /**
     * Song position for the active line, offset applied: every frame while Flow runs, else on
     * each player tick. Line style: nothing to do per word.
     */
    open fun onLinePosition(positionMs: Long) {
        if (!flowOn || !isActiveLine || spans.isEmpty()) return
        val t = positionMs - lineStartMs
        val dim = FlowTiming.dimFor(alpha)
        var changed = false
        for (i in spans.indices) {
            val level = dim + (1f - dim) * FlowTiming.wordFill(bounds, i, t)
            if (abs(level - spans[i].level) > LEVEL_EPSILON) {
                spans[i].level = level
                changed = true
            }
        }
        if (changed) invalidate()
    }

    private fun setAllLevels(level: Float) {
        var changed = false
        for (s in spans) {
            if (s.level != level) {
                s.level = level
                changed = true
            }
        }
        if (changed) invalidate()
    }

    private companion object {
        /** Below one step of 8 bit alpha: not worth a redraw. */
        const val LEVEL_EPSILON = 0.002f
    }
}

/**
 * v4.9.8: the lines of the full screen lyrics. Synced lyrics: the active line at full opacity,
 * the rest dimmed by distance. Untimed lyrics: every line the same, nothing highlighted.
 */
class FullscreenLyricsAdapter(
    private val typeface: Typeface?
) : RecyclerView.Adapter<FullscreenLyricsAdapter.Holder>() {

    class Holder(val line: LyricLineView) : RecyclerView.ViewHolder(line)

    /** 5.1.0: TalkBack's "play from this line" on a synced line (a finger tap comes in through the root). */
    var onLineAction: ((Int) -> Unit)? = null
    var lineActionLabel: String = ""

    var lines: List<LyricsLine> = emptyList()
        private set
    var synced: Boolean = false
        private set
    /** Index of the active line, -1 before the first one. */
    var active: Int = -1
    /** Text colour (white or dark, from the cover). */
    var ink: Int = FullscreenLyricsLook.WHITE_INK
    /** v4.9.8 part 4: the Lyrics style setting, Flow (word by word) or Line. Untimed lyrics ignore it. */
    var flow: Boolean = true
    /** Each line's Flow time ([FlowTiming.lineDurations]), worked out once per song. */
    private var flowDurations: LongArray = LongArray(0)

    private val ease = DecelerateInterpolator(1.6f)

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(lines: List<LyricsLine>, synced: Boolean) {
        this.lines = lines
        this.synced = synced
        active = -1
        flowDurations = if (synced) FlowTiming.lineDurations(lines) else LongArray(lines.size)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = lines.size

    @android.annotation.SuppressLint("WrongConstant") // Layout.BREAK_STRATEGY_* = LineBreaker's (API 29+) values
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val dm = parent.resources.displayMetrics
        val v = LyricLineView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_HORIZONTAL
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            this@FullscreenLyricsAdapter.typeface?.let { typeface = it }
            includeFontPadding = false
            setLineSpacing(0f, 1.12f)
            // Balanced wrapping: a long line breaks into two even halves, not one long + one word
            breakStrategy = Layout.BREAK_STRATEGY_BALANCED
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            val pad = (LINE_PAD_DP * dm.density).toInt()
            setPadding(0, pad, 0, pad)
        }
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val l = lines[position]
        val end = if (synced) l.startMs + flowDurations[position] else l.endMs
        holder.line.bindLine(l.text.trim(), l.startMs, end, l)
        style(holder.line, position, animate = false)
        if (synced && onLineAction != null) {
            androidx.core.view.ViewCompat.replaceAccessibilityAction(
                holder.line,
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                lineActionLabel
            ) { v, _ ->
                (v.parent as? RecyclerView)?.getChildAdapterPosition(v)
                    ?.takeIf { it != RecyclerView.NO_POSITION }
                    ?.let { onLineAction?.invoke(it) }
                true
            }
        } else {
            androidx.core.view.ViewCompat.removeAccessibilityAction(
                holder.line,
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK.id
            )
        }
    }

    /** A view coming back from RecyclerView's cache isn't re-bound: bring its look up to date. */
    override fun onViewAttachedToWindow(holder: Holder) {
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) style(holder.line, pos, animate = false)
    }

    fun style(v: LyricLineView, position: Int, animate: Boolean) {
        val d = FullscreenLyricsLook.distance(position, active)
        val a = FullscreenLyricsLook.lineAlpha(d, synced)
        val s = FullscreenLyricsLook.lineScale(d, synced)
        v.setTextColor(ink)
        v.flowOn = flow && synced
        v.setActiveLine(synced && d == 0)
        v.animate().cancel()
        if (animate) {
            v.animate().alpha(a).scaleX(s).scaleY(s).setDuration(STYLE_MS).setInterpolator(ease).start()
        } else {
            v.alpha = a
            v.scaleX = s
            v.scaleY = s
        }
    }

    companion object {
        /** The old lyrics screen used 20 sp and the Now Playing line 18 to 22 sp. */
        const val TEXT_SP = 27f
        const val LINE_PAD_DP = 11f
        const val STYLE_MS = 420L
    }
}
