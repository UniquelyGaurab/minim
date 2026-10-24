package com.minim.music.ui

import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.minim.music.R

/**
 * v4.9.9 part 3: rows rising into place when a list gets a new set of rows (a search result, a
 * playlist opening, Discover loading). The rows on screen slide up [RowEntranceMath.SHIFT_DP] and
 * fade in, a little apart, and only the first [RowEntranceMath.MAX_STAGGERED] are staggered so it's
 * over in under half a second. One animator drives every row on screen by its place after the
 * first visible one, so a rebind or a row scrolled in mid entrance just takes its place in it.
 *
 * It plays only when asked ([play], or [expect] then [loaded]), never on scrolling back to rows or
 * on a row's data changing, and not at all with Remove animations on. It moves only the rows'
 * translationY and alpha, never their colours, so the palette fade keeps working underneath.
 */
class RowEntrance(private val list: RecyclerView) {

    private val shiftPx = RowEntranceMath.SHIFT_DP * list.resources.displayMetrics.density
    private var armed = false
    private var armedAt = 0L
    private var expected = false
    private var listening = false
    private var animator: ValueAnimator? = null
    private var firstPosition = 0

    private val preDraw = ViewTreeObserver.OnPreDrawListener { onPreDraw(); true }

    init {
        list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val a = animator ?: return
                applyAt(view, a.currentPlayTime)
            }

            override fun onChildViewDetachedFromWindow(view: View) = restore(view)
        })
        list.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) = stop()
        })
    }

    /** The next non-empty set of rows is a new one: [loaded] plays the entrance for it. */
    fun expect() {
        expected = true
    }

    /** Call after handing the adapter its rows; plays when a new set was expected and there are rows. */
    fun loaded(count: Int) {
        if (!expected) return
        expected = false
        if (count > 0) play()
    }

    /** Plays the entrance for the rows the list lays out next (the ones just handed to it). */
    fun play() {
        stop()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        armed = true
        armedAt = SystemClock.uptimeMillis()
        if (!listening) {
            list.viewTreeObserver.addOnPreDrawListener(preDraw)
            listening = true
        }
        list.invalidate()
    }

    /** Ends any entrance at once, every row in place. */
    fun stop() {
        armed = false
        val a = animator
        animator = null
        a?.cancel()
        restoreAll()
    }

    private fun onPreDraw() {
        if (!armed) {
            stopListening()
            return
        }
        // A list that isn't on screen yet (a tab still hidden) gets a moment to show up; a load
        // nobody saw just shows its rows in place when it does
        if (!list.isShown || list.childCount == 0) {
            if (SystemClock.uptimeMillis() - armedAt > ARM_TIMEOUT_MS) {
                armed = false
                stopListening()
            }
            return
        }
        armed = false
        stopListening()
        start()
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        val vto = list.viewTreeObserver
        if (vto.isAlive) vto.removeOnPreDrawListener(preDraw)
    }

    private fun start() {
        var first = Int.MAX_VALUE
        for (i in 0 until list.childCount) {
            val p = list.getChildLayoutPosition(list.getChildAt(i))
            if (p != RecyclerView.NO_POSITION && p < first) first = p
        }
        firstPosition = if (first == Int.MAX_VALUE) 0 else first
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RowEntranceMath.TOTAL_MS
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                if (animator !== anim) return@addUpdateListener
                val elapsed = anim.currentPlayTime
                for (i in 0 until list.childCount) applyAt(list.getChildAt(i), elapsed)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator !== animation) return
                    animator = null
                    restoreAll()
                }
            })
        }
        animator = a
        // The first drawn frame already has every row lowered and hidden, so nothing flashes
        for (i in 0 until list.childCount) applyAt(list.getChildAt(i), 0L)
        a.start()
    }

    private fun applyAt(child: View, elapsed: Long) {
        val p = list.getChildLayoutPosition(child)
        val rank = if (p == RecyclerView.NO_POSITION) 0 else (p - firstPosition).coerceAtLeast(0)
        val e = RowEntranceMath.progress(elapsed, rank)
        child.setTag(R.id.tag_entrance, true)
        child.alpha = e
        child.translationY = (1f - e) * shiftPx
    }

    private fun restoreAll() {
        for (i in 0 until list.childCount) restore(list.getChildAt(i))
    }

    private fun restore(view: View) {
        if (view.getTag(R.id.tag_entrance) != true) return
        view.setTag(R.id.tag_entrance, null)
        view.alpha = 1f
        view.translationY = 0f
    }

    companion object {
        private const val ARM_TIMEOUT_MS = 1000L

        /**
         * The same entrance for rows that aren't in a RecyclerView (the Stats sheets): each view
         * rises and fades in, staggered by its place in [views].
         */
        fun playViews(views: List<View>) {
            if (views.isEmpty() || !ValueAnimator.areAnimatorsEnabled()) return
            val shift = RowEntranceMath.SHIFT_DP * views[0].resources.displayMetrics.density
            val ease = DecelerateInterpolator(1.5f)
            views.forEachIndexed { i, v ->
                v.animate().cancel()
                v.alpha = 0f
                v.translationY = shift
                v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(RowEntranceMath.delayFor(i))
                    .setDuration(RowEntranceMath.ITEM_MS)
                    .setInterpolator(ease)
                    .withEndAction { v.alpha = 1f; v.translationY = 0f }
                    .start()
            }
        }
    }
}

