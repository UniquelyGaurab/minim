package com.minim.music.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.core.view.doOnPreDraw
import androidx.core.view.drawToBitmap
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 5.5.1: the "Up Next" pill on Now Playing turns into the Up Next sheet, and back.
 *
 * - The sheet's own background is a rounded rect ([SheetBackground]) that stretches from the
 *   pill to the whole screen, its colour going from the pill's fill to the sheet's background;
 *   the screen is clipped to that same rect, so its contents only show inside it.
 * - The title row and the songs ride the top edge of the sheet (translation only, nothing is
 *   re-laid out); opening, the songs fade in one after another.
 * - Now Playing underneath dims as the sheet opens and lightens as it's dragged down.
 *
 * [progress] 0 = the pill, 1 = the sheet. [onSettled] runs when it rests at an end: closed, it
 * runs before the views are put back, so the caller can hide the sheet first (no flash).
 */
class QueueMorph(
    private val screen: ViewGroup,
    private val header: View,
    private val content: View,
    private val list: RecyclerView,
    /** Now Playing, dimmed under the sheet. */
    private val behind: View,
    /** (pill fill, sheet background) right now. */
    private val colors: () -> Pair<Int, Int>,
    private val onLock: () -> Unit
) {
    private val density = screen.resources.displayMetrics.density
    private var source: View? = null
    private var anim: ValueAnimator? = null
    private var pillRect: RectF? = null
    private var installed = false
    private var savedBackground: Drawable? = null
    private val bg = SheetBackground()
    private val dimDrawable = ColorDrawable(Color.BLACK)
    private val rowAnims = ArrayList<View>()
    private val clip = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val r = bg.rect
            if (r.isEmpty) outline.setRect(0, 0, 0, 0)
            else outline.setRoundRect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), bg.radius)
        }
    }

    /** 0 = the pill, 1 = the sheet (1 when no morph is going on). */
    var progress = 1f
        private set

    /** A morph is on screen (opening, being dragged, settling or closing). */
    val isActive get() = installed

    /** The pill the sheet came from, while it can still be landed on. */
    val canCloseIntoPill get() = source?.let { it.isShown && it.width > 0 && it.height > 0 } == true

    var onSettled: ((open: Boolean) -> Unit)? = null

    /**
     * Opens from [pill] (the screen must already be VISIBLE). False when the pill isn't on
     * screen: the caller just shows the sheet, as before.
     */
    fun open(pill: View): Boolean {
        if (!pill.isShown || pill.width == 0 || pill.height == 0) return false
        abort()
        source = pill
        install(0f)
        list.scrollToPosition(0)
        animateTo(1f)
        // The songs fade in one after another once the list has laid out its rows
        list.doOnPreDraw { if (installed && progress < 1f) cascade() }
        return true
    }

    /** Starts closing from the open sheet (drag or Back). False when there's no pill to land on. */
    fun beginClose(): Boolean {
        if (installed) {
            grab()
            return true
        }
        if (!canCloseIntoPill) return false
        install(1f)
        return true
    }

    /** Stops a running spring where it is, so the finger can take over. */
    fun grab(): Float {
        val a = anim
        anim = null
        a?.cancel()
        return progress
    }

    fun drag(p: Float) {
        if (!installed) return
        anim?.let { anim = null; it.cancel() }
        progress = p.coerceIn(0f, 1f)
        render()
    }

    /** How far the finger travels for the whole morph: as far as the sheet's top edge moves. */
    fun travelPx(): Float {
        val r = pillRect ?: return max(screen.height * 0.7f, 240f * density)
        return max(r.top, 200f * density)
    }

    /** The finger let go with speed [velocityDpPerS] (positive = down). */
    fun release(velocityDpPerS: Float) {
        if (!installed) return
        val open = QueueMotion.settlesOpen(progress, velocityDpPerS)
        val to = if (open) 1f else 0f
        val travelDp = travelPx() / density
        val speed = if (travelDp > 0f) (velocityDpPerS / travelDp) * (if (open) -1f else 1f) else 0f
        val ms = MorphMotion.settleMs(progress, to, speed, if (open) QueueMotion.OPEN_MS else QueueMotion.CLOSE_MS)
        run(to, ms, DecelerateInterpolator(1.6f), tick = true)
    }

    /** Plays the morph to [to] on its own (the tap, Back, the close button). */
    fun animateTo(to: Float) {
        if (!installed) return
        val full = if (to >= 1f) QueueMotion.OPEN_MS else QueueMotion.CLOSE_MS
        val ms = (full * abs(to - progress)).toLong().coerceAtLeast(MorphMotion.SETTLE_MIN_MS)
        run(to, ms, PathInterpolator(0.2f, 0f, 0f, 1f), tick = false)
    }

    /** Drops the morph at once and puts every view back (no [onSettled]). */
    fun abort() {
        grab()
        if (installed) reset()
    }

    private fun run(to: Float, ms: Long, interp: TimeInterpolator, tick: Boolean) {
        grab()
        val from = progress
        if (from == to || ms <= 0L) {
            settle(to, tick)
            return
        }
        val a = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = interp
            addUpdateListener {
                progress = it.animatedValue as Float
                render()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || anim !== animation) return
                    anim = null
                    settle(to, tick)
                }
            })
        }
        anim = a
        a.start()
    }

    private fun settle(to: Float, tick: Boolean) {
        progress = to
        if (to >= 1f) {
            // The songs still fading in finish on their own
            reset(endRows = false)
            if (tick) onLock()
            onSettled?.invoke(true)
        } else {
            if (tick) onLock()
            // Hidden first, then put back, so the full sheet never flashes for a frame
            onSettled?.invoke(false)
            reset()
        }
    }

    private fun install(p: Float) {
        progress = p
        installed = true
        pillRect = null
        bg.snapshot = null
        savedBackground = screen.background
        screen.background = bg
        screen.outlineProvider = clip
        screen.clipToOutline = true
        behind.foreground = dimDrawable
        render()
    }

    private fun reset(endRows: Boolean = true) {
        installed = false
        if (endRows) endCascade() else rowAnims.clear()
        screen.clipToOutline = false
        screen.outlineProvider = ViewOutlineProvider.BACKGROUND
        screen.background = savedBackground
        savedBackground = null
        screen.setBackgroundColor(colors().second)
        behind.foreground = null
        for (v in listOf(header, content)) {
            v.alpha = 1f
            v.translationY = 0f
        }
        source?.alpha = 1f
        bg.snapshot?.recycle()
        bg.snapshot = null
        bg.rect.setEmpty()
        pillRect = null
        progress = 1f
    }

    // ---- geometry ----------------------------------------------------------------------------

    private val a = IntArray(2)
    private val b = IntArray(2)

    private fun ready(v: View) = v.isLaidOut && !v.isLayoutRequested && v.width > 0 && v.height > 0

    private fun capture(): Boolean {
        val pill = source ?: return false
        if (!ready(screen) || !pill.isShown || pill.width == 0) return false
        pill.getLocationInWindow(a)
        screen.getLocationInWindow(b)
        val l = (a[0] - b[0]).toFloat()
        val t = (a[1] - b[1]).toFloat()
        pillRect = RectF(l, t, l + pill.width * pill.scaleX, t + pill.height * pill.scaleY)
        pill.alpha = 1f
        bg.snapshot = runCatching { pill.drawToBitmap() }.getOrNull()
        bg.pillFill = pill.backgroundTintList?.defaultColor ?: colors().first
        pill.alpha = 0f
        return true
    }

    private fun render() {
        if (!installed) return
        // Something (a palette change) put a plain colour back: the sheet keeps drawing itself
        if (screen.background !== bg) {
            savedBackground = screen.background
            screen.background = bg
        }
        if (pillRect == null && !capture()) {
            // Not laid out yet: show nothing of the sheet for that frame
            bg.rect.setEmpty()
            header.alpha = 0f
            content.alpha = 0f
            bg.invalidateSelf()
            screen.invalidateOutline()
            if (!waitingForLayout) {
                waitingForLayout = true
                screen.doOnPreDraw {
                    waitingForLayout = false
                    if (installed) render()
                }
            }
            return
        }
        val pill = pillRect ?: return
        val p = progress
        val full = RectF(0f, 0f, screen.width.toFloat(), screen.height.toFloat())
        val r = MorphMotion.lerpRect(floatArrayOf(pill.left, pill.top, pill.right, pill.bottom), floatArrayOf(full.left, full.top, full.right, full.bottom), p)
        bg.rect.set(r[0], r[1], r[2], r[3])
        bg.radius = QueueMotion.corner(p, pill.height() / 2f, QueueMotion.SHEET_CORNER_DP * density)
        bg.color = SearchMotion.lerpColor(bg.pillFill, colors().second, QueueMotion.color(p))
        bg.snapshotAlpha = QueueMotion.pillLabel(p)
        bg.snapshotRect.set(
            bg.rect.centerX() - pill.width() / 2f, pill.top + (bg.rect.top - pill.top) * 0.35f,
            bg.rect.centerX() + pill.width() / 2f, pill.top + (bg.rect.top - pill.top) * 0.35f + pill.height()
        )
        bg.invalidateSelf()
        screen.invalidateOutline()
        dimDrawable.alpha = (255 * QueueMotion.dim(p)).toInt().coerceIn(0, 255)
        // The title row and the songs ride the sheet's top edge
        val ride = bg.rect.top - full.top
        header.translationY = ride
        content.translationY = ride
        header.alpha = QueueMotion.header(p)
        content.alpha = if (rowAnims.isNotEmpty()) 1f else QueueMotion.content(p)
    }

    private var waitingForLayout = false

    /** The songs start hidden, then fade up one after another. */
    private fun cascade() {
        endCascade()
        val shift = 12f * density
        content.alpha = 1f
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            rowAnims.add(v)
            v.alpha = 0f
            v.translationY = shift
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(max(0L, QueueMotion.listDelay(i) - (progress * QueueMotion.OPEN_MS).toLong()))
                .setDuration(QueueMotion.LIST_ITEM_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { rowAnims.remove(v) }
                .start()
        }
    }

    private fun endCascade() {
        for (v in rowAnims.toList()) {
            v.animate().cancel()
            v.alpha = 1f
            v.translationY = 0f
        }
        rowAnims.clear()
    }

    /** The sheet while it morphs: the stretching rounded rect and the pill's fading label. */
    private class SheetBackground : Drawable() {
        val rect = RectF()
        var radius = 0f
        var color = Color.BLACK
        var pillFill = Color.BLACK
        var snapshot: Bitmap? = null
        val snapshotRect = RectF()
        var snapshotAlpha = 0f
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun draw(canvas: Canvas) {
            if (rect.isEmpty) return
            fill.color = color
            if (radius > 0.5f) canvas.drawRoundRect(rect, radius, radius, fill) else canvas.drawRect(rect, fill)
            val s = snapshot
            if (s != null && !s.isRecycled && snapshotAlpha > 0f) {
                bmp.alpha = (255 * snapshotAlpha).toInt().coerceIn(0, 255)
                canvas.drawBitmap(s, null, snapshotRect, bmp)
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
