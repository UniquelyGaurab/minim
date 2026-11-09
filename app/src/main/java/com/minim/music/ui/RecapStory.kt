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
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
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
import com.minim.music.data.stats.RecapMore
import com.minim.music.data.stats.RecapMoreMath
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.StatsFormat
import com.minim.music.data.stats.TopSong
import com.minim.music.ui.RecapSlides.Kind
import com.minim.music.util.ArtworkPipeline
import kotlin.math.abs

/**
 * The recap story, a mini Wrapped. Full-screen slides with Instagram-style progress bars:
 * tap right / left to go forward / back, press and hold to pause, swipe down (or Back) to close,
 * a soft haptic tick on each tap. The date and day sit small at the top of every slide.
 *
 * 5.8.2 (update 54): reworked. Intro, time (counts up), the day in hours, first and last song,
 * guess your top song, the top-5 countdown, top artist (with the streak as a line), tapes, the
 * roast, the listening personality and the redesigned card with Share, Save and Save as playlist.
 * Each slide has its own motion and its own colours, from that slide's cover or photo. The top
 * song plays behind the story (MainActivity runs that; this view only shows the sound button).
 */
@SuppressLint("ViewConstructor")
class RecapStory(
    context: Context,
    private val stats: DayStats,
    private val day: RecapDay,
    private val pal: Pal,
    /** Palettes from each slide's own cover or photo, by URL (missing ones use [pal]). */
    private val pals: Map<String, Pal>,
    /** 5.8.3 u55: first listens, hearted today, longest session, yesterday, streak, all-time plays. */
    private val more: RecapMore = RecapMore.NONE,
    private val artwork: ArtworkPipeline,
    private val artistPhotoUrl: String?,
    /** 5.7.0 u51: the top channel's photo, for the tapes slide (null: its first letter). */
    private val channelPhotoUrl: String?,
    private val roast: Pair<String, String>?,
    private val persona: RecapPersonality.Result?,
    startAtCard: Boolean,
    /** Null: no music behind this story (no sound button). Else whether it starts with sound. */
    soundOn: Boolean?,
    private val tick: (View) -> Unit,
    private val onBars: (lightBars: Boolean) -> Unit,
    private val onShare: (Bitmap) -> Unit,
    private val onSave: (Bitmap) -> Unit,
    private val onSavePlaylist: () -> Unit,
    private val onPlayTop: () -> Unit,
    private val onOpenArtist: () -> Unit,
    private val onSound: (Boolean) -> Unit,
    private val onClosed: () -> Unit
) : FrameLayout(context) {

    /**
     * One slide's colours, all solid and taken from a cover. 5.8.3 u55: the main text and the faint
     * labels are both made sure to read on [bg] (see [RecapInk]).
     */
    private class Look(val bg: Int, fgRaw: Int, mutedRaw: Int) {
        val fg: Int = RecapInk.text(bg, fgRaw)
        /** A second solid shade of the same cover, for shapes behind the words. */
        val tone: Int = RecapInk.tone(bg, fg)
        /** Faint labels: readable on [bg] and on [tone], since shapes sit behind some words. */
        val muted: Int = RecapInk.readableOn(listOf(bg, tone), mutedRaw, fg)
        val soft: Int get() = ColorUtils.blendARGB(bg, fg, 0.1f)
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    private val bold: Typeface = font(R.font.poppins_bold, Typeface.DEFAULT_BOLD)
    private val semi: Typeface = font(R.font.poppins_semibold, Typeface.DEFAULT_BOLD)
    private val regular: Typeface = font(R.font.poppins_regular, Typeface.DEFAULT)

    private val slides: List<Kind> = RecapSlides.plan(stats, day, roast != null, more)

    private fun lookA(p: Pal) = Look(p.bg, p.fg, p.muted)
    private fun lookB(p: Pal) = Look(p.fg, p.bg, ColorUtils.blendARGB(p.fg, p.bg, 0.6f))
    private fun lookC(p: Pal) = Look(ColorUtils.blendARGB(p.bg, p.fg, 0.12f), p.fg, p.muted)
    private fun palOf(url: String?): Pal? = url?.takeIf { it.isNotBlank() }?.let { pals[it] }

    /** Each slide's colours: its own cover or photo when it has one, else a turn on the top song's. */
    private val looks: List<Look> = slides.map { k ->
        when (k) {
            Kind.INTRO, Kind.TOP5, Kind.CARD -> lookA(pal)
            Kind.TIME, Kind.GUESS, Kind.ROAST -> lookB(pal)
            Kind.VS_YESTERDAY -> lookA(pal)
            Kind.SESSION -> palOf(more.session?.firstThumb)?.let { lookA(it) } ?: lookB(pal)
            Kind.FIRST_LISTENS -> palOf(more.firstListens.firstOrNull()?.thumbnailUrl)?.let { lookC(it) } ?: lookC(pal)
            Kind.HEARTED -> palOf(more.hearted.firstOrNull()?.thumbnailUrl)?.let { lookB(it) } ?: lookB(pal)
            Kind.STREAK -> lookC(pal)
            Kind.HOURS, Kind.PERSONA -> lookC(pal)
            Kind.FIRST_LAST -> palOf(day.first?.thumbnailUrl)?.let { lookA(it) } ?: lookC(pal)
            Kind.ARTIST -> palOf(artistPhotoUrl)?.let { lookA(it) } ?: lookC(pal)
            Kind.TAPES -> (palOf(channelPhotoUrl) ?: palOf(stats.tapes.topTape?.thumbnailUrl))?.let { lookA(it) } ?: lookB(pal)
        }
    }

    private val slideHost = FrameLayout(context)
    private val progress = ProgressBars(context, slides.size)
    private val dateLabel = TextView(context)
    private val soundButton = ImageView(context)
    private var sound = soundOn ?: false
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

    // Each slide's own motion, cancelled when the slide changes and paused while held
    private val motion = ArrayList<ValueAnimator>()
    // Steps of the current slide that happen at a set time into it (driven by its bar, so they wait while held)
    private val phases = ArrayList<Pair<Long, () -> Unit>>()
    private var guessed = false

    // touch
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val holdRunnable = Runnable {
        held = true
        animator?.pause()
        motion.forEach { if (it.isRunning) it.pause() }
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
        soundButton.apply {
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            contentDescription = "Sound"
            isClickable = true
            isFocusable = true
            visibility = if (soundOn == null) View.GONE else View.VISIBLE
            setImageResource(if (sound) R.drawable.ic_sound_on else R.drawable.ic_sound_off)
            setOnClickListener {
                tick(it)
                sound = !sound
                setImageResource(if (sound) R.drawable.ic_sound_on else R.drawable.ic_sound_off)
                onSound(sound)
            }
        }
        addView(soundButton, LayoutParams(dp(36f), dp(36f)).apply { gravity = Gravity.END or Gravity.TOP; rightMargin = dp(8f) })
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
            (soundButton.layoutParams as LayoutParams).topMargin = bars.top + dp(14f)
            slideHost.setPadding(0, bars.top + dp(48f), 0, bars.bottom + dp(16f))
            (noteView.layoutParams as LayoutParams).bottomMargin = bars.bottom + dp(84f)
            progress.requestLayout()
            insets
        }
        index = if (startAtCard) slides.size - 1 else 0
    }

    /** The slides this story shows, in order (for tests). */
    internal val kinds: List<Kind> get() = slides
    internal val currentKind: Kind get() = slides[index]

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
        cardImage?.let {
            it.setImageBitmap(bmp)
            it.alpha = 0f
            it.scaleX = 0.94f; it.scaleY = 0.94f
            it.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(DecelerateInterpolator()).start()
        }
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
        stopMotion()
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
        val a = animator
        if (visibility != VISIBLE) {
            if (a != null && a.isRunning && !a.isPaused) a.pause()
            motion.forEach { if (it.isRunning) it.pause() }
        } else if (!held && !closing) {
            if (a != null && a.isPaused) a.resume()
            motion.forEach { if (it.isPaused) it.resume() }
        }
    }

    // Removed from the screen (closed, or the activity went away): nothing keeps running or holds on
    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        stopMotion()
        removeCallbacks(holdRunnable)
        removeCallbacks(noteHide)
        super.onDetachedFromWindow()
    }

    private fun stopMotion() {
        val m = ArrayList(motion)
        motion.clear()
        m.forEach { it.cancel() }
        phases.clear()
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
        soundButton.setColorFilter(look.muted)
        progress.setColors(look.fg)
        progress.set(i, 0f)
        onBars(ColorUtils.calculateLuminance(look.bg) > 0.5)

        animator?.cancel()
        animator = null
        stopMotion()
        guessed = false
        slideHost.removeAllViews()
        cardImage = null
        cardPills = null
        val kind = slides[i]
        val v = build(kind, look)
        slideHost.addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        v.alpha = 0f
        v.translationX = if (forward) dp(18f).toFloat() else -dp(18f).toFloat()
        v.animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(DecelerateInterpolator()).start()

        // The guess waits for a pick before its bar runs
        if (kind != Kind.GUESS) startBar(kind)
    }

    private fun startBar(kind: Kind) {
        val isCard = kind == Kind.CARD
        val total = RecapSlides.durationMs(kind)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                progress.set(index, f)
                runPhases((f * total).toLong())
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    // The card stays up when its bar fills, so there's time to share it
                    if (!cancelled) {
                        runPhases(Long.MAX_VALUE)
                        if (!isCard) next()
                    }
                }
            })
            start()
            if (held) pause()
        }
    }

    private fun runPhases(elapsed: Long) {
        if (phases.isEmpty()) return
        val due = phases.filter { it.first <= elapsed }
        if (due.isEmpty()) return
        phases.removeAll(due.toSet())
        due.forEach { it.second() }
    }

    private fun at(ms: Long, step: () -> Unit) {
        if (ms <= 0L) step() else phases.add(ms to step)
    }

    // ---- motion ------------------------------------------------------------------------------

    private fun track(a: ValueAnimator): ValueAnimator {
        motion.add(a)
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { motion.remove(a) }
        })
        return a
    }

    /** Fades and lifts [v] in after [delay] ms. */
    private fun riseIn(v: View, delay: Long, dist: Float = 14f) {
        v.alpha = 0f
        v.translationY = dp(dist).toFloat()
        track(ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = 420
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                v.alpha = f
                v.translationY = dp(dist) * (1f - f)
            }
            start()
        })
    }

    /** Scales [v] up into place with a small overshoot. */
    private fun scaleIn(v: View, delay: Long, from: Float = 0.78f) {
        v.alpha = 0f
        v.scaleX = from; v.scaleY = from
        track(ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = 560
            interpolator = OvershootInterpolator(1.15f)
            addUpdateListener {
                val f = it.animatedValue as Float
                v.alpha = f.coerceIn(0f, 1f)
                val s = from + (1f - from) * f
                v.scaleX = s; v.scaleY = s
            }
            start()
        })
    }

    /** Shows [text] word by word, each word fading in. */
    private fun wordsIn(tv: TextView, text: String, delay: Long, perWord: Long = 120L) {
        val words = RecapMotion.wordStarts(text)
        val color = tv.currentTextColor
        val span = SpannableString(text)
        fun paint(f: Float) {
            for ((i, start) in words.withIndex()) {
                val end = if (i + 1 < words.size) words[i + 1] else text.length
                val a = RecapMotion.wordAlpha(f, i, words.size)
                span.getSpans(start, end, ForegroundColorSpan::class.java).forEach { span.removeSpan(it) }
                span.setSpan(ForegroundColorSpan(ColorUtils.setAlphaComponent(color, (a * android.graphics.Color.alpha(color)).toInt())),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            tv.text = span
        }
        paint(0f)
        track(ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = (perWord * words.size + 260L).coerceAtMost(2_000L)
            interpolator = LinearInterpolator()
            addUpdateListener { paint(it.animatedValue as Float) }
            start()
        })
    }

    /** Counts [tv] up from zero to [ms] of listening. */
    private fun countUp(tv: TextView, ms: Long, delay: Long) {
        tv.text = StatsFormat.duration(0L)
        track(ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = 1_400
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { tv.text = StatsFormat.duration(RecapMotion.countValue(ms, it.animatedValue as Float)) }
            start()
        })
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

    private fun image(sizeDp: Float, round: Boolean, look: Look, top: Float = 0f, radius: Float = 22f): ImageView =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(look.soft)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (round) outline.setOval(0, 0, view.width, view.height)
                    else outline.setRoundRect(0, 0, view.width, view.height, dp(radius).toFloat())
                }
            }
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { topMargin = dp(top) }
        }

    private fun cover(img: ImageView, url: String?, sizeDp: Float) {
        if (!url.isNullOrBlank()) artwork.loadInto(img, MusicArt.big(url), dp(sizeDp))
    }

    /**
     * 5.8.3 u55: a solid round shape of [color], centred behind a slide's words, [sizeDp] across.
     * Replaces the blurred cover: the shapes are flat colours from the same cover.
     */
    private fun solidDisc(root: FrameLayout, color: Int, sizeDp: Float): View {
        val v = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            }
        }
        root.addView(v, LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER).apply { bottomMargin = dp(40f) })
        return v
    }

    private fun build(kind: Kind, look: Look): View = when (kind) {
        Kind.INTRO -> buildIntro(look)
        Kind.TIME -> buildTime(look)
        Kind.VS_YESTERDAY -> buildVsYesterday(look)
        Kind.HOURS -> buildHours(look)
        Kind.SESSION -> buildSession(look)
        Kind.FIRST_LAST -> buildFirstLast(look)
        Kind.FIRST_LISTENS -> buildFirstListens(look)
        Kind.GUESS -> buildGuess(look)
        Kind.TOP5 -> buildTop5(look)
        Kind.ARTIST -> buildArtist(look)
        Kind.HEARTED -> buildHearted(look)
        Kind.TAPES -> buildTapes(look)
        Kind.STREAK -> buildStreak(look)
        Kind.ROAST -> buildRoast(look)
        Kind.PERSONA -> buildPersona(look)
        Kind.CARD -> buildCard(look)
    }

    private fun buildIntro(look: Look): View {
        val root = FrameLayout(context)
        // 5.8.3 u55: a solid disc in a second shade of the cover grows behind the words (no blur)
        val disc = solidDisc(root, look.tone, 300f)
        disc.alpha = 0f
        track(ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 150
            duration = 2_400
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                val f = it.animatedValue as Float
                disc.alpha = f.coerceIn(0f, 1f)
                disc.scaleX = 0.4f + 0.75f * f; disc.scaleY = disc.scaleX
            }
            start()
        })
        val col = column()
        val line = label(RecapDay.introLine(stats.day), 34f, bold, look.fg, lines = 3)
        col.addView(line)
        wordsIn(line, line.text.toString(), 200L, perWord = 160L)
        root.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        return root
    }

    private fun buildTime(look: Look): View {
        val col = column()
        col.addView(label("You listened for", 18f, regular, look.muted))
        // 5.7.0 u51: songs and tapes together, with tapes and channels under songs and artists
        val big = label(StatsFormat.duration(stats.totalMs), 64f, bold, look.fg, top = 10f, lines = 1)
        col.addView(big)
        countUp(big, stats.totalMs, 250L)
        val songs = if (stats.songs == 1) "1 song" else "${stats.songs} songs"
        val artists = if (stats.artists == 1) "1 artist" else "${stats.artists} artists"
        val l1 = label("$songs  ·  $artists", 16f, semi, look.fg, top = 18f)
        col.addView(l1)
        riseIn(l1, 1_250L)
        val t = stats.tapes
        if (t.shows) {
            val tapes = if (t.tapes == 1) "1 tape" else "${t.tapes} tapes"
            val channels = if (t.channels == 1) "1 channel" else "${t.channels} channels"
            val l2 = label("$tapes  ·  $channels", 16f, semi, look.fg, top = 6f)
            col.addView(l2)
            riseIn(l2, 1_450L)
        }
        com.minim.music.data.stats.TapeStats.savedLine(t)?.let {
            val l3 = label(it.replaceFirstChar { c -> c.uppercase() } + ".", 13f, regular, look.muted, top = 14f)
            col.addView(l3)
            riseIn(l3, 1_700L)
        }
        return col
    }

    private fun buildHours(look: Look): View {
        val col = column()
        val peak = day.peakHour
        col.addView(label("Your day in hours", 18f, regular, look.muted))
        val big = label(StatsFormat.hour(peak), 56f, bold, look.fg, top = 8f, lines = 1)
        col.addView(big)
        scaleIn(big, 1_100L, from = 0.9f)
        val chart = HourChart(context, day.hours, peak, look.fg, look.muted, semi, regular)
        col.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170f)).apply { topMargin = dp(18f) })
        track(ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 200
            duration = 1_200
            interpolator = DecelerateInterpolator()
            addUpdateListener { chart.grow = it.animatedValue as Float }
            start()
        })
        val line = label("Peak hour.  " + RecapSlides.peakLine(peak), 15f, semi, look.fg, top = 18f)
        col.addView(line)
        riseIn(line, 1_500L)
        return col
    }

    private fun songRow(look: Look, p: com.minim.music.data.stats.PlayRow, sizeDp: Float): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val img = image(sizeDp, round = false, look = look, radius = 14f)
        row.addView(img)
        cover(img, p.thumbnailUrl, sizeDp)
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(p.title, 18f, bold, look.fg, lines = 2).apply { gravity = Gravity.START })
        text.addView(label(p.artist, 14f, regular, look.muted, top = 2f, lines = 1).apply { gravity = Gravity.START })
        text.addView(label("at ${RecapDay.clock(p.playedAt)}", 13f, semi, look.fg, top = 6f, lines = 1).apply { gravity = Gravity.START })
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(16f) })
        return row
    }

    private fun buildFirstLast(look: Look): View {
        val col = column()
        val first = day.first!!
        val last = day.last!!
        if (day.sameFirstLast) {
            col.addView(label("You started and ended the day with", 18f, regular, look.muted))
            val img = image(220f, round = false, look = look, top = 20f)
            col.addView(img)
            cover(img, first.thumbnailUrl, 220f)
            scaleIn(img, 200L)
            val t = label(first.title, 24f, bold, look.fg, top = 20f)
            col.addView(t)
            wordsIn(t, first.title, 600L)
            col.addView(label(first.artist, 15f, regular, look.muted, top = 4f, lines = 1))
            val when_ = label("${RecapDay.clock(first.playedAt)} and ${RecapDay.clock(last.playedAt)}", 15f, semi, look.fg, top = 14f)
            col.addView(when_)
            riseIn(when_, 1_300L)
            return col
        }
        val l1 = label("Your day started with", 18f, regular, look.muted).apply { gravity = Gravity.START }
        col.addView(l1)
        val r1 = songRow(look, first, 104f)
        col.addView(r1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) })
        riseIn(r1, 250L)
        val l2 = label("and ended with", 18f, regular, look.muted, top = 40f).apply { gravity = Gravity.START }
        col.addView(l2)
        val r2 = songRow(look, last, 104f)
        col.addView(r2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) })
        l2.alpha = 0f
        r2.alpha = 0f
        at(1_700L) { riseIn(l2, 0L); riseIn(r2, 150L) }
        return col
    }

    private fun buildGuess(look: Look): View {
        val col = column()
        val options = RecapGuess.options(day, stats.day)!!
        val answer = day.top[0].trackId
        col.addView(label("Guess your top song", 26f, bold, look.fg))
        col.addView(label("Tap the one you played most.", 15f, regular, look.muted, top = 8f))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val reply = label(" ", 18f, semi, look.fg, top = 26f)
        val title = label(" ", 15f, regular, look.muted, top = 6f)
        val tiles = ArrayList<ImageView>()
        for ((i, s) in options.withIndex()) {
            val img = image(96f, round = false, look = look, radius = 16f)
            img.contentDescription = s.title
            cover(img, s.thumbnailUrl, 96f)
            img.isClickable = true
            img.isFocusable = true
            img.setOnClickListener {
                if (guessed) return@setOnClickListener
                guessed = true
                tick(it)
                val right = s.trackId == answer
                for ((j, t) in tiles.withIndex()) {
                    val isAnswer = options[j].trackId == answer
                    t.animate().alpha(if (isAnswer) 1f else 0.3f)
                        .scaleX(if (isAnswer) 1.12f else 0.92f).scaleY(if (isAnswer) 1.12f else 0.92f)
                        .setDuration(320).setInterpolator(OvershootInterpolator(1.4f)).start()
                    if (isAnswer) t.elevation = dp(10f).toFloat()
                }
                reply.text = RecapGuess.reply(right)
                title.text = day.top[0].title
                riseIn(reply, 120L)
                riseIn(title, 260L)
                startBar(Kind.GUESS)
            }
            tiles.add(img)
            row.addView(img, LinearLayout.LayoutParams(dp(96f), dp(96f)).apply {
                if (i > 0) leftMargin = dp(12f)
            })
            scaleIn(img, 250L + i * 140L)
        }
        col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28f) })
        col.addView(reply)
        col.addView(title)
        return col
    }

    private fun rankRow(look: Look, rank: Int, s: TopSong?): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label("$rank", 22f, bold, look.fg, lines = 1).apply { gravity = Gravity.START },
            LinearLayout.LayoutParams(dp(30f), LinearLayout.LayoutParams.WRAP_CONTENT))
        val img = image(54f, round = false, look = look, radius = 12f)
        row.addView(img)
        if (s != null) cover(img, s.thumbnailUrl, 54f) else img.setImageDrawable(null)
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(s?.title ?: "· · ·", 16f, semi, look.fg, lines = 1).apply { gravity = Gravity.START })
        val sub = if (s == null) "coming up" else {
            val plays = if (s.plays == 1) "1 play" else "${s.plays} plays"
            "${s.artist}  ·  $plays"
        }
        text.addView(label(sub, 13f, regular, look.muted, top = 2f, lines = 1).apply { gravity = Gravity.START })
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(14f) })
        return row
    }

    private fun buildTop5(look: Look): View {
        val root = FrameLayout(context)
        val top = day.top
        val rest = top.drop(1) // #2..#5
        val list = column().apply { gravity = Gravity.CENTER_VERTICAL }
        if (rest.isNotEmpty()) {
            list.addView(label("Your top ${top.size}", 26f, bold, look.fg).apply { gravity = Gravity.START })
            val hold = rankRow(look, 1, null)
            list.addView(hold, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22f) })
            hold.alpha = 0.45f
            val rows = rest.mapIndexed { i, s -> rankRow(look, i + 2, s) }
            for (r in rows) {
                r.alpha = 0f
                list.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f) })
            }
            // #5 comes in first, then #4 ... #2
            for ((n, r) in rows.reversed().withIndex()) at(700L + n * 750L) { riseIn(r, 0L, dist = 22f) }
            root.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        // #1 gets the whole screen. 5.8.3 u55: on the slide's own solid colour, with a solid disc in
        // a second shade of the cover behind it (the blurred cover made the words hard to read)
        val reveal = FrameLayout(context).apply { alpha = 0f; setBackgroundColor(look.bg) }
        val disc = solidDisc(reveal, look.tone, 330f)
        disc.alpha = 0f
        val col = column()
        val s = top[0]
        col.addView(label("Your #1", 18f, regular, look.muted))
        val img = image(250f, round = false, look = look, top = 18f, radius = 26f)
        img.elevation = dp(12f).toFloat()
        img.contentDescription = "Play ${s.title}"
        cover(img, s.thumbnailUrl, 250f)
        img.isClickable = true
        img.isFocusable = true
        img.setOnClickListener { tick(it); onPlayTop() }
        col.addView(img)
        val title = label(s.title, 26f, bold, look.fg, top = 22f)
        title.setOnClickListener { tick(it); onPlayTop() }
        col.addView(title)
        col.addView(label(s.artist, 15f, regular, look.muted, top = 4f, lines = 1))
        // 5.8.3 u55: the day's plays next to all-time plays, "16 today, 54 all time"
        val playsText = if (more.topAllTime > 0) RecapMoreMath.allTimeLine(s.plays, more.topAllTime)
            else if (s.plays == 1) "Played once" else "Played ${s.plays} times"
        val plays = label(playsText, 16f, semi, look.fg, top = 14f)
        col.addView(plays)
        val hint = label("tap the cover to play it", 12f, regular, look.muted, top = 18f)
        col.addView(hint)
        reveal.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        reveal.visibility = View.INVISIBLE
        root.addView(reveal, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        plays.alpha = 0f
        hint.alpha = 0f
        at(RecapSlides.top5RevealAt(rest.size)) {
            if (rest.isNotEmpty()) {
                list.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(280).start()
            }
            reveal.visibility = View.VISIBLE
            reveal.animate().alpha(1f).setDuration(420).setInterpolator(DecelerateInterpolator()).start()
            scaleIn(disc, 0L, from = 0.3f)
            scaleIn(img, 120L, from = 0.6f)
            wordsIn(title, s.title, 520L)
            riseIn(plays, 1_100L)
            riseIn(hint, 1_500L)
        }
        return root
    }

    private fun buildArtist(look: Look): View {
        val col = column()
        val a = stats.topArtist!!
        col.addView(label("Your top artist", 18f, regular, look.muted))
        val img = image(220f, round = true, look = look, top = 22f)
        img.contentDescription = "Open ${a.name}"
        col.addView(img)
        if (!artistPhotoUrl.isNullOrBlank()) artwork.loadInto(img, artistPhotoUrl, dp(220f))
        else img.setImageDrawable(InitialDrawable(a.name.trim().take(1).uppercase(), look.fg, look.bg, bold))
        img.isClickable = true
        img.isFocusable = true
        img.setOnClickListener { tick(it); onOpenArtist() }
        scaleIn(img, 150L)
        val name = label(a.name, 30f, bold, look.fg, top = 22f)
        name.setOnClickListener { tick(it); onOpenArtist() }
        col.addView(name)
        wordsIn(name, a.name, 550L, perWord = 180L)
        val time = label("${StatsFormat.duration(a.ms)} listened", 16f, semi, look.fg, top = 8f)
        col.addView(time)
        riseIn(time, 1_100L)
        RecapSlides.streakLine(stats)?.let {
            val st = label(it, 14f, regular, look.muted, top = 6f)
            col.addView(st)
            riseIn(st, 1_600L)
        }
        val hint = label("tap to open their page", 12f, regular, look.muted, top = 18f)
        col.addView(hint)
        riseIn(hint, 2_000L)
        return col
    }

    private fun buildTapes(look: Look): View {
        // 5.7.0 u51: "You spent 1h 10m with SR PAY STORIES"
        val col = column()
        val ch = stats.tapes.topChannel!!
        col.addView(label("You spent", 18f, regular, look.muted))
        val big = label(StatsFormat.duration(ch.ms), 56f, bold, look.fg, top = 8f, lines = 1)
        col.addView(big)
        countUp(big, ch.ms, 150L)
        col.addView(label("with", 18f, regular, look.muted, top = 10f))
        val img = image(150f, round = true, look = look, top = 18f)
        col.addView(img)
        if (!channelPhotoUrl.isNullOrBlank()) artwork.loadInto(img, channelPhotoUrl, dp(150f))
        else img.setImageDrawable(InitialDrawable(ch.name.trim().take(1).uppercase(), look.fg, look.bg, bold))
        scaleIn(img, 700L)
        val name = label(ch.name, 28f, bold, look.fg, top = 18f)
        col.addView(name)
        wordsIn(name, ch.name, 1_000L)
        val line = label(RecapSlides.tapesLine(stats), 16f, semi, look.muted, top = 8f)
        col.addView(line)
        riseIn(line, 1_600L)
        return col
    }

    // ---- 5.8.3 u55: the new slides ---------------------------------------------------------

    private fun matchWrap(top: Float = 0f) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    /** Runs [step] with 0..1 after [delay] ms, over [length] ms. */
    private fun grow(delay: Long, length: Long, step: (Float) -> Unit) {
        step(0f)
        track(ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = length
            interpolator = DecelerateInterpolator(1.3f)
            addUpdateListener { step(it.animatedValue as Float) }
            start()
        })
    }

    private fun weekday(day: Int, short: Boolean): String =
        Days.date(day).dayOfWeek.getDisplayName(
            if (short) java.time.format.TextStyle.SHORT else java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH
        )

    /** "40m more than Saturday", with the two days as bars growing side by side. */
    private fun buildVsYesterday(look: Look): View {
        val c = more.compare!!
        val before = RecapMoreMath.beforeName(stats.day)
        val col = column()
        col.addView(label("Compared with $before", 18f, regular, look.muted))
        val same = abs(c.diffMs) < RecapMoreMath.SAME_MS
        val big = label(RecapMoreMath.compareBig(c), 64f, bold, look.fg, top = 10f, lines = 1)
        col.addView(big)
        if (same) scaleIn(big, 250L, from = 0.85f) else countUp(big, abs(c.diffMs), 250L)
        val line = label(RecapMoreMath.compareLine(c, before), 18f, semi, look.fg, top = 6f)
        col.addView(line)
        riseIn(line, 1_000L)
        val bars = CompareBars(
            context, weekday(Days.previous(stats.day), true), c.yesterdayMs, weekday(stats.day, true), c.todayMs,
            look.fg, look.muted, look.tone, semi, regular
        )
        col.addView(bars, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(92f)).apply { topMargin = dp(26f) })
        grow(1_300L, 1_100L) { bars.grow = it }
        val counts = label(RecapMoreMath.compareCounts(c), 14f, regular, look.muted, top = 18f)
        col.addView(counts)
        riseIn(counts, 2_300L)
        return col
    }

    /** "2h 10m nonstop, starting 10:04 PM", with the stretch drawn on the day's 24 hours. */
    private fun buildSession(look: Look): View {
        val ss = more.session!!
        val col = column()
        col.addView(label("Your longest session", 18f, regular, look.muted))
        val big = label(StatsFormat.duration(ss.ms), 60f, bold, look.fg, top = 8f, lines = 1)
        col.addView(big)
        countUp(big, ss.ms, 200L)
        val line = label("nonstop, starting ${RecapDay.clock(ss.startMs)}", 18f, semi, look.fg, top = 6f)
        col.addView(line)
        riseIn(line, 1_000L)
        val dayStart = Days.startMs(stats.day)
        val dayLen = (Days.endMs(stats.day) - dayStart).toFloat().coerceAtLeast(1f)
        val bar = DayTimeline(
            context, ((ss.startMs - dayStart) / dayLen).coerceIn(0f, 1f), ((ss.endMs - dayStart) / dayLen).coerceIn(0f, 1f),
            look.fg, look.tone, look.muted, regular
        )
        col.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56f)).apply { topMargin = dp(28f) })
        grow(1_300L, 1_200L) { bar.grow = it }
        val songs = if (ss.plays == 1) "1 song" else "${ss.plays} songs"
        val sub = label("$songs, no break longer than ${RecapMoreMath.SESSION_GAP_MS / 60_000L} minutes", 13f, regular, look.muted, top = 16f)
        col.addView(sub)
        riseIn(sub, 2_400L)
        return col
    }

    /** "12 first listens": covers popping into a 2 x 5 grid, one at a time. Tap one for its title. */
    private fun buildFirstListens(look: Look): View {
        val all = more.firstListens
        val shown = all.take(RecapMoreMath.GRID_MAX)
        val col = column()
        val head = label(RecapMoreMath.firstListensHeadline(all.size), 32f, bold, look.fg, lines = 1)
        col.addView(head)
        wordsIn(head, head.text.toString(), 150L, perWord = 160L)
        val sub = label("songs you'd never played before", 15f, regular, look.muted, top = 4f)
        col.addView(sub)
        riseIn(sub, 500L)
        val gap = dp(10f)
        val avail = resources.displayMetrics.widthPixels - dp(36f) * 2
        val cell = ((avail - gap * 4) / 5).coerceIn(dp(40f), dp(84f))
        val pill = TextView(context).apply {
            textSize = 13f
            typeface = semi
            setTextColor(look.bg)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(look.fg)
            }
            visibility = View.INVISIBLE
            text = " "
        }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val covers = ArrayList<ImageView>()
        for (r in 0 until 2) {
            val items = shown.drop(r * 5).take(5)
            if (items.isEmpty()) break
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
            for ((i, song) in items.withIndex()) {
                val img = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(look.soft)
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(12f).toFloat())
                        }
                    }
                    contentDescription = song.title
                    isClickable = true
                    isFocusable = true
                }
                if (song.thumbnailUrl.isNotBlank()) artwork.loadInto(img, MusicArt.big(song.thumbnailUrl), cell)
                img.setOnClickListener {
                    tick(it)
                    pill.text = if (song.artist.isBlank()) song.title else "${song.title}  ·  ${song.artist}"
                    pill.visibility = View.VISIBLE
                    pill.scaleX = 0.85f; pill.scaleY = 0.85f; pill.alpha = 0f
                    pill.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220)
                        .setInterpolator(OvershootInterpolator(1.4f)).start()
                }
                row.addView(img, LinearLayout.LayoutParams(cell, cell).apply { if (i > 0) leftMargin = gap })
                covers.add(img)
            }
            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (r > 0) topMargin = gap })
        }
        col.addView(grid, matchWrap(24f))
        for ((i, img) in covers.withIndex()) {
            img.rotation = if (i % 2 == 0) -6f else 6f
            scaleIn(img, 800L + i * 230L, from = 0.4f)
            at(800L + i * 230L) { img.animate().rotation(0f).setDuration(420).start() }
        }
        val after = 800L + covers.size * 230L + 300L
        RecapMoreMath.moreLine(all.size, RecapMoreMath.GRID_MAX)?.let {
            val m = label(it, 15f, semi, look.fg, top = 16f)
            col.addView(m)
            riseIn(m, after)
        }
        col.addView(pill, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16f); gravity = Gravity.CENTER_HORIZONTAL })
        pill.maxWidth = avail
        val hint = label("tap a cover to see what it is", 12f, regular, look.muted, top = 10f)
        col.addView(hint)
        riseIn(hint, after + 300L)
        return col
    }

    /** Hearted today: a list where each row's heart fills in as the row arrives. */
    private fun buildHearted(look: Look): View {
        val all = more.hearted
        val shown = all.take(RecapMoreMath.LIST_MAX)
        val compact = shown.size > 6
        val coverDp = if (compact) 40f else 50f
        val rowGap = if (compact) 8f else 12f
        val col = column().apply { gravity = Gravity.CENTER_VERTICAL }
        col.addView(label("Hearted today", 16f, regular, look.muted).apply { gravity = Gravity.START })
        val head = label(RecapMoreMath.heartedHeadline(all.size), 28f, bold, look.fg, top = 2f, lines = 1).apply { gravity = Gravity.START }
        col.addView(head)
        wordsIn(head, head.text.toString(), 150L, perWord = 150L)
        for ((i, song) in shown.withIndex()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val img = image(coverDp, round = false, look = look, radius = 10f)
            row.addView(img)
            cover(img, song.thumbnailUrl, coverDp)
            val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            text.addView(label(song.title, if (compact) 14f else 16f, semi, look.fg, lines = 1).apply { gravity = Gravity.START })
            text.addView(label(song.artist, 12f, regular, look.muted, top = 2f, lines = 1).apply { gravity = Gravity.START })
            row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12f); rightMargin = dp(10f)
            })
            val heartSize = dp(if (compact) 22f else 26f)
            val heart = FrameLayout(context)
            val outline = ImageView(context).apply {
                setImageResource(R.drawable.ic_heart_outline)
                setColorFilter(look.muted)
            }
            val fillDrawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_heart_filled, null)!!.mutate().apply { setTint(look.fg) }
            val clip = android.graphics.drawable.ClipDrawable(fillDrawable, Gravity.BOTTOM, android.graphics.drawable.ClipDrawable.VERTICAL)
            clip.level = 0
            val fill = ImageView(context).apply { setImageDrawable(clip) }
            heart.addView(outline, LayoutParams(heartSize, heartSize))
            heart.addView(fill, LayoutParams(heartSize, heartSize))
            row.addView(heart, LinearLayout.LayoutParams(heartSize, heartSize))
            col.addView(row, matchWrap(if (i == 0) 18f else rowGap))
            val at = 600L + i * 260L
            riseIn(row, at, dist = 18f)
            // The heart fills from the bottom, then gives a small beat
            track(ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = at + 220L
                duration = 520
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    clip.level = (f * 10_000).toInt()
                    val beat = if (f > 0.75f) 1f + 0.22f * kotlin.math.sin((f - 0.75f) / 0.25f * Math.PI).toFloat() else 1f
                    heart.scaleX = beat; heart.scaleY = beat
                }
                start()
            })
        }
        RecapMoreMath.moreLine(all.size, RecapMoreMath.LIST_MAX)?.let {
            val m = label(it, 14f, semi, look.muted, top = 14f).apply { gravity = Gravity.START }
            col.addView(m)
            riseIn(m, 600L + shown.size * 260L + 400L)
        }
        return col
    }

    /** "Day 12 in a row": the Stats day streak, with the last week's days filling in. */
    private fun buildStreak(look: Look): View {
        val n = more.streakDays
        val col = column()
        col.addView(label("Day streak", 18f, regular, look.muted))
        val big = label("0", 96f, bold, look.fg, top = 2f, lines = 1)
        col.addView(big)
        grow(200L, 1_200L) { f -> big.text = "${(n * f + 0.5f).toInt().coerceIn(0, n)}" }
        val line = label(RecapMoreMath.streakLine(n), 24f, bold, look.fg, top = 4f, lines = 1)
        col.addView(line)
        wordsIn(line, line.text.toString(), 1_000L, perWord = 160L)
        val labels = (6 downTo 0).map { back ->
            var d = stats.day
            repeat(back) { d = Days.previous(d) }
            weekday(d, true).take(1)
        }
        val dots = WeekDots(context, labels, minOf(n, 7), look.fg, look.tone, look.muted, look.bg, semi)
        col.addView(dots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64f)).apply { topMargin = dp(28f) })
        grow(1_500L, 1_300L) { dots.grow = it }
        val rule = label("10 minutes or more of listening every day", 13f, regular, look.muted, top = 18f)
        col.addView(rule)
        riseIn(rule, 2_700L)
        return col
    }

    private fun buildRoast(look: Look): View {
        val col = column()
        val r = roast!!
        val a = label(r.first, 34f, bold, look.fg, lines = 3)
        col.addView(a)
        wordsIn(a, r.first, 200L, perWord = 140L)
        val b = label(r.second, 22f, regular, look.muted, top = 14f, lines = 2)
        col.addView(b)
        riseIn(b, 1_500L)
        return col
    }

    private fun buildPersona(look: Look): View {
        val col = column()
        val p = persona ?: RecapPersonality.pick(stats, day)!!
        col.addView(label("Your listening personality", 18f, regular, look.muted))
        val big = label(p.label, 48f, bold, look.fg, top = 14f, lines = 2)
        col.addView(big)
        scaleIn(big, 250L, from = 0.7f)
        val line = label(p.line, 17f, semi, look.fg, top = 14f, lines = 3)
        col.addView(line)
        riseIn(line, 1_000L)
        if (p.also.isNotEmpty()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            row.addView(label("also", 13f, regular, look.muted, lines = 1).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            for (t in p.also) {
                row.addView(TextView(context).apply {
                    text = t.label
                    textSize = 13f
                    typeface = semi
                    setTextColor(look.fg)
                    includeFontPadding = false
                    setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(16f).toFloat()
                        setColor(ColorUtils.blendARGB(look.bg, look.fg, 0.12f))
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8f) })
            }
            col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22f) })
            riseIn(row, 1_500L)
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
        val pills = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val share = pill("share", filled = true, look = look) { cardBitmap?.let(onShare) }
        val save = pill("save", filled = false, look = look) { cardBitmap?.let(onSave) }
        row.addView(share, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { rightMargin = dp(5f) })
        row.addView(save, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { leftMargin = dp(5f) })
        pills.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (day.ranked.isNotEmpty()) {
            val playlist = pill("save as playlist", filled = false, look = look) { onSavePlaylist() }
            pills.addView(playlist, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48f)).apply { topMargin = dp(10f) })
        }
        col.addView(pills, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16f)
        })
        cardImage = img
        cardPills = row
        val bmp = cardBitmap
        if (bmp != null) {
            img.setImageBitmap(bmp)
            scaleIn(img, 80L, from = 0.92f)
        } else row.alpha = 0.4f
        riseIn(pills, 350L, dist = 10f)
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
        motion.forEach { if (it.isPaused) it.resume() }
        progress.animate().alpha(1f).setDuration(150).start()
        dateLabel.animate().alpha(1f).setDuration(150).start()
    }

    private fun font(id: Int, fallback: Typeface): Typeface = try {
        ResourcesCompat.getFont(context, id) ?: fallback
    } catch (_: Exception) {
        fallback
    }

    /** 5.8.2: the day in hours: 24 bars growing up from the bottom, the peak hour solid and named. */
    private class HourChart(
        context: Context,
        private val hours: LongArray,
        private val peak: Int,
        private val fg: Int,
        private val muted: Int,
        private val labelFace: Typeface,
        private val smallFace: Typeface
    ) : View(context) {
        private val d = resources.displayMetrics.density
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val rect = RectF()
        var grow = 0f
            set(v) { field = v; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val max = hours.maxOrNull()?.takeIf { it > 0L } ?: return
            val labelH = 18f * d
            val topH = 22f * d
            val chartH = height - labelH - topH
            val gap = 3f * d
            val w = (width - gap * 23) / 24f
            val r = minOf(w / 2f, 4f * d)
            for (h in 0..23) {
                val left = h * (w + gap)
                val f = RecapMotion.barGrow(grow, h)
                val minH = 3f * d
                val full = if (hours[h] > 0L) maxOf(minH, chartH * hours[h] / max) else minH
                val bh = full * f
                rect.set(left, topH + chartH - bh, left + w, topH + chartH)
                bar.color = when {
                    h == peak -> fg
                    hours[h] > 0L -> ColorUtils.setAlphaComponent(fg, 110)
                    else -> ColorUtils.setAlphaComponent(fg, 34)
                }
                canvas.drawRoundRect(rect, r, r, bar)
                if (h == peak && f > 0.6f) {
                    text.typeface = labelFace
                    text.textSize = 12f * d
                    text.color = ColorUtils.setAlphaComponent(fg, ((f - 0.6f) / 0.4f * 255).toInt().coerceIn(0, 255))
                    val cx = (left + w / 2f).coerceIn(18f * d, width - 18f * d)
                    canvas.drawText(StatsFormat.hour(h), cx, rect.top - 6f * d, text)
                }
            }
            text.typeface = smallFace
            text.textSize = 11f * d
            text.color = muted
            for ((h, s) in listOf(0 to "12a", 6 to "6a", 12 to "12p", 18 to "6p")) {
                val cx = h * (w + gap) + w / 2f
                canvas.drawText(s, cx.coerceAtLeast(10f * d), height - 3f * d, text)
            }
        }
    }
    /** 5.8.3 u55: the day before and the day as two bars, each with its time at the end. */
    private class CompareBars(
        context: Context,
        private val nameA: String,
        private val msA: Long,
        private val nameB: String,
        private val msB: Long,
        private val fg: Int,
        private val muted: Int,
        private val track: Int,
        private val labelFace: Typeface,
        private val smallFace: Typeface
    ) : View(context) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        var grow = 0f
            set(v) { field = v; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val max = maxOf(msA, msB, 1L).toFloat()
            val nameW = 44f * d
            val valueW = 70f * d
            val barH = 14f * d
            val full = (width - nameW - valueW).coerceAtLeast(10f * d)
            val rows = listOf(Triple(nameA, msA, false), Triple(nameB, msB, true))
            for ((i, r) in rows.withIndex()) {
                val cy = height * (if (i == 0) 0.28f else 0.72f)
                text.typeface = if (r.third) labelFace else smallFace
                text.textSize = 14f * d
                text.color = if (r.third) fg else muted
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(r.first, 0f, cy + 5f * d, text)
                rect.set(nameW, cy - barH / 2f, nameW + full, cy + barH / 2f)
                paint.color = track
                canvas.drawRoundRect(rect, barH / 2f, barH / 2f, paint)
                val f = (grow * 1.15f - i * 0.15f).coerceIn(0f, 1f)
                val w = maxOf(barH, full * (r.second / max)) * f
                if (w > 0f) {
                    rect.set(nameW, cy - barH / 2f, nameW + w, cy + barH / 2f)
                    paint.color = if (r.third) fg else ColorUtils.blendARGB(track, fg, 0.55f)
                    canvas.drawRoundRect(rect, barH / 2f, barH / 2f, paint)
                }
                text.textAlign = Paint.Align.RIGHT
                text.color = if (r.third) fg else muted
                if (f > 0.5f) canvas.drawText(StatsFormat.duration(r.second), width.toFloat(), cy + 5f * d, text)
            }
        }
    }

    /** 5.8.3 u55: the day's 24 hours as a track, with the longest session filling in on it. */
    private class DayTimeline(
        context: Context,
        private val from: Float,
        private val to: Float,
        private val fg: Int,
        private val track: Int,
        private val muted: Int,
        private val smallFace: Typeface
    ) : View(context) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val rect = RectF()
        var grow = 0f
            set(v) { field = v; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val h = 16f * d
            val top = 8f * d
            rect.set(0f, top, width.toFloat(), top + h)
            paint.color = track
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
            val start = from * width
            val end = maxOf(start + h, to * width).coerceAtMost(width.toFloat())
            val now = start + (end - start) * grow
            if (grow > 0f) {
                rect.set(start.coerceAtMost(width - h), top, maxOf(now, start + h).coerceAtMost(width.toFloat()), top + h)
                paint.color = fg
                canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
            }
            text.typeface = smallFace
            text.textSize = 11f * d
            text.color = muted
            for ((frac, s) in listOf(0f to "12a", 0.25f to "6a", 0.5f to "12p", 0.75f to "6p", 1f to "12a")) {
                val cx = (frac * width).coerceIn(12f * d, width - 12f * d)
                canvas.drawText(s, cx, height - 4f * d, text)
            }
        }
    }

    /** 5.8.3 u55: the last 7 days as dots, the streak's days filling in one by one up to the recap day. */
    private class WeekDots(
        context: Context,
        private val labels: List<String>,
        private val filled: Int,
        private val fg: Int,
        private val track: Int,
        private val muted: Int,
        private val bg: Int,
        private val labelFace: Typeface
    ) : View(context) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        var grow = 0f
            set(v) { field = v; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val n = labels.size
            if (n == 0) return
            val step = width / n.toFloat()
            val r = minOf(step * 0.36f, 17f * d)
            val cy = r + 2f * d
            val firstOn = n - filled
            text.typeface = labelFace
            text.textSize = 12f * d
            for (i in 0 until n) {
                val cx = step * (i + 0.5f)
                paint.color = track
                canvas.drawCircle(cx, cy, r, paint)
                if (i >= firstOn && filled > 0) {
                    // Each streak day fills in turn, oldest first
                    val k = i - firstOn
                    val f = (grow * filled - k).coerceIn(0f, 1f)
                    if (f > 0f) {
                        paint.color = fg
                        canvas.drawCircle(cx, cy, r * (0.4f + 0.6f * f), paint)
                    }
                    text.color = if (f > 0.5f) bg else fg
                } else {
                    text.color = fg
                }
                canvas.drawText(labels[i], cx, cy + 4.5f * d, text)
            }
        }
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
