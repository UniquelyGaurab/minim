package com.minim.music.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 6.8.0: the playlist header sits over the list and moves with it. A drag that starts on the
 * header (on the title or a pill) still scrolls the list: once it's clearly vertical the header
 * hands the gesture to [target], so the page scrolls wherever you put your finger. Taps go to
 * the header's own buttons as before. A drag on empty header space never reaches here: the
 * header doesn't take it, so it falls through to the list underneath.
 */
class ScrollThroughHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** The list under the header, sharing its top-left corner. */
    var target: View? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var forwarding = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downTime = ev.downTime
                forwarding = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (target != null && dy > slop && dy > dx) {
                    forwarding = true
                    // the list starts the gesture where the finger first went down
                    val down = MotionEvent.obtain(downTime, ev.eventTime, MotionEvent.ACTION_DOWN, downX, downY, 0)
                    send(down)
                    down.recycle()
                    return true
                }
            }
        }
        return false
    }

    // taps still go through super (and so performClick); only a vertical drag is forwarded, and that's a scroll
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!forwarding) return super.onTouchEvent(ev)
        send(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) forwarding = false
        return true
    }

    /** [ev] is in this header's coordinates; the list sits [translationY] higher up the screen's top. */
    private fun send(ev: MotionEvent) {
        val t = target ?: return
        val copy = MotionEvent.obtain(ev)
        copy.offsetLocation(left - t.left + translationX - t.translationX, top - t.top + translationY - t.translationY)
        t.dispatchTouchEvent(copy)
        copy.recycle()
    }
}
