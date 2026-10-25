package com.minim.music.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 5.2.1: the Gemini chat's full-screen layer. In 5.2.0 it could be left on screen, invisible,
 * after the chat closed, and then it swallowed every tap: the mini player, the tabs and
 * Settings all stopped working. Now the layer only takes touches while the chat is open
 * ([acceptsTouches]); the rest of the time every touch goes straight through to the app
 * underneath, even mid-way through the closing animation.
 */
class GeminiRootLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var acceptsTouches: () -> Boolean = { true }

    /** Told when a touch reached the layer while it shouldn't be up (it then tidies itself away). */
    var onStrayTouch: (() -> Unit)? = null

    /** Told about every tap (a touch that didn't move), in screen coordinates. */
    var onTap: ((rawX: Float, rawY: Float) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!acceptsTouches()) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) onStrayTouch?.invoke()
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                moved = false
            }
            MotionEvent.ACTION_MOVE -> if (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop) moved = true
            MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke(ev.rawX, ev.rawY)
        }
        return super.dispatchTouchEvent(ev)
    }
}