/**
 * v4.9.9 part 3: the press on every tappable pill and circle button. A StateListAnimator on the
 * pressed state scales the view to [PressScaleMath.PRESSED_SCALE] and back, so it never touches
 * the view's click, long press, touch listener or haptics, and a cancelled press (a scroll, a drag
 * off the pill) springs back the same way. Animators follow Android's animation scale, so with
 * Remove animations on it's instant.
 */
object PressScale {

    /** Gives [view] the press scale (once; calling it again does nothing). */
    fun apply(view: View) {
        if (view.getTag(R.id.tag_press_scale) == true) return
        view.setTag(R.id.tag_press_scale, true)
        val pressed = intArrayOf(android.R.attr.state_pressed)
        view.stateListAnimator = StateListAnimator().apply {
            addState(pressed, scaleTo(view, PressScaleMath.PRESSED_SCALE, PressScaleMath.DOWN_MS))
            addState(IntArray(0), scaleTo(view, 1f, PressScaleMath.UP_MS))
        }
    }

    /**
     * Gives the press scale to every tappable pill and circle under [root]: clickable views whose
     * background is a pill (999dp corners) or a circle, and the Settings option pills. Text fields
     * with a pill outline (search, your name) are left alone.
     */
    fun applyTree(root: View) {
        if (isPressable(root)) apply(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) applyTree(root.getChildAt(i))
    }

    private fun isPressable(v: View): Boolean {
        if (!v.isClickable || v is EditText) return false
        if (v.tag == "opt" && v is TextView) return true
        val bg = v.background as? GradientDrawable ?: return false
        return when (bg.shape) {
            GradientDrawable.OVAL -> true
            GradientDrawable.RECTANGLE -> PressScaleMath.isPillCorner(bg.cornerRadius, v.resources.displayMetrics.density)
            else -> false
        }
    }

    private fun scaleTo(view: View, scale: Float, ms: Long): android.animation.Animator =
        android.animation.AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, scale),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, scale)
            )
            duration = ms
            interpolator = DecelerateInterpolator()
        }
}

/**
 * v4.9.9 part 3: Up next's moves and removals. The stock animator with its timings shortened to
 * the app's 150-300 ms and an ease-out curve instead of its ease-in-out: moved rows glide to
 * their new place, removed rows fade out and the rest close the gap. Changes (the highlight
 * moving to the next song, an arrow turning on or off) redraw in place with no crossfade.
 */
class QueueItemAnimator : DefaultItemAnimator() {

    private val ease = DecelerateInterpolator(1.5f)

    init {
        supportsChangeAnimations = false
        moveDuration = 240L
        removeDuration = 180L
        addDuration = 200L
        changeDuration = 0L
    }

    // The stock animator resets each row's curve to ease-in-out when it records the animation;
    // the row's ViewPropertyAnimator keeps whatever curve it was given last, so set ours after
    override fun animateMove(holder: RecyclerView.ViewHolder, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
        val r = super.animateMove(holder, fromX, fromY, toX, toY)
        holder.itemView.animate().interpolator = ease
        return r
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        val r = super.animateRemove(holder)
        holder.itemView.animate().interpolator = ease
        return r
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val r = super.animateAdd(holder)
        holder.itemView.animate().interpolator = ease
        return r
    }
}

/**
 * v4.9.9 part 3: a Stats number that can count up from zero. [countUp] starts the count from zero
 * to the value it shows (or arms it for the first value when it has none yet); [set] after that
 * changes the number directly, or glides from where a running count is.
 */
class CountingText(
    private val view: TextView,
    private val format: (from: Long, to: Long, t: Float) -> String
) {
    private var target: Long? = null
    private var armed = false
    private var animator: ValueAnimator? = null
    private var shown = 0L

    fun set(value: Long) {
        val running = animator
        when {
            armed -> {
                armed = false
                target = value
                animate(0L, value, CountUpMath.DURATION_MS)
            }
            running != null -> {
                if (value == target) return
                target = value
                animate(shown, value, CountUpMath.RETARGET_MS)
            }
            else -> {
                target = value
                show(value)
            }
        }
    }

    /** The Stats tab just opened: count up to the current value, or to the first one that arrives. */
    fun countUp() {
        val value = target
        if (value == null) {
            armed = ValueAnimator.areAnimatorsEnabled()
            return
        }
        animate(0L, value, CountUpMath.DURATION_MS)
    }

    private fun animate(start: Long, end: Long, ms: Long) {
        val old = animator
        animator = null
        old?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || start == end) {
            show(end)
            return
        }
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                if (animator !== anim) return@addUpdateListener
                val t = anim.animatedFraction
                shown = CountUpMath.valueAt(start, end, t)
                view.text = format(start, end, t)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator !== animation) return
                    animator = null
                    show(end)
                }
            })
        }
        animator = a
        view.text = format(start, end, 0f)
        a.start()
    }

    private fun show(value: Long) {
        shown = value
        view.text = format(value, value, 1f)
    }
}
