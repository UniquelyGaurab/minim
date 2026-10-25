package com.minim.music.ui

import com.minim.music.data.update.AppUpdates
import com.minim.music.data.update.AvailableUpdate
import com.minim.music.data.update.UpdateCheckResult
import com.minim.music.data.update.UpdateFlowState
import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.MotionEvent
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minim.music.MinimApp
import com.minim.music.R
import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.DayStreak
import com.minim.music.data.stats.TodayLists
import com.minim.music.data.stats.RecapRules
import com.minim.music.data.stats.StatsFormat
import com.minim.music.data.local.AppPreferences
import com.minim.music.data.model.Album
import com.minim.music.data.model.ArtistPage
import com.minim.music.data.model.ArtistRef
import com.minim.music.data.model.LyricsData
import com.minim.music.data.model.PlaybackUiState
import com.minim.music.data.model.Playlist
import com.minim.music.data.model.SourceResult
import com.minim.music.data.model.Track
import com.minim.music.ui.adapter.GridTileAdapter
import com.minim.music.ui.adapter.ShelfSongAdapter
import com.minim.music.ui.adapter.formatArtistCount
import com.minim.music.data.source.MusicSourceAdapter
import com.minim.music.ui.adapter.QueueAdapter
import com.minim.music.ui.adapter.SearchResultsAdapter
import com.minim.music.ui.adapter.TrackAdapter
import com.minim.music.util.ArtworkPipeline
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Minim v4: one activity, every screen stacked in activity_main.
 *
 *  Tabs (one shown at a time): Home, Discover, Stats (v4.9, was Recents), Library. Swipe up on Home opens Discover.
 *  On top of them, in order: List (Liked Songs / a playlist), Search, the floating mini player,
 *  Now Playing, Lyrics, Queue, Settings.
 *
 * Every view is coloured from its android:tag by [Painter] with the current [Pal], which comes
 * from the cover art of the song playing (or the design greens) in the Light or Dark setting.
 */
class MainActivity : AppCompatActivity() {

    private val app: MinimApp by lazy { MinimApp.instance }

    private enum class Tab { HOME, DISCOVER, STATS, LIBRARY }

    // ---- small view groups that appear more than once -------------------------------------

    private class TopRow(val root: View) {
        val logo: View = root.findViewById(R.id.top_logo)
        val search: View = root.findViewById(R.id.top_search)
        val tabHome: View = root.findViewById(R.id.top_tab_home)
        val tabDiscover: View = root.findViewById(R.id.top_tab_discover)
        val tabStats: View = root.findViewById(R.id.top_tab_stats)
        val tabLibrary: View = root.findViewById(R.id.top_tab_library)
        val lineHome: View = root.findViewById(R.id.top_line_home)
        val lineDiscover: View = root.findViewById(R.id.top_line_discover)
        val lineStats: View = root.findViewById(R.id.top_line_stats)
        /** v4.9: "new" tag and recap dot next to "stats". */
        val statsNew: View = root.findViewById(R.id.top_stats_new)
        val statsDot: View = root.findViewById(R.id.top_stats_dot)
        val lineLibrary: View = root.findViewById(R.id.top_line_library)
        val gear: View = root.findViewById(R.id.top_gear)
    }

    /**
     * A mini player. The floating one keeps its buttons inside the pill; since v4.9.7 the Home one
     * has them in [actions], three circles just below the pill (the pill itself is cover + text).
     */
    private class Mini(val root: View, val actions: View = root) {
        val art: ImageView = root.findViewById(R.id.mini_art)
        val title: TextView = root.findViewById(R.id.mini_title)
        val artist: TextView = root.findViewById(R.id.mini_artist)
        val like: ImageView = actions.findViewById(R.id.mini_like)
        val play: ImageView = actions.findViewById(R.id.mini_play)
        val next: ImageView = actions.findViewById(R.id.mini_next)
    }

    private class Controls(root: View) {
        val progress: ProgressLine = root.findViewById(R.id.pc_progress)
        val elapsed: TextView = root.findViewById(R.id.pc_elapsed)
        val remaining: TextView = root.findViewById(R.id.pc_remaining)
        val like: ImageView = root.findViewById(R.id.pc_like)
        val prev: TextView = root.findViewById(R.id.pc_prev)
        val play: TextView = root.findViewById(R.id.pc_play)
        val next: TextView = root.findViewById(R.id.pc_next)
        val repeat: ImageView = root.findViewById(R.id.pc_repeat)
    }

    // ---- views -------------------------------------------------------------------------------

    private lateinit var root: ViewGroup
    private lateinit var screenHome: View
    private lateinit var screenStats: View
    private lateinit var statsScreen: StatsScreen
    // v4.9: Stats tab state
    private var todayStats: DayStats? = null
    private var recapStats: DayStats? = null
    private var statsDay = 0
    private var statsJob: Job? = null
    private val artistPhotoTried = HashSet<String>()
    private lateinit var screenDiscover: View
    private lateinit var screenShelf: View
    private lateinit var discoverRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var discoverMessage: TextView
    private lateinit var discoverWelcome: View
    private lateinit var discoverWelcomeSub: TextView
    private lateinit var shelfTitle: TextView
    private lateinit var shelfCount: TextView
    private lateinit var shelfAdapter: com.minim.music.ui.adapter.ShelfAdapter
    private lateinit var shelfGridAdapter: GridTileAdapter
    private var openShelf: com.minim.music.data.discover.Shelf? = null
    private var discoverShelves: List<com.minim.music.data.discover.Shelf> = emptyList()
    private var discoverJob: kotlinx.coroutines.Job? = null
    private lateinit var screenLibrary: View
    private lateinit var screenArtist: View
    /** v4.6.1: covers the status bar in the app's colour when a screen sits on the Artist page. */
    private lateinit var statusCover: View
    private lateinit var screenList: View
    private lateinit var screenSearch: View
    private lateinit var screenNowPlaying: View
    private lateinit var screenFsLyrics: FullscreenLyricsLayout
    private lateinit var fsLyrics: FullscreenLyrics
    private lateinit var npContent: View
    private lateinit var screenQueue: View
    private lateinit var screenSettings: View
    private lateinit var screenWelcome: View
    private lateinit var welcomePages: List<View>
    private lateinit var welcomeName: EditText
    private lateinit var welcomeGo: TextView
    private var welcomePage = 0
    private var welcomeAnimating = false
    private var welcomeJob: Job? = null
    private var welcomePillAnim: ValueAnimator? = null

    private lateinit var topRows: List<Pair<Tab, TopRow>>

    private lateinit var homeListening: View
    private lateinit var homeMini: Mini
    private lateinit var floatMini: Mini
    private lateinit var homeGreeting: TextView

    private lateinit var listTitle: TextView
    private lateinit var listSubtitle: TextView
    private lateinit var listEmpty: TextView

    private lateinit var searchInput: EditText
    private lateinit var searchStatus: TextView

    private lateinit var npArt: ImageView
    private lateinit var npLyric: NowPlayingLyricView
    private lateinit var npControlsRoot: View
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npControls: Controls


    private lateinit var queueEmpty: View

    private lateinit var settingsName: EditText
    private lateinit var setSleepStatus: TextView

    // ---- lists (adapters are in ui/adapter) --------------------------------------------------

    private lateinit var libraryAdapter: GridTileAdapter
    private lateinit var listAdapter: TrackAdapter
    private lateinit var searchAdapter: SearchResultsAdapter
    private lateinit var queueAdapter: QueueAdapter

    // v4.9.9 part 3: rows rising into place when a list gets a new set of rows
    private lateinit var searchEntrance: RowEntrance
    private lateinit var listEntrance: RowEntrance
    private lateinit var discoverEntrance: RowEntrance
    private lateinit var shelfGridEntrance: RowEntrance
    private lateinit var artistSongsEntrance: RowEntrance
    private lateinit var artistAlbumsEntrance: RowEntrance

    // ---- state -------------------------------------------------------------------------------

    private var tab = Tab.HOME
    private var pal: Pal = Pal.build(0, false)
    private var paletteSeed = 0
    private var paletteAnimator: ValueAnimator? = null
    /** v4.9.9: title and artist fade on a song change. */
    private val textFade = TextCrossfade { animationsOn() }

    private var renderedTrackId: String? = null
    private var lastErrorShown: String? = null
    private var isSeeking = false

    private var lyrics: LyricsData? = null
    private var activeLyric = -1

    private var lastQuery = ""
    private var searchJob: Job? = null
    private var searchDebounce: Job? = null
    private var searchGeneration = 0

    /** null = list closed, -1 = Liked Songs, otherwise a playlist id. */
    private var openListId: Long? = null
    private var openListName = ""
    private var recentTracks: List<Track> = emptyList()
    private var refreshJob: Job? = null

    private var sleepChoice = 0

    private var screenGestures: GestureDetector? = null

    // v4.1: rotating greetings, mini player pop in/out, pill width
    // v4.5: regular + fun (data-based) greetings, every second one fun when one fits
    private val greetingRotation = Greetings.Rotation()
    private var greetingFacts = GreetingFacts()
    private var greetingFactsAt = 0L
    private var greetingIndex = Greetings.TIME_BASED
    private var greetingJob: Job? = null
    private var greetingAnimating = false
    private var isActivityResumed = false
    private var wasStopped = false
    private var lastHasTrack: Boolean? = null
    private val hidingViews = HashSet<View>()
    private var pillWidthAnimator: ValueAnimator? = null
    private lateinit var homeSearch: View
    private lateinit var homeHint: TextView

    // v4.2: Now Playing lyric line, greeting tap, rotating tips
    // v4.9.8: what the lyric slot shows (loading / "Lyrics not available" / lines) and if it taps
    private var npLyricMode = NowPlayingLyricSlot.Mode.LOADING
    private var greetingOverride: Pair<String, String>? = null
    private var greetingOverrideJob: Job? = null
    private var greetingPlayTrackId: String? = null
    private var lastGreetingTapAt = 0L
    private val tipBag = Greetings.ShuffleBag(Tips.count)
    private var tipIndex = Tips.FIRST
    private var tipJob: Job? = null
    private var tipAnimating = false

    // ==========================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // v4.6.1: draw behind the status bar so the Artist page photo can reach the top of the
        // screen. Every other screen is kept below it by the root's padding (setupWindowInsets).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isStatusBarContrastEnforced = false
        // v4.7: on first launch the notification prompt waits until the welcome screens are done
        if (app.preferences.welcomeDone) requestNotificationPermissionIfNeeded()

        bindViews()
        setupTopRows()
        setupHome()
        setupSearch()
        setupLists()
        setupDiscover()
        setupArtist()
        setupSheets()
        setupWindowInsets()
        setupMiniPlayers()
        setupNowPlaying()
        setupLyrics()
        setupQueue()
        setupSettings()
        setupScreenGestures()
        setupBack()
        setupWelcome()
        setupStats()
        // v4.9.9: every tappable pill and circle shrinks a little when pressed (after the setups,
        // so views made clickable by their click listeners are included)
        PressScale.applyTree(findViewById(android.R.id.content))

        pal = appPal()
        applyPalette(pal, includeLists = true)
        showTab(Tab.HOME, animate = false)
        showTimeGreeting()
        showFirstTip()
        if (!app.preferences.welcomeDone) showWelcome()
        else if (savedInstanceState == null) playStartupAnimation()
        observe()
        // Hotfix 4.9.10: "Minim crashed last time" with a Copy button, once, after a crash
        CrashCard.showIfPending(this, pal)

