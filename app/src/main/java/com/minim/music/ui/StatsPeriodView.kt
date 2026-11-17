package com.minim.music.ui

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.minim.music.R
import com.minim.music.data.stats.ChannelItem
import com.minim.music.data.stats.RepriseClock
import com.minim.music.data.stats.StatsInsights
import com.minim.music.data.stats.StatsPeriods
import com.minim.music.data.stats.TopArtist
import com.minim.music.data.stats.TopSong
import com.minim.music.util.ArtworkPipeline
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * 6.9.0: the new Stats layout, built into view_stats.xml's stats_v2 slot. Period pills (Today,
 * Week, Month, All time) swap the data of one layout: the Monthly teaser (last 7 days of a month),
 * a hero with the total, the compare line and bars, the extra numbers, Top 5 songs, artists and
 * channels, a swipeable month heatmap and the insights. Only fills in views; MainActivity loads
 * the numbers. Every view carries Painter tags, so Light, Dark and Haze repaint it like the rest.
 */
class StatsPeriodView(
    private val host: LinearLayout,
    private val artwork: ArtworkPipeline,
    private val cb: Callbacks
) {
    class Callbacks(
        val onPeriod: (StatsPeriods.Period) -> Unit,
        val onPlaySong: (List<TopSong>, Int) -> Unit,
        val onArtist: (String) -> Unit,
        val onChannel: (ChannelItem) -> Unit,
        val onDay: (Int) -> Unit,
        val onHeatMonth: (YearMonth) -> Unit,
        val onTeaserDive: () -> Unit,
        val onTeaserCard: () -> Unit,
        val tick: (View) -> Unit,
        val softHaptic: (View) -> Unit
    )

    class Model(
        val period: StatsPeriods.Period,
        val total: String,
        val compare: String?,
        val bars: List<StatsPeriods.Bar>,
        val songs: List<TopSong>,
        val artists: List<TopArtist>,
        val channels: List<ChannelItem>,
        /** Small number boxes: label to value (songs, artists, tapes, day streak, peak hour). */
        val numbers: List<Pair<String, String>>,
        val insights: List<StatsInsights.Row>
    )

    private val ctx: Context = host.context
    private val d = ctx.resources.displayMetrics.density
    private val regular: Typeface = ResourcesCompat.getFont(ctx, R.font.poppins_regular) ?: Typeface.DEFAULT
    private val semi: Typeface = ResourcesCompat.getFont(ctx, R.font.poppins_semibold) ?: Typeface.DEFAULT_BOLD
    private val bold: Typeface = ResourcesCompat.getFont(ctx, R.font.poppins_bold) ?: Typeface.DEFAULT_BOLD
    private var pal: Pal = Pal.build(0, false)

    var period: StatsPeriods.Period = StatsPeriods.Period.TODAY
        private set
    private val pills = LinkedHashMap<StatsPeriods.Period, TextView>()

    private val teaserBox = FrameLayout(ctx)
    private val teaserBlur = ImageView(ctx)
    private val teaserSharp = ImageView(ctx)
    private val teaserRing = Ring(ctx)
    private val teaserTitle = text(18f, bold, "text:fg")
    private val teaserCountdown = text(13f, semi, "text:fg")
    private val teaserTease = text(12f, regular, "text:muted")
    private val teaserActions = LinearLayout(ctx)
    private var teaserPhase: RepriseClock.Phase? = null
    private val weeklyLine = text(12f, regular, "text:muted")

    private val hero = LinearLayout(ctx)
    private val heroTotal = text(34f, bold, "text:fg")
    private val heroCompare = text(12f, regular, "text:muted")
    private val barsBox = FrameLayout(ctx)
    private var bars: List<StatsPeriods.Bar> = emptyList()
    private val numbersBox = LinearLayout(ctx)
    private val listsBox = LinearLayout(ctx)
    private val heatTitle = text(15f, semi, "text:fg")
    private val heatBox = FrameLayout(ctx)
    private val insightsBox = LinearLayout(ctx)

    private var heatMonth: YearMonth = YearMonth.now()
    private var heatGrid: List<Int?> = emptyList()
    private var heatView: HeatGrid? = null
    private var model: Model? = null

    init {
        host.removeAllViews()
        host.addView(pillRow(), lp(top = 14f))
        buildTeaser()
        host.addView(teaserBox, lp(top = 14f))
        weeklyLine.visibility = View.GONE
        host.addView(weeklyLine, lp(top = 10f))
        hero.orientation = LinearLayout.VERTICAL
        hero.addView(heroTotal)
        hero.addView(heroCompare, lp(top = 2f))
        hero.addView(barsBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118f)).apply { topMargin = dp(12f) })
        host.addView(hero, lp(top = 16f))
        // 74: nothing to show until the first model lands, so no blank gap
        hero.visibility = View.GONE
        numbersBox.visibility = View.GONE
        numbersBox.orientation = LinearLayout.VERTICAL
        host.addView(numbersBox, lp(top = 14f))
        listsBox.orientation = LinearLayout.VERTICAL
        host.addView(listsBox, lp(top = 6f))
        host.addView(heatHeader(), lp(top = 22f))
        host.addView(heatBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(236f)).apply { topMargin = dp(8f) })
        insightsBox.orientation = LinearLayout.VERTICAL
        host.addView(insightsBox, lp(top = 12f))
        stylePills()
    }

    // ------------------------------------------------------------------ pills

    private fun pillRow(): View {
        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, p) in StatsPeriods.Period.values().withIndex()) {
            val chip = TextView(ctx).apply {
                text = p.label
                typeface = regular
                textSize = 12f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setPadding(dp(14f), 0, dp(14f), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { v ->
                    if (period == p) return@setOnClickListener
                    cb.tick(v)
                    select(p)
                    cb.onPeriod(p)
                }
            }
            pills[p] = chip
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30f)).apply { if (i > 0) marginStart = dp(8f) })
        }
        scroll.addView(row)
        return scroll
    }

    fun select(p: StatsPeriods.Period) {
        period = p
        stylePills()
    }

    private fun stylePills() {
        for ((p, chip) in pills) ChipStyle.apply(chip, p == period, pal)
    }

    /** Called with the app's palette (Light, Dark or Haze): Painter covers the tags, this the rest. */
    fun restyle(p: Pal) {
        pal = p
        Painter.paint(host, p)
        stylePills()
        model?.let { renderBars(it) }
        drawHeat()
        teaserRing.setColors(p.fg)
        styleTeaserFrame()
    }

    // ------------------------------------------------------------------ hero, lists, insights

    /** The month the heatmap shows (live refreshes reload this one, not the current month). */
    val shownHeatMonth: YearMonth get() = heatMonth

    private var modelSig: String? = null

    fun setModel(m: Model) {
        // 74: live refreshes land every few seconds; rows only rebuild when something changed
        val sig = signature(m)
        if (sig == modelSig && model != null) return
        modelSig = sig
        model = m
        if (m.period != period) select(m.period)
        hero.visibility = View.VISIBLE
        heroTotal.text = m.total
        heroCompare.text = m.compare.orEmpty()
        heroCompare.visibility = if (m.compare == null) View.GONE else View.VISIBLE
        renderBars(m)
        renderNumbers(m.numbers)
        numbersBox.visibility = if (m.numbers.isEmpty()) View.GONE else View.VISIBLE
        listsBox.removeAllViews()
        if (m.songs.isNotEmpty()) {
            listsBox.addView(section("Top songs"), lp(top = 16f))
            for ((i, s) in m.songs.withIndex()) {
                listsBox.addView(row(i + 1, s.title, "${s.artist} · ${plays(s.plays)}", s.thumbnailUrl, round = false) { v ->
                    cb.tick(v); cb.onPlaySong(m.songs, i)
                }, lp(top = 6f))
            }
        }
        if (m.artists.isNotEmpty()) {
            listsBox.addView(section("Top artists"), lp(top = 16f))
            for ((i, a) in m.artists.withIndex()) {
                listsBox.addView(row(i + 1, a.name, StatsPeriods.long(a.ms), null, round = true) { v ->
                    cb.tick(v); cb.onArtist(a.name)
                }, lp(top = 6f))
            }
        }
        if (m.channels.isNotEmpty()) {
            listsBox.addView(section("Top channels"), lp(top = 16f))
            for ((i, c) in m.channels.withIndex()) {
                val sub = if (c.tapes == 1) "1 tape · ${StatsPeriods.long(c.ms)}" else "${c.tapes} tapes · ${StatsPeriods.long(c.ms)}"
                listsBox.addView(row(i + 1, c.name, sub, null, round = true) { v ->
                    cb.tick(v); cb.onChannel(c)
                }, lp(top = 6f))
            }
        }
        insightsBox.removeAllViews()
        if (m.insights.isNotEmpty()) {
            insightsBox.addView(section("Insights"), lp(top = 10f))
            for (r in m.insights) insightsBox.addView(insightRow(r), lp(top = 6f))
        }
        listsBox.visibility = if (listsBox.childCount == 0) View.GONE else View.VISIBLE
        insightsBox.visibility = if (insightsBox.childCount == 0) View.GONE else View.VISIBLE
        Painter.paint(host, pal)
        stylePills()
    }

    private fun signature(m: Model): String = buildString {
        append(m.period).append('|').append(m.total).append('|').append(m.compare)
        for (b in m.bars) append('|').append(b.ms)
        for (x in m.songs) append('|').append(x.trackId).append(x.plays).append(':').append(x.ms)
        for (x in m.artists) append('|').append(x.name).append(x.ms)
        for (x in m.channels) append('|').append(x.name).append(x.ms)
        for (x in m.numbers) append('|').append(x.first).append(x.second)
        for (x in m.insights) append('|').append(x.label).append(x.value)
    }

    @SuppressLint("ClickableViewAccessibility") // taps pick a bar or a day; the row has its own buttons
    private fun renderBars(m: Model) {
        bars = m.bars
        barsBox.removeAllViews()
        barsBox.visibility = if (m.bars.isEmpty()) View.GONE else View.VISIBLE
        if (m.bars.isEmpty()) return
        val values = m.bars.map { it.ms }
        val busy = values.indices.maxByOrNull { values[it] }?.takeIf { values[it] > 0L } ?: -1
        val v = RepriseBars(ctx, m.bars.map { it.label }, values, busy, -1, pal.fg, pal.muted, regular, regular)
        v.setOnTouchListener { view, e ->
            if (e.action == MotionEvent.ACTION_UP && view.width > 0) {
                val i = (e.x * bars.size / view.width).toInt().coerceIn(0, bars.size - 1)
                val day = bars[i].day
                if (day != null && bars[i].ms > 0L) { cb.tick(view); cb.onDay(day) }
            }
            true
        }
        barsBox.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun renderNumbers(nums: List<Pair<String, String>>) {
        numbersBox.removeAllViews()
        for (chunk in nums.chunked(2)) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, n) in chunk.withIndex()) {
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                    background = rounded(14f)
                    tag = "fill:soft"
                }
                box.addView(text(11f, regular, "text:muted").apply { text = n.first })
                box.addView(text(18f, bold, "text:fg").apply { text = n.second; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(top = 2f))
                row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(10f) })
            }
            if (chunk.size == 1) row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10f) })
            numbersBox.addView(row, lp(top = if (numbersBox.childCount == 0) 0f else 10f))
        }
    }

    private fun section(title: String) = text(15f, semi, "text:fg").apply { text = title }

    private fun row(rank: Int, title: String, sub: String, art: String?, round: Boolean, onClick: (View) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(8f), dp(12f), dp(8f))
            background = rounded(14f)
            tag = "fill:soft"
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick(it) }
            contentDescription = "$rank. $title, $sub"
        }
        row.addView(text(13f, semi, "text:muted").apply { text = "$rank"; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(22f), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (art != null) {
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(8f)
                tag = "fill:soft"
                clipToOutline = true
            }
            row.addView(iv, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { marginStart = dp(6f) })
            if (art.isNotBlank()) artwork.loadInto(iv, art, dp(40f))
        } else {
            val initial = text(15f, bold, "text:fg").apply {
                text = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•"
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = if (round) GradientDrawable.OVAL else GradientDrawable.RECTANGLE; setColor(0x33808080) }
                tag = "fill:soft text:fg"
            }
            row.addView(initial, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { marginStart = dp(6f) })
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(14f, semi, "text:fg").apply { text = title; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(text(12f, regular, "text:muted").apply { text = sub; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(top = 1f))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12f) })
        return row
    }

    private fun insightRow(r: StatsInsights.Row): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = rounded(14f)
            tag = "fill:soft"
        }
        row.addView(text(11f, regular, "text:muted").apply { text = r.label })
        row.addView(text(14f, semi, "text:fg").apply { text = r.value; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, lp(top = 2f))
        return row
    }

    // ------------------------------------------------------------------ heatmap

    private fun heatHeader(): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val prev = text(18f, semi, "text:fg").apply { text = "‹"; setPadding(dp(10f), 0, dp(10f), 0); contentDescription = "Previous month" }
        val next = text(18f, semi, "text:fg").apply { text = "›"; setPadding(dp(10f), 0, dp(10f), 0); contentDescription = "Next month" }
        prev.setOnClickListener { v -> cb.tick(v); moveHeat(-1) }
        next.setOnClickListener { v -> cb.tick(v); moveHeat(1) }
        row.addView(heatTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(prev)
        row.addView(next)
        return row
    }

    private fun moveHeat(by: Int) {
        val target = heatMonth.plusMonths(by.toLong())
        if (target.isAfter(YearMonth.now())) return
        heatMonth = target
        cb.onHeatMonth(target)
    }

    private var heatTotals: Map<Int, Long> = emptyMap()

    /** One month of day totals for the heatmap (songs and tapes). */
    fun setHeat(ym: YearMonth, totals: Map<Int, Long>) {
        heatMonth = ym
        heatTotals = totals
        heatGrid = StatsPeriods.monthGrid(ym)
        val name = ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val title = if (ym.year != YearMonth.now().year) "$name ${ym.year}" else name
        heatTitle.text = title
        drawHeat()
    }

    @SuppressLint("ClickableViewAccessibility") // taps pick a bar or a day; the row has its own buttons
    private fun drawHeat() {
        if (heatGrid.isEmpty()) return
        heatBox.removeAllViews()
        val todayKey = todayKey()
        val max = heatTotals.values.maxOrNull() ?: 0L
        val levels = heatGrid.map { k -> if (k == null) -1 else StatsPeriods.heatLevel(heatTotals[k] ?: 0L, max) }
        val grid = HeatGrid(ctx, levels, pal.fg, pal.muted, regular)
        // 74: day numbers, today's outline, and future days faint
        grid.dayNumbers = HeatDays.numbers(heatGrid)
        grid.todayIndex = heatGrid.indexOf(todayKey)
        grid.future = heatGrid.map { k -> k != null && HeatDays.isFuture(k, todayKey) }
        grid.boldFace = bold
        val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tapHeat(grid.indexAt(e.x, e.y), grid)
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                if (Math.abs(vx) > Math.abs(vy) && Math.abs(dx) > 40f * d) { moveHeat(if (dx < 0) 1 else -1); return true }
                return false
            }
        })
        grid.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(false)
            gestures.onTouchEvent(e)
        }
        grid.contentDescription = heatTitle.text
        heatView = grid
        heatBox.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** A tap on heatmap slot [i]: opens that day's Reprise when it's not in the future and has listening. */
    internal fun tapHeat(i: Int, v: View? = heatView): Boolean {
        val day = heatGrid.getOrNull(i) ?: return false
        if (!HeatDays.tappable(day, todayKey(), heatTotals)) return false
        v?.let { cb.tick(it) }
        cb.onDay(day)
        return true
    }

    internal val heatGridView: HeatGrid? get() = heatView

    /** 74: for tests: the sections a pill fills in. */
    internal val heroSection: View get() = hero
    internal val heroText: TextView get() = heroTotal
    internal val barsSection: View get() = barsBox
    internal val listsSection: ViewGroup get() = listsBox
    internal val numbersSection: ViewGroup get() = numbersBox
    internal val insightsSection: ViewGroup get() = insightsBox

    internal val heatSlots: List<Int?> get() = heatGrid

    /** Today's day key; tests pin it. */
    internal var todayOverride: Int? = null

    private fun todayKey(): Int = todayOverride ?: com.minim.music.data.stats.Days.keyOf(System.currentTimeMillis())

    // ------------------------------------------------------------------ the Monthly teaser

    private fun buildTeaser() {
        teaserBox.visibility = View.GONE
        teaserBox.clipToOutline = true
        teaserBox.background = rounded(22f)
        val art = FrameLayout(ctx)
        for (iv in listOf(teaserSharp, teaserBlur)) {
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            art.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        // A shade over the mosaic so the words stay readable on any cover
        art.addView(View(ctx).apply { setBackgroundColor(0x66000000) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        art.tag = Painter.UNPAINTED
        teaserBox.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
            tag = Painter.UNPAINTED
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val words = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (t in listOf(teaserTitle, teaserCountdown, teaserTease)) { t.tag = null; t.setTextColor(0xFFFFFFFF.toInt()) }
        teaserTease.setTextColor(0xCCFFFFFF.toInt())
        words.addView(teaserTitle)
        words.addView(teaserCountdown, lp(top = 2f))
        top.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        teaserRing.setColors(0xFFFFFFFF.toInt())
        top.addView(teaserRing, LinearLayout.LayoutParams(dp(46f), dp(46f)).apply { marginStart = dp(12f) })
        col.addView(top)
        col.addView(teaserTease, lp(top = 12f))
        teaserActions.orientation = LinearLayout.HORIZONTAL
        teaserActions.addView(action("Dive in") { v -> cb.tick(v); cb.onTeaserDive() })
        teaserActions.addView(action("Card") { v -> cb.tick(v); cb.onTeaserCard() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34f)).apply { marginStart = dp(8f) })
        col.addView(teaserActions, lp(top = 14f))
        teaserBox.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        teaserBox.minimumHeight = dp(150f)
    }

    private fun action(label: String, onClick: (View) -> Unit) = TextView(ctx).apply {
        text = label
        typeface = semi
        textSize = 13f
        includeFontPadding = false
        gravity = Gravity.CENTER
        setPadding(dp(16f), 0, dp(16f), 0)
        setTextColor(0xFF111111.toInt())
        background = GradientDrawable().apply { cornerRadius = 999f; setColor(0xFFFFFFFF.toInt()) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34f))
        isClickable = true
        setOnClickListener { onClick(it) }
    }

    private fun styleTeaserFrame() {
        (teaserBox.background as? GradientDrawable)?.setColor(ColorUtils.setAlphaComponent(pal.fg, 0x1C))
    }

    /**
     * Shows [t] (or hides the card for HIDDEN / null). Going from a countdown to HERE while the card
     * is on screen unblurs the mosaic with a soft haptic.
     */
    fun setTeaser(t: RepriseClock.Teaser?, tease: String?) {
        if (t == null || t.phase == RepriseClock.Phase.HIDDEN) {
            teaserBox.visibility = View.GONE
            teaserPhase = t?.phase
            return
        }
        val was = teaserPhase
        teaserPhase = t.phase
        teaserBox.visibility = View.VISIBLE
        styleTeaserFrame()
        val here = t.phase == RepriseClock.Phase.HERE
        teaserTitle.text = t.title
        teaserCountdown.text = t.countdown
        teaserCountdown.visibility = if (t.countdown.isEmpty()) View.GONE else View.VISIBLE
        teaserTease.text = if (here) "Tap in before anyone else sees it." else tease.orEmpty()
        teaserTease.visibility = if (teaserTease.text.isNullOrEmpty() || t.phase == RepriseClock.Phase.LOCKED) View.GONE else View.VISIBLE
        teaserActions.visibility = if (here) View.VISIBLE else View.GONE
        teaserRing.progress = t.progress
        teaserRing.visibility = if (here) View.GONE else View.VISIBLE
        val dropNow = here && was != null && was != RepriseClock.Phase.HERE && was != RepriseClock.Phase.HIDDEN &&
            was != RepriseClock.Phase.LOCKED && teaserBox.isShown
        if (dropNow) {
            cb.softHaptic(teaserBox)
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 900L
                addUpdateListener { teaserBlur.alpha = it.animatedValue as Float }
                start()
            }
        } else {
            teaserBlur.alpha = if (here) 0f else 1f
        }
    }

    /** The month's top covers: [sharp] for after the drop, [blurred] (Haze's blur) before it. */
    fun setTeaserArt(sharp: Bitmap?, blurred: Bitmap?) {
        teaserSharp.setImageBitmap(sharp)
        teaserBlur.setImageBitmap(blurred)
    }

    fun setWeeklyLine(line: String?) {
        weeklyLine.text = line.orEmpty()
        weeklyLine.visibility = if (line.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ bits

    private fun plays(n: Int) = if (n == 1) "1 play" else "$n plays"

    private fun text(size: Float, face: Typeface, tag: String?) = TextView(ctx).apply {
        textSize = size
        typeface = face
        includeFontPadding = false
        this.tag = tag
    }

    private fun rounded(r: Float) = GradientDrawable().apply { cornerRadius = r * d; setColor(0x1C808080) }

    private fun lp(top: Float = 0f) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(v: Float) = (v * d).toInt()

    /** The teaser's ring: fills as the month goes on. */
    class Ring(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val rect = RectF()
        var progress = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }

        fun setColors(fg: Int) {
            track.color = ColorUtils.setAlphaComponent(fg, 0x40)
            arc.color = fg
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val sw = 4f * d
            track.strokeWidth = sw
            arc.strokeWidth = sw
            rect.set(sw, sw, width - sw, height - sw)
            canvas.drawOval(rect, track)
            canvas.drawArc(rect, -90f, 360f * progress, false, arc)
        }
    }
}

/** 6.9.0 (74): the heatmap's day numbers and which days a tap opens. Keys are yyyymmdd. */
object HeatDays {
    /** Day of the month per slot, 0 for a blank slot. */
    fun numbers(grid: List<Int?>): List<Int> = grid.map { k -> k?.rem(100) ?: 0 }

    fun isFuture(key: Int, todayKey: Int): Boolean = key > todayKey

    /** Only a day up to today with some listening opens its Reprise. */
    fun tappable(key: Int?, todayKey: Int, totals: Map<Int, Long>): Boolean =
        key != null && !isFuture(key, todayKey) && (totals[key] ?: 0L) > 0L
}
