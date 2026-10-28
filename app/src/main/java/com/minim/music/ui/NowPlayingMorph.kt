package com.minim.music.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.core.view.drawToBitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 5.5.0: the mini player turns into Now Playing instead of being replaced by it, and back.
 *
 * - The black pill is Now Playing's own background while it morphs ([MorphBackground]): a
 *   rounded rect that stretches from the pill to the whole screen, losing its round ends, its
 *   black fading into the album colour, over a dim of the screen underneath.
 * - The real cover, title, artist and heart of Now Playing are moved with translation and scale
 *   (nothing is re-laid out) from where the pill shows them to their own places; the cover's
 *   corners go from the small round cover's to the big card's.
 * - Everything else on Now Playing fades in a beat late, one group after another.
 *
 * [progress] 0 = the pill, 1 = Now Playing. The finger drives it ([drag]); [release] springs to
 * an end; [animateTo] plays it (tap, the back pill, Back). [onSettled] runs once it rests at an
 * end, after every view is back to normal.
 */
class NowPlayingMorph(
    private val screen: ViewGroup,
    private val art: ImageView,
    private val title: TextView,
    private val artist: TextView,
    private val heart: ImageView,
    private val header: List<View>,
    private val lyric: View,
    private val controls: List<View>,
    private val pills: View,
    private val cardRadius: Float,
    /** Puts Now Playing's own colours back on the title, artist, heart and background. */
    private val restoreColors: () -> Unit,
    /** Now Playing's background colour right now (it can change with the song). */
    private val npBackground: () -> Int,
    private val onLock: () -> Unit
) {
    /** Where the morph starts (opening) or lands (closing): one of the two mini players. */
    class Source(
        val root: View,
        val art: View,
        val title: TextView,
        val artist: TextView,
        val heart: ImageView?
    )

    private class Geo(
        val pill: RectF,
        val pillFill: Int,
        val art: RectF,
        val title: RectF,
        val titleCenterY: Float,
        val titleSize: Float,
        val titleColor: Int,
        val artist: RectF,
        val artistCenterY: Float,
        val artistSize: Float,
        val artistColor: Int,
        val heart: RectF?,
        val heartTint: Int,
        val snapshot: Bitmap?
    )

    private class Target(
        val full: RectF,
        val art: RectF,
        val title: RectF,
        val titlePivotX: Float,
        val titleCenterY: Float,
        val titleColor: Int,
        val artist: RectF,
        val artistPivotX: Float,
        val artistCenterY: Float,
        val artistColor: Int,
        val heart: RectF,
        val heartTint: Int
    )

    private val density = screen.resources.displayMetrics.density
    private var source: Source? = null
    private var geo: Geo? = null
    private var target: Target? = null
    private var anim: ValueAnimator? = null
    private val savedAlpha = HashMap<View, Float>()
    private var sourceHeartAlpha = 1f
    private var waitingForLayout = false
    private val bg = MorphBackground()
    private var coverRadiusNow = cardRadius
    private val coverOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, coverRadiusNow)
        }
    }

    /** 0 = the pill, 1 = Now Playing (1 when no morph is going on). */
    var progress = 1f
        private set

    /** A morph is on screen (dragging, settling or playing). */
    val isActive get() = source != null

    /** A release or a tap is playing it on its own. */
    val isRunning get() = anim != null

    var onSettled: ((open: Boolean) -> Unit)? = null

    /**
     * Starts a morph between [src] and Now Playing at [startP] (0 when opening from the pill, 1
     * when closing). If one is already going on it is caught where it is instead.
     */
    fun begin(src: Source, startP: Float) {
        if (source != null) {
            grab()
            return
        }
        source = src
        progress = startP.coerceIn(0f, 1f)
        geo = null
        target = null
        savedAlpha.clear()
        for (v in fading()) savedAlpha[v] = v.alpha
        sourceHeartAlpha = src.heart?.alpha ?: 1f
        // Rest colours first, so the targets are Now Playing's real colours
        restoreColors()
        captureSource()
        art.outlineProvider = coverOutline
        render()
    }

    /** Stops a running spring where it is, so the finger can take over. */
    fun grab(): Float {
        val a = anim
        anim = null
        a?.cancel()
        return progress
    }

    fun drag(p: Float) {
        if (source == null) return
        anim?.let { anim = null; it.cancel() }
        progress = p.coerceIn(0f, 1f)
        render()
    }

    /** How far the finger travels for the whole morph: as far as the cover moves. */
    fun travelPx(): Float {
        val g = geo
        val t = target
        val fallback = screen.rootView.height * 0.55f
        if (g == null || t == null) return max(fallback, 240f * density)
        return max(abs(g.art.centerY() - t.art.centerY()), 200f * density)
    }

    /**
     * The finger let go with vertical speed [velocityDpPerS] (negative = up). Springs open or
     * closed, a tick once it locks.
     */
    fun release(velocityDpPerS: Float, startedOpen: Boolean) {
        if (source == null) return
        val open = MorphMotion.settlesOpen(progress, velocityDpPerS, startedOpen)
        val to = if (open) 1f else 0f
        // Speed toward the target, in progress per second
        val travelDp = travelPx() / density
        val speed = if (travelDp > 0f) (-velocityDpPerS / travelDp) * (if (open) 1f else -1f) else 0f
        val ms = MorphMotion.settleMs(progress, to, speed, if (open) MorphMotion.OPEN_MS else MorphMotion.CLOSE_MS)
        run(to, ms, DecelerateInterpolator(1.6f), tick = true)
    }

    /** Plays the morph to [to] on its own (a tap, the back pill, Back). */
    fun animateTo(to: Float) {
        if (source == null) return
        val full = if (to >= 1f) MorphMotion.OPEN_MS else MorphMotion.CLOSE_MS
        val ms = (full * abs(to - progress)).toLong().coerceAtLeast(MorphMotion.SETTLE_MIN_MS)
        run(to, ms, PathInterpolator(0.2f, 0f, 0f, 1f), tick = false)
    }

    /** Drops the morph at once and puts every view back (no [onSettled]). */
    fun abort() {
        if (source == null) return
        grab()
        reset()
    }

    private fun run(to: Float, ms: Long, interp: TimeInterpolator, tick: Boolean) {
        grab()
        val from = progress
        if (from == to || ms <= 0L) {
            settle(to, tick && MorphMotion.locked(-1f, to))
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
        reset()
        if (tick) onLock()
        onSettled?.invoke(to >= 1f)
    }

    private fun reset() {
        val src = source
        source = null
        waitingForLayout = false
        for (v in listOf(art, title, artist, heart)) {
            v.translationX = 0f
            v.translationY = 0f
            v.scaleX = 1f
            v.scaleY = 1f
            v.resetPivot()
        }
        for ((v, a) in savedAlpha) v.alpha = a
        savedAlpha.clear()
        art.outlineProvider = ViewOutlineProvider.BACKGROUND
        art.invalidateOutline()
        screen.alpha = 1f
        geo?.snapshot?.recycle()
        geo = null
        target = null
        bg.snapshot = null
        restoreColors()
        src?.root?.alpha = 1f
        src?.heart?.alpha = sourceHeartAlpha
        progress = 1f
    }

    private fun fading(): List<View> = header + lyric + controls + pills + heart

    // ---- geometry ----------------------------------------------------------------------------

    private val a = IntArray(2)
    private val b = IntArray(2)

    /** [v]'s rect in [screen]'s coordinates (from the window, for views outside Now Playing). */
    private fun windowRect(v: View): RectF {
        v.getLocationInWindow(a)
        screen.getLocationInWindow(b)
        val l = (a[0] - b[0]).toFloat()
        val t = (a[1] - b[1]).toFloat()
        return RectF(l, t, l + v.width * v.scaleX, t + v.height * v.scaleY)
    }

    /** [v]'s laid-out rect inside Now Playing, ignoring the morph's own transforms. */
    private fun layoutRect(v: View): RectF {
        val r = Rect(0, 0, v.width, v.height)
        screen.offsetDescendantRectToMyCoords(v, r)
        return RectF(r)
    }

    /** Where the words of [tv] sit inside [box] (its view rect), from its gravity. */
    private fun textRect(tv: TextView, box: RectF): RectF {
        val avail = (tv.width - tv.totalPaddingLeft - tv.totalPaddingRight).toFloat().coerceAtLeast(0f)
        val w = min(tv.paint.measureText(tv.text?.toString() ?: ""), avail)
        val g = Gravity.getAbsoluteGravity(tv.gravity, tv.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK
        val off = when (g) {
            Gravity.CENTER_HORIZONTAL -> (avail - w) / 2f
            Gravity.RIGHT -> avail - w
            else -> 0f
        }
        val l = box.left + tv.totalPaddingLeft + off
        return RectF(l, box.top, l + w, box.bottom)
    }

    private fun ready(v: View) = v.isLaidOut && !v.isLayoutRequested && v.width > 0 && v.height > 0

    private fun captureSource() {
        val src = source ?: return
        if (!ready(src.root)) return
        val titleBox = windowRect(src.title)
        val artistBox = windowRect(src.artist)
        // The pill's own buttons, without what flies away (the cover, words and heart)
        val hidden = listOfNotNull(src.art, src.title, src.artist, src.heart?.takeIf { isInside(it, src.root) })
        val before = hidden.map { it.alpha }
        hidden.forEach { it.alpha = 0f }
        val shot = runCatching { src.root.drawToBitmap() }.getOrNull()
        hidden.forEachIndexed { i, v -> v.alpha = before[i] }
        geo = Geo(
            pill = windowRect(src.root),
            pillFill = src.root.backgroundTintList?.defaultColor ?: Color.BLACK,
            art = windowRect(src.art),
            title = textRect(src.title, titleBox),
            titleCenterY = titleBox.centerY(),
            titleSize = src.title.textSize,
            titleColor = src.title.currentTextColor,
            artist = textRect(src.artist, artistBox),
            artistCenterY = artistBox.centerY(),
            artistSize = src.artist.textSize,
            artistColor = src.artist.currentTextColor,
            heart = src.heart?.takeIf { it.isShown && it.width > 0 }?.let { windowRect(it) },
            heartTint = src.heart?.imageTintList?.defaultColor ?: Color.WHITE,
            snapshot = shot
        )
        bg.snapshot = shot
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    private fun captureTarget() {
        if (screen.visibility != View.VISIBLE || !ready(screen) || !ready(art) || !ready(title)) return
        val titleBox = layoutRect(title)
        val artistBox = layoutRect(artist)
        val titleText = textRect(title, titleBox)
        val artistText = textRect(artist, artistBox)
        target = Target(
            full = RectF(0f, 0f, screen.width.toFloat(), screen.height.toFloat()),
            art = layoutRect(art),
            title = titleText,
            titlePivotX = titleText.left - titleBox.left,
            titleCenterY = titleBox.centerY(),
            titleColor = title.currentTextColor,
            artist = artistText,
            artistPivotX = artistText.left - artistBox.left,
            artistCenterY = artistBox.centerY(),
            artistColor = artist.currentTextColor,
            heart = layoutRect(heart),
            heartTint = heart.imageTintList?.defaultColor ?: Color.WHITE
        )
    }

    // ---- drawing -----------------------------------------------------------------------------

    private fun render() {
        val src = source ?: return
        if (geo == null) captureSource()
        if (target == null) captureTarget()
        val g = geo
        val t = target
        if (g == null || t == null) {
            // Not laid out yet: opening keeps showing the pill for that frame, closing keeps
            // showing Now Playing (the mini player it lands on stays hidden)
            if (progress < 0.5f) screen.alpha = 0f else src.root.alpha = 0f
            if (!waitingForLayout) {
                waitingForLayout = true
                val wait = if (g == null) src.root else screen
                wait.doOnPreDraw {
                    waitingForLayout = false
                    if (source === src) render()
                }
                wait.invalidate()
            }
            return
        }
        screen.alpha = 1f
        src.root.alpha = 0f
        src.heart?.alpha = 0f
        val p = progress

        // The pill stretching into the screen, over the dim
        val c = MorphMotion.lerpRect(g.pill.arr(), t.full.arr(), p)
        bg.rect.set(c[0], c[1], c[2], c[3])
        bg.radius = MorphMotion.corner(p, g.pill.height() / 2f)
        bg.color = MorphMotion.color(p).let { lerpColor(g.pillFill, npBackground(), it) }
        bg.dim = MorphMotion.dim(p)
        bg.snapshotRect.set(g.pill)
        bg.snapshotAlpha = MorphMotion.pillButtons(p)
        if (screen.background !== bg) screen.background = bg
        bg.invalidateSelf()

        // The cover
        place(art, g.art, t.art, p)
        val coverScale = MorphMotion.lerp(g.art.width(), t.art.width(), p) / t.art.width()
        coverRadiusNow = MorphMotion.radiusForScale(
            MorphMotion.coverRadius(p, g.art.width() / 2f, cardRadius), coverScale
        )
        art.invalidateOutline()

        // Title and artist slide (and grow) from the pill to their big places
        placeText(title, g.title.left, g.titleCenterY, g.titleSize, t.title.left, t.titleCenterY, t.titlePivotX, p)
        placeText(artist, g.artist.left, g.artistCenterY, g.artistSize, t.artist.left, t.artistCenterY, t.artistPivotX, p)
        val k = MorphMotion.color(p)
        title.setTextColor(lerpColor(g.titleColor, t.titleColor, k))
        artist.setTextColor(lerpColor(g.artistColor, t.artistColor, k))

        // The heart travels with the rest (it is the same heart, so it stays in sync)
        val hs = g.heart
        if (hs != null) {
            place(heart, hs, t.heart, p)
            heart.alpha = savedAlpha[heart] ?: 1f
            heart.imageTintList = ColorStateList.valueOf(lerpColor(g.heartTint, t.heartTint, k))
        } else {
            heart.alpha = (savedAlpha[heart] ?: 1f) * MorphMotion.controls(p)
        }

        // The rest fades in a beat late, one group after another
        for (v in header) v.alpha = (savedAlpha[v] ?: 1f) * MorphMotion.header(p)
        lyric.alpha = (savedAlpha[lyric] ?: 1f) * MorphMotion.lyric(p)
        for (v in controls) v.alpha = (savedAlpha[v] ?: 1f) * MorphMotion.controls(p)
        pills.alpha = (savedAlpha[pills] ?: 1f) * MorphMotion.pills(p)
    }

    /** Moves and scales [v] (laid out at [to]) so it shows at the rect between [from] and [to]. */
    private fun place(v: View, from: RectF, to: RectF, p: Float) {
        if (to.width() <= 0f || to.height() <= 0f) return
        v.pivotX = 0f
        v.pivotY = 0f
        val w = MorphMotion.lerp(from.width(), to.width(), p)
        val h = MorphMotion.lerp(from.height(), to.height(), p)
        v.scaleX = w / to.width()
        v.scaleY = h / to.height()
        v.translationX = MorphMotion.lerp(from.left, to.left, p) - to.left
        v.translationY = MorphMotion.lerp(from.top, to.top, p) - to.top
    }

    private fun placeText(
        v: TextView, fromLeft: Float, fromCy: Float, fromSize: Float,
        toLeft: Float, toCy: Float, pivotX: Float, p: Float
    ) {
        val start = if (v.textSize > 0f) fromSize / v.textSize else 1f
        val s = MorphMotion.lerp(start, 1f, p)
        v.pivotX = pivotX
        v.pivotY = v.height / 2f
        v.scaleX = s
        v.scaleY = s
        v.translationX = MorphMotion.lerp(fromLeft, toLeft, p) - toLeft
        v.translationY = MorphMotion.lerp(fromCy, toCy, p) - toCy
    }

    private fun RectF.arr() = floatArrayOf(left, top, right, bottom)

    private fun lerpColor(x: Int, y: Int, t: Float): Int = SearchMotion.lerpColor(x, y, t)

    /** Now Playing's background while it morphs: the dim, the stretching pill, the pill's buttons. */
    private class MorphBackground : Drawable() {
        val rect = RectF()
        var radius = 0f
        var color = Color.BLACK
        var dim = 0f
        var snapshot: Bitmap? = null
        val snapshotRect = RectF()
        var snapshotAlpha = 0f
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun draw(canvas: Canvas) {
            if (dim > 0f) canvas.drawColor(Color.argb((dim * 255).toInt().coerceIn(0, 255), 0, 0, 0))
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