        lifecycleScope.launch {
            app.database.refreshLikedCache()
            app.database.refreshNotInterestedCache()
            refreshData()
        }
    }

    override fun onStart() {
        super.onStart()
        if (::fsLyrics.isInitialized) fsLyrics.setHostVisible(true)
        // v4.9.9: the Now Playing lyric line's Flow frames too
        if (::npLyric.isInitialized) npLyric.setHostVisible(true)
        // v4.9.8: an app open (cold start, or the app back from the background, even into a new
        // MainActivity) may run a quiet update check; see onAppOpenedForUpdates
        app.foreground.takeOpen()?.let { onAppOpenedForUpdates(it) }
        // v4.9.6: new greetings from GitHub, about once a day (does nothing when not due)
        app.syncRemoteGreetings()
        // Coming back from the background counts as opening the app: time-based greeting first
        if (wasStopped) {
            wasStopped = false
            showTimeGreeting()
            showFirstTip()
            refreshStats()
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        syncGreetingRotation()
        // v4.9.6: back from the "Install unknown apps" setting with it turned on: carry on installing
        resumeUpdateInstallIfAllowed()
        checkRecapPopup()
    }

    override fun onPause() {
        isActivityResumed = false
        syncGreetingRotation()
        super.onPause()
    }

    override fun onStop() {
        wasStopped = true
        // v4.9.8 part 4: no Flow frames in the background
        if (::fsLyrics.isInitialized) fsLyrics.setHostVisible(false)
        if (::npLyric.isInitialized) npLyric.setHostVisible(false)
        // v4.9: save what's been heard so far right away (the app may be closed from here)
        app.playbackController.flushListening()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode changes are handled here (no restart): keep the app's own Light/Dark choice
        animatePaletteTo(appPal())
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        screenHome = findViewById(R.id.screen_home)
        screenStats = findViewById(R.id.screen_stats)
        statsScreen = StatsScreen(screenStats, app.artworkPipeline)
        screenDiscover = findViewById(R.id.screen_discover)
        screenShelf = findViewById(R.id.screen_shelf)
        screenLibrary = findViewById(R.id.screen_library)
        screenArtist = findViewById(R.id.screen_artist)
        statusCover = findViewById(R.id.status_cover)
        screenList = findViewById(R.id.screen_list)
        screenSearch = findViewById(R.id.screen_search)
        screenNowPlaying = findViewById(R.id.screen_now_playing)
        screenFsLyrics = findViewById(R.id.screen_fs_lyrics)
        npContent = findViewById(R.id.np_content)
        screenQueue = findViewById(R.id.screen_queue)
        screenSettings = findViewById(R.id.screen_settings)
        screenWelcome = findViewById(R.id.screen_welcome)

        topRows = listOf(
            Tab.HOME to TopRow(findViewById(R.id.home_top)),
            Tab.DISCOVER to TopRow(findViewById(R.id.discover_top)),
            Tab.STATS to TopRow(findViewById(R.id.stats_top)),
            Tab.LIBRARY to TopRow(findViewById(R.id.library_top))
        )

        homeListening = findViewById(R.id.home_listening)
        homeMini = Mini(findViewById(R.id.home_mini), findViewById(R.id.home_mini_actions))
        floatMini = Mini(findViewById(R.id.mini_player))
        homeGreeting = findViewById(R.id.home_greeting)
        homeSearch = findViewById(R.id.home_search)
        homeHint = findViewById(R.id.home_hint)

        listTitle = findViewById(R.id.list_title)
        listSubtitle = findViewById(R.id.list_subtitle)
        listEmpty = findViewById(R.id.list_empty)

        searchInput = findViewById(R.id.search_input)
        searchStatus = findViewById(R.id.search_status)

        npArt = findViewById(R.id.np_art)
        npLyric = findViewById(R.id.np_lyric)
        npControlsRoot = findViewById(R.id.np_controls)
        npTitle = findViewById(R.id.np_title)
        npArtist = findViewById(R.id.np_artist)
        npControls = Controls(findViewById(R.id.np_controls))

        queueEmpty = findViewById(R.id.queue_empty)

        settingsName = findViewById(R.id.settings_name)
        setSleepStatus = findViewById(R.id.set_sleep_status)

        // Round cover art: clip the image to its background shape (circle / rounded card)
        homeMini.art.clipToOutline = true
        floatMini.art.clipToOutline = true
        // Long titles scroll sideways (marquee) only when they don't fit; it needs "selected"
        for (mini in listOf(homeMini, floatMini)) {
            mini.title.isSelected = true
            mini.artist.isSelected = true
        }
        npArt.clipToOutline = true
    }

    // ==========================================================================================
    // Navigation
    // ==========================================================================================

    private val isSearchOpen get() = screenSearch.visibility == View.VISIBLE
    private val isListOpen get() = screenList.visibility == View.VISIBLE
    private val isShelfOpen get() = screenShelf.visibility == View.VISIBLE
    private val isArtistOpen get() = screenArtist.visibility == View.VISIBLE
    private val isNowPlayingOpen get() = screenNowPlaying.visibility == View.VISIBLE
    /** v4.9.8: the full screen lyrics (open or opening; a closing one counts as gone). */
    private val isLyricsOpen get() = ::fsLyrics.isInitialized && fsLyrics.isOpen
    private val isQueueOpen get() = screenQueue.visibility == View.VISIBLE
    private val isSettingsOpen get() = screenSettings.visibility == View.VISIBLE
    private val isWelcomeOpen get() = ::screenWelcome.isInitialized && screenWelcome.visibility == View.VISIBLE

    private fun setupTopRows() {
        for ((rowTab, row) in topRows) {
            // Home shows the "minim" logo; Recents and Library show a search pill instead
            row.logo.visibility = if (rowTab == Tab.HOME) View.VISIBLE else View.GONE
            row.search.visibility = if (rowTab == Tab.HOME) View.GONE else View.VISIBLE
            row.lineHome.visibility = if (rowTab == Tab.HOME) View.VISIBLE else View.INVISIBLE
            row.lineDiscover.visibility = if (rowTab == Tab.DISCOVER) View.VISIBLE else View.INVISIBLE
            row.lineStats.visibility = if (rowTab == Tab.STATS) View.VISIBLE else View.INVISIBLE
            row.lineLibrary.visibility = if (rowTab == Tab.LIBRARY) View.VISIBLE else View.INVISIBLE

            row.tabHome.setOnClickListener { switchTabByHand(it, Tab.HOME) }
            row.tabDiscover.setOnClickListener { switchTabByHand(it, Tab.DISCOVER) }
            row.tabStats.setOnClickListener { switchTabByHand(it, Tab.STATS) }
            row.tabLibrary.setOnClickListener { switchTabByHand(it, Tab.LIBRARY) }
            row.gear.setOnClickListener { openSettings() }
            row.search.setOnClickListener { openSearch() }
        }
        fitTopRows()
    }

    /**
     * v4.3: four tabs no longer fit every phone at the design size. Steps the gaps, tab text and
     * left area down (same step for every row, so the tab underline still lines up) until the
     * row fits the screen. On wide phones nothing changes.
     */
    private fun fitTopRows() {
        data class Fit(val gap: Int, val sp: Float, val frame: Int, val logoStart: Int, val pillStart: Int, val pillText: Boolean)
        val levels = listOf(
            Fit(20, 15f, 104, 34, 22, true),
            Fit(16, 15f, 100, 32, 18, true),
            Fit(14, 14f, 94, 28, 12, true),
            Fit(12, 13.5f, 90, 24, 8, true),
            Fit(10, 13f, 80, 22, 22, false),
            Fit(8, 12f, 74, 18, 18, false)
        )
        val avail = resources.displayMetrics.widthPixels
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        for (fit in levels) {
            var needed = 0
            for ((_, row) in topRows) {
                val frame = row.search.parent as View
                frame.layoutParams = frame.layoutParams.apply { width = dp(fit.frame.toFloat()).toInt() }
                (row.logo.layoutParams as ViewGroup.MarginLayoutParams).marginStart = dp(fit.logoStart.toFloat()).toInt()
                (row.search.layoutParams as ViewGroup.MarginLayoutParams).marginStart = dp(fit.pillStart.toFloat()).toInt()
                (row.search as? ViewGroup)?.getChildAt(1)?.visibility = if (fit.pillText) View.VISIBLE else View.GONE
                for (t in listOf(row.tabHome, row.tabDiscover, row.tabStats, row.tabLibrary)) {
                    // v4.9: the Stats tab's text sits in a row with its "new" tag / dot
                    val first = (t as? ViewGroup)?.getChildAt(0)
                    ((first as? TextView) ?: ((first as? ViewGroup)?.getChildAt(0) as? TextView))?.textSize = fit.sp
                    if (t !== row.tabHome) (t.layoutParams as ViewGroup.MarginLayoutParams).marginStart = dp(fit.gap.toFloat()).toInt()
                }
                row.root.requestLayout()
                row.root.measure(unspecified, unspecified)
                needed = maxOf(needed, row.root.measuredWidth)
            }
            if (needed + dp(12f) <= avail) break
        }
        fitTabLines()
    }

    /** The label of a tab (Stats keeps its text in a row with the "new" tag and the dot). */
    private fun tabLabel(t: View): TextView? {
        val first = (t as? ViewGroup)?.getChildAt(0)
        return (first as? TextView) ?: ((first as? ViewGroup)?.getChildAt(0) as? TextView)
    }

    /**
     * v4.9.9: each underline is as wide as its tab's word (was a fixed 18dp dash), so it resizes as
     * it glides between tabs. Measured from the text, so it is right before a screen is ever shown.
     * Lines sit at the start of their tab, under the word (Stats' tag and dot stay out of it).
     */
    private fun fitTabLines() {
        for ((_, row) in topRows) {
            for (t in Tab.values()) {
                val label = tabLabel(tabViewOf(row, t)) ?: continue
                val w = kotlin.math.ceil(
                    android.text.Layout.getDesiredWidth(label.text ?: "", label.paint)
                ).toInt() + label.paddingLeft + label.paddingRight
                val line = lineOf(row, t)
                val lp = line.layoutParams as android.widget.LinearLayout.LayoutParams
                if (w > 0 && (lp.width != w || lp.gravity != android.view.Gravity.START)) {
                    lp.width = w
                    lp.gravity = android.view.Gravity.START
                    line.layoutParams = lp
                }
            }
        }
    }

    /** A tab tap (or swipe-up): a light tick when the tab really changes. */
    private fun switchTabByHand(v: View, target: Tab) {
        if (target != tab) haptic(v, HapticFeedbackConstants.CLOCK_TICK)
        showTab(target)
    }

    private fun showTab(target: Tab, animate: Boolean = true) {
        if (target == Tab.STATS) onStatsShown()
        val from = tab
        tab = target
        // v4.9.9: the old screen stays up while it slides out (see animateTabChange)
        val slide = animate && from != target && animationsOn() && root.isShown
        val leaving = if (slide) screenOf(from) else null
        for (t in Tab.values()) {
            val s = screenOf(t)
            val v = if (t == target || s === leaving) View.VISIBLE else View.GONE
            if (s.visibility != v) s.visibility = v
        }
        if (target == Tab.HOME) renderGreeting(animate = false)
        if (target != Tab.HOME) lifecycleScope.launch { refreshData() }
        if (target == Tab.DISCOVER) loadDiscover(force = false)
        if (slide) animateTabChange(from, target) else stopTabTransition()
        updateChrome()
    }

    private fun rowOf(t: Tab): TopRow = topRows.first { it.first == t }.second

    private fun tabViewOf(row: TopRow, t: Tab): View = when (t) {
        Tab.HOME -> row.tabHome
        Tab.DISCOVER -> row.tabDiscover
        Tab.STATS -> row.tabStats
        Tab.LIBRARY -> row.tabLibrary
    }

    private fun lineOf(row: TopRow, t: Tab): View = when (t) {
        Tab.HOME -> row.lineHome
        Tab.DISCOVER -> row.lineDiscover
        Tab.STATS -> row.lineStats
        Tab.LIBRARY -> row.lineLibrary
    }

    private fun screenOf(t: Tab): View = when (t) {
        Tab.HOME -> screenHome
        Tab.DISCOVER -> screenDiscover
        Tab.STATS -> screenStats
        Tab.LIBRARY -> screenLibrary
    }

    // ------------------------------------------------------------------------------------------
    // v4.9.9: tab switch motion. The new screen's content slides in a little from the side you're
    // moving towards and fades in; the old screen's content slides the other way and fades out;
    // the underline glides to the new tab and takes its width. One animator drives both screens,
    // so a quick second tap turns everything around from where it is instead of running twice.
    //
    // While it runs the old screen sits below everything (negative Z, so it gets no touches) and
    // the new screen's background is see-through; the root behind both has the same colour.
    // ------------------------------------------------------------------------------------------

    private class TabSlide(
        val view: View,
        val a0: Float, val a1: Float,
        val x0: Float, val x1: Float,
        val ms: Long
    )

    private var tabAnim: ValueAnimator? = null
    private var tabOut: ViewGroup? = null
    private var tabIn: ViewGroup? = null
    private var tabSlides: List<TabSlide> = emptyList()
    private val tabEase = DecelerateInterpolator()

    private fun topRowIdOf(t: Tab): Int = when (t) {
        Tab.HOME -> R.id.home_top
        Tab.DISCOVER -> R.id.discover_top
        Tab.STATS -> R.id.stats_top
        Tab.LIBRARY -> R.id.library_top
    }

    /** The children of a tab screen that move: everything but its top row and views popping out. */
    private fun slidingChildren(screen: ViewGroup, t: Tab): List<View> {
        val top = topRowIdOf(t)
        val out = ArrayList<View>(screen.childCount)
        for (i in 0 until screen.childCount) {
            val c = screen.getChildAt(i)
            if (c.id == top || c.visibility != View.VISIBLE || c in hidingViews) continue
            out.add(c)
        }
        return out
    }

    private fun animateTabChange(from: Tab, to: Tab) {
        glideTabLine(from, to)

        val outScreen = screenOf(from) as? ViewGroup
        val inScreen = screenOf(to) as? ViewGroup
        if (outScreen == null || inScreen == null) {
            stopTabTransition()
            outScreen?.visibility = View.GONE
            return
        }
        val reversing = tabOut === inScreen
        // Stop the running switch where it is (the views keep their in-between alpha and offset)
        tabAnim?.let { a -> tabAnim = null; a.cancel() }
        tabSlides = emptyList()
        // A screen that was still sliding out and isn't coming back goes away now
        tabOut?.let { if (it !== inScreen && it !== outScreen) endTabOut(it) }
        tabIn?.let { if (it !== inScreen) restoreTabBackground(it) }
        if (reversing) liftTabOut(inScreen)
        tabOut = null
        tabIn = null

        val shift = dp(TabMotion.SHIFT_DP)
        val fromI = from.ordinal
        val toI = to.ordinal
        val slides = ArrayList<TabSlide>()
        // The top artist takeover's photo sits under Home: there the old screen just goes (as before)
        val keepOld = takeoverRoot.visibility != View.VISIBLE
        if (keepOld) {
            for (c in slidingChildren(outScreen, from)) {
                val end = TabMotion.outgoingEnd(fromI, toI, shift)
                slides.add(TabSlide(c, c.alpha, 0f, c.translationX, end, TabMotion.remainingMs(TabMotion.OUT_MS, c.alpha, 0f)))
            }
            sinkTabOut(outScreen)
            tabOut = outScreen
            inScreen.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            tabIn = inScreen
        } else {
            endTabOut(outScreen)
        }
        for (c in slidingChildren(inScreen, to)) {
            c.animate().cancel()
            if (!reversing) {
                c.translationX = TabMotion.incomingStart(fromI, toI, shift)
                c.alpha = 0f
            }
            slides.add(TabSlide(c, c.alpha, 1f, c.translationX, 0f, TabMotion.remainingMs(TabMotion.IN_MS, c.alpha, 1f)))
        }
        if (slides.isEmpty()) {
            stopTabTransition()
            return
        }
        tabSlides = slides
        val total = slides.maxOf { it.ms }.coerceAtLeast(1L)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                if (tabAnim !== it) return@addUpdateListener
                val elapsed = it.animatedFraction * total
                for (sl in tabSlides) {
                    val k = tabEase.getInterpolation(if (sl.ms <= 0L) 1f else (elapsed / sl.ms).coerceIn(0f, 1f))
                    sl.view.alpha = sl.a0 + (sl.a1 - sl.a0) * k
                    sl.view.translationX = sl.x0 + (sl.x1 - sl.x0) * k
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled && tabAnim === animation) stopTabTransition()
                }
            })
        }
        tabAnim = anim
        anim.start()
    }

    /** Ends any tab switch at once: the old screen gone, the new one settled with its background. */
    private fun stopTabTransition() {
        tabAnim?.let { a -> tabAnim = null; a.cancel() }
        val current = screenOf(tab)
        for (sl in tabSlides) {
            if (sl.view.parent === current) {
                sl.view.alpha = 1f
                sl.view.translationX = 0f
            }
        }
        tabSlides = emptyList()
        tabOut?.let { if (it !== current) endTabOut(it) else liftTabOut(it) }
        tabOut = null
        tabIn?.let { restoreTabBackground(it) }
        tabIn = null
    }

    /** The old screen goes below everything while it slides out (no shadow, no touches). */
    private fun sinkTabOut(screen: ViewGroup) {
        screen.outlineProvider = null
        screen.translationZ = -1f
        // Its top row (and the old underline) would show under the new one's
        topRowIn(screen)?.visibility = View.INVISIBLE
    }

    private fun liftTabOut(screen: ViewGroup) {
        screen.translationZ = 0f
        screen.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        topRowIn(screen)?.visibility = View.VISIBLE
    }

    private fun topRowIn(screen: ViewGroup): View? = topRows.firstOrNull { it.second.root.parent === screen }?.second?.root

    /** A screen that finished sliding out: hidden, its content put back for next time. */
    private fun endTabOut(screen: ViewGroup) {
        liftTabOut(screen)
        if (screen !== screenOf(tab)) screen.visibility = View.GONE
        for (i in 0 until screen.childCount) {
            val c = screen.getChildAt(i)
            if (c in hidingViews) continue
            c.alpha = 1f
            c.translationX = 0f
        }
    }

    private fun restoreTabBackground(screen: ViewGroup) {
        if (screen === screenHome && isTakeoverActive) applyTakeoverColors()
        else screen.setBackgroundColor(pal.bg)
    }

    /**
     * The new tab's underline starts where the old one is drawn right now (mid-glide after a quick
     * tap) and glides to its own place, stretching or shrinking to the new tab's width.
     */
    private fun glideTabLine(from: Tab, to: Tab) {
        // Every screen has the same top row, so the old row tells where both lines sit
        val fromRow = rowOf(from)
        val oldLine = lineOf(fromRow, from)
        val target = lineOf(fromRow, to)
        val line = lineOf(rowOf(to), to)
        if (oldLine.width <= 0 || target.width <= 0) return
        fun centre(l: View): Float = (l.parent as View).left + l.left + l.width / 2f
        val (dx, scale) = TabMotion.lineStart(
            centre(oldLine) + oldLine.translationX, oldLine.width * oldLine.scaleX,
            centre(target), target.width.toFloat()
        )
        // The old line stays put on its own screen for the next switch
        oldLine.animate().cancel()
        oldLine.translationX = 0f
        oldLine.scaleX = 1f
        line.animate().cancel()
        line.translationX = dx
        line.scaleX = scale
        line.animate().translationX(0f).scaleX(1f).setStartDelay(0L).setDuration(TabMotion.LINE_MS)
            .setInterpolator(DecelerateInterpolator(1.5f)).start()
    }

    /** Shows the floating mini player only where it belongs (Search, Library, Recents, lists). */
    private fun updateChrome() {
        val hasTrack = app.playbackController.uiState.value.currentTrack != null
        val coveredByPlayer = isNowPlayingOpen || isLyricsOpen || isQueueOpen || isSettingsOpen
        val onScreenThatFloats = isSearchOpen || isListOpen || isShelfOpen || isArtistOpen || tab != Tab.HOME
        // Pop in when playback starts and out when it stops; other changes (navigation) are instant
        val playbackChanged = lastHasTrack != null && lastHasTrack != hasTrack
        lastHasTrack = hasTrack
        setShown(floatMini.root, hasTrack && !coveredByPlayer && onScreenThatFloats, playbackChanged)
        setShown(homeListening, hasTrack, playbackChanged)
        setShown(homeMini.root, hasTrack, playbackChanged)
        setShown(homeMini.actions, hasTrack, playbackChanged)
        syncGreetingRotation()
        checkRecapPopup()
    }

    private fun setShown(v: View, show: Boolean, animate: Boolean) {
        if (show) {
            if (hidingViews.remove(v)) {
                v.animate().cancel()
                resetPop(v)
            }
            if (v.visibility == View.VISIBLE) return
            v.visibility = View.VISIBLE
            if (animate && animationsOn()) {
                v.alpha = 0f
                v.scaleX = 0.85f
                v.scaleY = 0.85f
                v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0L).setDuration(260L)
                    .setInterpolator(OvershootInterpolator(1.2f)).start()
            } else {
                resetPop(v)
            }
        } else {
            if (v in hidingViews || v.visibility != View.VISIBLE) return
            if (animate && animationsOn()) {
                hidingViews.add(v)
                v.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setStartDelay(0L).setDuration(200L)
                    .setInterpolator(AccelerateInterpolator()).withEndAction { finishHide(v) }.start()
                // Safety net in case something else cancels the animation
                v.postDelayed({ finishHide(v) }, 320L)
            } else {
                v.animate().cancel()
                v.visibility = View.GONE
                resetPop(v)
            }
        }
    }

    private fun finishHide(v: View) {
        if (!hidingViews.remove(v)) return
        v.visibility = View.GONE
        resetPop(v)
    }

    private fun resetPop(v: View) {
        v.alpha = 1f
        v.scaleX = 1f
        v.scaleY = 1f
    }

    private fun animationsOn(): Boolean = ValueAnimator.areAnimatorsEnabled()

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    recapStory != null -> closeRecapStory()
                    ::sheetRoot.isInitialized && sheetRoot.visibility == View.VISIBLE -> closeSheet()
                    isWelcomeOpen && welcomePage == 2 -> showWelcomePage(1)
                    isWelcomeOpen && welcomePage == 3 -> Unit // on its way to Home by itself
                    isSettingsOpen -> closeSettings()
                    isQueueOpen -> closeQueue()
                    isLyricsOpen -> closeLyrics()
                    isNowPlayingOpen -> collapseNowPlaying()
                    isSearchOpen -> closeSearch()
                    isListOpen -> closeList()
                    isShelfOpen -> closeShelf()
                    isArtistOpen -> closeArtist()
                    tab != Tab.HOME -> showTab(Tab.HOME)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    // ==========================================================================================
    // v4.7: Welcome screens (first launch only)
    // ==========================================================================================

    private fun setupWelcome() {
        welcomePages = listOf(
            findViewById(R.id.welcome_page_1),
            findViewById(R.id.welcome_page_2),
            findViewById(R.id.welcome_page_3)
        )
        welcomeName = findViewById(R.id.welcome_name)
        welcomeGo = findViewById(R.id.welcome_go)
        fitWelcomeHeadlines()
        findViewById<View>(R.id.welcome_next).setOnClickListener {
            if (welcomeAnimating || welcomePage != 1) return@setOnClickListener
            haptic(it)
            showWelcomePage(2)
        }
        welcomeGo.setOnClickListener {
            if (welcomeAnimating || welcomePage != 2) return@setOnClickListener
            haptic(it)
            finishWelcomeName()
        }
        welcomeName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                if (!welcomeAnimating && welcomePage == 2) finishWelcomeName()
                true
            } else {
                false
            }
        }
        // "skip" while the box is empty, "start vibing" once there's a name; the pill grows to fit
        welcomeName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val hasName = !s?.toString()?.trim().isNullOrEmpty()
                setWelcomePill(getString(if (hasName) R.string.welcome_start else R.string.welcome_skip))
            }
        })
    }

    /**
     * Big headlines like the Canva designs (about 40sp on a normal phone), a little smaller on
     * narrow or short screens so no line ever wraps or runs off the bottom.
     */
    private fun fitWelcomeHeadlines() {
        val m = resources.displayMetrics
        val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 40f, m)
        val width = m.widthPixels - dp(72f)
        // Longest lines: "No Log-ins." is about 5.8 font sizes wide, "We'll build" about 5.3
        val byWidth = width / 6.2f
        // Screen 3 has 9 lines at about 1.2 font sizes each
        val byHeight = (m.heightPixels - dp(180f)) / 11f
        val size = minOf(maxPx, byWidth, byHeight).coerceAtLeast(dp(22f))
        for (id in listOf(R.id.welcome_title_1, R.id.welcome_title_2, R.id.welcome_title_3)) {
            findViewById<TextView>(id).setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    private fun showWelcome() {
        screenWelcome.visibility = View.VISIBLE
        screenWelcome.alpha = 1f
        welcomePage = 0
        welcomePages.forEach { it.visibility = View.GONE }
        showWelcomePage(1)
        updateSystemBars()
        updateChrome()
    }

    /** Pages change with the Home greeting's animation: out and up, then in from below. */
    private fun showWelcomePage(page: Int) {
        val from = welcomePages.getOrNull(welcomePage - 1)
        val to = welcomePages[page - 1]
        welcomePage = page
        if (page != 2) hideKeyboard()
        if (!animationsOn()) {
            welcomePages.forEach { it.visibility = View.GONE }
            to.visibility = View.VISIBLE
            onWelcomePageShown(page)
            return
        }
        val shift = dp(14f)
        welcomeAnimating = true
        fun enter() {
            to.visibility = View.VISIBLE
            to.alpha = 0f
            to.translationY = shift
            to.animate().alpha(1f).translationY(0f).setStartDelay(0L).setDuration(260L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    welcomeAnimating = false
                    onWelcomePageShown(page)
                }.start()
        }
        if (from == null || from === to) {
            enter()
        } else {
            from.animate().cancel()
            from.animate().alpha(0f).translationY(-shift).setStartDelay(0L).setDuration(200L)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    from.visibility = View.GONE
                    from.alpha = 1f
                    from.translationY = 0f
                    enter()
                }.start()
        }
    }

    private fun onWelcomePageShown(page: Int) {
        if (page == 3) {
            // Screen 3 has no button: Home after 3 seconds
            welcomeJob?.cancel()
            welcomeJob = lifecycleScope.launch {
                delay(WELCOME_AUTO_MS)
                finishWelcome()
            }
        }
    }

    /** Screen 2's pill: save the name if one was typed ("skip" saves nothing), then screen 3. */
    private fun finishWelcomeName() {
        val name = welcomeName.text?.toString()?.trim().orEmpty()
        if (name.isNotEmpty()) app.preferences.userName = name
        hideKeyboard()
        welcomeName.clearFocus()
        // From here on it never shows again, even if the app is closed on screen 3
        app.preferences.welcomeDone = true
        showWelcomePage(3)
    }

    /** Fades the welcome screens away, revealing Home with the time-based greeting. */
    private fun finishWelcome() {
        if (!isWelcomeOpen) return
        welcomeJob = null
        fun done() {
            screenWelcome.visibility = View.GONE
            screenWelcome.alpha = 1f
            showTimeGreeting()
            updateSystemBars()
            updateChrome()
            requestNotificationPermissionIfNeeded()
        }
        if (animationsOn()) {
            screenWelcome.animate().alpha(0f).setStartDelay(0L).setDuration(300L)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    done()
                    playStartupAnimation()
                }.start()
        } else {
            done()
        }
    }

    /** New pill text; the pill's width animates from the old text's size to the new one's. */
    private fun setWelcomePill(text: String) {
        val pill = welcomeGo
        if (pill.text.toString() == text) return
        val from = pill.width
        pill.text = text
        if (!animationsOn() || from == 0 || !pill.isShown) return
        pill.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(pill.height, View.MeasureSpec.EXACTLY)
        )
        val to = pill.measuredWidth
        welcomePillAnim?.cancel()
        val lp = pill.layoutParams
        lp.width = from
        pill.layoutParams = lp
        pill.setTextColor(0x00FFFFFF)
        welcomePillAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                pill.layoutParams = pill.layoutParams.also { p -> p.width = (from + (to - from) * t).roundToInt() }
                // The words fade in as the pill reaches their size
                val alpha = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
                pill.setTextColor(((alpha * 255).roundToInt() shl 24) or 0xFFFFFF)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    pill.layoutParams = pill.layoutParams.also { p -> p.width = ViewGroup.LayoutParams.WRAP_CONTENT }
                    pill.setTextColor(0xFFFFFFFF.toInt())
                }
            })
            start()
        }
    }

    private fun setupScreenGestures() {
        // Swipe up: Home -> Discover (v4.3.1, was Recents). v4.9.8: does nothing on Now Playing
        // (lyrics open from the lyric line, Up next from its pill).
        screenGestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                if (isSeeking) return false
                val dy = e2.y - start.y
                val dx = e2.x - start.x
                val isSwipeUp = dy < -dp(70f) && abs(dy) > abs(dx) * 1.5f && abs(velocityY) > 500f
                if (!isSwipeUp) return false
                if (isWelcomeOpen) return false
                if (recapStory != null || recapStoryOpening) return false
                if (::sheetRoot.isInitialized && sheetRoot.visibility == View.VISIBLE) return false
                when {
                    isSettingsOpen || isQueueOpen || isLyricsOpen || isNowPlayingOpen -> return false
                    isSearchOpen || isListOpen || isShelfOpen || isArtistOpen -> return false
                    tab == Tab.HOME -> switchTabByHand(homeHint, Tab.DISCOVER)
                    else -> return false
                }
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        screenGestures?.onTouchEvent(ev)
        // v4.9.3: a drag or scroll mid-takeover fades it out early
        if (isTakeoverActive) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { takeoverDownX = ev.rawX; takeoverDownY = ev.rawY }
                MotionEvent.ACTION_MOVE -> {
                    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop * 2
                    if (abs(ev.rawX - takeoverDownX) > slop || abs(ev.rawY - takeoverDownY) > slop) endTakeoverEarly()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ==========================================================================================
    // Home
    // ==========================================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHome() {
        setupTakeover()
        homeSearch.setOnClickListener { openSearch() }
        // The tip opens what it talks about: Discover for the swipe-up and Discover tips, Settings for Settings tips
        homeHint.setOnClickListener {
            when {
                tipIndex == Tips.SWIPE_UP_DISCOVER -> switchTabByHand(it, Tab.DISCOVER)
                tipIndex == Tips.OPEN_DISCOVER -> switchTabByHand(it, Tab.DISCOVER)
                tipIndex in Tips.settingsTips -> openSettings()
            }
        }

        // Tap the greeting: a random Liked song. Press feedback is a soft shrink, no ripple box.
        homeGreeting.isClickable = true
        homeGreeting.setOnClickListener { onGreetingTapped(it) }
        homeGreeting.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.96f).scaleY(0.96f).setStartDelay(0L).setDuration(90L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setStartDelay(0L).setDuration(160L)
                        .setInterpolator(DecelerateInterpolator()).start()
            }
            false
        }
    }

    // ---- v4.2: greeting tap ------------------------------------------------------------------

    private fun onGreetingTapped(v: View) {
        // v4.9.3: during the takeover the greeting is part of it: open the artist's page
        if (isTakeoverActive) {
            haptic(v)
            openTakeoverArtist()
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastGreetingTapAt < 700L) return
        lastGreetingTapAt = now
        haptic(v, HapticFeedbackConstants.CONTEXT_CLICK)
        lifecycleScope.launch {
            val liked = try {
                app.database.getLikedTracks()
            } catch (_: Exception) {
                emptyList()
            }
            if (liked.isEmpty()) {
                val name = app.preferences.userName
                showGreetingMessage("Like a few songs", if (name.isNotEmpty()) "first, $name." else "first.", null)
                return@launch
            }
            // Liked Songs shuffled, starting with the random pick
            val queue = liked.shuffled()
            val pick = queue.first()
            try {
                app.playbackController.playTrack(pick, queue)
            } catch (_: Exception) {
                endGreetingMessage()
                return@launch
            }
            showGreetingMessage("Playing", pick.title, pick.id)
            // The player never picked it up (no connection to the player yet): put the greeting back
            delay(2_500L)
            if (greetingPlayTrackId == pick.id &&
                app.playbackController.uiState.value.currentTrack?.id != pick.id
            ) {
                endGreetingMessage()
            }
        }
    }

    /** Shows a two-line message in the greeting's place for 5 s, then the rotation carries on. */
    private fun showGreetingMessage(l1: String, l2: String, playingTrackId: String?) {
        greetingOverrideJob?.cancel()
        greetingOverride = l1 to l2
        greetingPlayTrackId = playingTrackId
        renderGreeting(animate = true)
        greetingOverrideJob = lifecycleScope.launch {
            delay(GREETING_MESSAGE_MS)
            endGreetingMessage()
        }
    }

    private fun endGreetingMessage() {
        if (greetingOverride == null) return
        greetingOverrideJob?.cancel()
        greetingOverrideJob = null
        greetingOverride = null
        greetingPlayTrackId = null
        greetingIndex = nextGreeting()
        renderGreeting(animate = true)
        // Restart the 10 s clock so the next greeting doesn't arrive straight away
        greetingJob?.cancel()
        greetingJob = null
        syncGreetingRotation()
    }

    // ---- v4.2: rotating tips -----------------------------------------------------------------

    private fun showFirstTip() {
        tipIndex = Tips.FIRST
        tipBag.markShown(Tips.FIRST)
        renderTip(animate = false)
    }

    private fun nextTrueTip(): Int {
        // A tip about a gesture that is changed or off in Settings is skipped (never repeats the current)
        repeat(Tips.count * 2) {
            val pick = tipBag.next()
            if (pick != tipIndex && Tips.isTrue(pick, app.preferences)) return pick
        }
        return Tips.FIRST
    }

    /** Soft crossfade to tip [tipIndex]. */
    private fun renderTip(animate: Boolean) {
        val text = Tips.all[tipIndex]
        homeHint.contentDescription = text
        if (!animate || !animationsOn() || !homeHint.isShown) {
            if (tipAnimating) {
                tipAnimating = false
                homeHint.animate().cancel()
                homeHint.alpha = 1f
            }
            homeHint.text = text
            return
        }
        tipAnimating = true
        homeHint.animate().alpha(0f).setStartDelay(0L).setDuration(220L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                homeHint.text = text
                homeHint.animate().alpha(1f).setStartDelay(0L).setDuration(300L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { tipAnimating = false }
                    .start()
            }
            .start()
    }

    /** App start: the top row fades in, then greeting, search pill and hint rise in, staggered. */
    private fun playStartupAnimation() {
        if (!animationsOn()) return
        val top = findViewById<View>(R.id.home_top)
        top.alpha = 0f
        top.animate().alpha(1f).setStartDelay(0L).setDuration(250L)
            .setInterpolator(DecelerateInterpolator()).start()
        listOf(homeGreeting, homeSearch, homeHint).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dp(18f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(150L + i * 80L).setDuration(280L)
                .setInterpolator(DecelerateInterpolator()).withEndAction { v.animate().setStartDelay(0L) }
                .start()
        }
    }

    /** On open: always the time-based greeting first. */
    private fun showTimeGreeting() {
        greetingIndex = Greetings.TIME_BASED
        greetingRotation.markShown(Greetings.TIME_BASED)
        renderGreeting(animate = false)
        lifecycleScope.launch { refreshGreetingFacts(force = true) }
    }

    // ---- v4.5: fun greetings from the user's own data -----------------------------------------

    /** Re-reads the greeting facts from the database when they are over a minute old (or forced). */
    private suspend fun refreshGreetingFacts(force: Boolean = false) {
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (!force && greetingFactsAt != 0L && nowMs - greetingFactsAt < GREETING_FACTS_MAX_AGE_MS) return
        val dayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        greetingFacts = try {
            app.database.getGreetingFacts(dayStart)
        } catch (_: Exception) {
            return
        }
        greetingFactsAt = maxOf(1L, nowMs)
    }

    /** The stored facts plus what the player and the liked cache say right now. */
    private fun liveGreetingFacts(): GreetingFacts {
        val st = app.playbackController.uiState.value
        val t = st.currentTrack
        val likedIds = app.database.likedIds.value
        val title = t?.title?.takeIf { it.isNotBlank() && it != "Unknown Track" }
        return greetingFacts.copy(
            nowId = t?.id,
            nowTitle = title,
            nowArtist = if (title != null) t?.artist else null,
            nowPlaying = title != null && (st.isPlaying || st.isBuffering),
            nowLiked = title != null && t != null && likedIds.contains(t.id),
            // The liked cache is live; the stored count covers the moment before it has loaded
            likedCount = if (likedIds.isNotEmpty()) likedIds.size else greetingFacts.likedCount
        )
    }

    private fun nextGreeting(): Int {
        val facts = liveGreetingFacts()
        val now = java.util.Calendar.getInstance()
        val name = app.preferences.userName
        return greetingRotation.next { Greetings.fits(it, facts, name, now) }
    }

    /** A regular greeting to fall back on; personal ones (v4.7) only when they fit the saved name. */
    private fun nextRegularGreeting(): Int {
        val name = app.preferences.userName
        return greetingRotation.nextRegular { Greetings.fits(it, GreetingFacts(), name) }
    }

    /** The lines for [greetingIndex]; a fun one whose data has gone (song stopped...) becomes a regular one. */
    private fun currentGreetingLines(): Pair<String, String> {
        greetingOverride?.let { return it }
        val name = app.preferences.userName
        if (Greetings.isData(greetingIndex)) {
            Greetings.dataLines(greetingIndex, liveGreetingFacts())?.let { return it }
            greetingIndex = nextRegularGreeting()
        }
        // A personal one whose name was added or removed since it was picked, or a remote one (v4.9.6)
        // that left the file or its dates: use a regular one instead
        repeat(3) {
            when {
                Greetings.isPersonal(greetingIndex) -> {
                    Greetings.personalLines(greetingIndex, name)?.let { return it }
                    greetingIndex = nextRegularGreeting()
                }
                Greetings.isRemote(greetingIndex) -> {
                    Greetings.remoteLines(greetingIndex, name)?.let { return it }
                    greetingIndex = nextRegularGreeting()
                }
                else -> return Greetings.lines(greetingIndex, name)
            }
        }
        if (greetingIndex !in 0 until Greetings.COUNT) greetingIndex = Greetings.TIME_BASED
        return Greetings.lines(greetingIndex, name)
    }

    private val isHomeShowing: Boolean
        get() = isActivityResumed && !isWelcomeOpen && tab == Tab.HOME && !isSearchOpen && !isListOpen &&
            !isNowPlayingOpen && !isLyricsOpen && !isQueueOpen && !isSettingsOpen

    /** Every 10 s a new greeting and a new tip, only while Home is on screen and the app is in front. */
    private fun syncGreetingRotation() {
        syncTipRotation()
        // v4.9.3: leaving Home (tabs, Search, Now Playing...) fades the takeover out early
        if (isTakeoverActive && !takeoverStillOk()) endTakeoverEarly()
        if (isHomeShowing && takeoverPending) runPendingTakeover()
        if (isHomeShowing) {
            if (greetingJob?.isActive == true) return
            greetingJob = lifecycleScope.launch {
                while (isActive) {
                    delay(GREETING_INTERVAL_MS)
                    // A "Playing ..." message is on screen: leave it for its 5 s
                    if (greetingOverride != null) continue
                    refreshGreetingFacts()
                    if (greetingOverride != null) continue
                    // v4.9.3: the top artist takeover is one of the greetings, at most every 10 minutes
                    if (tryStartTakeover(fromSong = false)) continue
                    if (greetingOverride != null) continue
                    greetingIndex = nextGreeting()
                    renderGreeting(animate = true)
                }
            }
        } else if (greetingJob != null) {
            greetingJob?.cancel()
            greetingJob = null
            if (greetingAnimating) {
                // Stopped half-way: put the current greeting back in place
                greetingAnimating = false
                homeGreeting.animate().cancel()
                homeGreeting.alpha = 1f
                homeGreeting.translationY = 0f
                renderGreeting(animate = false)
            }
        }
    }

    private fun syncTipRotation() {
        if (isHomeShowing) {
            if (tipJob?.isActive == true) return
            tipJob = lifecycleScope.launch {
                while (isActive) {
                    delay(TIP_INTERVAL_MS)
                    tipIndex = nextTrueTip()
                    renderTip(animate = true)
                }
            }
        } else if (tipJob != null) {
            tipJob?.cancel()
            tipJob = null
            if (tipAnimating) renderTip(animate = false)
        }
    }

    /** Shows greeting [greetingIndex]: the old text fades out and up, the new one rises in. */
    private fun renderGreeting(animate: Boolean) {
        val (l1, l2) = currentGreetingLines()
        if (!animate || !animationsOn() || !homeGreeting.isShown) {
            if (greetingAnimating) {
                greetingAnimating = false
                homeGreeting.animate().cancel()
                homeGreeting.alpha = 1f
                homeGreeting.translationY = 0f
            }
            setGreetingText(l1, l2)
            return
        }
        val shift = dp(14f)
        greetingAnimating = true
        homeGreeting.animate().alpha(0f).translationY(-shift).setStartDelay(0L).setDuration(200L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                setGreetingText(l1, l2)
                homeGreeting.translationY = shift
                homeGreeting.animate().alpha(1f).translationY(0f).setStartDelay(0L).setDuration(260L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { greetingAnimating = false }
                    .start()
            }
            .start()
    }

    /** Two lines; the text shrinks a little when a long name would not fit. */
    private fun setGreetingText(l1: String, l2: String) {
        val metrics = resources.displayMetrics
        val base = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, GREETING_SP, metrics)
        val smallest = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, GREETING_MIN_SP, metrics)
        homeGreeting.setTextSize(TypedValue.COMPLEX_UNIT_PX, base)
        val available = metrics.widthPixels - dp(64f) - homeGreeting.paddingStart - homeGreeting.paddingEnd
        val widest = maxOf(homeGreeting.paint.measureText(l1), homeGreeting.paint.measureText(l2))
        if (widest > available && available > 0f) {
            homeGreeting.setTextSize(TypedValue.COMPLEX_UNIT_PX, maxOf(smallest, base * available / widest * 0.98f))
        }
        // Still too wide at the smallest size (a long song title): end the line with "..."
        val line1 = if (available > 0f) TextUtils.ellipsize(l1, homeGreeting.paint, available, TextUtils.TruncateAt.END) else l1
        val line2 = if (available > 0f) TextUtils.ellipsize(l2, homeGreeting.paint, available, TextUtils.TruncateAt.END) else l2
        homeGreeting.text = "$line1\n$line2"
    }

    /**
     * The Home pill wraps its content: at least [PILL_MIN_DP] wide so short titles don't look tiny,
     * at most 85% of the screen (then the title/artist scroll). Width changes glide over 250 ms.
     */
    private fun fitHomePill(animate: Boolean) {
        val m = homeMini
        val metrics = resources.displayMetrics
        val d = metrics.density
        // v4.9.7: the pill is only cover + text now (the buttons are circles below it)
        val fixed = m.root.paddingStart + m.root.paddingEnd + (38 + 10) * d
        // v4.9.9: measure the text that is coming in, so the pill resizes while the old text fades
        val textW = maxOf(
            m.title.paint.measureText(textFade.target(m.title).toString()),
            m.artist.paint.measureText(textFade.target(m.artist).toString())
        )
        val maxW = metrics.widthPixels * 0.85f
        val minW = minOf(PILL_MIN_DP * d, maxW)
        val target = (fixed + textW + 4 * d).coerceIn(minW, maxW).roundToInt()
        val lp = m.root.layoutParams
        val current = if (lp.width > 0) lp.width else m.root.width
        pillWidthAnimator?.cancel()
        if (!animate || !animationsOn() || current <= 0 || current == target || !m.root.isShown) {
            lp.width = target
            m.root.layoutParams = lp
            m.root.post { restartMarquee(m) }
            return
        }
        pillWidthAnimator = ValueAnimator.ofInt(current, target).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lp.width = it.animatedValue as Int
                m.root.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = restartMarquee(m)
            })
            start()
        }
    }

    /** Re-checks whether the title/artist overflow, so the marquee starts or stops after a resize. */
    private fun restartMarquee(m: Mini) {
        for (t in listOf(m.title, m.artist)) {
            t.isSelected = false
            t.isSelected = true
        }
    }

    // ==========================================================================================
    // Search
    // ==========================================================================================

    private fun setupSearch() {
        searchAdapter = SearchResultsAdapter(
            artwork = app.artworkPipeline,
            onClick = { track, list -> app.playbackController.playRadio(track, list) },
            onLongClick = { anchor, track -> showTrackOptions(anchor, track) },
            onArtistClick = { artist -> openArtist(artist, fromSearch = true) }
        )
        findViewById<RecyclerView>(R.id.search_results).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = searchAdapter
            searchEntrance = RowEntrance(this)
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                searchNow()
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.search_go).setOnClickListener { searchNow() }
        // v4.7: live search. Runs about 300 ms after typing stops; every new search replaces
        // the one before it, so older results can never show up over newer ones.
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                searchDebounce?.cancel()
                if (query.isEmpty()) {
                    clearSearchResults()
                    return
                }
                if (query == lastQuery) return
                searchDebounce = lifecycleScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    runSearch(live = true)
                }
            }
        })
    }

    /** The search key or the arrow: search right away (unless it's already showing) and hide the keyboard. */
    private fun searchNow() {
        searchDebounce?.cancel()
        val query = searchInput.text?.toString()?.trim().orEmpty()
        if (query.isNotEmpty() && query == lastQuery) {
            hideKeyboard()
            searchInput.clearFocus()
            return
        }
        runSearch(live = false)
    }

    /** The box was emptied: drop any search on its way and clear the results. */
    private fun clearSearchResults() {
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
        lastQuery = ""
        searchAdapter.submit(emptyList())
        searchAdapter.submitArtists(emptyList(), first = false)
        searchStatus.visibility = View.GONE
    }

    private fun openSearch() {
        screenSearch.visibility = View.VISIBLE
        updateChrome()
        searchInput.requestFocus()
        showKeyboard(searchInput)
    }

    private fun closeSearch() {
        hideKeyboard()
        searchInput.clearFocus()
        screenSearch.visibility = View.GONE
        updateChrome()
    }

    /**
     * [live] = started by typing (v4.7): the keyboard stays up and the results already on screen
     * stay until the new ones arrive. Each search gets a number; a result whose number is no
     * longer the latest is thrown away.
     */
    private fun runSearch(live: Boolean = false) {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return
        if (!live) {
            hideKeyboard()
            searchInput.clearFocus()
        }
        lastQuery = query
        val generation = ++searchGeneration
        searchJob?.cancel()
        if (!live || searchAdapter.itemCount == 0) {
            searchStatus.text = getString(R.string.searching)
            searchStatus.visibility = View.VISIBLE
            searchAdapter.submit(emptyList())
            searchAdapter.submitArtists(emptyList(), first = false)
        }
        searchJob = lifecycleScope.launch {
            // v4.6: artists are looked up next to the songs; the songs never wait for them
            val artistsAsync = async { app.sourceAdapter.searchArtists(query) }
            val result = app.sourceAdapter.searchTracks(query)
            if (generation != searchGeneration) return@launch
            var tracksFound = 0
            var failed = false
            when (result) {
                is SourceResult.Success -> {
                    // v4.9.9: a new set of results rises in; the same songs again don't move
                    val newSet = result.data.map { it.id } != searchAdapter.shownTrackIds
                    searchAdapter.submit(result.data)
                    if (newSet && result.data.isNotEmpty()) searchEntrance.play()
                    tracksFound = result.data.size
                    if (result.data.isEmpty()) {
                        searchStatus.text = getString(R.string.no_results)
                        searchStatus.visibility = View.VISIBLE
                    } else {
                        searchStatus.visibility = View.GONE
                    }
                }
                is SourceResult.Error -> {
                    failed = true
                    searchStatus.text = getString(R.string.search_failed)
                    searchStatus.visibility = View.VISIBLE
                }
            }
            if (live) {
                // Songs are in before the artists: keep only the pills from the last search that
                // still match what's typed now (no flicker), until this search's artists arrive
                val (kept, keptExact) = pickArtistResults(query, searchAdapter.shownArtists)
                searchAdapter.submitArtists(kept, first = keptExact || tracksFound == 0)
                if (kept.isNotEmpty() && (tracksFound == 0 || failed)) searchStatus.visibility = View.GONE
            }
            val artists = (artistsAsync.await() as? SourceResult.Success)?.data.orEmpty()
            if (generation != searchGeneration) return@launch
            val (shown, exact) = pickArtistResults(query, artists)
            searchAdapter.submitArtists(shown, first = exact || tracksFound == 0)
            if (shown.isNotEmpty() && (tracksFound == 0 || failed)) searchStatus.visibility = View.GONE
        }
    }

    /**
     * v4.6: at most two artist pills, and only artists whose name matches what was typed (so a
     * song search doesn't show random artists). Second value: the top one is an exact match,
     * so it goes above the songs.
     */
    private fun pickArtistResults(query: String, artists: List<ArtistRef>): Pair<List<ArtistRef>, Boolean> {
        val q = MusicSourceAdapter.normName(query)
        if (q.isEmpty()) return Pair(emptyList(), false)
        val matching = artists.filter {
            val n = MusicSourceAdapter.normName(it.name)
            n.isNotEmpty() && (n == q || n.startsWith(q) || (q.startsWith(n) && n.length >= 3))
        }
        val sorted = matching.sortedByDescending { MusicSourceAdapter.normName(it.name) == q }
        val shown = sorted.take(2)
        return Pair(shown, shown.firstOrNull()?.let { MusicSourceAdapter.normName(it.name) == q } == true)
    }

    // ==========================================================================================
    // Recents, Library and the song list (Liked Songs / a playlist)
    // ==========================================================================================

    private fun setupLists() {
        libraryAdapter = GridTileAdapter(
            artwork = app.artworkPipeline,
            onClick = { tile ->
                when (tile) {
                    is GridTile.Liked -> openList(LIKED_LIST, getString(R.string.liked_songs))
                    is GridTile.RecentsTile -> openList(RECENTS_LIST, getString(R.string.recents))
                    is GridTile.PlaylistTile -> openList(tile.playlist.id, tile.playlist.name)
                    is GridTile.NewPlaylist -> showCreatePlaylistSheet(null)
                    is GridTile.Song, is GridTile.AlbumTile -> Unit
                }
            },
            onLongClick = { _, tile ->
                if (tile is GridTile.PlaylistTile) confirmDeletePlaylist(tile.playlist)
            }
        )
        findViewById<RecyclerView>(R.id.library_grid).apply {
            layoutManager = GridLayoutManager(this@MainActivity, GRID_COLUMNS)
            adapter = libraryAdapter
        }

        listAdapter = TrackAdapter(
            artwork = app.artworkPipeline,
            onClick = { track, list ->
                // v4.9: Recents (now in Library) keeps its old tap: a radio of similar songs
                if (openListId == RECENTS_LIST) app.playbackController.playRadio(track, list)
                else app.playbackController.playTrack(track, list)
            },
            onLongClick = { anchor, track ->
                // Inside a user playlist (not Liked Songs / Recents) the menu also offers "Remove from playlist".
                val id = openListId
                showTrackOptions(anchor, track, if (id != null && id >= 0L) id else null)
            }
        )
        findViewById<RecyclerView>(R.id.list_tracks).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = listAdapter
            listEntrance = RowEntrance(this)
        }
    }

    // ---- v4.3 Discover ---------------------------------------------------------------------------

    private var discoverHasError = false
    private lateinit var shelfPlayAll: View

    private fun setupDiscover() {
        discoverRefresh = findViewById(R.id.discover_refresh)
        discoverMessage = findViewById(R.id.discover_message)
        discoverWelcome = findViewById(R.id.discover_welcome)
        discoverWelcomeSub = findViewById(R.id.discover_welcome_sub)
        shelfTitle = findViewById(R.id.shelf_page_title)
        shelfCount = findViewById(R.id.shelf_page_count)
        shelfAdapter = com.minim.music.ui.adapter.ShelfAdapter(
            artwork = app.artworkPipeline,
            onOpenShelf = { shelf -> openShelfPage(shelf) },
            onSongClick = { track, shelf -> app.playbackController.playRadio(track, shelf.tracks) },
            onSongLongClick = { anchor, track -> showTrackOptions(anchor, track, fromDiscover = true) }
        )
        findViewById<RecyclerView>(R.id.discover_shelves).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = shelfAdapter
            itemAnimator = null
            discoverEntrance = RowEntrance(this)
        }
        discoverRefresh.setOnRefreshListener { loadDiscover(force = true) }
        discoverMessage.setOnClickListener {
            if (discoverHasError) {
                haptic(it)
                loadDiscover(force = true)
            }
        }

        shelfGridAdapter = GridTileAdapter(
            artwork = app.artworkPipeline,
            onClick = { tile ->
                val shelf = openShelf
                if (tile is GridTile.Song && shelf != null) app.playbackController.playRadio(tile.track, shelf.tracks)
                if (tile is GridTile.AlbumTile) openAlbum(tile.album)
            },
            onLongClick = { anchor, tile ->
                if (tile is GridTile.Song) {
                    val fromArtist = openShelf?.key?.startsWith(ARTIST_SHELF) == true
                    showTrackOptions(anchor, tile.track, fromDiscover = !fromArtist)
                }
            }
        )
        findViewById<RecyclerView>(R.id.shelf_grid).apply {
            layoutManager = GridLayoutManager(this@MainActivity, GRID_COLUMNS)
            adapter = shelfGridAdapter
            shelfGridEntrance = RowEntrance(this)
        }
        findViewById<View>(R.id.shelf_back).setOnClickListener {
            haptic(it)
            closeShelf()
        }
        shelfPlayAll = findViewById(R.id.shelf_play_all)
        shelfPlayAll.setOnClickListener { v ->
            val shelf = openShelf ?: return@setOnClickListener
            if (shelf.tracks.isEmpty()) return@setOnClickListener
            haptic(v)
            // "Play all" queues the whole shelf in order (no radio)
            app.playbackController.playTrack(shelf.tracks.first(), shelf.tracks)
        }
    }

    /**
     * Shows the saved shelves at once, then rebuilds in the background when they are from an
     * earlier day / time of day, when there are none yet, or on pull-to-refresh.
     */
    private fun loadDiscover(force: Boolean) {
        if (discoverJob?.isActive == true) {
            if (!force) return
            discoverRefresh.isRefreshing = true
            return // the running build will finish and clear the spinner
        }
        discoverJob = lifecycleScope.launch {
            val engine = app.discoverEngine
            val cached = try { engine.cached() } catch (_: Exception) { null }
            if (cached != null) showDiscover(cached, afterBuild = false)
            else if (discoverShelves.isEmpty() && discoverWelcome.visibility != View.VISIBLE) {
                // v4.7: the big "play a few songs" message stays up while it checks again
                showDiscoverMessage(getString(R.string.discover_loading), error = false)
            }
            val needsBuild = force || engine.isStale(cached) || cached?.shelves.isNullOrEmpty()
            if (!needsBuild) {
                discoverRefresh.isRefreshing = false
                return@launch
            }
            val fresh = try { engine.rebuild() } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { null }
            discoverRefresh.isRefreshing = false
            if (fresh != null) showDiscover(fresh, afterBuild = true)
            else if (discoverShelves.isEmpty()) showDiscoverMessage(getString(R.string.discover_error), error = true)
        }
    }

    private fun showDiscover(s: com.minim.music.data.discover.DiscoverSnapshot, afterBuild: Boolean) {
        // v4.9.9: a new set of shelves rises in; the same shelves with a song less ("not
        // interested") or fresher songs just update in place
        val newSet = s.shelves.map { it.key } != discoverShelves.map { it.key }
        discoverShelves = s.shelves
        shelfAdapter.submit(s.shelves)
        shelfAdapter.setActiveTrack(app.playbackController.uiState.value.currentTrack?.id)
        if (newSet && s.shelves.isNotEmpty()) discoverEntrance.play()
        when {
            s.shelves.isNotEmpty() -> {
                discoverHasError = false
                discoverMessage.visibility = View.GONE
                discoverWelcome.visibility = View.GONE
            }
            !s.hasHistory -> showDiscoverWelcome(thin = false)
            !afterBuild -> showDiscoverMessage(getString(R.string.discover_loading), error = false)
            app.discoverEngine.lastMixSuccesses == 0 -> showDiscoverMessage(getString(R.string.discover_error), error = true)
            else -> showDiscoverWelcome(thin = true)
        }
        // An open shelf page follows its shelf (it may have lost a "not interested" song)
        openShelf?.takeIf { !it.key.startsWith(ARTIST_SHELF) }?.let { open ->
            val updated = s.shelves.firstOrNull { it.key == open.key }
            if (updated == null) closeShelf() else if (updated != open) fillShelfPage(updated)
        }
    }

    private fun showDiscoverMessage(text: String, error: Boolean) {
        discoverHasError = error
        discoverMessage.text = text
        discoverMessage.visibility = View.VISIBLE
        discoverWelcome.visibility = View.GONE
    }

    /**
     * v4.7: not enough listening history yet (none at all, or too little for a shelf): a big
     * headline in the welcome screens' style instead of the small grey line.
     */
    private fun showDiscoverWelcome(thin: Boolean) {
        discoverHasError = false
        discoverMessage.visibility = View.GONE
        discoverWelcomeSub.setText(if (thin) R.string.discover_welcome_thin else R.string.discover_welcome_empty)
        discoverWelcome.visibility = View.VISIBLE
    }

    private fun openShelfPage(shelf: com.minim.music.data.discover.Shelf) {
        fillShelfPage(shelf)
        findViewById<RecyclerView>(R.id.shelf_grid).scrollToPosition(0)
        shelfGridEntrance.play()
        screenShelf.visibility = View.VISIBLE
        if (animationsOn()) {
            screenShelf.alpha = 0f
            screenShelf.translationX = dp(24f)
            screenShelf.animate().alpha(1f).translationX(0f).setDuration(220L)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        updateChrome()
    }

    private fun fillShelfPage(shelf: com.minim.music.data.discover.Shelf) {
        openShelf = shelf
        shelfTitle.text = shelf.title
        if (shelf.albums.isNotEmpty()) {
            // v4.6: the Artist page's "Albums" grid
            shelfCount.text = if (shelf.albums.size == 1) getString(R.string.album_count_one)
            else getString(R.string.album_count, shelf.albums.size)
            shelfGridAdapter.submit(shelf.albums.map { GridTile.AlbumTile(it) })
            shelfPlayAll.visibility = View.GONE
        } else {
            shelfCount.text = songCount(shelf.tracks.size)
            shelfGridAdapter.submit(shelf.tracks.map { GridTile.Song(it) })
            shelfPlayAll.visibility = View.VISIBLE
        }
        shelfGridAdapter.setActiveTrack(app.playbackController.uiState.value.currentTrack?.id)
    }

    private fun closeShelf() {
        openShelf = null
        screenShelf.animate().cancel()
        screenShelf.alpha = 1f
        screenShelf.translationX = 0f
        screenShelf.visibility = View.GONE
        updateChrome()
    }

    // ---- v4.6 Artist page --------------------------------------------------------------------

    /** Where the Artist page was opened from, put back when it closes. */
    private data class ArtistReturn(
        val search: Boolean,
        val listId: Long?,
        val listName: String,
        val shelf: com.minim.music.data.discover.Shelf?
    )

    private lateinit var artistHeader: SquareFrameLayout
    private lateinit var artistPhoto: ImageView
    private lateinit var artistFade: View
    private lateinit var artistLogo: TextView
    private lateinit var artistSearch: View
    private lateinit var artistSearchIcon: ImageView
    private lateinit var artistSearchText: TextView
    private lateinit var artistName: TextView
    private lateinit var artistSubs: TextView
    private lateinit var artistShuffle: TextView
    private lateinit var artistMessage: TextView
    private lateinit var artistScroll: ScrollView
    private lateinit var artistSongsShelf: View
    private lateinit var artistAlbumsShelf: View
    private lateinit var artistSongs: ShelfSongAdapter
    private lateinit var artistAlbums: ShelfSongAdapter

    private var artistReturn: ArtistReturn? = null
    private var artistJob: Job? = null
    /** What the page is showing: a found artist, or (from Now Playing) a name still being looked up. */
    private var artistRef: ArtistRef? = null
    private var artistLookupName: String? = null
    private var artistPage: ArtistPage? = null
    private var artistHasError = false
    /** Header photo and how light the photo is behind "minim" and behind the search pill. */
    private var artistPhotoUrl: String? = null
    private var artistHasPhoto = false
    private var logoOnLight = false
    private var searchOnLight = false
    /** v4.8: the photo, kept so the pinned "minim" / search pill can follow it while the page scrolls. */
    private var artistBitmap: android.graphics.Bitmap? = null
    /** v4.8: the pinned controls have scrolled past the photo and sit on the page colour. */
    private var logoOverPage = false
    private var searchOverPage = false
    private var artistToneScroll = 0
    /** v4.6.1: how light the photo is behind the status bar (clock and battery). */
    private var statusOnLight = false
    private lateinit var artistStatusScrim: View
    /** Status bar height in px (the window draws behind it). */
    private var statusInset = 0
    private var artistLogoBaseTop = 0
    private var artistSearchBaseTop = 0
    private var appliedLightStatus: Boolean? = null
    private var appliedLightNav: Boolean? = null

    /** v4.6: an album opened from the Artist page, shown in the playlist screen. */
    private var openAlbum: Album? = null
    private var albumJob: Job? = null
    private var albumHasError = false

    private fun setupArtist() {
        artistHeader = findViewById(R.id.artist_header)
        artistPhoto = findViewById(R.id.artist_photo)
        artistFade = findViewById(R.id.artist_fade)
        artistLogo = findViewById(R.id.artist_logo)
        artistSearch = findViewById(R.id.artist_search)
        artistSearchIcon = findViewById(R.id.artist_search_icon)
        artistSearchText = findViewById(R.id.artist_search_text)
        artistName = findViewById(R.id.artist_name)
        artistSubs = findViewById(R.id.artist_subs)
        artistShuffle = findViewById(R.id.artist_shuffle)
        artistMessage = findViewById(R.id.artist_message)
        artistScroll = findViewById(R.id.artist_scroll)
        artistSongsShelf = findViewById(R.id.artist_songs_shelf)
        artistAlbumsShelf = findViewById(R.id.artist_albums_shelf)
        artistStatusScrim = findViewById(R.id.artist_status_scrim)
        artistLogoBaseTop = (artistLogo.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        artistSearchBaseTop = (artistSearch.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        // Once the header scrolls away, fill in behind the status bar so the clock stays readable
        artistScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            updateArtistScrim()
            updatePinnedArtistTones()
        }

        artistSongs = ShelfSongAdapter(app.artworkPipeline)
        artistAlbums = ShelfSongAdapter(app.artworkPipeline).apply { onAlbumClick = { openAlbum(it) } }
        for ((shelfView, adapter) in listOf(artistSongsShelf to artistSongs, artistAlbumsShelf to artistAlbums)) {
            shelfView.findViewById<RecyclerView>(R.id.shelf_row).apply {
                layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
                this.adapter = adapter
                itemAnimator = null
            }
        }
        artistSongsEntrance = RowEntrance(artistSongsShelf.findViewById(R.id.shelf_row))
        artistAlbumsEntrance = RowEntrance(artistAlbumsShelf.findViewById(R.id.shelf_row))
        artistSongsShelf.findViewById<TextView>(R.id.shelf_title).setText(R.string.artist_songs)
        artistAlbumsShelf.findViewById<TextView>(R.id.shelf_title).setText(R.string.artist_albums)
        artistSongsShelf.findViewById<View>(R.id.shelf_header).setOnClickListener {
            val page = artistPage ?: return@setOnClickListener
            if (page.songs.isEmpty()) return@setOnClickListener
            haptic(it)
            openShelfPage(
                com.minim.music.data.discover.Shelf(
                    ARTIST_SHELF + "songs", getString(R.string.artist_songs_by, page.name), page.songs
                )
            )
        }
        artistAlbumsShelf.findViewById<View>(R.id.shelf_header).setOnClickListener {
            val page = artistPage ?: return@setOnClickListener
            if (page.albums.isEmpty()) return@setOnClickListener
            haptic(it)
            openShelfPage(
                com.minim.music.data.discover.Shelf(
                    ARTIST_SHELF + "albums", getString(R.string.artist_albums_by, page.name),
                    emptyList(), page.albums
                )
            )
        }

        artistSearch.setOnClickListener {
            haptic(it)
            openSearch()
        }
        artistShuffle.setOnClickListener {
            val songs = artistPage?.songs.orEmpty()
            if (songs.isEmpty()) return@setOnClickListener
            haptic(it)
            // The artist's top songs in a random order; the radio queue carries on after them
            app.playbackController.playListThenRadio(songs.shuffled())
        }
        artistMessage.setOnClickListener {
            if (!artistHasError) return@setOnClickListener
            haptic(it)
            val ref = artistRef
            val name = artistLookupName
            when {
                ref != null -> loadArtist(ref)
                name != null -> lookUpArtist(name)
            }
        }

        // Album song list (the playlist screen): tap the error to try again
        listEmpty.setOnClickListener {
            val album = openAlbum
            if (album != null && albumHasError) {
                haptic(it)
                loadAlbum(album)
            }
        }

        // Now Playing: tap the artist name to open their page
        npArtist.setOnClickListener {
            val line = app.playbackController.uiState.value.currentTrack?.artist ?: return@setOnClickListener
            if (MusicSourceAdapter.firstArtistName(line).isBlank()) return@setOnClickListener
            haptic(it)
            collapseNowPlaying()
            openArtistByName(line)
        }

        // v4.8: long-press the title or artist on Now Playing for the song menu
        val npMenu = View.OnLongClickListener {
            val t = app.playbackController.uiState.value.currentTrack ?: return@OnLongClickListener false
            showTrackOptions(it, t, fromNowPlaying = true)
            true
        }
        npTitle.setOnLongClickListener(npMenu)
        npArtist.setOnLongClickListener(npMenu)
    }

    /** Opens the page for an artist from Search. */
    private fun openArtist(ref: ArtistRef, fromSearch: Boolean) {
        showArtistScreen(fromSearch)
        if (artistPage?.url == ref.url && !artistHasError) return
        artistLookupName = null
        artistRef = ref
        artistPage = null
        artistScroll.scrollTo(0, 0)
        showArtistHeader(ref.name, formatArtistCount(resources, ref.count, ref.countKind),
            MusicSourceAdapter.upscaleYtImage(ref.thumbnailUrl))
        loadArtist(ref)
    }

    /** Opens the page for the artist line on Now Playing ("Arijit Singh, Pritam" -> Arijit Singh). */
    private fun openArtistByName(line: String) {
        val name = MusicSourceAdapter.firstArtistName(line)
        showArtistScreen(fromSearch = false)
        val shown = artistPage
        if (shown != null && !artistHasError &&
            MusicSourceAdapter.normName(shown.name) == MusicSourceAdapter.normName(name)
        ) return
        artistRef = null
        artistPage = null
        artistScroll.scrollTo(0, 0)
        showArtistHeader(name, getString(R.string.artist_label), "")
        lookUpArtist(line)
    }

    private fun lookUpArtist(line: String) {
        artistLookupName = line
        artistJob?.cancel()
        setArtistMessage(getString(R.string.artist_loading), error = false)
        hideArtistShelves()
        artistJob = lifecycleScope.launch {
            when (val found = app.sourceAdapter.findArtist(line)) {
                is SourceResult.Success -> {
                    if (artistLookupName != line) return@launch
                    val ref = found.data
                    if (ref == null) {
                        setArtistMessage(getString(R.string.artist_not_found), error = false)
                        return@launch
                    }
                    artistLookupName = null
                    artistRef = ref
                    showArtistHeader(ref.name, formatArtistCount(resources, ref.count, ref.countKind),
                        MusicSourceAdapter.upscaleYtImage(ref.thumbnailUrl))
                    loadArtist(ref)
                }
                is SourceResult.Error -> {
                    if (artistLookupName != line) return@launch
                    setArtistMessage(getString(R.string.artist_error), error = true)
                }
            }
        }
    }

    private fun loadArtist(ref: ArtistRef) {
        artistJob?.cancel()
        setArtistMessage(getString(R.string.artist_loading), error = false)
        hideArtistShelves()
        artistJob = lifecycleScope.launch {
            val result = app.sourceAdapter.fetchArtist(ref)
            if (artistRef?.url != ref.url) return@launch
            when (result) {
                is SourceResult.Success -> showArtistPage(result.data)
                is SourceResult.Error -> setArtistMessage(getString(R.string.artist_error), error = true)
            }
        }
    }

    private fun showArtistPage(page: ArtistPage) {
        // v4.9.9: the shelves' songs and albums rise in when the page loads, not on a redraw
        val previous = artistPage
        val newSongs = previous == null || previous.songs != page.songs
        val newAlbums = previous == null || previous.albums != page.albums
        artistPage = page
        showArtistHeader(page.name, formatArtistCount(resources, page.count, page.countKind), page.photoUrl)
        val active = app.playbackController.uiState.value.currentTrack?.id
        if (page.songs.isNotEmpty()) {
            artistSongs.bind(
                com.minim.music.data.discover.Shelf(ARTIST_SHELF + "songs", "", page.songs),
                active, pal,
                { track, shelf -> app.playbackController.playRadio(track, shelf.tracks) },
                { anchor, track -> showTrackOptions(anchor, track) }
            )
            artistSongsShelf.findViewById<RecyclerView>(R.id.shelf_row).scrollToPosition(0)
            if (newSongs) artistSongsEntrance.play()
        }
        if (page.albums.isNotEmpty()) {
            artistAlbums.bind(
                com.minim.music.data.discover.Shelf(ARTIST_SHELF + "albums", "", emptyList(), page.albums),
                active, pal, { _, _ -> }, { _, _ -> }
            )
            artistAlbumsShelf.findViewById<RecyclerView>(R.id.shelf_row).scrollToPosition(0)
            if (newAlbums) artistAlbumsEntrance.play()
        }
        // Hide a shelf that has nothing in it
        artistSongsShelf.visibility = if (page.songs.isNotEmpty()) View.VISIBLE else View.GONE
        artistAlbumsShelf.visibility = if (page.albums.isNotEmpty()) View.VISIBLE else View.GONE
        artistShuffle.visibility = if (page.songs.isNotEmpty()) View.VISIBLE else View.GONE
        if (page.songs.isEmpty() && page.albums.isEmpty()) {
            setArtistMessage(getString(R.string.artist_nothing), error = false)
        } else {
            artistHasError = false
            artistMessage.visibility = View.GONE
        }
        Painter.paint(artistSongsShelf, pal)
        Painter.paint(artistAlbumsShelf, pal)
    }

    private fun hideArtistShelves() {
        artistSongsShelf.visibility = View.GONE
        artistAlbumsShelf.visibility = View.GONE
        artistShuffle.visibility = View.GONE
    }

    private fun setArtistMessage(text: String, error: Boolean) {
        artistHasError = error
        artistMessage.text = text
        artistMessage.visibility = View.VISIBLE
    }

    /** Name, subscriber line and (when there is one) the photo, read for how light its top is. */
    private fun showArtistHeader(name: String, subs: String, photoUrl: String) {
        artistName.text = name
        artistSubs.text = subs
        artistShuffle.contentDescription = getString(R.string.artist_shuffle_desc)
        val url = photoUrl.trim()
        if (url == artistPhotoUrl && (artistHasPhoto || url.isEmpty())) return
        if (url.isEmpty()) {
            // Keep a photo we already have (from the search result) when the page brings none
            if (artistHasPhoto && artistPhotoUrl != null) return
            artistPhotoUrl = null
            setArtistPhoto(null)
            return
        }
        artistPhotoUrl = url
        val sizePx = resources.displayMetrics.widthPixels.coerceIn(320, 1080)
        // v4.9.9: a photo already in memory shows at once; only a fetched one fades in
        app.artworkPipeline.cachedBitmap(url, sizePx)?.let {
            setArtistPhoto(it, fade = false)
            return
        }
        lifecycleScope.launch {
            val bmp = app.artworkPipeline.loadBitmap(url, sizePx)
            if (artistPhotoUrl != url) return@launch
            setArtistPhoto(bmp)
        }
    }

    private fun setArtistPhoto(bmp: android.graphics.Bitmap?, fade: Boolean = true) {
        artistBitmap = if (bmp == null || bmp.isRecycled) null else bmp
        artistToneScroll = 0
        logoOverPage = false
        searchOverPage = false
        if (bmp == null || bmp.isRecycled) {
            artistHasPhoto = false
            artistPhoto.setImageDrawable(null)
            artistPhoto.visibility = View.GONE
        } else {
            artistHasPhoto = true
            readPhotoTones(bmp)
            artistPhoto.setImageBitmap(bmp)
            artistPhoto.visibility = View.VISIBLE
            artistPhoto.animate().cancel()
            if (fade && animationsOn()) {
                artistPhoto.alpha = 0f
                artistPhoto.animate().alpha(1f).setDuration(com.minim.music.util.ArtworkFade.FADE_MS)
                    .setInterpolator(DecelerateInterpolator(1.5f)).start()
            } else {
                artistPhoto.alpha = 1f
            }
        }
        paintArtistHeader(pal)
        updateSystemBars()
        updatePinnedArtistTones()
    }

    /**
     * v4.8: "minim" and the search pill are pinned while the page scrolls under them, so the part
     * of the photo behind them changes. Re-read it every few dp of scroll (with a little slack so
     * they don't flicker on a border) and repaint only when one of them flips.
     */
    private fun updatePinnedArtistTones() {
        if (!::artistHeader.isInitialized || !isArtistOpen) return
        val scroll = artistScroll.scrollY
        if (abs(scroll - artistToneScroll) < dp(6f) && !(scroll == 0 && artistToneScroll != 0)) return
        artistToneScroll = scroll
        val viewW = (if (artistHeader.width > 0) artistHeader.width else resources.displayMetrics.widthPixels)
            .coerceAtLeast(1)
        val viewH = viewW + statusInset
        val inset = statusInset.toFloat() / viewH
        val below = inset + 0.20f * viewW / viewH
        val shift = scroll.toFloat() / viewH
        val y0 = inset + shift
        val y1 = below + shift
        // More than half of the area behind them is past the bottom of the photo
        val overPage = y0 + (y1 - y0) / 2f > 1f
        var logoLight = logoOnLight
        var searchLight = searchOnLight
        val bmp = artistBitmap
        if (artistHasPhoto && bmp != null && !bmp.isRecycled && !overPage) {
            if (scroll == 0) {
                // Resting position: exactly the 4.6.1 reading
                logoLight = isAreaLight(bmp, viewW, viewH, 0.0f, 0.45f, inset, below)
                searchLight = isAreaLight(bmp, viewW, viewH, 0.55f, 1.0f, inset, below)
            } else {
                val yEnd = minOf(1f, y1)
                val logoLum = areaLuminance(bmp, viewW, viewH, 0.0f, 0.45f, y0, yEnd, withFade = true)
                val searchLum = areaLuminance(bmp, viewW, viewH, 0.55f, 1.0f, y0, yEnd, withFade = true)
                logoLight = if (logoOnLight) logoLum > 0.37 else logoLum > 0.43
                searchLight = if (searchOnLight) searchLum > 0.37 else searchLum > 0.43
            }
        }
        if (logoLight != logoOnLight || searchLight != searchOnLight ||
            overPage != logoOverPage || overPage != searchOverPage
        ) {
            logoOnLight = logoLight
            searchOnLight = searchLight
            logoOverPage = overPage
            searchOverPage = overPage
            paintArtistHeader(pal)
        }
    }

    /**
     * v4.6.1: how light the photo is behind the status bar, behind "minim" and behind the search
     * pill, read from the part of the photo the header really shows (centre-cropped to the
     * header, which reaches up behind the status bar).
     */
    private fun readPhotoTones(bmp: android.graphics.Bitmap) {
        val viewW = (if (artistHeader.width > 0) artistHeader.width else resources.displayMetrics.widthPixels)
            .coerceAtLeast(1)
        val viewH = viewW + statusInset
        val inset = statusInset.toFloat() / viewH
        // "minim" and the pill sit in the top 20% of the square below the status bar, as before
        val below = inset + 0.20f * viewW / viewH
        statusOnLight = statusInset > 0 && isAreaLight(bmp, viewW, viewH, 0.0f, 1.0f, 0f, inset)
        logoOnLight = isAreaLight(bmp, viewW, viewH, 0.0f, 0.45f, inset, below)
        searchOnLight = isAreaLight(bmp, viewW, viewH, 0.55f, 1.0f, inset, below)
    }

    /**
     * How light the photo is in the area [x0]..[x1] x [y0]..[y1] (fractions of a [viewW] x
     * [viewH] header that shows the photo centre-cropped). True means dark ink on top of it.
     */
    private fun isAreaLight(
        bmp: android.graphics.Bitmap, viewW: Int, viewH: Int,
        x0: Float, x1: Float, y0: Float, y1: Float
    ): Boolean = areaLuminance(bmp, viewW, viewH, x0, x1, y0, y1, withFade = false) > 0.40

    /**
     * Average luminance of that area (0 = black, 1 = white). v4.8: [withFade] also counts the
     * dark fade at the bottom of the header, which matters once the page has scrolled.
     */
    private fun areaLuminance(
        bmp: android.graphics.Bitmap, viewW: Int, viewH: Int,
        x0: Float, x1: Float, y0: Float, y1: Float, withFade: Boolean
    ): Double {
        if (bmp.width <= 0 || bmp.height <= 0) return 0.0
        // centerCrop: scale to cover the view, then take the middle
        val scale = maxOf(viewW.toFloat() / bmp.width, viewH.toFloat() / bmp.height)
        val shownW = viewW / scale
        val shownH = viewH / scale
        val left = (bmp.width - shownW) / 2f
        val top = (bmp.height - shownH) / 2f
        val ax = (left + shownW * x0).toInt().coerceIn(0, bmp.width - 1)
        val bx = (left + shownW * x1).toInt().coerceIn(ax + 1, bmp.width)
        val ay = (top + shownH * y0).toInt().coerceIn(0, bmp.height - 1)
        val by = (top + shownH * y1).toInt().coerceIn(ay + 1, bmp.height)
        val step = maxOf(1, minOf(bmp.width, bmp.height) / 90)
        var sum = 0.0
        var n = 0
        var y = ay
        while (y < by) {
            // shape_header_fade: clear at the top, #66 black at the middle, #C4 black at the bottom
            val fade = if (!withFade) 0f else {
                val fy = ((y - top) / shownH).coerceIn(0f, 1f)
                if (fy < 0.5f) 0.40f * (fy / 0.5f) else 0.40f + 0.37f * ((fy - 0.5f) / 0.5f)
            }
            var x = ax
            while (x < bx) {
                var c = bmp.getPixel(x, y)
                if (fade > 0f) c = androidx.core.graphics.ColorUtils.blendARGB(c, android.graphics.Color.BLACK, fade)
                sum += androidx.core.graphics.ColorUtils.calculateLuminance(c)
                n++
                x += step
            }
            y += step
        }
        return if (n > 0) sum / n else 0.0
    }

    /**
     * v4.6.1: the window draws behind the system bars. The root keeps every screen inside them
     * with padding; only the Artist page (and the strip that covers it) reach up behind the
     * status bar. Its photo grows by the status bar height so it still looks square below it,
     * and "minim" and the search pill move down by the same amount.
     */
    private fun setupWindowInsets() {
        root.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            statusInset = bars.top
            fun margins(view: View, top: Int, height: Int? = null) {
                val lp = view.layoutParams as ViewGroup.MarginLayoutParams
                if (lp.topMargin != top || (height != null && lp.height != height)) {
                    lp.topMargin = top
                    if (height != null) lp.height = height
                    view.layoutParams = lp
                }
            }
            margins(screenArtist, -bars.top)
            // v4.7: the welcome screens are white right up behind the status and navigation bars
            (screenWelcome.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
                if (lp.topMargin != -bars.top || lp.bottomMargin != -bars.bottom) {
                    lp.topMargin = -bars.top
                    lp.bottomMargin = -bars.bottom
                    screenWelcome.layoutParams = lp
                }
            }
            screenWelcome.setPadding(0, bars.top, 0, bars.bottom)
            margins(statusCover, -bars.top, bars.top)
            // v4.9.3: the takeover photo fills the whole screen, behind both system bars
            (takeoverRoot.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
                if (lp.topMargin != -bars.top || lp.bottomMargin != -bars.bottom ||
                    lp.leftMargin != -bars.left || lp.rightMargin != -bars.right
                ) {
                    lp.setMargins(-bars.left, -bars.top, -bars.right, -bars.bottom)
                    takeoverRoot.layoutParams = lp
                }
            }
            // v4.9.8: the full screen lyrics fill the whole screen too, behind both bars
            (screenFsLyrics.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
                if (lp.topMargin != -bars.top || lp.bottomMargin != -bars.bottom ||
                    lp.leftMargin != -bars.left || lp.rightMargin != -bars.right
                ) {
                    lp.setMargins(-bars.left, -bars.top, -bars.right, -bars.bottom)
                    screenFsLyrics.layoutParams = lp
                }
            }
            if (::fsLyrics.isInitialized) fsLyrics.setInsets(bars.top, bars.bottom)
            margins(artistStatusScrim, 0, bars.top)
            margins(artistLogo, artistLogoBaseTop + bars.top)
            applySheetInsets(bars, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            margins(artistSearch, artistSearchBaseTop + bars.top)
            artistHeader.extraTop = bars.top
            updateArtistScrim()
            insets
        }
        // Screens open and close by visibility; keep the status bar right whenever they change
        root.viewTreeObserver.addOnGlobalLayoutListener {
            updateArtistScrim()
            updateSystemBars()
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** Fills in behind the status bar as the header's bottom edge reaches it. */
    private fun updateArtistScrim() {
        if (!::artistStatusScrim.isInitialized) return
        val inset = statusInset
        val a = if (inset <= 0) 0f else {
            val headerBottom = artistHeader.bottom - artistScroll.scrollY
            ((2f * inset - headerBottom) / inset).coerceIn(0f, 1f)
        }
        if (artistStatusScrim.alpha != a) {
            artistStatusScrim.alpha = a
            updateSystemBars()
        }
    }

    /**
     * v4.6.1: status bar icons. On the Artist page (with nothing on top of it) they follow the
     * photo behind them, or the solid block when there is no photo; everywhere else they follow
     * the app's background exactly as before. The strip that covers the status bar shows
     * whenever another screen sits on top of the Artist page.
     */
    private fun updateSystemBars() {
        if (!::statusCover.isInitialized || !::artistStatusScrim.isInitialized) return
        val artistOnTop = isArtistOpen && !(isShelfOpen || isListOpen || isSearchOpen ||
            isNowPlayingOpen || isLyricsOpen || isQueueOpen || isSettingsOpen)
        val cover = if (isArtistOpen && !artistOnTop) View.VISIBLE else View.GONE
        if (statusCover.visibility != cover) statusCover.visibility = cover
        // v4.9.8: the strip fades away under the full screen lyrics (applyFsLyricsChrome)
        if (::fsLyrics.isInitialized && !fsLyrics.isShowing && statusCover.alpha != 1f) statusCover.alpha = 1f
        val p = pal
        var lightStatus = if (artistOnTop && artistStatusScrim.alpha < 0.5f) {
            if (artistHasPhoto) statusOnLight else Painter.isLight(p.fg)
        } else {
            Painter.isLight(p.bg)
        }
        var lightNav = Painter.isLight(p.bg)
        // v4.9.3: during the top artist takeover the bars follow the photo
        if (isTakeoverActive && takeoverT >= 0.5f && tab == Tab.HOME && !isArtistOpen && !isShelfOpen &&
            !isSearchOpen && !isListOpen && !isNowPlayingOpen && !isLyricsOpen && !isQueueOpen && !isSettingsOpen
        ) {
            lightStatus = takeoverLight
            lightNav = takeoverLight
        }
        // v4.9.8: over the blurred cover the icons follow the lyric ink, switching halfway in
        // (and back halfway out, so leaving gives Now Playing's icons back exactly)
        if (::fsLyrics.isInitialized && fsLyrics.isShowing && FullscreenLyricsLook.chromeSwitched(fsLyrics.mix)) {
            lightStatus = fsLyrics.inkIsDark
            lightNav = fsLyrics.inkIsDark
        }
        if (isWelcomeOpen) {
            // White welcome screens: dark clock, battery and navigation icons
            lightStatus = true
            lightNav = true
        }
        // v4.9.1: the recap story covers everything; its bars follow the slide's own colours
        val storyBars = recapStoryLightBars
        if (recapStory != null && storyBars != null) {
            lightStatus = storyBars
            lightNav = storyBars
        }
        if (appliedLightStatus == lightStatus && appliedLightNav == lightNav) return
        appliedLightStatus = lightStatus
        appliedLightNav = lightNav
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightStatus
            isAppearanceLightNavigationBars = lightNav
        }
    }

    /** Header colours: from the photo when there is one, else a solid block in the song's colours. */
    private fun paintArtistHeader(p: Pal) {
        if (!::artistHeader.isInitialized) return
        val lightTone = if (Painter.isLight(p.bg)) p.bg else p.fg
        val darkTone = if (Painter.isLight(p.bg)) p.fg else p.bg
        fun pill(v: View, fill: Int) { v.backgroundTintList = android.content.res.ColorStateList.valueOf(fill) }
        if (artistHasPhoto) {
            pill(artistHeader, darkTone)
            artistFade.visibility = View.VISIBLE
            artistLogo.setTextColor(if (logoOverPage) p.fg else if (logoOnLight) darkTone else lightTone)
            var searchFill = if (searchOnLight) darkTone else lightTone
            var searchInk = if (searchOnLight) lightTone else darkTone
            if (searchOverPage) { searchFill = p.fg; searchInk = p.bg }
            pill(artistSearch, searchFill)
            artistSearchText.setTextColor(searchInk)
            artistSearchIcon.imageTintList = android.content.res.ColorStateList.valueOf(searchInk)
            // The name sits on the dark fade, so it is always light
            artistName.setTextColor(lightTone)
            artistSubs.setTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(lightTone, 0xDD))
            pill(artistShuffle, lightTone)
            artistShuffle.setTextColor(darkTone)
        } else {
            pill(artistHeader, p.fg)
            artistFade.visibility = View.GONE
            // v4.8: past the solid block (pinned, page scrolled far) they switch to the page colours
            artistLogo.setTextColor(if (logoOverPage) p.fg else p.bg)
            val searchFill = if (searchOverPage) p.fg else p.bg
            val searchInk = if (searchOverPage) p.bg else p.fg
            pill(artistSearch, searchFill)
            artistSearchText.setTextColor(searchInk)
            artistSearchIcon.imageTintList = android.content.res.ColorStateList.valueOf(searchInk)
            artistName.setTextColor(p.bg)
            artistSubs.setTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(p.bg, 0xDD))
            pill(artistShuffle, p.bg)
            artistShuffle.setTextColor(p.fg)
        }
    }

    private fun showArtistScreen(fromSearch: Boolean) {
        if (!isArtistOpen) {
            artistReturn = ArtistReturn(
                search = fromSearch || isSearchOpen,
                listId = if (isListOpen) openListId else null,
                listName = openListName,
                shelf = if (isShelfOpen) openShelf?.takeIf { !it.key.startsWith(ARTIST_SHELF) } else null
            )
        }
        // The Artist page sits under Search, the song list and the shelf grid: close them
        if (isSearchOpen) closeSearch()
        if (isListOpen) closeList()
        if (isShelfOpen) closeShelf()
        if (!isArtistOpen) {
            screenArtist.visibility = View.VISIBLE
            if (animationsOn()) {
                screenArtist.alpha = 0f
                screenArtist.translationX = dp(24f)
                screenArtist.animate().alpha(1f).translationX(0f).setDuration(220L)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
        updateChrome()
    }

    private fun closeArtist() {
        artistJob?.cancel()
        screenArtist.animate().cancel()
        screenArtist.alpha = 1f
        screenArtist.translationX = 0f
        screenArtist.visibility = View.GONE
        val back = artistReturn
        artistReturn = null
        if (artistHasError) {
            // Nothing worth keeping: next time starts fresh
            artistPage = null
        }
        if (back != null) {
            back.shelf?.let { openShelfPage(it) }
            back.listId?.let { openList(it, back.listName) }
            if (back.search) screenSearch.visibility = View.VISIBLE
        }
        updateChrome()
    }

    /** An album from the Artist page, in the playlist screen. */
    private fun openAlbum(album: Album) {
        haptic(screenList)
        openListId = null
        openAlbum = album
        listTitle.text = album.title
        listSubtitle.text = album.subtitle
        listAdapter.submitList(emptyList())
        findViewById<RecyclerView>(R.id.list_tracks).scrollToPosition(0)
        listEntrance.expect()
        screenList.visibility = View.VISIBLE
        updateChrome()
        loadAlbum(album)
    }

    private fun loadAlbum(album: Album) {
        albumJob?.cancel()
        albumHasError = false
        listEmpty.text = getString(R.string.album_loading)
        listEmpty.visibility = View.VISIBLE
        albumJob = lifecycleScope.launch {
            val result = app.sourceAdapter.fetchAlbumTracks(album)
            if (openAlbum?.url != album.url) return@launch
            when (result) {
                is SourceResult.Success -> {
                    listAdapter.submitList(result.data)
                    listAdapter.setActiveTrack(app.playbackController.uiState.value.currentTrack?.id)
                    listEntrance.loaded(result.data.size)
                    listSubtitle.text = listOf(album.subtitle, songCount(result.data.size))
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    if (result.data.isEmpty()) {
                        listEmpty.text = getString(R.string.album_empty)
                        listEmpty.visibility = View.VISIBLE
                    } else {
                        listEmpty.visibility = View.GONE
                    }
                }
                is SourceResult.Error -> {
                    albumHasError = true
                    listEmpty.text = getString(R.string.album_error)
                    listEmpty.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun markNotInterested(track: Track) {
        lifecycleScope.launch {
            app.database.markNotInterested(track)
            app.discoverEngine.hideTrack(track.id)?.let { showDiscover(it, afterBuild = false) }
            showNote(getString(R.string.not_interested_done))
        }
    }

    private fun openList(id: Long, name: String) {
        openAlbum = null
        albumJob?.cancel()
        openListId = id
        openListName = name
        listTitle.text = name
        listSubtitle.text = ""
        listAdapter.submitList(emptyList())
        listEmpty.visibility = View.GONE
        listEntrance.expect()
        screenList.visibility = View.VISIBLE
        updateChrome()
        lifecycleScope.launch { refreshOpenList() }
    }

    private fun closeList() {
        openListId = null
        openAlbum = null
        albumJob?.cancel()
        screenList.visibility = View.GONE
        updateChrome()
    }

    private suspend fun refreshOpenList() {
        val id = openListId ?: return
        val tracks = when (id) {
            LIKED_LIST -> app.database.getLikedTracks()
            RECENTS_LIST -> app.database.getRecentTracks(50)
            else -> app.database.getPlaylistTracks(id)
        }
        if (openListId != id) return
        listAdapter.submitList(tracks)
        // v4.9.9: rises in on opening; later refreshes (a like, a play) just update
        listEntrance.loaded(tracks.size)
        listSubtitle.text = songCount(tracks.size)
        listEmpty.text = getString(
            when (id) {
                LIKED_LIST -> R.string.liked_empty
                RECENTS_LIST -> R.string.recents_empty
                else -> R.string.playlist_empty
            }
        )
        listEmpty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun songCount(n: Int): String =
        if (n == 1) getString(R.string.song_count_one) else getString(R.string.song_count, n)

    /** Reloads Recents, Library and the open list from the database. */
    private suspend fun refreshData() {
        val recent = app.database.getRecentTracks(50)
        recentTracks = recent

        val liked = app.database.getLikedTracks()
        val playlists = app.database.getPlaylists()
        val tiles = ArrayList<GridTile>(playlists.size + 2)
        // Design order: Liked Songs, Recents (v4.9, was a tab), New Playlist, then your playlists.
        tiles.add(GridTile.Liked(liked.size, liked.firstOrNull()?.thumbnailUrl))
        tiles.add(GridTile.RecentsTile(recent.size))
        tiles.add(GridTile.NewPlaylist)
        for (p in playlists) {
            val cover = app.database.getPlaylistTracks(p.id).firstOrNull()?.thumbnailUrl
            tiles.add(GridTile.PlaylistTile(p, cover))
        }
        libraryAdapter.submit(tiles)
        // v4.5: playlists or likes may have changed: the fun greetings re-read on their next turn
        greetingFactsAt = 0L

        val openId = openListId
        if (openId != null && openId >= 0L && playlists.none { it.id == openId }) {
            closeList()
        } else {
            refreshOpenList()
        }
        refreshStats()
    }

    private fun scheduleRefresh(delayMs: Long = 0L) {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            if (delayMs > 0) delay(delayMs)
            refreshData()
        }
    }

    // ==========================================================================================
    // Mini players, Now Playing, Lyrics, Queue
    // ==========================================================================================

    private fun setupMiniPlayers() {
        for (mini in listOf(homeMini, floatMini)) {
            mini.root.setOnClickListener { expandNowPlaying() }
            mini.play.setOnClickListener {
                haptic(it)
                app.playbackController.togglePlayPause()
            }
            mini.next.setOnClickListener {
                haptic(it)
                app.playbackController.skipNext()
            }
            mini.like.setOnClickListener {
                haptic(it)
                toggleLikeCurrent()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupNowPlaying() {
        findViewById<View>(R.id.np_back).setOnClickListener { collapseNowPlaying() }
        // v4.9.7: 3 dot menu at the top right (Add to playlist, Go to artist, Share, Sleep timer)
        findViewById<View>(R.id.np_more).setOnClickListener { v ->
            haptic(v)
            showNowPlayingMenu()
        }
        // v4.9.8: "Up Next" pill (was "swipe up for lyrics"; the long-press for Up next is gone)
        findViewById<View>(R.id.np_up_next).setOnClickListener { v ->
            haptic(v)
            openQueue()
        }
        // v4.9.8: tap the lyric line for full screen lyrics (only while the song has lyrics)
        npLyric.setOnClickListener { v ->
            if (!NowPlayingLyricSlot.isTappable(npLyricMode)) return@setOnClickListener
            haptic(v)
            openFullscreenLyrics()
        }
        applyNpLyricMode()
        setupControls(npControls)
        // Art at 73% of the width (as before); smaller only if the screen is too short for it all
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) root.post { fitNowPlaying() }
        }

        // Album-art gestures (each can be changed or turned off in Settings)
        val artGestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (app.preferences.gestureSwipeAction == AppPreferences.GESTURE_DISABLED || e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                // Sideways only
                if (abs(dx) > dp(40f) && abs(velocityX) > 200f && abs(dx) > abs(dy)) {
                    haptic(npArt)
                    if (dx < 0) app.playbackController.skipNext() else app.playbackController.skipPrevious()
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                when (app.preferences.gestureDoubleTapAction) {
                    AppPreferences.GESTURE_DTAP_PLAY_PAUSE -> app.playbackController.togglePlayPause()
                    AppPreferences.GESTURE_DTAP_LIKE -> toggleLikeCurrent()
                    else -> return false
                }
                haptic(npArt)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                when (app.preferences.gestureLongPressAction) {
                    AppPreferences.GESTURE_LPRESS_LYRICS -> openFullscreenLyrics()
                    else -> return
                }
                haptic(npArt, HapticFeedbackConstants.LONG_PRESS)
            }
        })
        npArt.setOnTouchListener { _, event ->
            artGestures.onTouchEvent(event)
            true
        }
    }

    /**
     * Now Playing sizing: art and controls keep 73% of the width like before. On a short screen
     * the art alone gets smaller so everything still fits; the Space views share what is left.
     */
    private fun fitNowPlaying() {
        val group = npContent as? ViewGroup ?: return
        // The space inside the root's system-bar padding (the window draws behind the bars)
        val w = root.width - root.paddingLeft - root.paddingRight
        val h = root.height - root.paddingTop - root.paddingBottom
        if (w <= 0 || h <= 0) return
        val wanted = (w * 0.73f).roundToInt()
        var used = group.paddingTop + group.paddingBottom
        val widthSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child === npArt || child.visibility == View.GONE) continue
            val lp = child.layoutParams as ViewGroup.MarginLayoutParams
            used += lp.topMargin + lp.bottomMargin
            used += if (child is android.widget.Space || lp.height > 0) {
                lp.height.coerceAtLeast(0)
            } else {
                child.measure(widthSpec, heightSpec)
                child.measuredHeight
            }
        }
        val size = minOf(wanted, h - used).coerceAtLeast(dp(150f).roundToInt())
        val artLp = npArt.layoutParams
        if (artLp.width != size || artLp.height != size) {
            artLp.width = size
            artLp.height = size
            npArt.layoutParams = artLp
        }
        val cLp = npControlsRoot.layoutParams
        if (cLp.width != wanted) {
            cLp.width = wanted
            npControlsRoot.layoutParams = cLp
        }
    }

    /** v4.9.8: the full screen lyrics mode (replaces the old lyrics screen). */
    private fun setupLyrics() {
        fsLyrics = FullscreenLyrics(screenFsLyrics, app.artworkPipeline, lifecycleScope, object : FullscreenLyrics.Host {
            override val nowPlayingContent: View get() = npContent
            override val nowPlayingBar: ProgressLine get() = npControls.progress
            override fun palette(): Pal = pal
            override fun coverLuminance(bitmap: android.graphics.Bitmap, viewW: Int, viewH: Int): Double =
                areaLuminance(bitmap, viewW, viewH, 0f, 1f, 0f, 1f, withFade = false)
            override fun animationsOn(): Boolean = this@MainActivity.animationsOn()
            override fun togglePlayPause(): Boolean {
                val wasPlaying = app.playbackController.uiState.value.isPlaying
                app.playbackController.togglePlayPause()
                return wasPlaying
            }
            override fun seek(fraction: Float, finished: Boolean) = seekFromBar(fraction, finished, fsLyrics.bar)
            override fun onChromeChanged() = applyFsLyricsChrome()
        })
        fsLyrics.flowStyle = app.preferences.isFlowLyrics
        npLyric.flowStyle = app.preferences.isFlowLyrics
        npLyric.animationsOn = { animationsOn() }
    }

    /**
     * v4.9.8: while the full screen lyrics show, the navigation bar fades to see-through over the
     * blurred cover (like the takeover), the strip over the Artist page's status bar fades with
     * it, and the status bar icons follow the lyric ink (updateSystemBars).
     */
    private fun applyFsLyricsChrome() {
        if (!::fsLyrics.isInitialized) return
        // v4.9.9: once Now Playing has faded out under the full screen lyrics, its lyric line stops drawing
        npLyric.setCovered(fsLyrics.isShowing && FullscreenLyricsLook.nowPlayingAlpha(fsLyrics.mix) <= 0f)
        val k = fsLyrics.backdropMix
        val p = pal
        window.navigationBarColor = (p.bg and 0x00FFFFFF) or (((1f - k) * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24)
        if (::statusCover.isInitialized) statusCover.alpha = 1f - k
        updateSystemBars()
    }

    private fun setupControls(c: Controls) {
        c.like.setOnClickListener {
            haptic(it)
            toggleLikeCurrent()
        }
        c.prev.setOnClickListener {
            haptic(it)
            app.playbackController.skipPrevious()
        }
        c.play.setOnClickListener {
            haptic(it)
            app.playbackController.togglePlayPause()
        }
        c.next.setOnClickListener {
            haptic(it)
            app.playbackController.skipNext()
        }
        c.repeat.setOnClickListener {
            haptic(it)
            app.playbackController.toggleRepeat()
        }
        c.progress.onSeek = { fraction, finished -> seekFromBar(fraction, finished, c.progress) }
    }

    /** Dragging either progress bar (Now Playing's or the full screen lyrics' one) seeks. */
    private fun seekFromBar(fraction: Float, finished: Boolean, source: ProgressLine) {
        val durationMs = app.playbackController.uiState.value.durationMs
        if (durationMs > 0L) {
            val target = (fraction * durationMs).toLong()
            if (finished) {
                isSeeking = false
                app.playbackController.seekTo(target)
            } else {
                isSeeking = true
            }
            // Preview the time while dragging, and keep the other bar in step
            npControls.elapsed.text = ArtworkPipeline.formatDurationSec(target / 1000L)
            npControls.remaining.text = formatRemaining(durationMs, target)
            for (bar in listOf(npControls.progress, fsLyrics.bar)) if (bar !== source) bar.setFraction(fraction)
        } else if (finished) {
            isSeeking = false
        }
    }

    private fun expandNowPlaying() {
        if (app.playbackController.uiState.value.currentTrack == null) return
        hideKeyboard()
        screenNowPlaying.visibility = View.VISIBLE
        updateChrome()
        // v4.9.8: blur the cover now, so the full screen lyrics open without a wait
        fsLyrics.prefetch()
    }

    private fun collapseNowPlaying() {
        fsLyrics.close(animate = false)
        screenNowPlaying.visibility = View.GONE
        updateChrome()
    }

    /**
     * v4.9.8 entry point for the full screen lyrics: the tap on the Now Playing lyric line and
     * the Lyrics long press. Only songs with lyrics get here (NowPlayingLyricSlot blocks the rest).
     */
    private fun openFullscreenLyrics() {
        if (app.playbackController.uiState.value.currentTrack == null) return
        if (!isNowPlayingOpen) return
        // The right line first, so the list opens already centred on it
        updateLyricLine(app.playbackController.uiState.value.positionMs, animate = false)
        fsLyrics.open()
        updateChrome()
    }

    /** Back from the full screen lyrics: the whole animation in reverse. */
    private fun closeLyrics(animate: Boolean = true) {
        fsLyrics.close(animate)
        updateChrome()
    }

    private fun setupQueue() {
        queueAdapter = QueueAdapter(
            artwork = app.artworkPipeline,
            onItemClick = { index -> app.playbackController.playQueueIndex(index) },
            onMoveUp = { index -> if (index > 0) app.playbackController.moveQueueItem(index, index - 1) },
            onMoveDown = { index -> app.playbackController.moveQueueItem(index, index + 1) },
            onRemove = { index -> app.playbackController.removeQueueItem(index) },
            onLongClick = { index ->
                app.playbackController.uiState.value.queue.getOrNull(index)?.let { t ->
                    showTrackOptions(null, t, queueIndex = index)
                }
            }
        )
        findViewById<RecyclerView>(R.id.queue_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = queueAdapter
            // v4.9.9: moved rows glide, removed rows fade and the gap closes
            itemAnimator = QueueItemAnimator()
        }
        findViewById<View>(R.id.queue_close).setOnClickListener { closeQueue() }
    }

    private fun openQueue() {
        screenQueue.visibility = View.VISIBLE
        updateChrome()
    }

    private fun closeQueue() {
        screenQueue.visibility = View.GONE
        updateChrome()
    }

    private fun toggleLikeCurrent() {
        val track = app.playbackController.uiState.value.currentTrack ?: return
        lifecycleScope.launch {
            app.database.toggleLike(track)
            refreshData()
        }
    }

    // ==========================================================================================
    // Settings
    // ==========================================================================================

    private fun setupSettings() {
        findViewById<View>(R.id.settings_close).setOnClickListener { closeSettings() }

        settingsName.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveName()
                v.clearFocus()
                hideKeyboard()
                true
            } else {
                false
            }
        }
        settingsName.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveName() }

        // Theme: the app's own Light / Dark setting
        option(R.id.set_theme_light) { setThemeMode(AppPreferences.THEME_LIGHT) }
        option(R.id.set_theme_dark) { setThemeMode(AppPreferences.THEME_DARK) }
        option(R.id.set_colours_dynamic) { setColourMode(AppPreferences.COLOURS_DYNAMIC) }
        option(R.id.set_colours_default) { setColourMode(AppPreferences.COLOURS_DEFAULT) }

        val p = app.preferences
        option(R.id.set_swipe_skip) { p.gestureSwipeAction = AppPreferences.GESTURE_SWIPE_SKIP }
        option(R.id.set_swipe_off) { p.gestureSwipeAction = AppPreferences.GESTURE_DISABLED }
        option(R.id.set_dtap_play) { p.gestureDoubleTapAction = AppPreferences.GESTURE_DTAP_PLAY_PAUSE }
        option(R.id.set_dtap_like) { p.gestureDoubleTapAction = AppPreferences.GESTURE_DTAP_LIKE }
        option(R.id.set_dtap_off) { p.gestureDoubleTapAction = AppPreferences.GESTURE_DISABLED }
        option(R.id.set_lpress_lyrics) { p.gestureLongPressAction = AppPreferences.GESTURE_LPRESS_LYRICS }
        option(R.id.set_lpress_off) { p.gestureLongPressAction = AppPreferences.GESTURE_DISABLED }
        // v4.9.8 part 4: Lyrics style, applied live (also while full screen lyrics is open)
        option(R.id.set_lyrics_flow) {
            p.lyricsStyle = AppPreferences.LYRICS_STYLE_FLOW
            fsLyrics.flowStyle = true
            npLyric.flowStyle = true
        }
        option(R.id.set_lyrics_line) {
            p.lyricsStyle = AppPreferences.LYRICS_STYLE_LINE
            fsLyrics.flowStyle = false
            npLyric.flowStyle = false
        }
        option(R.id.set_boost_off) { p.volumeBoost = AppPreferences.BOOST_OFF }
        option(R.id.set_boost_normal) { p.volumeBoost = AppPreferences.BOOST_NORMAL }
        option(R.id.set_boost_loud) { p.volumeBoost = AppPreferences.BOOST_LOUD }
        option(R.id.set_fade_off) { p.crossfadeSeconds = 0 }
        option(R.id.set_fade_2) { p.crossfadeSeconds = 2 }
        option(R.id.set_fade_4) { p.crossfadeSeconds = 4 }
        option(R.id.set_haptics_on) {
            p.hapticsEnabled = true
            haptic(findViewById(R.id.set_haptics_on))
        }
        option(R.id.set_haptics_off) { p.hapticsEnabled = false }

        // Lyrics timing: - / + in 50 ms steps (hold to repeat), tap the value to reset to +300 ms
        setupLyricsStep(findViewById(R.id.set_lyrics_minus), -AppPreferences.LYRICS_OFFSET_STEP)
        setupLyricsStep(findViewById(R.id.set_lyrics_plus), AppPreferences.LYRICS_OFFSET_STEP)
        findViewById<View>(R.id.set_lyrics_value).setOnClickListener { v ->
            if (p.lyricsOffsetMs != AppPreferences.LYRICS_OFFSET_DEFAULT) haptic(v, HapticFeedbackConstants.CLOCK_TICK)
            setLyricsOffset(AppPreferences.LYRICS_OFFSET_DEFAULT)
        }

        option(R.id.set_sleep_off) {
            sleepChoice = 0
            app.sleepTimerManager.cancelTimer()
        }
        for ((id, minutes) in listOf(R.id.set_sleep_15 to 15, R.id.set_sleep_30 to 30, R.id.set_sleep_60 to 60)) {
            option(id) {
                sleepChoice = minutes
                app.sleepTimerManager.startTimer(minutes)
            }
        }

        findViewById<View>(R.id.set_clear_cache).setOnClickListener {
            app.artworkPipeline.clearAllCaches()
            app.lyricsProvider.clearCache()
            Toast.makeText(this, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
        }

        // "4.1.0 (build 5)": the version without the "-debug" ending, then the build number
        var version = ""
        var build = 0
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            version = info.versionName?.substringBefore('-').orEmpty()
            build = info.longVersionCode.toInt()
        } catch (_: Exception) {
        }
        findViewById<TextView>(R.id.set_about).text = getString(R.string.about_text, version, build)

        // v4.9.6: "Check for updates" with the version beside it
        findViewById<TextView>(R.id.set_check_updates_version).text =
            getString(R.string.update_settings_version, version)
        findViewById<View>(R.id.set_check_updates).setOnClickListener {
            haptic(it)
            checkForUpdatesNow()
        }
    }

    /** A lyrics timing button: one step per tap, repeating every 80 ms while held. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupLyricsStep(button: View, delta: Int) {
        val repeater = object : Runnable {
            override fun run() {
                if (stepLyricsOffset(button, delta)) button.postDelayed(this, 80L)
            }
        }
        button.setOnClickListener { stepLyricsOffset(button, delta) }
        button.setOnLongClickListener {
            button.post(repeater)
            true
        }
        button.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                button.removeCallbacks(repeater)
            }
            false
        }
    }

    /** Moves the lyrics offset one step; false once it hits the end of the range. */
    private fun stepLyricsOffset(button: View, delta: Int): Boolean {
        val old = app.preferences.lyricsOffsetMs
        val next = (old + delta).coerceIn(AppPreferences.LYRICS_OFFSET_MIN, AppPreferences.LYRICS_OFFSET_MAX)
        if (next == old) return false
        haptic(button, HapticFeedbackConstants.CLOCK_TICK)
        setLyricsOffset(next)
        return true
    }

    private fun setLyricsOffset(ms: Int) {
        app.preferences.lyricsOffsetMs = ms
        renderLyricsOffset()
        // Takes effect straight away on the playing song
        updateLyricLine(app.playbackController.uiState.value.positionMs, animate = true)
    }

    private fun renderLyricsOffset() {
        val ms = app.preferences.lyricsOffsetMs
        val text = when {
            ms > 0 -> getString(R.string.lyrics_timing_value_pos, ms)
            ms < 0 -> getString(R.string.lyrics_timing_value_neg, -ms)
            else -> getString(R.string.lyrics_timing_value, 0)
        }
        findViewById<TextView>(R.id.set_lyrics_value).apply {
            this.text = text
            contentDescription = text + ". " + getString(R.string.lyrics_timing_reset)
        }
    }

    /** A settings option pill: runs [action], then refreshes which pills are selected. */
    private fun option(id: Int, action: () -> Unit) {
        findViewById<View>(id).setOnClickListener {
            action()
            renderSettingsOptions()
        }
    }

    private fun setThemeMode(mode: Int) {
        if (app.preferences.themeMode == mode) return
        app.preferences.themeMode = mode
        animatePaletteTo(appPal())
    }

    /** v4.9.4: Dynamic / Default colours, applied straight away with the usual colour fade. */
    private fun setColourMode(mode: Int) {
        if (app.preferences.colourMode == mode) return
        app.preferences.colourMode = mode
        if (isTakeoverActive) buildTakeoverPals()
        animatePaletteTo(appPal())
    }

    private fun renderSettingsOptions() {
        val p = app.preferences
        fun sel(id: Int, on: Boolean) {
            findViewById<View>(id).isSelected = on
        }
        sel(R.id.set_theme_light, !p.isDarkTheme)
        sel(R.id.set_theme_dark, p.isDarkTheme)
        sel(R.id.set_colours_dynamic, p.isDynamicColours)
        sel(R.id.set_colours_default, !p.isDynamicColours)
        sel(R.id.set_swipe_skip, p.gestureSwipeAction == AppPreferences.GESTURE_SWIPE_SKIP)
        sel(R.id.set_swipe_off, p.gestureSwipeAction == AppPreferences.GESTURE_DISABLED)
        sel(R.id.set_dtap_play, p.gestureDoubleTapAction == AppPreferences.GESTURE_DTAP_PLAY_PAUSE)
        sel(R.id.set_dtap_like, p.gestureDoubleTapAction == AppPreferences.GESTURE_DTAP_LIKE)
        sel(R.id.set_dtap_off, p.gestureDoubleTapAction == AppPreferences.GESTURE_DISABLED)
        sel(R.id.set_lpress_lyrics, p.gestureLongPressAction == AppPreferences.GESTURE_LPRESS_LYRICS)
        sel(R.id.set_lpress_off, p.gestureLongPressAction == AppPreferences.GESTURE_DISABLED)
        sel(R.id.set_lyrics_flow, p.isFlowLyrics)
        sel(R.id.set_lyrics_line, !p.isFlowLyrics)
        sel(R.id.set_boost_off, p.volumeBoost == AppPreferences.BOOST_OFF)
        sel(R.id.set_boost_normal, p.volumeBoost == AppPreferences.BOOST_NORMAL)
        sel(R.id.set_boost_loud, p.volumeBoost == AppPreferences.BOOST_LOUD)
        sel(R.id.set_fade_off, p.crossfadeSeconds == 0)
        sel(R.id.set_fade_2, p.crossfadeSeconds == 2)
        sel(R.id.set_fade_4, p.crossfadeSeconds == 4)
        sel(R.id.set_haptics_on, p.hapticsEnabled)
        sel(R.id.set_haptics_off, !p.hapticsEnabled)
        sel(R.id.set_sleep_off, sleepChoice == 0)
        sel(R.id.set_sleep_15, sleepChoice == 15)
        sel(R.id.set_sleep_30, sleepChoice == 30)
        sel(R.id.set_sleep_60, sleepChoice == 60)
        renderLyricsOffset()
        Painter.paint(screenSettings, pal)
    }

    private fun openSettings() {
        settingsName.setText(app.preferences.userName)
        renderSettingsOptions()
        screenSettings.visibility = View.VISIBLE
        updateChrome()
    }

    private fun closeSettings() {
        saveName()
        settingsName.clearFocus()
        hideKeyboard()
        screenSettings.visibility = View.GONE
        renderGreeting(animate = false)
        updateChrome()
    }

    private fun saveName() {
        app.preferences.userName = settingsName.text?.toString().orEmpty()
        renderGreeting(animate = false)
    }

    // ==========================================================================================
    // Observing playback, lyrics, likes and the sleep timer
    // ==========================================================================================

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.playbackController.uiState.collect { renderPlayback(it) } }
                launch { app.playbackController.lyricsState.collect { renderLyrics(it) } }
                // v4.9.6: update download progress / done / failed
                launch { app.updates.state.collect { onUpdateFlowState(it) } }
                launch { app.database.likedIds.collect { renderLikes(it); greetingFactsAt = 0L } }
                // v4.3: a counted play (30+ s) can add a song to Recents
                launch {
                    app.playbackController.playsVersion.collect {
                        if (it > 0) {
                            greetingFactsAt = 0L
                            scheduleRefresh(300L)
                        }
                    }
                }
                // v4.9: live Stats while listening (saved every few seconds), and a new day at midnight
                launch {
                    app.playbackController.listenVersion.collect {
                        if (it > 0 && tab == Tab.STATS) refreshStats()
                    }
                }
                launch {
                    while (isActive) {
                        delay(15_000L)
                        if (statsDay != 0 && Days.keyOf(System.currentTimeMillis()) != statsDay) refreshStats()
                    }
                }
                launch {
                    app.sleepTimerManager.remainingTimeMs.collect { remainingMs ->
                        if (remainingMs > 0L) {
                            setSleepStatus.text = getString(
                                R.string.sleep_status,
                                ArtworkPipeline.formatDurationSec((remainingMs + 999L) / 1000L)
                            )
                            setSleepStatus.visibility = View.VISIBLE
                        } else {
                            setSleepStatus.visibility = View.GONE
                            if (sleepChoice != 0) {
                                sleepChoice = 0
                                if (isSettingsOpen) renderSettingsOptions()
                            }
                        }
                    }
                }
                launch {
                    while (isActive) {
                        delay(250L)
                        app.playbackController.refreshProgress()
                        updateLyricLine(app.playbackController.uiState.value.positionMs, animate = true)
                    }
                }
            }
        }
    }

    private fun renderPlayback(state: PlaybackUiState) {
        val track = state.currentTrack
        updateChrome()
        listAdapter.setActiveTrack(track?.id)
        searchAdapter.setActiveTrack(track?.id)
        shelfAdapter.setActiveTrack(track?.id)
        shelfGridAdapter.setActiveTrack(track?.id)
        artistSongs.setActiveTrack(track?.id)
        queueAdapter.submitQueue(state.queue, state.currentIndex)
        queueEmpty.visibility = if (state.queue.isEmpty()) View.VISIBLE else View.GONE

        if (track == null) {
            renderedTrackId = null
            if (isNowPlayingOpen || isLyricsOpen || isQueueOpen) {
                screenQueue.visibility = View.GONE
                collapseNowPlaying()
            }
            setPaletteSeed(0)
            return
        }

        // v4.9.9: a new song's title and artist fade in over the old ones (not on the first song)
        val fadeText = renderedTrackId != null && renderedTrackId != track.id
        for (mini in listOf(homeMini, floatMini)) {
            textFade.set(mini.title, track.title, fadeText) { restartMarquee(mini) }
            textFade.set(mini.artist, track.artist, fadeText) { restartMarquee(mini) }
            mini.play.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            mini.play.contentDescription = getString(if (state.isPlaying) R.string.pause else R.string.play)
        }
        textFade.set(npTitle, track.title, fadeText)
        textFade.set(npArtist, track.artist, fadeText)
        fsLyrics.onTrack(track.thumbnailUrl)
        // v4.9.8 part 4: a new song while Now Playing is open: get its full screen lyrics cover
        // ready now, so opening shows it at once (a no-op once loaded, or while that mode is open)
        if (isNowPlayingOpen) fsLyrics.prefetch()

        if (renderedTrackId != track.id) {
            val hadTrack = renderedTrackId != null
            renderedTrackId = track.id
            fitHomePill(animate = hadTrack)
            restartMarquee(floatMini)
            // v4.9.3: one of today's top artist's songs starting on Home brings the takeover
            if (hadTrack) onTakeoverTrackChanged()
            val density = resources.displayMetrics.density
            val miniPx = (40 * density).toInt().coerceAtLeast(64)
            // v4.9.8: Now Playing (and the coming full screen lyrics) get the HD cover, about screen width
            val bigPx = resources.displayMetrics.widthPixels.coerceIn(720, 1440)
            app.artworkPipeline.loadInto(homeMini.art, track.thumbnailUrl, miniPx)
            app.artworkPipeline.loadInto(floatMini.art, track.thumbnailUrl, miniPx)
            app.artworkPipeline.loadInto(npArt, track.thumbnailUrl, bigPx)
            // The new song is saved to Recents in the background; reload once it's there
            scheduleRefresh(800L)
        }
        // v4.9.8 part 4: play / pause reaches the Flow clock at once, not on the next tick
        if (state.isPlaying != renderedPlaying) {
            renderedPlaying = state.isPlaying
            updateLyricLine(state.positionMs, animate = true)
        }

        val playLabel = when {
            state.isPlaying -> R.string.pause
            state.isBuffering -> R.string.loading
            else -> R.string.play
        }
        val repeatIcon = if (state.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        val repeatDesc = when (state.repeatMode) {
            Player.REPEAT_MODE_ONE -> R.string.repeat_one
            Player.REPEAT_MODE_ALL -> R.string.repeat_all
            else -> R.string.repeat_off
        }
        if (!isSeeking) fsLyrics.setProgress(if (state.durationMs > 0L) state.positionMs.toFloat() / state.durationMs else 0f)
        for (c in listOf(npControls)) {
            c.play.setText(playLabel)
            c.repeat.setImageResource(repeatIcon)
            c.repeat.contentDescription = getString(repeatDesc)
            // Off: faded icon. All / One: full icon (One has its own "1" icon)
            c.repeat.imageAlpha = if (state.repeatMode == Player.REPEAT_MODE_OFF) 110 else 255
            if (!isSeeking) {
                c.elapsed.text = ArtworkPipeline.formatDurationSec(state.positionMs / 1000L)
                c.remaining.text = formatRemaining(state.durationMs, state.positionMs)
                c.progress.setFraction(
                    if (state.durationMs > 0L) state.positionMs.toFloat() / state.durationMs else 0f
                )
            }
        }
        renderLikes(app.database.likedIds.value)

        val error = state.errorMessage
        if (!error.isNullOrBlank() && error != lastErrorShown) {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            // The greeting said "Playing ..." but playback failed: put the greeting back
            if (greetingPlayTrackId != null) endGreetingMessage()
        }
        lastErrorShown = error

        setPaletteSeed(state.accentColor ?: 0)
    }

    private fun renderLikes(likedIds: Set<String>) {
        val id = app.playbackController.uiState.value.currentTrack?.id ?: return
        val liked = likedIds.contains(id)
        val icon = if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        val desc = getString(if (liked) R.string.unlike else R.string.like)
        for (v in listOf(homeMini.like, floatMini.like, npControls.like)) {
            v.setImageResource(icon)
            v.contentDescription = desc
        }
    }

    private fun renderLyrics(data: LyricsData?) {
        lyrics = data
        activeLyric = -1
        // Now Playing's line slot: empty until a synced line starts (and for unsynced lyrics);
        // v4.9.8: a faint "Lyrics not available" when the song has none
        npLyricMode = NowPlayingLyricSlot.mode(data)
        applyNpLyricMode()
        // v4.9.9: the line view fades out the old song's line in place (instant when it can't be seen)
        npLyric.setLyrics(data, getString(R.string.np_lyrics_unavailable), animate = true)
        // v4.9.8: the full screen lyrics get the same lyrics (untimed ones show without highlighting)
        fsLyrics.setLyrics(data)
        if (data != null && data.isSynced && data.lines.isNotEmpty()) {
            updateLyricLine(app.playbackController.uiState.value.positionMs, animate = false)
        }
    }

    /** The play state the lyrics last heard about (v4.9.8 part 4, for the Flow clock). */
    private var renderedPlaying = false

    private fun updateLyricLine(positionMs: Long, animate: Boolean) {
        val data = lyrics ?: return
        if (!data.isSynced || data.lines.isEmpty()) return
        // Lyrics timing: the file's own [offset:] plus the user's setting (+ = lines show earlier)
        val shifted = FlowTiming.shiftedPosition(positionMs, data.offsetMs, app.preferences.lyricsOffsetMs)
        // The current line is the last one that has started (-1 before the first line)
        var index = -1
        for (i in data.lines.indices) {
            if (data.lines[i].startMs <= shifted) index = i else break
        }
        if (index != activeLyric) {
            activeLyric = index
            fsLyrics.setActive(index, animate)
        }
        val playing = app.playbackController.uiState.value.isPlaying
        // v4.9.9: the Now Playing line (Flow word by word, slides to the next line, swaps on a seek)
        npLyric.onPosition(index, shifted, playing, animate)
        // v4.9.8 part 4: feeds Flow's per frame clock (and the active line's per word hook)
        fsLyrics.onLinePosition(shifted, playing)
    }

    /** v4.9.8: only a song with lyrics makes the slot tappable (and visible to TalkBack as a button). */
    private fun applyNpLyricMode() {
        val tappable = NowPlayingLyricSlot.isTappable(npLyricMode)
        npLyric.isClickable = tappable
        npLyric.isFocusable = tappable
        npLyric.contentDescription = when {
            tappable -> getString(R.string.np_open_lyrics)
            // v4.9.9: the slot is a layout now, so TalkBack reads the message from here
            npLyricMode == NowPlayingLyricSlot.Mode.NONE -> getString(R.string.np_lyrics_unavailable)
            else -> null
        }
        npLyric.importantForAccessibility =
            if (tappable || npLyricMode == NowPlayingLyricSlot.Mode.NONE) View.IMPORTANT_FOR_ACCESSIBILITY_YES
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** A light haptic tick, if it's on in Settings (the phone's own haptics setting also applies). */
    private fun haptic(v: View, type: Int = HapticFeedbackConstants.CONTEXT_CLICK) {
        if (!app.preferences.hapticsEnabled) return
        v.performHapticFeedback(type)
    }

    // ==========================================================================================
    // v4.9: Stats tab (replaces Recents) and the daily recap
    // ==========================================================================================

    private fun setupStats() {
        statsScreen.diveIn.setOnClickListener {
            haptic(it)
            openRecapStory(atCard = false)
        }
        statsScreen.openCard.setOnClickListener {
            haptic(it)
            openRecapStory(atCard = true)
        }
        statsScreen.hero.setOnClickListener {
            haptic(it)
            openRecapStory(atCard = false)
        }
        // v4.9.2: today's live boxes are tappable (the recap boxes and "listened" are not)
        statsScreen.todaySongsBox.setOnClickListener {
            if ((todayStats?.songs ?: 0) <= 0) return@setOnClickListener
            haptic(it)
            showTodaySongsSheet()
        }
        statsScreen.todayArtistsBox.setOnClickListener {
            if ((todayStats?.artists ?: 0) <= 0) return@setOnClickListener
            haptic(it)
            showTodayArtistsSheet()
        }
        statsScreen.todayArtistCard.setOnClickListener {
            val name = statsScreen.todayTopArtistName ?: return@setOnClickListener
            haptic(it)
            openArtistByName(name)
        }
        updateStatsBadges()
    }

    /** Reloads today's live numbers and the recap (cheap: one day of rows each). */
    private fun refreshStats() {
        if (!::statsScreen.isInitialized) return
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            try {
                val today = Days.keyOf(System.currentTimeMillis())
                val todayS = app.database.getDayStats(today)
                // Newest past day first, until a non-quiet one: quiet days don't replace a better
                // recap, but the most recent day's stats still show when it was quiet
                val past = ArrayList<DayStats>()
                for (d in app.database.pastHistoryDays(today)) {
                    val s = app.database.getDayStats(d)
                    past.add(s)
                    if (!s.isQuiet || past.size >= MAX_RECAP_LOOKBACK) break
                }
                val pick = RecapRules.pick(today, past)
                val recapS = pick.recap
                statsDay = today
                todayStats = todayS
                recapStats = recapS
                statsScreen.renderToday(todayS)
                todayS.topArtist?.name?.let { loadRecapArtistPhoto(it) }
                // v4.9.2: day streak (10+ min a day; today under 10 min never breaks it)
                val streakFrom = Days.key(Days.date(today).minusDays(DayStreak.MAX_DAYS.toLong()))
                statsScreen.renderStreak(DayStreak.count(today, app.database.getDayTotals(streakFrom, today)))
                statsScreen.renderQuietDay(pick.quietDay)
                statsScreen.renderRecap(recapS)
                recapS?.topArtist?.name?.let { loadRecapArtistPhoto(it) }
                // Looking at the Stats tab counts as checking the recap
                if (tab == Tab.STATS && recapS != null && !isWelcomeOpen) markRecapViewed(recapS)
                updateStatsBadges()
                onRecapLoaded()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun onStatsShown() {
        val prefs = app.preferences
        if (!prefs.statsTabSeen) prefs.statsTabSeen = true
        recapStats?.let { markRecapViewed(it) }
        updateStatsBadges()
        // v4.9.9: the big numbers count up from zero when the tab opens (not on tapping it again);
        // the refresh just below glides any that changed. Called before the tab changes over.
        if (tab != Tab.STATS) statsScreen.countUp()
        refreshStats()
    }

    /** "new" next to stats until the tab is opened once, then a dot while a recap is unchecked. */
    private fun updateStatsBadges() {
        val prefs = app.preferences
        val showNew = !prefs.statsTabSeen
        val showDot = !showNew && RecapRules.showDot(recapStats?.day, prefs.recapViewedDay)
        for ((_, row) in topRows) {
            row.statsNew.visibility = if (showNew) View.VISIBLE else View.GONE
            row.statsDot.visibility = if (showDot) View.VISIBLE else View.GONE
        }
    }

    /** The recap's top artist photo: cached, else found like the Artist page finds it. */
    private fun loadRecapArtistPhoto(name: String) {
        app.preferences.artistPhoto(name)?.let {
            statsScreen.showArtistPhoto(name, it)
            statsScreen.showTodayArtistPhoto(name, it)
            if (name == recapStats?.topArtist?.name) recapArtistPhoto = it
            return
        }
        if (!artistPhotoTried.add(name.lowercase())) return
        lifecycleScope.launch {
            val found = try {
                app.sourceAdapter.findArtist(name)
            } catch (_: Exception) {
                null
            }
            val ref = (found as? SourceResult.Success)?.data ?: return@launch
            val url = MusicSourceAdapter.upscaleYtImage(ref.thumbnailUrl, 600)
            if (url.isBlank()) return@launch
            app.preferences.setArtistPhoto(name, url)
            if (name == recapStats?.topArtist?.name) recapArtistPhoto = url
            statsScreen.showArtistPhoto(name, url)
            statsScreen.showTodayArtistPhoto(name, url)
        }
    }

    // ------------------------------------------------------------------------------------------
    // v4.9.3: top artist takeover. For 10 s the whole Home canvas becomes today's top artist's
    // photo (rules in ArtistTakeover). Home's background goes see-through over the photo, which
    // sits under Home; text and pills flip white/black with the photo, the mini pill takes the
    // photo's colours, and everything fades back to the song's colours after.
    // ------------------------------------------------------------------------------------------

    private lateinit var takeoverRoot: View
    private lateinit var takeoverPhoto: ImageView
    private lateinit var takeoverWash: View
    private var takeoverAnim: ValueAnimator? = null
    private var takeoverT = 0f
    private var takeoverLight = false
    private var takeoverHomePal: Pal? = null
    private var takeoverMiniPal: Pal? = null
    private var takeoverArtist: String? = null
    private var takeoverLinesShown: Pair<String, String>? = null
    private var takeoverPreparing = false
    private var takeoverPending = false
    private var lastTakeoverAt: Long? = null
    private val takeoverPicker = ArtistTakeover.LinePicker()
    private var topToday: com.minim.music.data.stats.TopArtist? = null
    private var topTodayAt = 0L
    private var takeoverDownX = 0f
    private var takeoverDownY = 0f
    private val isTakeoverActive get() = takeoverHomePal != null
    private var takeoverSeed = 0

    /**
     * Home and mini player colours for the takeover. Text stays white/black from the photo's
     * brightness either way; in Default colours (v4.9.4) the photo's own colour is left out,
     * so the Home player stays black and white.
     */
    private fun buildTakeoverPals() {
        val light = takeoverLight
        val ink = if (light) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        val photoPal = Pal.build(ColourMode.seedFor(takeoverSeed, app.preferences.isDynamicColours), darkMode = !light)
        takeoverHomePal = Pal(bg = photoPal.bg, fg = ink, muted = androidx.core.graphics.ColorUtils.setAlphaComponent(ink, 0x99))
        takeoverMiniPal = photoPal
    }

    private fun setupTakeover() {
        takeoverRoot = findViewById(R.id.home_takeover)
        takeoverPhoto = findViewById(R.id.home_takeover_photo)
        takeoverWash = findViewById(R.id.home_takeover_wash)
        // Tapping the background during the takeover opens that artist's page
        screenHome.setOnClickListener { v ->
            if (isTakeoverActive) {
                haptic(v)
                openTakeoverArtist()
            }
        }
    }

    /** Where a takeover may start: Home on screen with nothing over it. */
    private fun takeoverCanStart(): Boolean =
        isHomeShowing && !isArtistOpen && !isShelfOpen && !isSheetOpen && recapStory == null &&
            !recapStoryOpening && ::takeoverRoot.isInitialized

    /** Where a running takeover may carry on (a sheet over Home doesn't stop it). */
    private fun takeoverStillOk(): Boolean = isHomeShowing && !isArtistOpen && !isShelfOpen && recapStory == null

    /** Today's top artist by listening time, same as the Stats tab. Re-read at most once a minute. */
    private suspend fun refreshTopToday(force: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && topTodayAt != 0L && now - topTodayAt < GREETING_FACTS_MAX_AGE_MS) return
        val today = Days.keyOf(System.currentTimeMillis())
        topToday = try {
            TodayLists.artists(app.database.getDayPlays(today), app.database.getDayListens(today)).firstOrNull()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        topTodayAt = maxOf(1L, now)
    }

    /**
     * Starts the takeover if the rules allow it. [fromSong]: one of the top artist's songs just
     * started (2-minute gap); otherwise it's the greeting rotation (10-minute gap).
     */
    private suspend fun tryStartTakeover(fromSong: Boolean): Boolean {
        if (isTakeoverActive || takeoverPreparing || !takeoverCanStart()) return false
        val now = android.os.SystemClock.elapsedRealtime()
        val gapOk = if (fromSong) ArtistTakeover.songAllowed(now, lastTakeoverAt)
        else ArtistTakeover.rotationAllowed(now, lastTakeoverAt)
        if (!gapOk) return false
        takeoverPreparing = true
        try {
            refreshTopToday(force = fromSong)
            val top = topToday ?: return false
            if (top.ms < ArtistTakeover.MIN_LISTEN_MS) return false
            if (fromSong) {
                val st = app.playbackController.uiState.value
                if (!(st.isPlaying || st.isBuffering)) return false
                if (!ArtistTakeover.isByArtist(st.currentTrack?.artist, top.name)) return false
            }
            val url = artistPhotoUrl(top.name)
            val bmp = if (url == null) null else try {
                app.artworkPipeline.loadBitmap(url, resources.displayMetrics.widthPixels.coerceIn(480, 1080))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!ArtistTakeover.qualifies(top.ms, bmp != null) || bmp == null || url == null) return false
            val seed = try {
                app.artworkPipeline.extractAccentForUrl(url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                0
            }
            // Things may have moved on while the photo loaded
            if (isTakeoverActive || !takeoverCanStart()) return false
            if (greetingOverride != null) {
                // A "Playing ..." message is up: let it finish, then try again
                if (fromSong) takeoverPending = true
                return false
            }
            startTakeover(top, bmp, seed)
            lastTakeoverAt = android.os.SystemClock.elapsedRealtime()
            return true
        } finally {
            takeoverPreparing = false
            // v4.9.6: an update pop-up that waited for this may go now if no takeover came of it
            // (no photo, song changed...); finishTakeover() covers the case where one did start.
            if (!isTakeoverActive) checkUpdatePrompt()
        }
    }

    /** A new song started: the takeover comes right away if it's by today's top artist (Home only). */
    private fun onTakeoverTrackChanged() {
        if (isTakeoverActive) return
        if (!takeoverCanStart()) {
            // Not on Home: it waits until Home is back
            takeoverPending = true
            return
        }
        lifecycleScope.launch {
            // Give the player a moment to report it's playing
            delay(600L)
            tryStartTakeover(fromSong = true)
        }
    }

    /** Called when Home comes back on screen: a song trigger that came in while away gets its turn. */
    private fun runPendingTakeover() {
        if (!takeoverPending || isTakeoverActive || takeoverPreparing) return
        takeoverPending = false
        lifecycleScope.launch {
            delay(400L)
            tryStartTakeover(fromSong = true)
        }
    }

    private fun startTakeover(top: com.minim.music.data.stats.TopArtist, bmp: android.graphics.Bitmap, seed: Int) {
        val w = (if (root.width > 0) root.width else resources.displayMetrics.widthPixels).coerceAtLeast(1)
        val h = (if (root.height > 0) root.height else resources.displayMetrics.heightPixels).coerceAtLeast(1)
        // Same brightness check as the Artist page, over the whole picture as Home shows it
        val light = isAreaLight(bmp, w, h, 0f, 1f, 0f, 1f)
        takeoverLight = light
        takeoverSeed = seed
        buildTakeoverPals()
        takeoverArtist = top.name
        // A soft wash so text stays readable on busy photos
        takeoverWash.setBackgroundColor(if (light) 0x66FFFFFF else 0x70000000)
        takeoverPhoto.setImageBitmap(bmp)
        takeoverPhoto.scaleX = 1f
        takeoverPhoto.scaleY = 1f
        takeoverPhoto.translationX = 0f
        takeoverPhoto.translationY = 0f
        takeoverRoot.alpha = 0f
        takeoverRoot.visibility = View.VISIBLE
        takeoverT = -1f
        setTakeoverMix(0f)

        // The greeting: two lines with the artist's name and today's minutes
        val lines = ArtistTakeover.lines(takeoverPicker.next(), top.name, top.ms)
        greetingOverrideJob?.cancel()
        greetingOverrideJob = null
        greetingPlayTrackId = null
        greetingOverride = lines
        takeoverLinesShown = lines
        renderGreeting(animate = true)
        homeGreeting.contentDescription = "${lines.first} ${lines.second}"

        val zoom = animationsOn()
        val drift = dp(14f)
        takeoverAnim?.cancel()
        takeoverAnim = ValueAnimator.ofFloat(0f, ArtistTakeover.DURATION_MS.toFloat()).apply {
            duration = ArtistTakeover.DURATION_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                val el = a.animatedValue as Float
                val inT = (el / ArtistTakeover.FADE_IN_MS).coerceIn(0f, 1f)
                val outT = ((ArtistTakeover.DURATION_MS - el) / ArtistTakeover.FADE_OUT_MS).coerceIn(0f, 1f)
                val m = minOf(inT, outT)
                setTakeoverMix(m * m * (3f - 2f * m)) // smoothstep: no hard cuts
                if (zoom) {
                    // Slow documentary zoom (Ken Burns) with a gentle drift
                    val p = el / ArtistTakeover.DURATION_MS
                    val s = 1f + (ArtistTakeover.ZOOM_TO - 1f) * p
                    takeoverPhoto.scaleX = s
                    takeoverPhoto.scaleY = s
                    takeoverPhoto.translationX = -drift * p
                    takeoverPhoto.translationY = -drift * 0.5f * p
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled && takeoverAnim === animation) finishTakeover()
                }
            })
            start()
        }
    }

    /** Fades the takeover out early (left Home, opened something, dragged). */
    private fun endTakeoverEarly() {
        if (!isTakeoverActive) return
        val running = takeoverAnim
        takeoverAnim = null
        running?.cancel()
        if (!animationsOn() || !takeoverStillOk() || takeoverT <= 0f) {
            finishTakeover()
            return
        }
        val from = takeoverT
        val fade = ValueAnimator.ofFloat(from, 0f).apply {
            duration = ArtistTakeover.EARLY_FADE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { setTakeoverMix(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled && takeoverAnim === animation) finishTakeover()
                }
            })
        }
        takeoverAnim = fade
        fade.start()
    }

    /** Back to normal: song colours, normal status bar, the greeting rotation carries on. */
    private fun finishTakeover() {
        if (!isTakeoverActive) return
        takeoverAnim?.let { a -> takeoverAnim = null; a.cancel() }
        setTakeoverMix(0f)
        takeoverHomePal = null
        takeoverMiniPal = null
        takeoverArtist = null
        takeoverRoot.visibility = View.GONE
        takeoverRoot.alpha = 0f
        takeoverPhoto.setImageDrawable(null)
        homeGreeting.contentDescription = null
        // Home back in the song's colours exactly
        Painter.paint(screenHome, pal)
        window.navigationBarColor = pal.bg
        updateSystemBars()
        val ours = takeoverLinesShown
        takeoverLinesShown = null
        if (ours != null && greetingOverride == ours) endGreetingMessage()
        // v4.9.6: an update pop-up may have been waiting for the takeover to end
        checkUpdatePrompt()
    }

    private fun openTakeoverArtist() {
        val name = takeoverArtist ?: return
        finishTakeover()
        openArtistByName(name)
    }

    /** [t] 0 = normal Home, 1 = full takeover. */
    private fun setTakeoverMix(t: Float) {
        if (t == takeoverT) return
        takeoverT = t
        takeoverRoot.alpha = t
        applyTakeoverColors()
        updateSystemBars()
    }

    /** Home's colours for the current mix; also re-applied after every palette change. */
    private fun applyTakeoverColors() {
        val homePal = takeoverHomePal ?: return
        val t = takeoverT.coerceIn(0f, 1f)
        Painter.paint(screenHome, pal.lerp(homePal, t))
        takeoverMiniPal?.let {
            val miniPal = pal.lerp(it, t)
            Painter.paint(homeMini.root, miniPal)
            Painter.paint(homeMini.actions, miniPal)
        }
        // Home goes see-through so the photo underneath shows; the root behind it is the song colour
        screenHome.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        window.navigationBarColor =
            androidx.core.graphics.ColorUtils.setAlphaComponent(pal.bg, ((1f - t) * 255f).toInt().coerceIn(0, 255))
    }

    // ------------------------------------------------------------------------------------------
    // v4.9.2: today's songs and artists sheets (from the Stats tab's live boxes)
    // ------------------------------------------------------------------------------------------

    private var todaySheetJob: Job? = null

    /** Today's songs with how many times each was played; tapping one plays it. */
    private fun showTodaySongsSheet() {
        todaySheetJob?.cancel()
        todaySheetJob = lifecycleScope.launch {
            val today = Days.keyOf(System.currentTimeMillis())
            val songs = try {
                TodayLists.songs(app.database.getDayPlays(today), app.database.getDayListens(today))
                    .take(TODAY_SHEET_MAX)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (songs.isEmpty()) return@launch
            val tracks = app.database.getTracksByIds(songs.map { it.trackId })
            val byId = tracks.associateBy { it.id }
            val total = songs.sumOf { it.plays }
            val rows = ArrayList<View>()
            openSheet { box ->
                sheetHeading(
                    box, getString(R.string.stats_today_songs_sheet),
                    "${Days.label(today)} · ${statsScreen.playsText(total)}"
                )
                for (s in songs) {
                    val track = byId[s.trackId] ?: continue
                    val row = layoutInflater.inflate(R.layout.item_sheet_song, box, false)
                    val art = row.findViewById<ImageView>(R.id.song_art).apply { clipToOutline = true }
                    row.findViewById<TextView>(R.id.song_title).text = s.title
                    row.findViewById<TextView>(R.id.song_artist).text = s.artist
                    val plays = statsScreen.playsText(s.plays)
                    row.findViewById<TextView>(R.id.song_plays).text = plays
                    row.contentDescription = "${s.title}, ${s.artist}, $plays"
                    app.artworkPipeline.loadInto(art, s.thumbnailUrl, dp(44f).toInt())
                    row.clipToOutline = true
                    row.setOnClickListener { v ->
                        if (sheetClosing) return@setOnClickListener
                        haptic(v)
                        closeSheet { app.playbackController.playRadio(track, tracks) }
                    }
                    box.addView(row)
                    rows += row
                }
            }
            // v4.9.9: the rows rise in a little apart as the sheet comes up
            RowEntrance.playViews(rows)
        }
    }

    /** Today's artists with how long each was heard; tapping one opens their Artist page. */
    private fun showTodayArtistsSheet() {
        todaySheetJob?.cancel()
        todaySheetJob = lifecycleScope.launch {
            val today = Days.keyOf(System.currentTimeMillis())
            val artists = try {
                TodayLists.artists(app.database.getDayPlays(today), app.database.getDayListens(today))
                    .take(TODAY_SHEET_MAX)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (artists.isEmpty()) return@launch
            val count = artists.size
            val rows = ArrayList<View>()
            openSheet { box ->
                sheetHeading(
                    box, getString(R.string.stats_today_artists_sheet),
                    "${Days.label(today)} · $count ${getString(if (count == 1) R.string.stats_artist_one else R.string.stats_artists)}"
                )
                for (a in artists) {
                    val row = sheetAction(box, R.drawable.ic_person, a.name, trailing = StatsFormat.duration(a.ms)) {
                        closeSheet { openArtistByName(a.name) }
                    }
                    // v4.9.3: the artist's round photo instead of the person icon (kept as the fallback)
                    showSheetArtistPhoto(row, a.name)
                    rows += row
                }
            }
            RowEntrance.playViews(rows)
        }
    }

    /** v4.9.3: puts [name]'s round photo in a sheet row's icon spot, fading in once it's found. */
    private fun showSheetArtistPhoto(row: View, name: String) {
        val icon = row.findViewById<ImageView>(R.id.action_icon)
        val label = row.findViewById<TextView>(R.id.action_label)
        val sizePx = dp(30f).toInt()
        lifecycleScope.launch {
            val url = artistPhotoUrl(name) ?: return@launch
            // v4.9.9: a photo already in memory shows at once, without the fade
            val fromMemory = app.artworkPipeline.cachedBitmap(url, dp(64f).toInt()) != null
            val bmp = try {
                app.artworkPipeline.loadBitmap(url, dp(64f).toInt())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            // The photo is a bit bigger than the icon. v4.9.6: it grows to the right from the
            // row's padding edge and the label's gap shrinks by the same amount, so the label still
            // lines up. (4.9.3 pulled the photo left with a negative margin, into the row's
            // padding, and the row clips its children to its padding: that cut the left side flat.)
            icon.tag = null
            icon.imageTintList = null
            RoundPhoto.apply(icon)
            (icon.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
                val grow = sizePx - lp.width
                if (grow > 0) {
                    lp.width = sizePx
                    lp.height = sizePx
                    lp.marginStart = 0
                    icon.layoutParams = lp
                    (label.layoutParams as ViewGroup.MarginLayoutParams).let { llp ->
                        llp.marginStart = (llp.marginStart - grow).coerceAtLeast(dp(8f).toInt())
                        label.layoutParams = llp
                    }
                }
            }
            icon.setImageBitmap(bmp)
            if (!fromMemory && animationsOn()) {
                icon.alpha = 0f
                icon.animate().alpha(1f).setDuration(com.minim.music.util.ArtworkFade.FADE_MS)
                    .setInterpolator(DecelerateInterpolator(1.5f)).start()
            }
        }
    }

    /** In-flight / finished photo lookups this session, so each artist is looked up once. */
    private val artistPhotoLookups = HashMap<String, kotlinx.coroutines.Deferred<String?>>()

    /** v4.9.3: an artist's photo: cached, else found the same way the Stats tab and Artist page find it. */
    private suspend fun artistPhotoUrl(name: String): String? {
        app.preferences.artistPhoto(name)?.takeIf { it.isNotBlank() }?.let { return it }
        val key = name.lowercase()
        val lookup = artistPhotoLookups.getOrPut(key) {
            lifecycleScope.async {
                val found = try {
                    app.sourceAdapter.findArtist(name)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val ref = (found as? SourceResult.Success)?.data ?: return@async null
                val url = MusicSourceAdapter.upscaleYtImage(ref.thumbnailUrl, 600)
                if (url.isBlank()) return@async null
                app.preferences.setArtistPhoto(name, url)
                url
            }
        }
        return try {
            lookup.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private var recapArtistPhoto: String? = null

    private fun onRecapLoaded() = checkRecapPopup()

    // ------------------------------------------------------------------------------------------
    // v4.9.1: recap popup (slides up on the first open after midnight when there's a new recap)
    // ------------------------------------------------------------------------------------------

    private val recapGate = RecapPopupGate()
    private var recapPopupPending: Runnable? = null

    /**
     * Asks [RecapPopupGate] if the popup may show now. Called when the recap loads (app open,
     * midnight), on every navigation change (updateChrome), on resume and when a sheet closes.
     * It shows a moment later, after a re-check, so it never lands mid-animation.
     */
    private fun checkRecapPopup() {
        checkRecapPopupOnly()
        // v4.9.6: the update pop-up waits for the same things (sheets, the recap story)
        checkUpdatePrompt()
    }

    private fun checkRecapPopupOnly() {
        if (!::statsScreen.isInitialized || !::sheetRoot.isInitialized) return
        val action = recapGateAction()
        if (action != RecapPopupGate.Action.SHOW) {
            recapPopupPending?.let { root.removeCallbacks(it) }
            recapPopupPending = null
            return
        }
        if (recapPopupPending != null) return
        val r = Runnable {
            recapPopupPending = null
            if (recapGateAction() == RecapPopupGate.Action.SHOW) showRecapPopup()
            // v4.9.6: the recap popup didn't come after all: an update pop-up queued behind it may go
            else checkUpdatePrompt()
        }
        recapPopupPending = r
        root.postDelayed(r, RECAP_POPUP_DELAY_MS)
    }

    private fun recapGateAction(): RecapPopupGate.Action {
        val prefs = app.preferences
        val st = app.playbackController.uiState.value
        val onHome = tab == Tab.HOME && !isSearchOpen && !isListOpen && !isShelfOpen && !isArtistOpen &&
            !isLyricsOpen && !isQueueOpen && !isSettingsOpen
        // Quick in-and-out of the sheet, background or welcome screens doesn't count as leaving Home
        val sheetVisible = sheetRoot.visibility == View.VISIBLE
        val busy = !isActivityResumed || isWelcomeOpen || !prefs.welcomeDone || sheetVisible ||
            recapStory != null || recapStoryOpening
        return recapGate.update(
            eligible = RecapPopupGate.eligible(recapStats, prefs.recapPopupDay, prefs.recapViewedDay),
            onHome = onHome,
            nowPlayingOpen = isNowPlayingOpen,
            musicPlaying = st.currentTrack != null && (st.isPlaying || st.isBuffering),
            busy = busy
        )
    }

    /** The popup: Minim sheet with the recap's date and day, a small numbers line and "dive in". */
    private fun showRecapPopup() {
        val recap = recapStats ?: return
        // Once per recap: marked as soon as it's on screen. Dismissing it keeps the Stats dot
        // (recapViewedDay only changes when the recap is actually looked at).
        app.preferences.recapPopupDay = recap.day
        openSheet { box ->
            sheetHeading(box, getString(R.string.recap_popup_title), Days.label(recap.day))
            val v = layoutInflater.inflate(R.layout.sheet_recap_popup, box, false)
            v.findViewById<TextView>(R.id.recap_popup_line).apply {
                val songs = if (recap.songs == 1) "1 ${getString(R.string.stats_song_one)}"
                else "${recap.songs} ${getString(R.string.stats_songs)}"
                text = getString(R.string.recap_popup_line, StatsFormat.duration(recap.timeMs), songs)
                visibility = View.VISIBLE
            }
            v.findViewById<View>(R.id.recap_popup_dive_in).setOnClickListener {
                if (sheetClosing) return@setOnClickListener
                haptic(it)
                closeSheet { openRecapStory(atCard = false) }
            }
            box.addView(v)
        }
        updateStatsBadges()
    }

    // ------------------------------------------------------------------------------------------
    // v4.9.6: in-app updates. A quiet check of the latest GitHub Release on every app open, the
    // update pop-up (Download now / Later), the download with progress, and Android's installer.
    // "Check for updates" in Settings is the only manual way in; there's no "new version" pill.
    // ------------------------------------------------------------------------------------------

    private class UpdateSheetViews(
        val root: View,
        val status: TextView,
        val progress: ProgressLine,
        val primary: TextView,
        val secondary: TextView
    )

    private enum class UpdatePrimary { DOWNLOAD, INSTALL, RETRY, SETTINGS, HIDE }

    private var updateSheet: UpdateSheetViews? = null
    private var updateSheetFor: AvailableUpdate? = null
    private var updatePromptPending: Runnable? = null
    private var updateCheckJob: Job? = null
    private var updateManualJob: Job? = null
    /** The finished download for the offered version, found on disk when the check came back. */
    private var updateSavedApk: java.io.File? = null
    /** The user tapped Download now: open the installer as soon as the file is ready. */
    private var updateWantsInstall = false
    /** Waiting for the user to allow "Install unknown apps" (the file is ready). */
    private var updateAwaitingPermission: Pair<AvailableUpdate, java.io.File>? = null
    /** Coming back from the installer or that setting isn't a new app open for the pop-up. */
    private var updateSkipNextOpen = false

    /**
     * v4.9.8: a cold start always checks; coming back to the front checks when the last good check is
     * a few hours old (sooner after a failed one). Either way an update found earlier and not answered
     * in this session can still show.
     */
    private fun onAppOpenedForUpdates(open: com.minim.music.util.AppForegroundTracker.AppOpen) {
        if (updateSkipNextOpen) {
            updateSkipNextOpen = false
            return
        }
        val gate = app.updateGate
        gate.onAppOpened(open.awayMs)
        val schedule = app.updateSchedule
        val now = android.os.SystemClock.elapsedRealtime()
        if (updateCheckJob?.isActive == true || !schedule.shouldCheck(now, open.coldStart)) {
            checkUpdatePrompt()
            return
        }
        schedule.onCheckStarted(now)
        updateCheckJob = lifecycleScope.launch {
            var finished = false
            try {
                val result = app.updates.check()
                schedule.onCheckFinished(
                    android.os.SystemClock.elapsedRealtime(),
                    com.minim.music.data.update.UpdateCheckSchedule.isOk(result)
                )
                finished = true
                updateSavedApk = savedApkFor(result) ?: updateSavedApk.takeIf { result is UpdateCheckResult.Failed }
                // A failed check keeps what an earlier one found; it never shows an error on its own
                gate.onCheckResult(result)
                checkUpdatePrompt()
            } finally {
                if (!finished) schedule.onCheckCancelled()
            }
        }
    }

    private suspend fun savedApkFor(result: UpdateCheckResult): java.io.File? {
        val u = (result as? UpdateCheckResult.Available)?.update ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { app.updates.savedApk(u) } catch (e: Exception) { null }
        }
    }

    /** True while something else is in front: the pop-up waits (queued) until it's clear. */
    private fun updatePromptBusy(): Boolean {
        val sheetVisible = ::sheetRoot.isInitialized && sheetRoot.visibility == View.VISIBLE
        return !isActivityResumed || isWelcomeOpen || !app.preferences.welcomeDone || sheetVisible ||
            recapStory != null || recapStoryOpening || recapPopupPending != null ||
            isTakeoverActive || takeoverPreparing
    }

    private fun updateFlowActive(): Boolean =
        app.updates.isBusy || updateWantsInstall || updateAwaitingPermission != null

    /** Asks [UpdatePromptGate] whether the pop-up may show; shows it a moment later after a re-check. */
    private fun checkUpdatePrompt() {
        if (!::sheetRoot.isInitialized || !::root.isInitialized) return
        maybeContinueUpdateInstall()
        val action = app.updateGate.action(updatePromptBusy(), updateFlowActive())
        if (action != UpdatePromptGate.Action.SHOW) {
            if (action == UpdatePromptGate.Action.NONE) {
                updatePromptPending?.let { root.removeCallbacks(it) }
                updatePromptPending = null
            }
            return
        }
        if (updatePromptPending != null) return
        val r = Runnable {
            updatePromptPending = null
            val u = app.updateGate.pending
            if (u != null && app.updateGate.action(updatePromptBusy(), updateFlowActive()) == UpdatePromptGate.Action.SHOW) {
                showUpdatePopup(u)
            }
        }
        updatePromptPending = r
        root.postDelayed(r, UPDATE_POPUP_DELAY_MS)
    }

    /** Settings: check right away; "You're on the latest", a friendly error, or the pop-up. */
    private fun checkForUpdatesNow() {
        if (updateManualJob?.isActive == true) return
        val now = app.updates.state.value
        if (now is UpdateFlowState.Downloading) {
            // Already on its way: show the progress
            updateWantsInstall = true
            if (!isSheetOpen) showUpdatePopup(now.update)
            return
        }
        val label = findViewById<TextView>(R.id.set_check_updates)
        label.text = getString(R.string.update_checking)
        updateManualJob = lifecycleScope.launch {
            val result = app.updates.check()
            app.updateSchedule.onCheckFinished(
                android.os.SystemClock.elapsedRealtime(),
                com.minim.music.data.update.UpdateCheckSchedule.isOk(result)
            )
            val saved = savedApkFor(result)
            label.text = getString(R.string.update_check)
            when (result) {
                is UpdateCheckResult.Available -> {
                    updateSavedApk = saved
                    app.updateGate.onManualCheck(result)
                    updatePromptPending?.let { root.removeCallbacks(it) }
                    updatePromptPending = null
                    if (!isSheetOpen && isActivityResumed) showUpdatePopup(result.update)
                    else checkUpdatePrompt()
                }
                is UpdateCheckResult.UpToDate, UpdateCheckResult.NoRelease -> {
                    app.updateGate.onCheckResult(result)
                    showNote(getString(R.string.update_latest))
                }
                is UpdateCheckResult.Failed -> showNote(
                    getString(if (result.offline) R.string.update_check_offline else R.string.update_check_failed)
                )
            }
        }
    }

    /** The pop-up: Minim's sheet with the new version, short notes, Later and Download now. */
    private fun showUpdatePopup(update: AvailableUpdate) {
        if (isWelcomeOpen) return
        app.updateGate.markShown(update)
        openSheet { box ->
            sheetHeading(
                box,
                getString(R.string.update_title, update.version.toString()),
                getString(R.string.update_sub, app.updates.installedVersionLabel)
            )
            val v = layoutInflater.inflate(R.layout.sheet_update, box, false)
            v.findViewById<TextView>(R.id.update_notes).text =
                update.notes.ifBlank { getString(R.string.update_no_notes) }
            val views = UpdateSheetViews(
                root = v,
                status = v.findViewById(R.id.update_status),
                progress = v.findViewById<ProgressLine>(R.id.update_progress).apply { isEnabled = false },
                primary = v.findViewById(R.id.update_primary),
                secondary = v.findViewById(R.id.update_secondary)
            )
            views.primary.setOnClickListener { p ->
                if (sheetClosing) return@setOnClickListener
                haptic(p)
                onUpdatePrimary(p.tag as? UpdatePrimary ?: UpdatePrimary.DOWNLOAD)
            }
            views.secondary.setOnClickListener { s ->
                if (sheetClosing) return@setOnClickListener
                haptic(s)
                onUpdateSecondary()
            }
            box.addView(v)
            updateSheet = views
            updateSheetFor = update
            renderUpdateSheet()
        }
    }

    private fun onUpdatePrimary(what: UpdatePrimary) {
        val update = updateSheetFor ?: return
        when (what) {
            UpdatePrimary.DOWNLOAD, UpdatePrimary.RETRY, UpdatePrimary.INSTALL -> {
                updateWantsInstall = true
                val saved = updateSavedApk?.takeIf {
                    it.isFile && it.name == com.minim.music.data.update.UpdateRules.apkFileName(update.version)
                }
                val st = app.updates.state.value
                when {
                    st is UpdateFlowState.Ready && st.update.version == update.version -> proceedToInstall(st.update, st.file)
                    saved != null && what == UpdatePrimary.INSTALL -> proceedToInstall(update, saved)
                    else -> {
                        app.updates.reset()
                        app.updates.startDownload(update)
                    }
                }
            }
            UpdatePrimary.SETTINGS -> openUnknownSourcesSetting()
            UpdatePrimary.HIDE -> closeSheet()
        }
        renderUpdateSheet()
    }

    /** Later (or Cancel while downloading). */
    private fun onUpdateSecondary() {
        val st = app.updates.state.value
        if (st is UpdateFlowState.Downloading) {
            app.updates.cancelDownload()
            updateWantsInstall = false
            renderUpdateSheet()
            return
        }
        updateWantsInstall = false
        updateAwaitingPermission = null
        app.updates.reset()
        closeSheet()
    }

    /** Draws the pop-up for where the update has got to. */
    private fun renderUpdateSheet() {
        val v = updateSheet ?: return
        val update = updateSheetFor ?: return
        if (v.root.parent == null) { updateSheet = null; return }
        val st = app.updates.state.value
        val mine = when (st) {
            is UpdateFlowState.Downloading -> st.update.version == update.version
            is UpdateFlowState.Ready -> st.update.version == update.version
            is UpdateFlowState.Failed -> st.update.version == update.version
            else -> false
        }
        fun buttons(primary: Int, tag: UpdatePrimary, secondary: Int) {
            v.primary.setText(primary)
            v.primary.tag = tag
            v.secondary.setText(secondary)
        }
        when {
            updateAwaitingPermission != null -> {
                v.progress.visibility = View.GONE
                v.status.visibility = View.VISIBLE
                v.status.setText(R.string.update_permission)
                buttons(R.string.update_open_settings, UpdatePrimary.SETTINGS, R.string.update_later)
            }
            mine && st is UpdateFlowState.Downloading -> {
                v.status.visibility = View.VISIBLE
                v.progress.visibility = View.VISIBLE
                val total = st.total
                if (total > 0) {
                    val f = (st.done.toFloat() / total).coerceIn(0f, 1f)
                    v.progress.setFraction(f)
                    v.status.text = if (st.done <= 0L) getString(R.string.update_starting)
                    else getString(R.string.update_downloading, (f * 100).toInt(), mb(st.done), mb(total))
                } else {
                    v.progress.setFraction(0f)
                    v.status.text = if (st.done <= 0L) getString(R.string.update_starting)
                    else getString(R.string.update_downloading_plain, mb(st.done))
                }
                buttons(R.string.update_hide, UpdatePrimary.HIDE, R.string.update_cancel)
            }
            mine && st is UpdateFlowState.Failed -> {
                v.progress.visibility = View.GONE
                v.status.visibility = View.VISIBLE
                v.status.text = updateProblemText(st.problem)
                buttons(R.string.update_try_again, UpdatePrimary.RETRY, R.string.update_later)
            }
            else -> {
                v.progress.visibility = View.GONE
                v.status.visibility = View.GONE
                val ready = (mine && st is UpdateFlowState.Ready) || updateSavedApk?.isFile == true
                if (ready) buttons(R.string.update_install_now, UpdatePrimary.INSTALL, R.string.update_later)
                else buttons(R.string.update_download_now, UpdatePrimary.DOWNLOAD, R.string.update_later)
            }
        }
        Painter.paint(v.root, pal)
    }

    private fun mb(bytes: Long): String {
        val m = bytes / (1024.0 * 1024.0)
        return if (m >= 10) String.format(java.util.Locale.US, "%.0f MB", m)
        else String.format(java.util.Locale.US, "%.1f MB", m)
    }

    private fun updateProblemText(p: UpdateFlowState.Problem): String = getString(
        when (p) {
            UpdateFlowState.Problem.OFFLINE -> R.string.update_err_offline
            UpdateFlowState.Problem.SERVER -> R.string.update_err_server
            UpdateFlowState.Problem.STORAGE -> R.string.update_err_storage
            UpdateFlowState.Problem.BROKEN -> R.string.update_err_broken
            UpdateFlowState.Problem.OTHER_APP -> R.string.update_err_other_app
            UpdateFlowState.Problem.OLDER -> R.string.update_err_older
        }
    )

    /** Progress, done or failed, from [AppUpdates.state] (collected while the app is in front). */
    private fun onUpdateFlowState(st: UpdateFlowState) {
        renderUpdateSheet()
        when (st) {
            is UpdateFlowState.Ready -> {
                updateSavedApk = st.file
                maybeContinueUpdateInstall()
            }
            is UpdateFlowState.Failed -> if (updateSheet == null && updateWantsInstall) {
                // Failed while the pop-up was hidden: say so in the small note
                updateWantsInstall = false
                showNote(updateProblemText(st.problem))
                app.updates.reset()
            }
            else -> Unit
        }
    }

    /** The download finished while the pop-up was hidden (or behind something): install when it's clear. */
    private fun maybeContinueUpdateInstall() {
        val st = app.updates.state.value as? UpdateFlowState.Ready ?: return
        if (!updateWantsInstall || !isActivityResumed) return
        val otherSheet = ::sheetRoot.isInitialized && sheetRoot.visibility == View.VISIBLE && updateSheet == null
        if (otherSheet || recapStory != null || recapStoryOpening || isWelcomeOpen) return
        proceedToInstall(st.update, st.file)
    }

    /** Opens Android's installer, or first asks for "Install unknown apps" when it's off. */
    private fun proceedToInstall(update: AvailableUpdate, file: java.io.File) {
        if (!file.isFile) {
            updateSavedApk = null
            app.updates.reset()
            app.updates.startDownload(update)
            return
        }
        if (!app.updates.canInstall()) {
            // Another sheet is up: keep wanting the install, carry on once it closes
            if (updateSheet == null && ::sheetRoot.isInitialized && sheetRoot.visibility == View.VISIBLE) return
            updateAwaitingPermission = update to file
            updateWantsInstall = false
            if (updateSheet == null) showUpdatePopup(update) else renderUpdateSheet()
            return
        }
        updateWantsInstall = false
        updateAwaitingPermission = null
        val intent = app.updates.installIntent(file)
        if (intent == null) {
            app.updates.reset()
            showNote(getString(R.string.update_err_installer))
            renderUpdateSheet()
            return
        }
        // v4.9.7: stop the music before Android replaces the app. Updating Minim while it was
        // playing could make the installer say "App not installed" even when it had worked.
        pauseForInstall()
        if (!AppUpdates.tryStart(this, intent)) {
            app.updates.reset()
            showNote(getString(R.string.update_err_installer))
            renderUpdateSheet()
            return
        }
        // The installer is in front now; coming back from it isn't a new open
        updateSkipNextOpen = true
        app.updates.reset()
        if (updateSheet != null) closeSheet()
    }

    /** Pauses playback (and saves the listening time so far) right before the installer opens. */
    private fun pauseForInstall() {
        try {
            app.playbackController.flushListening()
            app.playbackController.pause()
        } catch (_: Exception) {
            // Never let this block the update
        }
    }

    private fun openUnknownSourcesSetting() {
        updateSkipNextOpen = true
        val ok = AppUpdates.tryStart(this, app.updates.unknownSourcesIntent()) ||
            AppUpdates.tryStart(this, app.updates.securitySettingsIntent())
        if (!ok) {
            updateSkipNextOpen = false
            showNote(getString(R.string.update_err_settings))
        }
    }

    /** onResume: if they turned "Install unknown apps" on, open the installer right away. */
    private fun resumeUpdateInstallIfAllowed() {
        val (update, file) = updateAwaitingPermission ?: return
        if (!app.updates.canInstall()) return
        updateAwaitingPermission = null
        proceedToInstall(update, file)
    }

    /** The pop-up was closed (Later, Hide, a swipe, or the installer opening). */
    private fun onUpdateSheetClosed() {
        updateSheet = null
        if (app.updates.state.value is UpdateFlowState.Downloading) {
            if (updateWantsInstall) showNote(getString(R.string.update_note_background))
            return
        }
        updateAwaitingPermission = null
        if (app.updates.state.value is UpdateFlowState.Failed) app.updates.reset()
        if (app.updates.state.value !is UpdateFlowState.Ready) updateWantsInstall = false
    }

    // ------------------------------------------------------------------------------------------
    // v4.9.1: the recap story and its summary card (from the popup's "dive in" and the Stats tab)
    // ------------------------------------------------------------------------------------------

    private var recapStory: RecapStory? = null
    private var recapStoryOpening = false
    private var recapStoryLightBars: Boolean? = null
    private var recapCardJob: Job? = null
    private var pendingRecapSave: Pair<android.graphics.Bitmap, Int>? = null

    /** Android 9 only: the old storage permission, asked for the first time Save is tapped. */
    private val storagePermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingRecapSave
        pendingRecapSave = null
        if (pending == null) return@registerForActivityResult
        if (granted) saveRecapCard(pending.first, pending.second)
        else recapStoryNote(getString(R.string.recap_save_needs_permission))
    }

    /** Looking at the recap (story or Stats tab): clears the Stats dot and the popup is done for it. */
    private fun markRecapViewed(recap: DayStats) {
        val prefs = app.preferences
        if (prefs.recapViewedDay != recap.day) prefs.recapViewedDay = recap.day
        if (prefs.recapPopupDay != recap.day) prefs.recapPopupDay = recap.day
        recapPopupPending?.let { root.removeCallbacks(it) }
        recapPopupPending = null
        updateStatsBadges()
    }

    /**
     * Opens the full-screen story for the current recap (or straight on its summary card). It
     * stays reachable from the Stats tab until a newer day replaces the recap.
     */
    private fun openRecapStory(atCard: Boolean) {
        if (recapStory != null || recapStoryOpening) return
        val recap = recapStats ?: return
        markRecapViewed(recap)
        recapStoryOpening = true
        lifecycleScope.launch {
            val dark = app.preferences.isDarkTheme
            // Colours from the day's top song cover (black and white when there's no cover)
            val storyPal = try {
                RecapCard.palette(app.artworkPipeline, recap, dark)
            } catch (e: kotlinx.coroutines.CancellationException) {
                recapStoryOpening = false
                throw e
            } catch (_: Exception) {
                Pal.build(0, dark)
            }
            recapStoryOpening = false
            if (isFinishing || isDestroyed || recapStory != null) return@launch
            showRecapStory(recap, storyPal, atCard)
        }
    }

    private fun showRecapStory(recap: DayStats, storyPal: Pal, atCard: Boolean) {
        val host = findViewById<ViewGroup>(android.R.id.content) ?: return
        if (::sheetRoot.isInitialized && sheetRoot.visibility == View.VISIBLE) closeSheet()
        hideKeyboard()
        val photo = recap.topArtist?.name?.let { app.preferences.artistPhoto(it) }
        val story = RecapStory(
            context = this,
            stats = recap,
            pal = storyPal,
            artwork = app.artworkPipeline,
            artistPhotoUrl = photo,
            roast = RecapRoast.line(recap),
            startAtCard = atCard,
            tick = { haptic(it, HapticFeedbackConstants.CLOCK_TICK) },
            onBars = { light ->
                recapStoryLightBars = light
                updateSystemBars()
            },
            onShare = { bmp -> shareRecapCard(bmp, recap.day) },
            onSave = { bmp -> requestSaveRecapCard(bmp, recap.day) },
            onClosed = { onRecapStoryClosed() }
        )
        recapStory = story
        // Above everything (root's screens, sheets and notes), right up behind the system bars
        host.addView(story, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        recapCardJob?.cancel()
        recapCardJob = lifecycleScope.launch {
            val bmp = try {
                RecapCard.build(this@MainActivity, app.artworkPipeline, recap, storyPal, photo)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (bmp != null && recapStory === story) story.setCard(bmp)
        }
    }

    /** Swipe down or Back: back where they were, with the app's own status bar and colours. */
    private fun onRecapStoryClosed() {
        recapStory = null
        recapStoryLightBars = null
        recapCardJob?.cancel()
        recapCardJob = null
        pendingRecapSave = null
        updateSystemBars()
        updateStatsBadges()
        checkRecapPopup()
    }

    private fun closeRecapStory(): Boolean = recapStory?.onBack() ?: false

    private fun recapStoryNote(text: String) {
        val story = recapStory
        if (story != null) story.note(text) else showNote(text)
    }

    private fun shareRecapCard(bmp: android.graphics.Bitmap, day: Int) {
        lifecycleScope.launch {
            try {
                val uri = RecapShare.shareUri(this@MainActivity, bmp, day)
                startActivity(RecapShare.shareIntent(uri))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                recapStoryNote(getString(R.string.recap_share_failed))
            }
        }
    }

    private fun requestSaveRecapCard(bmp: android.graphics.Bitmap, day: Int) {
        if (RecapShare.needsPermission(this)) {
            pendingRecapSave = bmp to day
            try {
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } catch (_: Exception) {
                pendingRecapSave = null
                recapStoryNote(getString(R.string.recap_save_failed))
            }
            return
        }
        saveRecapCard(bmp, day)
    }

    private fun saveRecapCard(bmp: android.graphics.Bitmap, day: Int) {
        lifecycleScope.launch {
            val ok = RecapShare.save(this@MainActivity, bmp, day)
            recapStoryNote(getString(if (ok) R.string.recap_saved else R.string.recap_save_failed))
        }
    }

    // ==========================================================================================
    // Colours
    // ==========================================================================================

    private fun setPaletteSeed(seed: Int) {
        if (seed == paletteSeed) return
        paletteSeed = seed
        animatePaletteTo(appPal())
    }

    /** v4.9.4: the app palette: song colours in Dynamic, black and white in Default. */
    private fun appPal(): Pal = Pal.build(
        ColourMode.seedFor(paletteSeed, app.preferences.isDynamicColours),
        app.preferences.isDarkTheme
    )

    /**
     * The one shared colour fade (v4.9.9: eased, with text that crosses over at the midpoint).
     * Everything coloured from the palette reads [pal], which this moves from the colours on screen
     * right now to [target]; a quick skip simply retargets from the in-between colours, so nothing
     * flashes. Lists are painted every frame through the root (their rows are on screen); their
     * adapters get the final colours once at the end, for rows that are off screen.
     * Default black and white never changes, so it never fades.
     */
    private fun animatePaletteTo(target: Pal) {
        val running = paletteAnimator
        paletteAnimator = null
        running?.cancel()
        val from = pal
        if (from == target) {
            // A fade was cut short exactly on its target: the adapters still need the final colours
            if (running != null) applyPalette(target, includeLists = true)
            return
        }
        if (!animationsOn() || !root.isShown) {
            applyPalette(target, includeLists = true)
            return
        }
        val fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PaletteFade.DURATION_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                if (paletteAnimator !== it) return@addUpdateListener
                val t = it.animatedValue as Float
                if (t < 1f) applyPalette(PaletteFade.at(from, target, t), includeLists = false)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled || paletteAnimator !== animation) return
                    paletteAnimator = null
                    applyPalette(target, includeLists = true)
                }
            })
        }
        paletteAnimator = fade
        fade.start()
    }

    private fun applyPalette(p: Pal, includeLists: Boolean) {
        pal = p
        Painter.paint(root, p)
        // v4.9.9: a tab sliding in keeps its see-through background until the switch ends
        tabIn?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        paintArtistHeader(p)
        // v4.6.1: the status bar is see-through; the root's background (p.bg) shows behind it,
        // so it looks the same as before everywhere except on the Artist page
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = p.bg
        // v4.9.3: a takeover on Home keeps its colours through a song change
        if (isTakeoverActive) applyTakeoverColors()
        // v4.9.8: the full screen lyrics keep their see-through nav bar through a song change
        if (::fsLyrics.isInitialized) {
            fsLyrics.onPalette(p)
            if (fsLyrics.isShowing) applyFsLyricsChrome()
        }
        updateSystemBars()
        if (includeLists) {
            shelfAdapter.setPalette(p)
            shelfGridAdapter.setPalette(p)
            artistSongs.setPalette(p)
            artistAlbums.setPalette(p)
            discoverRefresh.setColorSchemeColors(p.fg)
            discoverRefresh.setProgressBackgroundColorSchemeColor(p.bg)
            libraryAdapter.setPalette(p)
            listAdapter.setPalette(p)
            searchAdapter.setPalette(p)
            queueAdapter.setPalette(p)
        }
    }

    // ==========================================================================================
    // v4.8: Minim sheets (song menu and playlists), replacing Android's stock popup and dialogs
    // ==========================================================================================

    private lateinit var sheetRoot: android.widget.FrameLayout
    private lateinit var sheetScrim: View
    private lateinit var sheetPanel: SheetPanel
    private lateinit var sheetScroll: ScrollView
    private lateinit var sheetContent: android.widget.LinearLayout
    private lateinit var minimNote: TextView
    private var sheetPadBottom = 0
    private var sheetNavInset = 0
    private var sheetImeInset = 0
    private var sheetClosing = false
    private var noteHide: Runnable? = null
    private val isSheetOpen get() = ::sheetRoot.isInitialized && sheetRoot.visibility == View.VISIBLE && !sheetClosing

    private fun setupSheets() {
        sheetRoot = findViewById(R.id.sheet_root)
        sheetScrim = findViewById(R.id.sheet_scrim)
        sheetPanel = findViewById(R.id.sheet_panel)
        sheetScroll = findViewById(R.id.sheet_scroll)
        sheetContent = findViewById(R.id.sheet_content)
        minimNote = findViewById(R.id.minim_note)
        sheetPadBottom = sheetPanel.paddingBottom
        sheetPanel.scrollChild = sheetScroll
        sheetScrim.setOnClickListener { closeSheet() }
        sheetPanel.listener = object : SheetPanel.Listener {
            override fun onDrag(fraction: Float) {
                sheetScrim.alpha = (1f - fraction).coerceIn(0f, 1f)
            }
            override fun onDragDismiss() = closeSheet()
            override fun onDragCancel() {
                sheetPanel.animate().translationY(0f).setDuration(180L)
                    .setInterpolator(DecelerateInterpolator()).start()
                sheetScrim.animate().alpha(1f).setDuration(180L).start()
            }
        }
    }

    /** Insets for the sheet: it reaches behind the bars; its panel stays above the nav bar and the keyboard. */
    private fun applySheetInsets(bars: androidx.core.graphics.Insets, ime: Int) {
        if (!::sheetRoot.isInitialized) return
        (sheetRoot.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
            if (lp.topMargin != -bars.top || lp.bottomMargin != -bars.bottom ||
                lp.leftMargin != -bars.left || lp.rightMargin != -bars.right
            ) {
                lp.topMargin = -bars.top
                lp.bottomMargin = -bars.bottom
                lp.leftMargin = -bars.left
                lp.rightMargin = -bars.right
                sheetRoot.layoutParams = lp
            }
        }
        sheetNavInset = bars.bottom
        sheetImeInset = ime
        sheetPanel.setPadding(
            sheetPanel.paddingLeft, sheetPanel.paddingTop, sheetPanel.paddingRight,
            sheetPadBottom + maxOf(bars.bottom, ime)
        )
    }

    /**
     * Opens the sheet with [build] filling it. If a sheet is already open, its content changes
     * in place (Add to playlist -> New playlist) with a short resize-and-fade.
     */
    private fun openSheet(build: (android.widget.LinearLayout) -> Unit) {
        if (!::sheetRoot.isInitialized || isWelcomeOpen) return
        val alreadyOpen = isSheetOpen
        sheetRoot.animate().cancel()
        sheetPanel.animate().cancel()
        sheetScrim.animate().cancel()
        sheetClosing = false
        // v4.9.6: new content replaces the update pop-up
        if (updateSheet != null) onUpdateSheetClosed()
        if (alreadyOpen && animationsOn()) {
            android.transition.TransitionManager.beginDelayedTransition(
                sheetRoot, android.transition.AutoTransition().setDuration(200L)
            )
        }
        sheetContent.removeAllViews()
        sheetScroll.scrollTo(0, 0)
        build(sheetContent)
        // v4.9.9: the sheet's pills (menu items, buttons) shrink a little when pressed
        PressScale.applyTree(sheetContent)
        Painter.paint(sheetRoot, pal)
        if (alreadyOpen) {
            sheetPanel.translationY = 0f
            sheetScrim.alpha = 1f
            return
        }
        sheetRoot.visibility = View.VISIBLE
        if (!animationsOn()) {
            sheetScrim.alpha = 1f
            sheetPanel.translationY = 0f
            return
        }
        // Measure first so it slides up exactly its own height
        val w = if (sheetRoot.width > 0) sheetRoot.width else resources.displayMetrics.widthPixels
        val h = if (sheetRoot.height > 0) sheetRoot.height else resources.displayMetrics.heightPixels
        sheetPanel.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)
        )
        sheetPanel.translationY = sheetPanel.measuredHeight.toFloat().coerceAtLeast(dp(200f))
        sheetScrim.alpha = 0f
        sheetScrim.animate().alpha(1f).setDuration(220L).start()
        sheetPanel.animate().translationY(0f).setDuration(280L)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun closeSheet(then: (() -> Unit)? = null) {
        if (!::sheetRoot.isInitialized || sheetRoot.visibility != View.VISIBLE) { then?.invoke(); return }
        if (sheetClosing) return
        sheetClosing = true
        if (currentFocus is EditText) hideKeyboard()
        val finish = {
            sheetRoot.visibility = View.GONE
            sheetClosing = false
            sheetPanel.translationY = 0f
            sheetContent.removeAllViews()
            // v4.9.6: the update pop-up went away (Later, Hide, a swipe or the installer opening)
            if (updateSheet != null) onUpdateSheetClosed()
            then?.invoke()
            // A held recap popup may be waiting for the sheet to go
            checkRecapPopup()
        }
        if (!animationsOn()) { finish(); return }
        val dist = (sheetPanel.height + sheetNavInset).toFloat().coerceAtLeast(dp(200f))
        sheetScrim.animate().alpha(0f).setDuration(200L).start()
        sheetPanel.animate().translationY(dist).setDuration(220L)
            .setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction { finish() }
            .start()
    }

    /** v4.8: small Minim-style confirmation pill above the mini player ("Added to Gym"). */
    private fun showNote(text: String) {
        if (!::minimNote.isInitialized) return
        noteHide?.let { minimNote.removeCallbacks(it) }
        minimNote.animate().cancel()
        minimNote.text = text
        Painter.paint(minimNote, pal)
        minimNote.visibility = View.VISIBLE
        if (animationsOn()) {
            minimNote.alpha = 0f
            minimNote.translationY = dp(10f)
            minimNote.animate().alpha(1f).translationY(0f).setDuration(200L)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            minimNote.alpha = 1f
            minimNote.translationY = 0f
        }
        val hide = Runnable {
            if (animationsOn()) {
                minimNote.animate().alpha(0f).setDuration(220L)
                    .withEndAction { minimNote.visibility = View.GONE }.start()
            } else minimNote.visibility = View.GONE
        }
        noteHide = hide
        minimNote.postDelayed(hide, 1900L)
    }

    private fun sheetHeading(parent: android.widget.LinearLayout, title: String, sub: String? = null) {
        val v = layoutInflater.inflate(R.layout.sheet_heading, parent, false)
        v.findViewById<TextView>(R.id.sheet_heading).text = title
        v.findViewById<TextView>(R.id.sheet_heading_sub).apply {
            if (sub.isNullOrBlank()) visibility = View.GONE else { text = sub; visibility = View.VISIBLE }
        }
        parent.addView(v)
    }

    private fun sheetSongHeader(parent: android.widget.LinearLayout, track: Track) {
        val v = layoutInflater.inflate(R.layout.sheet_song_header, parent, false)
        val art = v.findViewById<ImageView>(R.id.sheet_art).apply { clipToOutline = true }
        v.findViewById<TextView>(R.id.sheet_title).text = track.title
        v.findViewById<TextView>(R.id.sheet_subtitle).text = track.artist
        app.artworkPipeline.loadInto(art, track.thumbnailUrl, dp(58f).toInt())
        parent.addView(v)
    }

    /** One big rounded pill row. [outlined] draws it as an outline instead of a soft fill. */
    private fun sheetAction(
        parent: android.widget.LinearLayout,
        icon: Int,
        label: String,
        trailing: String? = null,
        trailingIcon: Int? = null,
        outlined: Boolean = false,
        onClick: (View) -> Unit
    ): View {
        val row = layoutInflater.inflate(R.layout.item_sheet_action, parent, false)
        row.findViewById<ImageView>(R.id.action_icon).setImageResource(icon)
        row.findViewById<TextView>(R.id.action_label).text = label
        row.findViewById<TextView>(R.id.action_trailing).apply {
            if (trailing.isNullOrBlank()) visibility = View.GONE else { text = trailing; visibility = View.VISIBLE }
        }
        row.findViewById<ImageView>(R.id.action_trailing_icon).apply {
            if (trailingIcon == null) visibility = View.GONE else { setImageResource(trailingIcon); visibility = View.VISIBLE }
        }
        if (outlined) {
            row.setBackgroundResource(R.drawable.shape_pill_outline)
            row.tag = "fill:fg"
        }
        row.clipToOutline = true // keeps the press ripple inside the pill
        row.contentDescription = if (trailing.isNullOrBlank()) label else "$label, $trailing"
        if (parent.childCount == 0) {
            (row.layoutParams as ViewGroup.MarginLayoutParams).topMargin = 0
        }
        row.setOnClickListener { v ->
            if (sheetClosing) return@setOnClickListener
            haptic(v)
            onClick(v)
        }
        PressScale.apply(row) // v4.9.9, also for rows added after the sheet opened
        parent.addView(row)
        return row
    }

    // ------------------------------------------------------------------------------------------
    // Song menu
    // ------------------------------------------------------------------------------------------

    /**
     * The song menu, everywhere a song is long-pressed: Play next, Add to queue, Add to playlist,
     * Like/Unlike, Go to artist, Share, plus what fits where it opened (Remove from playlist,
     * Not interested in Discover, Remove from queue).
     * [queueIndex] is set when it opens from the queue; [fromNowPlaying] when it opens on Now Playing.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun showTrackOptions(
        anchor: View?,
        track: Track,
        fromPlaylistId: Long? = null,
        fromDiscover: Boolean = false,
        queueIndex: Int? = null,
        fromNowPlaying: Boolean = false
    ) {
        val state = app.playbackController.uiState.value
        val liked = app.database.likedIds.value.contains(track.id)
        val isPlayingNow = state.currentTrack?.id == track.id &&
            (fromNowPlaying || (queueIndex != null && queueIndex == state.currentIndex))
        val artistLine = track.artist
        val hasArtist = MusicSourceAdapter.firstArtistName(artistLine).isNotBlank()
        val onThatArtist = isArtistOnTop() &&
            MusicSourceAdapter.normName(MusicSourceAdapter.firstArtistName(artistLine)) ==
                MusicSourceAdapter.normName(artistPage?.name ?: artistLookupName ?: "")

        openSheet { box ->
            sheetSongHeader(box, track)
            if (!isPlayingNow) {
                sheetAction(box, R.drawable.ic_play_next, getString(R.string.play_next)) {
                    closeSheet()
                    if (queueIndex != null) playQueueItemNext(queueIndex)
                    else {
                        app.playbackController.playNext(track)
                        showNote(getString(R.string.note_playing_next))
                    }
                }
            }
            if (queueIndex == null && !isPlayingNow) {
                sheetAction(box, R.drawable.ic_queue_add, getString(R.string.sheet_add_to_queue)) {
                    closeSheet()
                    app.playbackController.addToQueue(track)
                    showNote(getString(R.string.note_added_queue))
                }
            }
            sheetAction(box, R.drawable.ic_playlist_add, getString(R.string.add_to_playlist)) {
                showAddToPlaylistSheet(track)
            }
            sheetAction(
                box,
                if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline,
                getString(if (liked) R.string.unlike else R.string.like)
            ) {
                closeSheet()
                lifecycleScope.launch {
                    app.database.toggleLike(track)
                    refreshData()
                    showNote(getString(if (liked) R.string.note_unliked else R.string.note_liked))
                }
            }
            if (hasArtist && !onThatArtist) {
                sheetAction(box, R.drawable.ic_person, getString(R.string.sheet_go_to_artist)) {
                    closeSheet()
                    goToArtist(artistLine)
                }
            }
            sheetAction(box, R.drawable.ic_share, getString(R.string.sheet_share)) {
                closeSheet { shareTrack(track) }
            }
            if (fromPlaylistId != null) {
                sheetAction(box, R.drawable.ic_playlist_remove, getString(R.string.remove_from_playlist)) {
                    closeSheet()
                    val listName = openListName
                    lifecycleScope.launch {
                        app.database.removeTrackFromPlaylist(fromPlaylistId, track.id)
                        refreshData()
                        refreshOpenList()
                        showNote(getString(R.string.note_removed_from, listName))
                    }
                }
            }
            if (fromDiscover) {
                sheetAction(box, R.drawable.ic_block, getString(R.string.not_interested)) {
                    closeSheet()
                    markNotInterested(track)
                }
            }
            if (queueIndex != null && !isPlayingNow) {
                sheetAction(box, R.drawable.ic_close, getString(R.string.sheet_remove_from_queue)) {
                    closeSheet()
                    app.playbackController.removeQueueItem(queueIndex)
                    showNote(getString(R.string.note_removed_queue))
                }
            }
        }
    }

    /**
     * v4.9.7 Now Playing 3 dot menu, a Minim sheet in the app's colours: Add to playlist,
     * Go to artist (the first one when there are several), Share and Sleep timer.
     */
    private fun showNowPlayingMenu() {
        val track = app.playbackController.uiState.value.currentTrack ?: return
        val hasArtist = MusicSourceAdapter.firstArtistName(track.artist).isNotBlank()
        openSheet { box ->
            sheetSongHeader(box, track)
            sheetAction(box, R.drawable.ic_playlist_add, getString(R.string.add_to_playlist)) {
                showAddToPlaylistSheet(track)
            }
            if (hasArtist) {
                sheetAction(box, R.drawable.ic_person, getString(R.string.sheet_go_to_artist)) {
                    closeSheet()
                    goToArtist(track.artist)
                }
            }
            sheetAction(box, R.drawable.ic_share, getString(R.string.sheet_share)) {
                closeSheet { shareTrack(track) }
            }
            sheetAction(
                box,
                R.drawable.ic_bedtime,
                getString(R.string.settings_sleep),
                trailing = sleepRemainingLabel() ?: getString(R.string.opt_off)
            ) {
                showSleepTimerSheet()
            }
        }
    }

    /** "12:34" left on the sleep timer, or null when it's off. */
    private fun sleepRemainingLabel(): String? {
        val ms = app.sleepTimerManager.remainingTimeMs.value
        if (ms <= 0L) return null
        return ArtworkPipeline.formatDurationSec((ms + 999L) / 1000L)
    }

    /** Sleep timer from Now Playing: Off / 15 / 30 / 60 min, the same choices as Settings. */
    private fun showSleepTimerSheet() {
        val left = sleepRemainingLabel()
        openSheet { box ->
            sheetHeading(box, getString(R.string.settings_sleep), left?.let { getString(R.string.sleep_status, it) })
            val choices = listOf(
                0 to getString(R.string.opt_off),
                15 to getString(R.string.opt_15),
                30 to getString(R.string.opt_30),
                60 to getString(R.string.opt_60)
            )
            for ((minutes, label) in choices) {
                val picked = if (minutes == 0) left == null else left != null && sleepChoice == minutes
                sheetAction(
                    box,
                    R.drawable.ic_bedtime,
                    label,
                    trailingIcon = if (picked) R.drawable.ic_check else null
                ) {
                    closeSheet()
                    sleepChoice = minutes
                    if (minutes == 0) {
                        app.sleepTimerManager.cancelTimer()
                        if (left != null) showNote(getString(R.string.note_sleep_off))
                    } else {
                        app.sleepTimerManager.startTimer(minutes)
                        showNote(getString(R.string.note_sleep_set, label))
                    }
                    if (isSettingsOpen) renderSettingsOptions()
                }
            }
        }
    }

    /** From the queue: move that song to right after the one playing. */
    private fun playQueueItemNext(index: Int) {
        val state = app.playbackController.uiState.value
        val to = SongMenuLogic.playNextTarget(index, state.currentIndex, state.queue.size)
        if (to == null) {
            if (index == state.currentIndex + 1) showNote(getString(R.string.note_already_next))
            return
        }
        app.playbackController.moveQueueItem(index, to)
        showNote(getString(R.string.note_playing_next))
    }

    private fun isArtistOnTop(): Boolean =
        isArtistOpen && !isShelfOpen && !isListOpen && !isSearchOpen && !isNowPlayingOpen &&
            !isLyricsOpen && !isQueueOpen && !isSettingsOpen

    private fun goToArtist(line: String) {
        if (isQueueOpen) closeQueue()
        if (isLyricsOpen) closeLyrics(animate = false)
        if (isSettingsOpen) closeSettings()
        if (isNowPlayingOpen) collapseNowPlaying()
        openArtistByName(line)
    }

    private fun shareTrack(track: Track) {
        val url = SongMenuLogic.shareUrl(track.id, track.sourceUrl)
        val text = SongMenuLogic.shareText(track.title, track.artist, url)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, track.title)
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(android.content.Intent.createChooser(send, getString(R.string.share_chooser)))
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------------------------------
    // Playlists
    // ------------------------------------------------------------------------------------------

    /** Add to playlist: "+ New playlist" first, then your playlists as pills. One tap adds and closes. */
    private fun showAddToPlaylistSheet(track: Track) {
        lifecycleScope.launch {
            val playlists = app.database.getPlaylists()
            if (playlists.isEmpty()) {
                // Nothing to pick from yet: straight to naming a new one (the song goes in it)
                showCreatePlaylistSheet(track)
                return@launch
            }
            val already = app.database.getPlaylistIdsContaining(track.id)
            openSheet { box ->
                sheetHeading(box, getString(R.string.add_to_playlist), track.title)
                sheetAction(box, R.drawable.ic_add, getString(R.string.sheet_new_playlist), outlined = true) {
                    showCreatePlaylistSheet(track)
                }
                for (pl in playlists) {
                    val isIn = pl.id in already
                    sheetAction(
                        box,
                        R.drawable.ic_queue_add,
                        pl.name,
                        trailing = if (isIn) getString(R.string.sheet_in_playlist) else songCount(pl.trackCount),
                        trailingIcon = if (isIn) R.drawable.ic_check else null
                    ) {
                        closeSheet()
                        if (isIn) {
                            showNote(getString(R.string.note_already_in, pl.name))
                        } else lifecycleScope.launch {
                            app.database.addTrackToPlaylist(pl.id, track)
                            refreshData()
                            if (isListOpen && openListId == pl.id) refreshOpenList()
                            showNote(getString(R.string.added_to_playlist, pl.name))
                        }
                    }
                }
            }
        }
    }

    /** New playlist: one big name box; "create" stays faded until something is typed. */
    private fun showCreatePlaylistSheet(addAfterCreate: Track?) {
        openSheet { box ->
            sheetHeading(box, getString(R.string.sheet_new_playlist), addAfterCreate?.title)
            val v = layoutInflater.inflate(R.layout.sheet_create_playlist, box, false)
            val input = v.findViewById<EditText>(R.id.sheet_name_input)
            val error = v.findViewById<TextView>(R.id.sheet_name_error)
            val create = v.findViewById<TextView>(R.id.sheet_create)
            var ready = false
            fun setReady(on: Boolean) {
                if (on == ready) return
                ready = on
                create.isEnabled = on
                val a = if (on) 1f else 0.35f
                if (animationsOn()) create.animate().alpha(a).setDuration(180L).start() else create.alpha = a
            }
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    setReady(!s?.toString()?.trim().isNullOrEmpty())
                    if (error.visibility == View.VISIBLE) error.visibility = View.GONE
                }
            })
            fun submit() {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || sheetClosing) return
                haptic(create)
                lifecycleScope.launch {
                    val newId = app.database.createPlaylist(name)
                    if (newId == -1L) {
                        error.setText(R.string.sheet_playlist_exists)
                        error.visibility = View.VISIBLE
                        return@launch
                    }
                    if (addAfterCreate != null) app.database.addTrackToPlaylist(newId, addAfterCreate)
                    refreshData()
                    closeSheet()
                    showNote(
                        if (addAfterCreate != null) getString(R.string.added_to_playlist, name)
                        else getString(R.string.note_created, name)
                    )
                }
            }
            create.setOnClickListener { if (ready) submit() }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    if (ready) submit()
                    true
                } else false
            }
            box.addView(v)
            input.requestFocus()
            showKeyboard(input)
        }
    }

    /** Delete a playlist (long-press it in Library): same sheet style, "delete" and "cancel" pills. */
    private fun confirmDeletePlaylist(playlist: Playlist) {
        openSheet { box ->
            sheetHeading(box, getString(R.string.delete_playlist_title, playlist.name), getString(R.string.sheet_delete_sub))
            val v = layoutInflater.inflate(R.layout.sheet_confirm, box, false)
            v.findViewById<TextView>(R.id.sheet_confirm_cancel).setOnClickListener { closeSheet() }
            v.findViewById<TextView>(R.id.sheet_confirm_ok).setOnClickListener {
                if (sheetClosing) return@setOnClickListener
                haptic(it)
                closeSheet()
                lifecycleScope.launch {
                    app.database.deletePlaylist(playlist.id)
                    refreshData()
                    showNote(getString(R.string.note_deleted, playlist.name))
                }
            }
            box.addView(v)
        }
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    private fun formatRemaining(durationMs: Long, positionMs: Long): String {
        if (durationMs <= 0L) return getString(R.string.time_zero_remaining)
        val leftSec = ((durationMs - positionMs).coerceAtLeast(0L) + 999L) / 1000L
        return "-" + ArtworkPipeline.formatDurationSec(leftSec)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun showKeyboard(target: View) {
        target.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val token = currentFocus?.windowToken ?: window.decorView.windowToken
        token?.let { imm?.hideSoftInputFromWindow(it, 0) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    companion object {
        private const val GRID_COLUMNS = 2
        private const val LIKED_LIST = -1L
        /** v4.9: Recents opens in the song list screen from Library. */
        private const val RECENTS_LIST = -2L
        /** v4.9.2: most rows in today's songs / artists sheets. */
        private const val TODAY_SHEET_MAX = 100

        /** v4.9: how many quiet past days the Stats tab looks through for a better recap. */
        private const val MAX_RECAP_LOOKBACK = 30
        /** v4.9.1: the popup waits this long (and re-checks) so it never lands mid-animation. */
        private const val RECAP_POPUP_DELAY_MS = 700L
        private const val UPDATE_POPUP_DELAY_MS = 900L
        private const val ARTIST_SHELF = "artist:"
        private const val GREETING_INTERVAL_MS = 10_000L
        private const val GREETING_MESSAGE_MS = 5_000L
        private const val GREETING_FACTS_MAX_AGE_MS = 60_000L
        private const val TIP_INTERVAL_MS = 10_000L
        private const val GREETING_SP = 24f
        private const val GREETING_MIN_SP = 17f
        private const val PILL_MIN_DP = 170f
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val WELCOME_AUTO_MS = 3_000L
    }
}
