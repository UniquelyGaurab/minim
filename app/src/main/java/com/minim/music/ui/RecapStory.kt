package com.minim.music.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.minim.music.R
import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.StatsFormat
import com.minim.music.ui.RecapSlides.Kind
import com.minim.music.util.ArtworkPipeline
import kotlin.math.abs

/**
 * v4.9.1: the recap story, a mini Wrapped. Full-screen slides with Instagram-style progress bars:
 * tap right / left to go forward / back, press and hold to pause, swipe down (or Back) to close,
 * a soft haptic tick on each tap. The date and day sit small at the top of every slide. Colours
 * come from the day's top song cover; slides without data are left out. Last: the 9:16 card with
 * Share and Save.
 */
@SuppressLint("ViewConstructor")
class RecapStory(
    context: Context,
    private val stats: DayStats,
    private val pal: Pal,
    private val artwork: ArtworkPipeline,
    private val artistPhotoUrl: String?,
    private val roast: Pair<String, String>?,
    startAtCard: Boolean,
    private val tick: (View) -> Unit,
    private val onBars: (lightBars: Boolean) -> Unit,
    private val onShare: (Bitmap) -> Unit,
    private val onSave: (Bitmap) -> Unit,
    private val onClosed: () -> Unit
) : FrameLayout(context) {


    private class Look(val bg: Int, val fg: Int, val muted: Int)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    private val bold: Typeface = font(R.font.poppins_bold, Typeface.DEFAULT_BOLD)
    private val semi: Typeface = font(R.font.poppins_semibold, Typeface.DEFAULT_BOLD)
    private val regular: Typeface = font(R.font.poppins_regular, Typeface.DEFAULT)

    private val slides: List<Kind> = RecapSlides.plan(stats, roast != null)

    private val looks: List<Look> = run {
        val soft = ColorUtils.blendARGB(pal.bg, pal.fg, 0.12f)
        val a = Look(pal.bg, pal.fg, pal.muted)
        val b = Look(pal.fg, pal.bg, ColorUtils.blendARGB(pal.fg, pal.bg, 0.6f))
        val c = Look(soft, pal.fg, pal.muted)
        slides.mapIndexed { i, k -> if (k == Kind.CARD) a else listOf(a, b, c)[i % 3] }
    }

    private val slideHost = FrameLayout(context)
    private val progress = ProgressBars(context, slides.size)
    private val dateLabel = TextView(context)
    private var index = 0
    private var animator: ValueAnimator? = null
    private var held = false
    private var closing = false
    private var cardBitmap: Bitmap? = null
    private var cardImage: ImageView? = null
    private var cardPills: View? = null
    private var currentLook: Look? = null
    private val noteView = TextView(context)
    private val noteHide = Runnable {
        noteView.animate().alpha(0f).setDuration(220).withEndAction { noteView.visibility = View.GONE }.start()
    }

    // touch
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val holdRunnable = Runnable {
        held = true
        animator?.pause()
        progress.animate().alpha(0.35f).setDuration(150).start()
        dateLabel.animate().alpha(0.35f).setDuration(150).start()
    }

    init {
        isClickable = true
        isFocusable = true
        elevation = 40f * density
        addView(slideHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, dp(3f)).apply {
            leftMargin = dp(14f); rightMargin = dp(14f)
        })
        dateLabel.apply {
            typeface = semi
            textSize = 12f
            gravity = Gravity.CENTER
            text = Days.label(stats.day)
        }
        addView(dateLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        noteView.apply {
            typeface = semi
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(18f), dp(9f), dp(18f), dp(9f))
            visibility = View.GONE
            elevation = dp(8f).toFloat()
        }
        addView(noteView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        })
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (progress.layoutParams as LayoutParams).topMargin = bars.top + dp(10f)
            (dateLabel.layoutParams as LayoutParams).topMargin = bars.top + dp(22f)
            slideHost.setPadding(0, bars.top + dp(48f), 0, bars.bottom + dp(16f))
            (noteView.layoutParams as LayoutParams).bottomMargin = bars.bottom + dp(84f)
            progress.requestLayout()
            insets
        }
        index = if (startAtCard) slides.size - 1 else 0
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
        translationY = height.toFloat().coerceAtLeast(dp(80f).toFloat())
        alpha = 0f
        animate().translationY(0f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        show(index, forward = true)
    }

    /** The rendered 9:16 card (made in the background, so it can arrive after the story opens). */
    fun setCard(bmp: Bitmap) {
        cardBitmap = bmp
        cardImage?.setImageBitmap(bmp)
        cardPills?.alpha = 1f
    }

    /** A small pill near the bottom ("Saved to Pictures/Minim"), in the slide's own colours. */
    fun note(text: String) {
        if (closing) return
        val look = currentLook ?: return
        removeCallbacks(noteHide)
        noteView.animate().cancel()
        noteView.text = text
        noteView.setTextColor(look.bg)
        noteView.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(20f).toFloat()
            setColor(look.fg)
        }
        noteView.visibility = View.VISIBLE
        noteView.alpha = 0f
        noteView.translationY = dp(8f).toFloat()
        noteView.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        postDelayed(noteHide, 1900L)
    }

    fun close() {
        if (closing) return
        closing = true
        animator?.cancel()
        removeCallbacks(holdRunnable)
        removeCallbacks(noteHide)
        animate().translationY(height.toFloat()).alpha(0f).setDuration(220)
            .withEndAction {
                (parent as? ViewGroup)?.removeView(this)
                onClosed()
            }.start()
    }

    /** For MainActivity's Back handling: closes the story. Returns true when it was showing. */
    fun onBack(): Boolean {
        if (closing) return false
        close()
        return true
    }

    // Behind the share sheet or with the app in the background, the bars wait instead of running on
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        val a = animator ?: return
        if (visibility != VISIBLE) {
            if (a.isRunning && !a.isPaused) a.pause()
        } else if (!held && !closing && a.isPaused) {
            a.resume()
        }
    }

    // Removed from the screen (closed, or the activity went away): nothing keeps running or holds on
    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        removeCallbacks(holdRunnable)
        removeCallbacks(noteHide)
        super.onDetachedFromWindow()
    }

    private fun next() {
        // The card is the end: tapping it doesn't close the story (swipe down or Back does),
        // so a stray tap next to Share / Save never loses it.
        if (index < slides.size - 1) show(index + 1, forward = true)
    }

    private fun prev() {
        show((index - 1).coerceAtLeast(0), forward = false)
    }

    private fun show(i: Int, forward: Boolean) {
        if (closing) return
        index = i
        val look = looks[i]
        currentLook = look
        setBackgroundColor(look.bg)
        dateLabel.setTextColor(look.muted)
        progress.setColors(look.fg)
        progress.set(i, 0f)
        onBars(ColorUtils.calculateLuminance(look.bg) > 0.5)

        slideHost.removeAllViews()
        cardImage = null
        cardPills = null
        val v = build(slides[i], look)
        slideHost.addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        v.alpha = 0f
        v.translationX = if (forward) dp(18f).toFloat() else -dp(18f).toFloat()
        v.animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(DecelerateInterpolator()).start()

        animator?.cancel()
        val isCard = slides[i] == Kind.CARD
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (isCard) 7_000L else 5_500L
            interpolator = LinearInterpolator()
            addUpdateListener { progress.set(index, it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    // The card stays up when its bar fills, so there's time to share it
                    if (!cancelled && !isCard) next()
                }
            })
            start()
            if (held) pause()
        }
    }

    // ---- slides ------------------------------------------------------------------------------

    private fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(36f), 0, dp(36f), dp(40f))
    }

    private fun label(s: String, size: Float, face: Typeface, color: Int, top: Float = 0f, lines: Int = 2): TextView =
        TextView(context).apply {
            text = s
            textSize = size
            typeface = face
            setTextColor(color)
            gravity = Gravity.CENTER
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(top) }
        }

    private fun image(sizeDp: Float, round: Boolean, look: Look, top: Float = 0f): ImageView =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(look.muted)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (round) outline.setOval(0, 0, view.width, view.height)
                    else outline.setRoundRect(0, 0, view.width, view.height, dp(22f).toFloat())
                }
            }
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { topMargin = dp(top) }
        }

    private fun build(kind: Kind, look: Look): View {
        val col = column()
        when (kind) {
            Kind.TIME -> {
                col.addView(label("You listened for", 18f, regular, look.muted))
                col.addView(label(StatsFormat.duration(stats.timeMs), 64f, bold, look.fg, top = 10f, lines = 1))
                val songs = if (stats.songs == 1) "1 song" else "${stats.songs} songs"
                val artists = if (stats.artists == 1) "1 artist" else "${stats.artists} artists"
                col.addView(label("$songs  ·  $artists", 16f, semi, look.fg, top = 18f))
                if (stats.estimated) {
                    col.addView(label("Plays from before 4.9 count each song's full length.", 12f, regular, look.muted, top = 28f))
                }
            }
            Kind.SONG -> {
                val s = stats.topSong!!
                col.addView(label("Your top song", 18f, regular, look.muted))
                val img = image(240f, round = false, look = look, top = 22f)
                col.addView(img)
                artwork.loadInto(img, MusicArt.big(s.thumbnailUrl), dp(240f))
                col.addView(label(s.title, 24f, bold, look.fg, top = 22f))
                col.addView(label(s.artist, 15f, regular, look.muted, top = 6f, lines = 1))
                val plays = if (s.plays == 1) "Played once" else "Played ${s.plays} times"
                col.addView(label(plays, 16f, semi, look.fg, top = 16f))
            }
            Kind.ARTIST -> {
                val a = stats.topArtist!!
                col.addView(label("Your top artist", 18f, regular, look.muted))
                val img = image(220f, round = true, look = look, top = 22f)
                col.addView(img)
                if (!artistPhotoUrl.isNullOrBlank()) artwork.loadInto(img, artistPhotoUrl, dp(220f))
                else img.setImageDrawable(InitialDrawable(a.name.trim().take(1).uppercase(), look.fg, look.bg, bold))
                col.addView(label(a.name, 28f, bold, look.fg, top = 22f))
                col.addView(label("${StatsFormat.duration(a.ms)} listened", 16f, semi, look.muted, top = 8f))
            }
            Kind.PEAK -> {
                col.addView(label("You were most into it at", 18f, regular, look.muted))
                col.addView(label(StatsFormat.hour(stats.peakHour), 72f, bold, look.fg, top = 10f, lines = 1))
                col.addView(label(RecapSlides.peakLine(stats.peakHour), 16f, semi, look.fg, top = 14f))
            }
            Kind.STREAK -> {
                val s = stats.streak!!
                col.addView(label("Longest streak", 18f, regular, look.muted))
                col.addView(label("${s.count} songs", 64f, bold, look.fg, top = 10f, lines = 1))
                col.addView(label("by ${s.artist}, back to back", 18f, semi, look.fg, top = 12f))
            }
            Kind.ROAST -> {
                val r = roast!!
                col.addView(label(r.first, 34f, bold, look.fg, lines = 3))
                col.addView(label(r.second, 22f, regular, look.muted, top = 14f, lines = 2))
            }
            Kind.CARD -> return buildCard(look)
        }
        return col
    }

    private fun buildCard(look: Look): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28f), dp(4f), dp(28f), dp(8f))
        }
        val img = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    // Round the image itself (fitCenter leaves space at the sides)
                    val d = (view as ImageView).drawable
                    if (d == null || d.intrinsicWidth <= 0) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(18f).toFloat()); return
                    }
                    val scale = minOf(view.width.toFloat() / d.intrinsicWidth, view.height.toFloat() / d.intrinsicHeight)
                    val w = (d.intrinsicWidth * scale).toInt()
                    val h = (d.intrinsicHeight * scale).toInt()
                    val l = (view.width - w) / 2
                    val t = (view.height - h) / 2
                    outline.setRoundRect(l, t, l + w, t + h, dp(18f).toFloat())
                }
            }
            elevation = dp(6f).toFloat()
        }
        col.addView(img, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val share = pill("share", filled = true, look = look) { cardBitmap?.let(onShare) }
        val save = pill("save", filled = false, look = look) { cardBitmap?.let(onSave) }
        row.addView(share, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { rightMargin = dp(5f) })
        row.addView(save, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { leftMargin = dp(5f) })
        col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18f)
        })
        cardImage = img
        cardPills = row
        val bmp = cardBitmap
        if (bmp != null) img.setImageBitmap(bmp) else row.alpha = 0.4f
        return col
    }

    private fun pill(text: String, filled: Boolean, look: Look, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 15f
            typeface = semi
            gravity = Gravity.CENTER
            val r = dp(24f).toFloat()
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = r
                if (filled) setColor(look.fg) else {
                    setColor(ColorUtils.blendARGB(look.bg, look.fg, 0.1f))
                }
            }
            setTextColor(if (filled) look.bg else look.fg)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                tick(it)
                onClick()
            }
        }

    // ---- touch: tap left/right, hold to pause, swipe down to close ----------------------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                if (dy > slop && dy > abs(ev.rawX - downX)) {
                    dragging = true
                    removeCallbacks(holdRunnable)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (closing) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; dragging = false
                postDelayed(holdRunnable, 220L)
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                if (!dragging && dy > slop && dy > abs(ev.rawX - downX)) {
                    dragging = true
                    removeCallbacks(holdRunnable)
                }
                if (dragging) {
                    translationY = dy.coerceAtLeast(0f)
                    alpha = 1f - (translationY / height.coerceAtLeast(1)).coerceIn(0f, 0.5f)
                    animator?.pause()
                } else if (abs(ev.rawX - downX) > slop * 2 || abs(dy) > slop * 2) {
                    removeCallbacks(holdRunnable)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                val wasHeld = held
                if (held) release()
                if (dragging) {
                    dragging = false
                    if (translationY > height * 0.2f || translationY > dp(140f)) close()
                    else {
                        animate().translationY(0f).alpha(1f).setDuration(180).start()
                        if (!held) animator?.resume()
                    }
                } else if (!wasHeld && ev.actionMasked == MotionEvent.ACTION_UP) {
                    val back = ev.x < width * 0.3f
                    val moves = if (back) index > 0 else index < slides.size - 1
                    if (moves) tick(this)
                    if (back) prev() else next()
                }
            }
        }
        return true
    }

    private fun release() {
        held = false
        animator?.resume()
        progress.animate().alpha(1f).setDuration(150).start()
        dateLabel.animate().alpha(1f).setDuration(150).start()
    }

    private fun font(id: Int, fallback: Typeface): Typeface = try {
        ResourcesCompat.getFont(context, id) ?: fallback
    } catch (_: Exception) {
        fallback
    }

    /** Instagram-style bars: one per slide, the current one filling up. */
    private class ProgressBars(context: Context, private val count: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var color = 0
        private var current = 0
        private var fraction = 0f
        private val gap = 4f * resources.displayMetrics.density
        private val rect = RectF()

        fun setColors(c: Int) { color = c; invalidate() }
        fun set(i: Int, f: Float) { current = i; fraction = f; invalidate() }

        override fun onDraw(canvas: Canvas) {
            if (count <= 0) return
            val w = (width - gap * (count - 1)) / count
            val h = height.toFloat()
            val r = h / 2f
            for (i in 0 until count) {
                val left = i * (w + gap)
                rect.set(left, 0f, left + w, h)
                paint.color = ColorUtils.setAlphaComponent(color, 70)
                canvas.drawRoundRect(rect, r, r, paint)
                val fill = when {
                    i < current -> 1f
                    i == current -> fraction
                    else -> 0f
                }
                if (fill > 0f) {
                    rect.set(left, 0f, left + w * fill, h)
                    paint.color = color
                    canvas.drawRoundRect(rect, r, r, paint)
                }
            }
        }
    }

    /** A letter in a circle, for an artist without a photo. */
    private class InitialDrawable(
        private val letter: String,
        private val fill: Int,
        private val textColor: Int,
        face: Typeface
    ) : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textAlign = Paint.Align.CENTER }
        override fun draw(canvas: Canvas) {
            val b = bounds
            p.color = fill
            canvas.drawRect(b, p)
            tp.color = textColor
            tp.textSize = b.height() * 0.42f
            val y = b.exactCenterY() - (tp.descent() + tp.ascent()) / 2f
            canvas.drawText(letter, b.exactCenterX(), y, tp)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }
}

/** Bigger cover URLs for the story and card (YouTube Music art comes with a size in the URL). */
object MusicArt {
    fun big(url: String): String =
        com.minim.music.data.source.MusicSourceAdapter.upscaleYtImage(url, 720)
}
