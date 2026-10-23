package com.minim.music.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * v4.8: the panel of a Minim bottom sheet.
 *
 * - Never taller than [maxHeightFraction] of its parent; the inner ScrollView takes the rest.
 * - Drag it down to dismiss. A downward drag is only taken over when the list inside is
 *   already at its top, so scrolling a long playlist list still works.
 */
class SheetPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    interface Listener {
        /** 0 = fully open, 1 = dragged all the way down. */
        fun onDrag(fraction: Float)
        fun onDragDismiss()
        fun onDragCancel()
    }

    var listener: Listener? = null
    var maxHeightFraction = 0.88f
    /** The scrollable child; a drag only starts when it can't scroll up any more. */
    var scrollChild: View? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The parent hands us its own height as the limit; keep a strip of the page visible above.
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val parentH = (parent as? View)?.height ?: 0
        val base = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) parentH else size
        val spec = if (base > 0) {
            MeasureSpec.makeMeasureSpec((base * maxHeightFraction).toInt(), MeasureSpec.AT_MOST)
        } else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, spec)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dy = ev.rawY - downY
                val dx = ev.rawX - downX
                val atTop = scrollChild?.canScrollVertically(-1) != true
                if (dy > touchSlop && dy > abs(dx) * 1.2f && atTop) {
                    dragging = true
                    downY = ev.rawY // start the drag from here so it doesn't jump
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                return true // the panel itself swallows taps so they don't reach the scrim
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                if (!dragging && dy > touchSlop && dy > abs(ev.rawX - downX)) {
                    dragging = true
                    downY = ev.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    val t = max(0f, ev.rawY - downY)
                    translationY = t
                    listener?.onDrag(if (height > 0) min(1f, t / height) else 0f)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    val far = height > 0 && translationY > height * 0.3f
                    val flung = vy > max(minFling * 4f, 900f)
                    if (ev.actionMasked == MotionEvent.ACTION_UP && (far || flung)) listener?.onDragDismiss()
                    else listener?.onDragCancel()
                }
                dragging = false
                velocity?.recycle()
                velocity = null
                return true
            }
        }
        return true
    }
}
