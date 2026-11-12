package com.minim.music.ui

import com.minim.music.data.update.AppUpdates
import com.minim.music.data.config.Announcement
import com.minim.music.data.config.FestivalEvent
import com.minim.music.data.config.InstalledVersion
import com.minim.music.data.config.UpdateLock
import com.minim.music.data.config.VibeConfig
import com.minim.music.data.update.AvailableUpdate
import com.minim.music.data.update.UpdateCheckResult
import com.minim.music.data.update.UpdateFlowState
import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.minim.music.data.account.AccountOutcome
import com.minim.music.data.account.AccountProblem
import com.minim.music.data.account.MinimAccount
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import com.minim.music.data.sync.CloudSync
import com.minim.music.data.sync.SyncText
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.minim.music.data.ai.ChatTools
import com.minim.music.data.ai.GeminiChatSession
import com.minim.music.data.ai.GeminiModels
import com.minim.music.data.ai.GeminiRoute
import com.minim.music.data.ai.isAiLyrics
import com.minim.music.data.ai.GeminiEndpoint
import com.minim.music.data.ai.AiLyricsSong
import com.minim.music.data.ai.AiLyricsResult
import com.minim.music.data.ai.AppCheckDebugToken
import com.minim.music.data.ai.GeminiSettings
import com.minim.music.data.ai.MinimActions
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Minim v4: one activity, every screen stacked in activity_main.
 *
 *  Tabs (one shown at a time): Home, stories (5.7.0 u44, was Lab, was Discover), Stats (v4.9, was Recents), Library.
 *  5.7.0: no swipe up on Home any more; Discover's shelves live on the Search page.
 *  On top of them, in order: List (Liked Songs / a playlist), Search, the floating mini player,
 *  Now Playing, Lyrics, Queue, Settings.
 *
 * Every view is coloured from its android:tag by [Painter] with the current [Pal], which comes
 * from the cover art of the song playing (or the design greens) in the Light or Dark setting.
 */
class MainActivity : AppCompatActivity() {

    private val app: MinimApp by lazy { MinimApp.instance }

    private enum class Tab { HOME, STORIES, STATS, LIBRARY }

    // ---- small view groups that appear more than once -------------------------------------

    private class TopRow(val root: View) {
        val logo: View = root.findViewById(R.id.top_logo)
        val search: View = root.findViewById(R.id.top_search)
        val tabHome: View = root.findViewById(R.id.top_tab_home)
        val tabStories: View = root.findViewById(R.id.top_tab_stories)
        val tabStats: View = root.findViewById(R.id.top_tab_stats)
        val tabLibrary: View = root.findViewById(R.id.top_tab_library)
        val lineHome: View = root.findViewById(R.id.top_line_home)
        val lineStories: View = root.findViewById(R.id.top_line_stories)
        val lineStats: View = root.findViewById(R.id.top_line_stats)
        /** v4.9: "new" tag and recap dot next to "stats". */
        val statsNew: View = root.findViewById(R.id.top_stats_new)
        val statsDot: View = root.findViewById(R.id.top_stats_dot)
        /** 5.7.0 u44: "new" tag next to "stories" (until the tab is first tapped). */
        val storiesNew: View = root.findViewById(R.id.top_stories_new)
        val lineLibrary: View = root.findViewById(R.id.top_line_library)
        /** v5.0: the profile picture that replaced the gear; still opens Settings. */
        val gear: AvatarView = root.findViewById(R.id.top_gear)
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
        /** 5.7.0 u48: the pill's background, with the fill sweep. */
        var sweep: PillSweepDrawable? = null
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
    private lateinit var heartFx: HeartFx
    private lateinit var screenHome: View
    private lateinit var screenStats: View
    private lateinit var statsScreen: StatsScreen
    // v4.9: Stats tab state
    private var todayStats: DayStats? = null
    private var recapStats: DayStats? = null
    private var statsDay = 0
    private var statsJob: Job? = null
    private val artistPhotoTried = HashSet<String>()
    private lateinit var screenStories: View
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
    /** 5.8.5 u57: the tab row at the top of Search (same layout as the tab screens' rows). */
    private lateinit var searchRow: TopRow
    /** Every tab row: the four tab screens' and Search's (fitting, badges, avatars). */
    private val allRows: List<TopRow> get() = topRows.map { it.second } + searchRow

    private lateinit var homeListening: View
    private lateinit var homeMini: Mini
    private lateinit var floatMini: Mini
    private lateinit var homeGreeting: TextView

    private lateinit var listTitle: TextView
    private lateinit var listSubtitle: TextView
    // 5.3.2: rename box, "+ Add songs" pill and the "from [logo]" row
    private lateinit var listTitleEdit: EditText
    private lateinit var listAddSongs: TextView
    private lateinit var listSubtitleRow: View
    private lateinit var listSourceFrom: TextView
    private lateinit var listSourceLogo: ImageView
    private var isRenaming = false
    private lateinit var listEmpty: TextView

    private lateinit var searchInput: EditText
    private lateinit var searchStatus: TextView

    private lateinit var npArt: ImageView
    private lateinit var npLyric: NowPlayingLyricView
    private lateinit var npControlsRoot: View
    private lateinit var npTitle: TextView
    private lateinit var npVibing: TextView
    private lateinit var npArtist: TextView
    private lateinit var npControls: Controls


    private lateinit var queueEmpty: View

    private lateinit var settingsName: EditText

    // ---- lists (adapters are in ui/adapter) --------------------------------------------------

    private lateinit var libraryAdapter: GridTileAdapter
    /** 5.8.5 u57: every Library tile (before the "Search Playlists" filter) and what's typed. */
    private var libraryTiles: List<GridTile> = emptyList()
    private var libraryQuery: String = ""
    private var libraryFilterOpen = false
    private lateinit var listAdapter: TrackAdapter
    private lateinit var likedArtistAdapter: com.minim.music.ui.adapter.LikedArtistAdapter
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
    /** 5.3.0: "spotify" / "youtube" / "text" for the open playlist, null when it wasn't imported. */
    private var openListSource: String? = null
    private var recentTracks: List<Track> = emptyList()
    private var refreshJob: Job? = null



    // 5.5.0: the mini player <-> Now Playing morph (follows the finger both ways)
    private lateinit var npMorph: NowPlayingMorph
    private lateinit var queueMorph: QueueMorph
    private lateinit var settingsReveal: SettingsReveal
    private lateinit var queueList: RecyclerView
    /** Keeps the floating mini player on screen (invisible) while it morphs, so it has a place. */
    private var miniHeldForMorph = false
    /** This touch started on a mini player pill: a swipe up opens Now Playing. */
    private var downOnMiniPill = false
    private var npSwipeCandidate = false
    private var npSwiping = false
    private var npSwipeDownX = 0f
    private var npSwipeDownY = 0f
    private var npSwipeVelocity: android.view.VelocityTracker? = null

    // v4.1: rotating greetings, mini player pop in/out, pill width
    // v4.5: regular + fun (data-based) greetings, every second one fun when one fits
    private val greetingRotation = Greetings.Rotation()
    private var greetingFacts = GreetingFacts()
    private var greetingFactsAt = 0L
    private var greetingIndex = Greetings.TIME_BASED
    private var greetingJob: Job? = null
    private var greetingFileJob: Job? = null
    /** The two lines on screen, so a "new" greeting that reads the same doesn't animate. */
    private var shownGreeting: Pair<String, String>? = null
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

    /**
     * 5.7.0 u50: true when this launch went straight to the "Minim crashed last time" screen
     * ([CrashReportActivity]) instead of building the app. Nothing else was set up, so every
     * lifecycle method below just hands over to Android.
     */
    private var showingCrashReport = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 5.7.0 u50: a crash report from last time is shown first, before any of the app's own
        // screens are built, so even a crash that happens on every launch can be copied or shared
        if (CrashReportActivity.openIfPending(this, savedInstanceState)) {
            showingCrashReport = true
            return
        }
        intent?.removeExtra(CrashReportActivity.EXTRA_SHOWN)
        setContentView(R.layout.activity_main)
        // v4.6.1: draw behind the status bar so the Artist page photo can reach the top of the
        // screen. Every other screen is kept below it by the root's padding (setupWindowInsets).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            // 5.5.2 (39): remembered so the nav bar goes back to exactly this off the wallpaper
            baseNavContrast = window.isNavigationBarContrastEnforced
            themeNavContrast = baseNavContrast
        }
        // v4.7: on first launch the notification prompt waits until the welcome screens are done
        if (app.preferences.welcomeDone) requestNotificationPermissionIfNeeded()

        bindViews()
        setupTopRows()
        setupHome()
        setupSearch()
        setupLists()
        setupDiscover()
        setupStories()
        setupArtist()
        setupSheets()
        setupImport()
        setupWindowInsets()
        setupMiniPlayers()
        setupNowPlaying()
        setupLyrics()
        fsLyrics.onCaptionPill = { app.playbackController.nextCaptionTrack() }
        setupQueue()
        setupSettings()
        setupGemini()
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
        // 5.3.0: Share -> Minim opened the app (a cold start); onNewIntent covers a running one
        if (savedInstanceState == null) handleImportIntent(intent)
        // 5.7.0 u50: "Minim crashed last time" is its own screen now (CrashReportActivity), shown at
        // the top of onCreate before anything here can crash again

        lifecycleScope.launch {
            app.database.refreshLikedCache()
            app.database.refreshNotInterestedCache()
            refreshData()
        }
    }

    override fun onStart() {
        super.onStart()
        if (showingCrashReport) return
        if (::fsLyrics.isInitialized) fsLyrics.setHostVisible(true)
        // v4.9.9: the Now Playing lyric line's Flow frames too
        if (::npLyric.isInitialized) npLyric.setHostVisible(true)
        // 5.8.2: the recap's song carries on with the story
        recapMusic?.resume()
        // v4.9.8: an app open (cold start, or the app back from the background, even into a new
        // MainActivity) may run a quiet update check; see onAppOpenedForUpdates
        app.foreground.takeOpen()?.let { onAppOpenedForUpdates(it) }
        // v4.9.6: new greetings from GitHub. 5.1.0: checked every 10 minutes while the app is open
        // (the store skips the download when it isn't due, and an unchanged file is a tiny 304)
        startGreetingFileChecks()
        // Coming back from the background counts as opening the app: time-based greeting first
        // 5.0.0 part 3: read the cloud config again (at most every 30 min) and redo today's festival
        app.vibe.refresh()
        renderFestival()
        if (wasStopped) {
            wasStopped = false
            showTimeGreeting()
            showFirstTip()
            refreshStats()
        }
    }

    override fun onResume() {
        super.onResume()
        if (showingCrashReport) return
        isActivityResumed = true
        syncGreetingRotation()
        // v4.9.6: back from the "Install unknown apps" setting with it turned on: carry on installing
        resumeUpdateInstallIfAllowed()
        checkRecapPopup()
        maybeOfferRestore()
        maybeShowLibraryCopyNote()
    }

    /**
     * 5.10.0: the first upgrade couldn't copy the 5.9.1 library (low storage, or the copy failed),
     * so this version is writing the 5.9.1 file itself: say once that going back to an older
     * version isn't safe until there's space. LibraryFiles tries the copy again on every start.
     */
    private fun maybeShowLibraryCopyNote() {
        if (isWelcomeOpen || !::minimNote.isInitialized) return
        when (com.minim.music.data.local.LibraryFiles.takeNote(this)) {
            com.minim.music.data.local.LibraryFiles.Note.LOW_SPACE -> showNote(getString(R.string.library_copy_low_space))
            com.minim.music.data.local.LibraryFiles.Note.COPY_FAILED -> showNote(getString(R.string.library_copy_failed))
            null -> Unit
        }
    }

    override fun onPause() {
        if (showingCrashReport) {
            super.onPause()
            return
        }
        isActivityResumed = false
        syncGreetingRotation()
        super.onPause()
    }

    override fun onStop() {
        if (showingCrashReport) {
            super.onStop()
            return
        }
        wasStopped = true
        greetingFileJob?.cancel()
        greetingFileJob = null
        // v4.9.8 part 4: no Flow frames in the background
        if (::fsLyrics.isInitialized) fsLyrics.setHostVisible(false)
        if (::npLyric.isInitialized) npLyric.setHostVisible(false)
        // 5.8.2: no recap song in the background
        recapMusic?.pause()
        // v4.9: save what's been heard so far right away (the app may be closed from here)
        app.playbackController.flushListening()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (showingCrashReport) return
        // uiMode changes are handled here (no restart): keep the app's own Light/Dark choice
        animatePaletteTo(appPal())
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        screenHome = findViewById(R.id.screen_home)
        screenStats = findViewById(R.id.screen_stats)
        statsScreen = StatsScreen(screenStats, app.artworkPipeline)
        screenStories = findViewById(R.id.screen_stories)
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
            Tab.STORIES to TopRow(findViewById(R.id.stories_top)),
            Tab.STATS to TopRow(findViewById(R.id.stats_top)),
            Tab.LIBRARY to TopRow(findViewById(R.id.library_top))
        )
        searchRow = TopRow(findViewById(R.id.search_top))

        homeListening = findViewById(R.id.home_listening)
        homeMini = Mini(findViewById(R.id.home_mini), findViewById(R.id.home_mini_actions))
        floatMini = Mini(findViewById(R.id.mini_player))
        // 5.7.0 u48: both pills fill from the left as the song plays (the tint stays the Painter's)
        for (mini in listOf(homeMini, floatMini)) {
            val sweep = PillSweepDrawable(animationsOn = { animationsOn() })
            mini.sweep = sweep
            mini.root.background = sweep
        }
        homeGreeting = findViewById(R.id.home_greeting)
        homeSearch = findViewById(R.id.home_search)
        homeHint = findViewById(R.id.home_hint)

        listTitle = findViewById(R.id.list_title)
        listSubtitle = findViewById(R.id.list_subtitle)
        listEmpty = findViewById(R.id.list_empty)
        setupListHeader()

        searchInput = findViewById(R.id.search_input)
        searchStatus = findViewById(R.id.search_status)

        npArt = findViewById(R.id.np_art)
        npLyric = findViewById(R.id.np_lyric)
        npControlsRoot = findViewById(R.id.np_controls)
        npTitle = findViewById(R.id.np_title)
        npVibing = findViewById(R.id.np_vibing)
        npArtist = findViewById(R.id.np_artist)
        npControls = Controls(findViewById(R.id.np_controls))

        queueEmpty = findViewById(R.id.queue_empty)

        settingsName = findViewById(R.id.settings_name)

        // Round cover art: clip the image to its background shape (circle / rounded card)
        homeMini.art.clipToOutline = true
        floatMini.art.clipToOutline = true
        // Long titles scroll sideways (marquee) only when they don't fit; it needs "selected"
        for (mini in listOf(homeMini, floatMini)) {
            mini.title.isSelected = true
            mini.artist.isSelected = true
        }
        npArt.clipToOutline = true

        // 5.5.0: the animated hearts (the burst and big heart draw in the root's overlay)
        heartFx = HeartFx(root) { ValueAnimator.areAnimatorsEnabled() }
        for (v in listOf(homeMini.like, floatMini.like, npControls.like)) heartFx.attach(v)
        // 5.7.0 u50: the Artist page heart is attached in setupArtist(), once artistLike exists.
        // u48 attached it here, before setupArtist() had looked it up, so every launch crashed.
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
            // Home and (5.7.0 u46) stories show the "minim" logo; Stats and Library a search pill instead
            row.logo.visibility = if (com.minim.music.data.stories.TopRowBranding.showsLogo(rowTab.name)) View.VISIBLE else View.GONE
            // 5.7.0 u45: stories has no music search (its + Add is the first tile of the channel row)
            row.search.visibility = if (rowTab == Tab.HOME || rowTab == Tab.STORIES) View.GONE else View.VISIBLE
            row.storiesNew.visibility = if (app.preferences.storiesTabSeen) View.GONE else View.VISIBLE
            row.lineHome.visibility = if (rowTab == Tab.HOME) View.VISIBLE else View.INVISIBLE
            row.lineStories.visibility = if (rowTab == Tab.STORIES) View.VISIBLE else View.INVISIBLE
            row.lineStats.visibility = if (rowTab == Tab.STATS) View.VISIBLE else View.INVISIBLE
            row.lineLibrary.visibility = if (rowTab == Tab.LIBRARY) View.VISIBLE else View.INVISIBLE

            row.tabHome.setOnClickListener { switchTabByHand(it, Tab.HOME) }
            row.tabStories.setOnClickListener { switchTabByHand(it, Tab.STORIES) }
            row.tabStats.setOnClickListener { switchTabByHand(it, Tab.STATS) }
            row.tabLibrary.setOnClickListener { switchTabByHand(it, Tab.LIBRARY) }
            row.gear.setOnClickListener { v -> openSettings(from = v) }
            // v5.0: the avatar is round, not a pill, so it gets the 4.9.9 press shrink explicitly
            PressScale.apply(row.gear)
            row.search.setOnClickListener { openSearch(it) }
        }
        // 5.8.5 u57: Search's own tab row: the logo (never the search button, Search is open), the
        // current tab underlined, and a tap on a tab leaves Search for that tab
        searchRow.logo.visibility = View.VISIBLE
        searchRow.search.visibility = View.GONE
        searchRow.storiesNew.visibility = if (app.preferences.storiesTabSeen) View.GONE else View.VISIBLE
        markSearchRowTab()
        searchRow.tabHome.setOnClickListener { leaveSearchFor(it, Tab.HOME) }
        searchRow.tabStories.setOnClickListener { leaveSearchFor(it, Tab.STORIES) }
        searchRow.tabStats.setOnClickListener { leaveSearchFor(it, Tab.STATS) }
        searchRow.tabLibrary.setOnClickListener { leaveSearchFor(it, Tab.LIBRARY) }
        searchRow.gear.setOnClickListener { v -> openSettings(from = v) }
        PressScale.apply(searchRow.gear)
        fitTopRows()
    }

    /**
     * v4.3: four tabs no longer fit every phone at the design size. Steps the gaps, tab text and
     * left area down (same step for every row, so the tab underline still lines up) until the
     * row fits the screen. On wide phones nothing changes.
     */
    /** 5.8.5 u57: the fit level the rows use now (see fitTopRows). */
    private var topFitLevel = 0
    private var topFitWatch = false

    private fun fitTopRows(startAt: Int = 0) {
        // 5.8.5 u57: the left area is as wide as what it shows (the logo, or the round search
        // button lined up where the logo starts), and the first tab follows it at the same gap the
        // tabs keep between themselves. Logo and button share one start, so a logo tab and a search
        // tab differ only in the left item's width, and slideTopRow slides the tabs across that.
        data class Fit(val gap: Int, val sp: Float, val logoStart: Int, val button: Int)
        val levels = listOf(
            Fit(20, 15f, 34, 30),
            Fit(16, 15f, 32, 30),
            Fit(14, 14f, 28, 30),
            Fit(12, 13.5f, 24, 28),
            Fit(10, 13f, 22, 28),
            Fit(8, 12f, 18, 26),
            Fit(8, 11f, 16, 26),
            Fit(6, 10.5f, 14, 24)
        )
        val avail = resources.displayMetrics.widthPixels
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        topFitLevel = startAt.coerceIn(0, levels.lastIndex)
        for ((index, fit) in levels.withIndex()) {
            if (index < startAt.coerceAtMost(levels.lastIndex)) continue
            topFitLevel = index
            var needed = 0
            for (row in allRows) {
                (row.logo.layoutParams as ViewGroup.MarginLayoutParams).marginStart = dp(fit.logoStart.toFloat()).toInt()
                (row.search.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    marginStart = dp(fit.logoStart.toFloat()).toInt()
                    width = dp(fit.button.toFloat()).toInt()
                    height = width
                }
                for (t in listOf(row.tabHome, row.tabStories, row.tabStats, row.tabLibrary)) {
                    // v4.9: the Stats tab's text sits in a row with its "new" tag / dot
                    val first = (t as? ViewGroup)?.getChildAt(0)
                    ((first as? TextView) ?: ((first as? ViewGroup)?.getChildAt(0) as? TextView))?.textSize = fit.sp
                    (t.layoutParams as ViewGroup.MarginLayoutParams).marginStart = dp(fit.gap.toFloat()).toInt()
                }
                row.root.requestLayout()
                row.root.measure(unspecified, unspecified)
                // 5.8.5 u57: add the children up (the row's own measured width came out short at
                // 130% font, so the avatar could end past the screen's edge)
                val g = row.root as ViewGroup
                var sum = g.paddingStart + g.paddingEnd
                for (i in 0 until g.childCount) {
                    val c = g.getChildAt(i)
                    if (c.visibility == View.GONE) continue
                    val lp = c.layoutParams as ViewGroup.MarginLayoutParams
                    sum += c.measuredWidth + lp.marginStart + lp.marginEnd
                }
                needed = maxOf(needed, sum, row.root.measuredWidth)
            }
            if (needed + dp(12f) <= avail) break
        }
        fitTabLines()
        // 5.8.5 u57: the measure above can come out short (a "new" tag shown later, big font), so
        // each row checks itself once laid out and steps down a level when its avatar would end
        // past the screen's edge
        if (!topFitWatch) {
            topFitWatch = true
            for (row in allRows) {
                row.root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    if (topRowOverflows(v as ViewGroup) && topFitLevel < levels.lastIndex) {
                        val next = topFitLevel + 1
                        // Checked again once things settle, so a passing state doesn't shrink it
                        v.post { if (topFitLevel < next && topRowOverflows(v)) fitTopRows(next) }
                    }
                }
            }
        }
    }

    private fun topRowOverflows(g: ViewGroup): Boolean {
        if (g.width <= 0 || !g.isShown) return false
        val last = (g.childCount - 1 downTo 0).map { g.getChildAt(it) }.firstOrNull { it.visibility != View.GONE }
            ?: return false
        return last.right > g.width - g.paddingEnd
    }

    /**
     * 5.8.5 u57: where a row's first tab starts (from the row's start): its left item plus the
     * gap. Measured, so it's right for a row whose screen hasn't been laid out yet.
     */
    private fun tabsStart(row: TopRow): Float {
        val left = row.logo.parent as View
        left.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return (left.measuredWidth + (row.tabHome.layoutParams as ViewGroup.MarginLayoutParams).marginStart).toFloat()
    }

    /** The views of a row that move together when its left item changes width: tabs and avatar. */
    private fun rowMovers(row: TopRow): List<View> =
        listOf(row.tabHome, row.tabStories, row.tabStats, row.tabLibrary, row.gear)

    /**
     * 5.8.5 u57: switching between a logo tab (Home, tapes) and a search tab (Stats, Library), the
     * new row's tabs and avatar start where the old row's were and slide to their own place, so
     * the gap after the left item always matches the gaps between the tabs; the new left item
     * (logo or round button) fades and grows in where the old one was.
     */
    private fun slideTopRow(from: Tab, to: Tab) {
        val fromRow = rowOf(from)
        val toRow = rowOf(to)
        if (fromRow === toRow) return
        // Where the old row's tabs are drawn right now (partway through a slide after a quick tap)
        val drawnAt = tabsStart(fromRow) + fromRow.tabHome.translationX
        val dx = drawnAt - tabsStart(toRow)
        // (The old row is put back once its screen has gone: stopTabTransition)
        val movers = rowMovers(toRow)
        for (v in movers) {
            v.animate().cancel()
            v.translationX = if (kotlin.math.abs(dx) < 0.5f) 0f else dx
            if (v.translationX != 0f) {
                v.animate().translationX(0f).setStartDelay(0L).setDuration(TabMotion.LINE_MS)
                    .setInterpolator(DecelerateInterpolator(1.5f)).start()
            }
        }
        // The new left item (logo or round search button) fades and grows in where the old one was
        val logoChanged = fromRow.logo.visibility != toRow.logo.visibility
        val leftItem = if (toRow.logo.visibility == View.VISIBLE) toRow.logo else toRow.search
        leftItem.animate().cancel()
        if (logoChanged) {
            leftItem.alpha = 0f
            leftItem.scaleX = 0.85f; leftItem.scaleY = 0.85f
            leftItem.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(40L).setDuration(TabMotion.IN_MS)
                .setInterpolator(DecelerateInterpolator(1.5f)).start()
        } else {
            leftItem.alpha = 1f; leftItem.scaleX = 1f; leftItem.scaleY = 1f
        }
    }

    /** Puts a row's tabs, avatar and left item straight back (no slide running). */
    private fun settleRowSlide(row: TopRow) {
        for (v in rowMovers(row)) { v.animate().cancel(); v.translationX = 0f }
        for (v in listOf(row.logo, row.search)) { v.animate().cancel(); v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f }
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
        for (row in allRows) {
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

    /** A tab tap: a light tick when the tab really changes. */
    private fun switchTabByHand(v: View, target: Tab) {
        if (target != tab) haptic(v, HapticFeedbackConstants.CLOCK_TICK)
        showTab(target)
    }

    private fun showTab(target: Tab, animate: Boolean = true) {
        if (target == Tab.STATS) onStatsShown()
        if (target == Tab.STORIES) onStoriesShown()
        val from = tab
        // 5.8.5 u57: leaving Library puts "Search Playlists" away (the full grid next time)
        if (target != Tab.LIBRARY && libraryFilterOpen) closeLibrarySearch(animate = false)
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
        if (slide) animateTabChange(from, target) else {
            stopTabTransition()
            settleRowSlide(rowOf(target))
        }
        // 5.5.2: the festival wallpaper fades in under Home and out for the other tabs
        syncThemeLayer(animate = slide)
        updateChrome()
    }

    private fun rowOf(t: Tab): TopRow = topRows.first { it.first == t }.second

    private fun tabViewOf(row: TopRow, t: Tab): View = when (t) {
        Tab.HOME -> row.tabHome
        Tab.STORIES -> row.tabStories
        Tab.STATS -> row.tabStats
        Tab.LIBRARY -> row.tabLibrary
    }

    private fun lineOf(row: TopRow, t: Tab): View = when (t) {
        Tab.HOME -> row.lineHome
        Tab.STORIES -> row.lineStories
        Tab.STATS -> row.lineStats
        Tab.LIBRARY -> row.lineLibrary
    }

    private fun screenOf(t: Tab): View = when (t) {
        Tab.HOME -> screenHome
        Tab.STORIES -> screenStories
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
        Tab.STORIES -> R.id.stories_top
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
        // 5.8.5 u57: the tabs slide when the left item changes width (before the line glides, so
        // the line's start is measured on the row as it is drawn)
        slideTopRow(from, to)
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
        // (5.5.2: the same with a festival wallpaper, which fades on its own instead)
        val keepOld = takeoverRoot.visibility != View.VISIBLE && themeHomePal == null
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
        // 5.8.5 u57: rows of the screens not showing go back to their own place
        for ((t, row) in topRows) if (t != tab) settleRowSlide(row)
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
        if (screen === screenHome) applyHomeOverrides()
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
        fun centre(l: View): Float = (l.parent as View).let { it.left + it.translationX } + l.left + l.width / 2f
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
        // 5.3.0: the import review hides it too (its Save pill sits where the mini player would)
        // 5.5.1: while Settings grows out of / shrinks into the avatar, the screen behind still shows
        val settingsCovers = isSettingsOpen && !(::settingsReveal.isInitialized && settingsReveal.isRunning)
        val coveredByPlayer = (isNowPlayingOpen && !miniHeldForMorph) || isLyricsOpen || isQueueOpen || settingsCovers || isImportReviewOpen
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
                    // 5.0.0 part 3: the locked update pop-up doesn't close; Back leaves the app
                    isLockSheetOpen -> moveTaskToBack(true)
                    ::sheetRoot.isInitialized && sheetRoot.visibility == View.VISIBLE -> closeSheet()
                    // 5.2.0: the Gemini chat sheet (Back with the keyboard up just hides the keyboard)
                    isGeminiOpen -> geminiChat.close()
                    isWelcomeOpen && welcomePage == 2 -> showWelcomePage(1)
                    isWelcomeOpen && welcomePage == 3 -> Unit // on its way to Home by itself
                    isSettingsOpen -> closeSettings(animate = true)
                    isQueueOpen -> closeQueue(animate = true)
                    isLyricsOpen -> closeLyrics()
                    isNowPlayingOpen -> collapseNowPlaying(animate = true)
                    isImportReviewOpen -> closeImportReview()
                    isSearchOpen -> closeSearch(animate = true)
                    // 5.3.2: Back while renaming keeps the old name
                    isListOpen && isRenaming -> cancelRename()
                    isListOpen -> closeList()
                    isShelfOpen -> closeShelf()
                    isArtistOpen -> closeArtist()
                    // 5.7.0 u46: a channel's list (from its photo or its shelf title) goes back to the shelves
                    tab == Tab.STORIES && ::storiesScreen.isInitialized && storiesScreen.backToShelves() -> Unit
                    // 5.8.5 u57: Back closes "Search Playlists" first
                    tab == Tab.LIBRARY && libraryFilterOpen -> closeLibrarySearch(animate = true)
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
            // 5.3.0: a link shared while the welcome screens were up
            pendingImportText?.let { text -> pendingImportText = null; showImportSheet(text) }
            // 5.10.0: a fresh install can bring its library back from a backup folder
            maybeOfferRestore()
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (showingCrashReport) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downOnMiniPill = isTouchOn(homeMini.root, ev) || isTouchOn(floatMini.root, ev)
        }
        // 5.5.0: swiping down on Now Playing shrinks it back into the mini player by the finger
        // 5.5.1: dragging the Up Next sheet down springs it back into its pill
        if (handleQueueSwipe(ev)) return true
        if (handleNowPlayingSwipe(ev)) return true
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
        setupTheme()
        homeSearch.setOnClickListener { openSearch(it) }
        // The tip opens what it talks about: Search for the picked-songs tips (5.7.0: Discover's
        // shelves live there now), Settings for Settings tips
        homeHint.setOnClickListener {
            when {
                tipIndex == Tips.SEARCH_PICKS || tipIndex == Tips.OPEN_PICKS -> {
                    haptic(it)
                    openSearch(homeSearch)
                }
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
                app.database.getLikedSongs()
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
                app.playbackController.playTrack(pick, queue, com.minim.music.data.fermata.FermataSource.LIBRARY)
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
        // Restart the 7 s clock so the next greeting doesn't arrive straight away
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
        pickOpener()
        renderGreeting(animate = false)
        lifecycleScope.launch { refreshGreetingFacts(force = true) }
    }

    /** 5.5.2: the first greeting: the time-based one, or during a festival one of its time-of-day lines. */
    private fun pickOpener() {
        val name = app.preferences.userName
        greetingIndex = if (Greetings.themeOn) {
            greetingRotation.opener { Greetings.fits(it, GreetingFacts(), name) }
        } else Greetings.TIME_BASED
        greetingRotation.markShown(greetingIndex)
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

    /** A regular greeting to fall back on: the time-based ones and the remote anytime lines that fit. */
    private fun nextRegularGreeting(): Int {
        val name = app.preferences.userName
        return greetingRotation.nextRegular { Greetings.fits(it, GreetingFacts(), name) }
    }

    /**
     * The lines for [greetingIndex]. A stat one whose data has gone (song stopped...), a remote one
     * that left the file or its dates, or the named time greeting after the name was removed
     * becomes a regular one instead.
     */
    private fun currentGreetingLines(): Pair<String, String> {
        greetingOverride?.let { return it }
        val name = app.preferences.userName
        repeat(3) {
            when {
                Greetings.isRemote(greetingIndex) -> {
                    val facts = if (Greetings.isAnytime(greetingIndex)) GreetingFacts() else liveGreetingFacts()
                    Greetings.remoteLines(greetingIndex, name, facts = facts)?.let { return it }
                    greetingIndex = nextRegularGreeting()
                }
                greetingIndex == Greetings.TIME_NAMED && name.isBlank() -> greetingIndex = nextRegularGreeting()
                greetingIndex == Greetings.TIME_NAMED || greetingIndex == Greetings.TIME_BASED ->
                    return Greetings.lines(greetingIndex, name)
                else -> greetingIndex = Greetings.TIME_BASED
            }
        }
        greetingIndex = Greetings.TIME_BASED
        return Greetings.lines(greetingIndex, name)
    }

    private val isHomeShowing: Boolean
        get() = isActivityResumed && !isWelcomeOpen && tab == Tab.HOME && !isSearchOpen && !isListOpen &&
            !isNowPlayingOpen && !isLyricsOpen && !isQueueOpen && !isSettingsOpen

    /**
     * A new greeting every 7 s (5.1.0, was 10 s) and a new tip every 10 s, only while Home is on
     * screen and the app is in front.
     */
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

    /** 5.1.0: looks for a new greetings file now and then every 10 minutes, until the app goes to the background. */
    private fun startGreetingFileChecks() {
        if (greetingFileJob?.isActive == true) return
        greetingFileJob = lifecycleScope.launch {
            while (isActive) {
                app.syncRemoteGreetings()
                // 5.5.2: festival themes on the same 10-minute beat (also catches midnight)
                app.syncThemes()
                // A little over 10 minutes, so the store's "10 minutes since the last check" has passed
                delay(GREETING_FILE_CHECK_MS + 5_000L)
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
        // 5.1.0: the same words again (only the time greeting fits) stay put instead of flashing
        if (animate && !greetingAnimating && shownGreeting == (l1 to l2)) return
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
        shownGreeting = l1 to l2
    }

    /**
     * The Home pill wraps its content: at least [PILL_MIN_DP] wide so short titles don't look tiny,
     * at most the screen less [MiniPill.SIDE_MARGIN_DP] a side (then the title/artist scroll). Width changes glide over 250 ms.
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
        // 5.7.0 u45: at most the screen less MiniPill.SIDE_MARGIN_DP on each side (was 85% of it),
        // the same clear space the floating pill keeps from the edges
        val target = MiniPill.homeWidth(fixed + textW + 4 * d, metrics.widthPixels, d, PILL_MIN_DP)
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
        searchDiscover = findViewById(R.id.search_discover)
        searchResultsList = findViewById(R.id.search_results)
        searchAdapter = SearchResultsAdapter(
            artwork = app.artworkPipeline,
            onClick = { track, list -> app.playbackController.playRadio(track, list, com.minim.music.data.fermata.FermataSource.SEARCH) },
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
                    setSearchTyping(false)
                    return
                }
                setSearchTyping(true)
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

    /**
     * 5.5.0: [source] is the pill that was tapped; the Search screen grows out of it (SearchMorph).
     * The focus and keyboard start with the morph so the keyboard rises in step.
     */
    private fun openSearch(source: View? = null) {
        endSearchLeave()
        setSearchTyping(searchInput.text?.toString()?.trim().orEmpty().isNotEmpty())
        // 5.7.0: Discover's shelves live here now: show the saved ones, refresh when stale
        loadDiscover(force = false)
        loadSearchExtras()
        markSearchRowTab()
        prepareSearchRow()
        searchMorph.open(source) {
            applySearchRow(1f)
            updateChrome()
            searchInput.requestFocus()
            showKeyboard(searchInput)
        }
    }

    /**
     * [animate] (Back): shrinks back into the pill it came from. Other closes (going somewhere
     * else from Search) stay instant, as before.
     */
    private fun closeSearch(animate: Boolean = false) {
        endSearchLeave()
        // A tab switch from Search had already cleared it away
        if (screenSearch.visibility != View.VISIBLE && !searchMorph.closing) {
            restoreSearchBehind()
            updateChrome()
            return
        }
        hideKeyboard()
        searchInput.clearFocus()
        val hide = {
            screenSearch.visibility = View.GONE
            restoreSearchBehind()
            settleSearchRow()
            updateChrome()
        }
        if (animate) {
            prepareSearchRow()
            searchMorph.close(hide)
        } else {
            searchMorph.finish()
            hide()
        }
    }

    private val searchMorph: SearchMorph by lazy {
        SearchMorph(
            overlayHost = root,
            screen = screenSearch,
            // 5.8.5 u57: no header logo any more; the tab row follows the morph (onProgress)
            logo = null,
            input = searchInput,
            go = findViewById(R.id.search_go),
            results = findViewById(R.id.search_results),
            status = searchStatus,
            discover = searchDiscover,
            colors = { pal.fg to pal.bg },
            animationsOn = { ValueAnimator.areAnimatorsEnabled() },
            onProgress = { t -> applySearchRow(t) }
        )
    }

    // ---- 5.8.5 u57: Search's tab row -----------------------------------------------------------
    //
    // Search shows the same tab row as the tab screens. While the box grows out of a search button
    // (Stats, Library), the tab row behind is handed over to Search's: Search's tabs start exactly
    // where the screen's tabs are and slide to their own place (the logo is wider than the round
    // button), and the logo fades in where the button was. Over Home (same logo, same place) or
    // over a page with no tab row (Artist, a playlist, a grid) Search's row just fades in with the
    // screen. Closing plays the same backwards.

    /** The tab row Search grows over (null over the Artist page, a playlist or a grid). */
    private var searchBehind: TopRow? = null
    /** How far Search's tabs sit from the row behind: behind's tabs start minus Search's. */
    private var searchRowDx = 0f
    private var searchLeave: ValueAnimator? = null

    private fun prepareSearchRow() {
        val behind = if (isListOpen || isShelfOpen || isArtistOpen || isImportReviewOpen) null else rowOf(tab)
        if (searchBehind != null && searchBehind !== behind) restoreSearchBehind()
        searchBehind = behind
        searchRowDx = if (behind == null) 0f else tabsStart(behind) - tabsStart(searchRow)
        if (kotlin.math.abs(searchRowDx) < 0.5f) searchRowDx = 0f
    }

    /** [t]: 0 = Search still the pill it grows from, 1 = fully open. */
    private fun applySearchRow(t: Float) {
        val behind = searchBehind
        val movers = rowMovers(searchRow)
        if (behind == null || searchRowDx == 0f) {
            // Same row behind (Home), or no row: Search's row fades in with the screen
            searchRow.root.alpha = SearchMotion.backdrop(t)
            for (v in movers) v.translationX = 0f
            searchRow.logo.alpha = 1f
            return
        }
        searchRow.root.alpha = 1f
        // The row behind hands its tabs over: Search's are drawn on top of them, so they hide
        for (v in rowMovers(behind)) v.alpha = 0f
        val k = SearchMotion.backdrop(t)
        for (v in movers) v.translationX = searchRowDx * (1f - k)
        searchRow.logo.alpha = SearchMotion.logo(t)
    }

    /** Search is gone (or the row behind changed): the screen's own tabs show again. */
    private fun restoreSearchBehind() {
        searchBehind?.let { b -> for (v in rowMovers(b)) v.alpha = 1f }
        searchBehind = null
        searchRowDx = 0f
    }

    /** The tab you're on stays underlined in Search's row. */
    private fun markSearchRowTab() {
        for (t in Tab.values()) {
            val line = lineOf(searchRow, t)
            line.animate().cancel()
            line.translationX = 0f
            line.scaleX = 1f
            line.visibility = if (t == tab) View.VISIBLE else View.INVISIBLE
        }
    }

    /** Search shown again without the morph (Back from a grid or the Artist page): fully open. */
    private fun settleSearchRow() {
        searchBehind = null
        searchRowDx = 0f
        searchRow.root.alpha = 1f
        for (v in rowMovers(searchRow)) { v.animate().cancel(); v.translationX = 0f; v.alpha = 1f }
        searchRow.logo.alpha = 1f
        markSearchRowTab()
    }

    /**
     * 5.8.5 u57: a tab tapped in Search's row. The tab you're on just closes Search (back into
     * the pill it came from). Another tab: the keyboard goes, anything under Search (the Artist
     * page, a playlist, a grid) closes, that tab comes up under Search and Search clears away
     * from it: the underline glides to the tab, the tabs slide to where that tab's row has them,
     * the box and results fade off to the side and the tab's content slides in, as a tab switch.
     */
    private fun leaveSearchFor(v: View, target: Tab) {
        if (!isSearchOpen || searchLeave != null || searchMorph.closing) return
        if (target == tab) {
            closeSearch(animate = true)
            return
        }
        haptic(v, HapticFeedbackConstants.CLOCK_TICK)
        hideKeyboard()
        searchInput.clearFocus()
        searchMorph.finish()
        restoreSearchBehind()
        settleSearchRow()
        // What was under Search goes for good: the tab itself comes up
        if (isListOpen) closeList()
        if (isShelfOpen) { shelfFromSearch = false; closeShelf(backToSearch = false) }
        if (isArtistOpen) {
            artistReturn = null
            closeArtist()
        }
        val from = tab
        showTab(target, animate = false)
        if (!animationsOn() || !root.isShown) {
            screenSearch.visibility = View.GONE
            settleSearchRow()
            updateChrome()
            return
        }
        animateSearchToTab(from, target)
    }

    private fun animateSearchToTab(from: Tab, to: Tab) {
        val toRow = rowOf(to)
        val toScreen = screenOf(to) as ViewGroup
        val dx = tabsStart(toRow) - tabsStart(searchRow)
        val moving = kotlin.math.abs(dx) >= 0.5f
        val shift = dp(TabMotion.SHIFT_DP)
        val bar = findViewById<View>(R.id.search_bar)
        val body = findViewById<View>(R.id.search_body)
        val outEnd = TabMotion.outgoingEnd(from.ordinal, to.ordinal, shift)
        val movers = rowMovers(searchRow)

        // The underline: Search's row glides it when the tabs travel; otherwise (the same logo
        // row, Home or tapes) the tab's own row glides it under Search's fading row
        fun centre(l: View): Float = (l.parent as View).left + l.left + l.width / 2f
        val oldLine = lineOf(searchRow, from)
        val newLineInSearch = lineOf(searchRow, to)
        val (lx, ls) = TabMotion.lineStart(centre(oldLine), oldLine.width.toFloat(), centre(newLineInSearch), newLineInSearch.width.toFloat())
        val glider = if (moving) newLineInSearch else lineOf(toRow, to)
        if (moving) {
            oldLine.visibility = View.INVISIBLE
            newLineInSearch.visibility = View.VISIBLE
            // The tab's own row hides under Search's while Search's tabs travel onto it
            for (m in rowMovers(toRow)) m.alpha = 0f
        } else {
            for (t in Tab.values()) lineOf(searchRow, t).visibility = View.INVISIBLE
        }
        if (oldLine.width > 0 && newLineInSearch.width > 0) {
            glider.animate().cancel()
            glider.translationX = lx
            glider.scaleX = ls
            glider.animate().translationX(0f).scaleX(1f).setStartDelay(0L).setDuration(TabMotion.LINE_MS)
                .setInterpolator(DecelerateInterpolator(1.5f)).start()
        }
        // The round search button of the tab's row grows in where Search's logo fades
        val toLeft = if (toRow.logo.visibility == View.VISIBLE) toRow.logo else toRow.search
        if (moving) {
            toLeft.animate().cancel()
            toLeft.alpha = 0f
            toLeft.scaleX = 0.85f; toLeft.scaleY = 0.85f
            toLeft.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60L).setDuration(TabMotion.IN_MS)
                .setInterpolator(DecelerateInterpolator(1.5f)).start()
        }
        // The tab's content slides in from the side you're moving towards, as a tab switch
        val incoming = slidingChildren(toScreen, to)
        for (c in incoming) {
            c.animate().cancel()
            c.translationX = TabMotion.incomingStart(from.ordinal, to.ordinal, shift)
            c.alpha = 0f
        }
        val ease = DecelerateInterpolator(1.5f)
        val bg = screenSearch.background
        val total = TabMotion.LINE_MS
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                if (searchLeave !== it) return@addUpdateListener
                val f = it.animatedFraction
                val k = ease.getInterpolation(f)
                // The box and the results fade off to the other side, quicker than the rest
                val o = ease.getInterpolation((f * total / TabMotion.OUT_MS).coerceIn(0f, 1f))
                bar.alpha = 1f - o; body.alpha = 1f - o
                bar.translationX = outEnd * o; body.translationX = outEnd * o
                // Search's background clears, uncovering the tab
                bg?.alpha = (255 * (1f - SearchMotion.window(f, 0f, 0.8f))).toInt()
                if (moving) {
                    for (m in movers) m.translationX = dx * k
                    searchRow.logo.alpha = 1f - SearchMotion.window(f, 0f, 0.6f)
                } else {
                    searchRow.root.alpha = 1f - SearchMotion.window(f, 0f, 0.8f)
                }
                val i = ease.getInterpolation((f * total / TabMotion.IN_MS).coerceIn(0f, 1f))
                for (c in incoming) {
                    c.alpha = i
                    c.translationX = TabMotion.incomingStart(from.ordinal, to.ordinal, shift) * (1f - i)
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (searchLeave !== animation) return
                    searchLeave = null
                    finishSearchLeave(toRow, incoming, bar, body)
                }
            })
        }
        searchLeave = anim
        anim.start()
    }

    /** Ends a Search-to-tab clear-away at once (another tap, Back, Search opening again). */
    private fun endSearchLeave() {
        val a = searchLeave ?: return
        a.end()
    }

    private fun finishSearchLeave(toRow: TopRow, incoming: List<View>, bar: View, body: View) {
        screenSearch.visibility = View.GONE
        for (v in listOf(bar, body)) { v.alpha = 1f; v.translationX = 0f }
        screenSearch.background?.alpha = 255
        for (m in rowMovers(toRow)) m.alpha = 1f
        for (c in incoming) { c.alpha = 1f; c.translationX = 0f }
        settleSearchRow()
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
                    is GridTile.OfflineTile -> openList(OFFLINE_LIST, getString(R.string.offline_title))
                    is GridTile.PlaylistTile -> openList(tile.playlist.id, tile.playlist.name, tile.playlist.source)
                    is GridTile.Song, is GridTile.AlbumTile -> Unit
                }
            },
            onLongClick = { _, tile ->
                // 5.4.0: a small menu (Save offline / Remove from offline, Delete) instead of straight to delete
                when (tile) {
                    is GridTile.PlaylistTile -> showPlaylistOptions(tile.playlist)
                    is GridTile.Liked -> showLikedOptions()
                    else -> Unit
                }
            }
        )
        findViewById<RecyclerView>(R.id.library_grid).apply {
            layoutManager = GridLayoutManager(this@MainActivity, GRID_COLUMNS)
            adapter = libraryAdapter
            // 5.8.5 u57: the filter's tiles fade and glide, quickly
            (itemAnimator as? androidx.recyclerview.widget.DefaultItemAnimator)?.apply {
                supportsChangeAnimations = false
                addDuration = 180L; removeDuration = 140L; moveDuration = 220L
            }
        }
        setupLibraryPills()

        listAdapter = TrackAdapter(
            artwork = app.artworkPipeline,
            onClick = { track, list ->
                // v4.9: Recents (now in Library) keeps its old tap: a radio of similar songs
                // 5.9.0: Liked, Recents and Offline are your library; anything else here is a playlist
                // (or an album, which opens in the playlist screen)
                val src = if (openListId == RECENTS_LIST || openListId == LIKED_LIST || openListId == OFFLINE_LIST)
                    com.minim.music.data.fermata.FermataSource.LIBRARY else com.minim.music.data.fermata.FermataSource.PLAYLIST
                if (openListId == RECENTS_LIST) app.playbackController.playRadio(track, list, src)
                else app.playbackController.playTrack(track, list, src)
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
        // 5.7.0 u48: Liked > Artists swaps this in; tapping a row opens the Artist page
        likedArtistAdapter = com.minim.music.ui.adapter.LikedArtistAdapter(app.artworkPipeline) { a ->
            openArtist(a.toRef(), fromSearch = false)
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
            onSongClick = { track, shelf -> app.playbackController.playRadio(track, shelf.tracks, shelfSource(shelf)) },
            onSongLongClick = { anchor, track -> showTrackOptions(anchor, track, fromDiscover = true) },
            onArtistClick = { name -> openArtistByName(name) },
            artistPhoto = { name -> app.preferences.artistPhoto(name) },
            onArtistPhotoNeeded = { name -> findShelfArtistPhoto(name) }
        )
        findViewById<RecyclerView>(R.id.discover_shelves).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = shelfAdapter
            itemAnimator = null
            discoverEntrance = RowEntrance(this)
            // 5.7.0: on the Search page, scrolling the shelves puts the keyboard away
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING && searchInput.hasFocus()) {
                        hideKeyboard()
                        searchInput.clearFocus()
                    }
                }
            })
        }
        discoverRefresh.setOnRefreshListener {
            loadDiscover(force = true)
            loadSearchExtras()
        }
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
                if (tile is GridTile.Song && shelf != null) app.playbackController.playRadio(tile.track, shelf.tracks, shelfSource(shelf))
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
            app.playbackController.playTrack(shelf.tracks.first(), shelf.tracks, shelfSource(shelf))
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
        discoverShelves = s.shelves
        renderSearchShelves()
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
        openShelf?.takeIf { !it.key.startsWith(ARTIST_SHELF) && it.key != GEMINI_SHELF }?.let { open ->
            val updated = s.shelves.firstOrNull { it.key == open.key }
            if (updated == null) closeShelf() else if (updated != open) fillShelfPage(updated)
        }
    }

    // ---- 5.7.0 Search page shelves ---------------------------------------------------------------

    /** The user's top artists (round photos at the top of the Search page). */
    private var searchTopArtists: List<String> = emptyList()
    /** Today's Gemini shelf, with the title Gemini wrote for it. */
    private var geminiShelf: com.minim.music.data.discover.Shelf? = null
    private var searchExtrasJob: kotlinx.coroutines.Job? = null
    /** The shelf keys on screen, so a new set rises in and a changed one just updates. */
    private var shownShelfKeys: List<String> = emptyList()

    /**
     * What the Search page shows while the box is empty: your top artists, the Gemini shelf,
     * then Discover's shelves. The two extras only show alongside Discover's shelves, so the
     * "play a few songs" and error messages never sit over them.
     */
    private fun renderSearchShelves() {
        val hidden = app.database.notInterestedIds.value
        val list = buildSearchShelves(
            discoverShelves, searchTopArtists,
            geminiShelf?.let { g -> g.copy(tracks = g.tracks.filter { it.id !in hidden }) },
            getString(R.string.shelf_top_artists)
        )
        val keys = list.map { it.key }
        val newSet = keys != shownShelfKeys
        shownShelfKeys = keys
        shelfAdapter.submit(list)
        shelfAdapter.setActiveTrack(app.playbackController.uiState.value.currentTrack?.id)
        // v4.9.9: a new set of shelves rises in; the same shelves with a song less ("not
        // interested") or fresher songs just update in place
        if (newSet && list.isNotEmpty() && isSearchOpen && !isSearchTyping) discoverEntrance.play()
    }

    /** Works out the top artists and today's Gemini shelf, then redraws the shelves. */
    private fun loadSearchExtras() {
        if (searchExtrasJob?.isActive == true) return
        searchExtrasJob = lifecycleScope.launch {
            val engine = app.discoverEngine
            val artists = try { engine.topArtists() } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { searchTopArtists }
            if (artists != searchTopArtists) {
                searchTopArtists = artists
                renderSearchShelves()
            }
            val maker = app.geminiShelfMaker
            val settings = app.geminiSettings
            val ctx = applicationContext
            val keyHash = withContext(Dispatchers.IO) {
                com.minim.music.data.ai.GeminiShelfMaker.keyHash(settings.apiKey().orEmpty(), settings.model)
            }
            val today = java.time.LocalDate.now().toEpochDay()
            // Yesterday's shelf stays up while today's is made
            if (geminiShelf == null) maker.saved()?.let { showGeminiShelf(it) }
            if (!maker.isStale(maker.saved(), today, keyHash)) return@launch
            if (!settings.hasApiKey && !com.minim.music.data.ai.GeminiRoute.hasBuiltIn(ctx)) return@launch
            val made = maker.today(
                today = today,
                keyHash = keyHash,
                taste = {
                    withContext(Dispatchers.IO) {
                        val stats = app.database.getAllTrackStats()
                        com.minim.music.data.ai.GeminiShelfPrompt.taste(
                            stats, engine.topArtists(8), app.database.notInterestedIds.value
                        )
                    }
                },
                endpoint = { com.minim.music.data.ai.GeminiRoute.endpoint(ctx, settings) },
                find = { pick -> findGeminiPick(pick) },
                skip = { t -> t.id in app.database.notInterestedIds.value }
            )
            if (made != null) showGeminiShelf(made)
        }
    }

    private fun showGeminiShelf(d: com.minim.music.data.ai.GeminiShelfDay) {
        val shelf = com.minim.music.data.discover.Shelf(GEMINI_SHELF, d.title, d.tracks)
        if (shelf == geminiShelf) return
        geminiShelf = shelf
        renderSearchShelves()
        // An open Gemini shelf page follows the new shelf
        if (openShelf?.key == GEMINI_SHELF) fillShelfPage(shelf)
    }

    /** One Gemini pick -> the YouTube Music song, only when the import's matcher is sure of it. */
    private suspend fun findGeminiPick(pick: com.minim.music.data.ai.GeminiPick): Track? {
        val query = if (pick.artist.isBlank()) pick.title else "${pick.title} ${pick.artist}"
        val found = app.sourceAdapter.searchForImport(query, videos = false) ?: return null
        val song = com.minim.music.data.importer.SourceSong(
            pick.title, if (pick.artist.isBlank()) emptyList() else listOf(pick.artist)
        )
        val m = com.minim.music.data.importer.SongMatcher.match(song, found)
        return if (m.confidence == com.minim.music.data.importer.Confidence.SURE) m.best?.track else null
    }

    /** A top artist without a saved photo: found like the Artist page finds it, then saved. */
    private fun findShelfArtistPhoto(name: String) {
        if (!artistPhotoTried.add(name.lowercase())) return
        lifecycleScope.launch {
            val found = try { app.sourceAdapter.findArtist(name) } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { null }
            val ref = (found as? SourceResult.Success)?.data ?: return@launch
            val url = MusicSourceAdapter.upscaleYtImage(ref.thumbnailUrl, 600)
            if (url.isBlank()) return@launch
            app.preferences.setArtistPhoto(name, url)
            if (name in searchTopArtists) renderSearchShelves()
        }
    }

    /** True while the Search box has text: results show and the shelves are hidden. */
    private var isSearchTyping = false
    private lateinit var searchDiscover: View
    private lateinit var searchResultsList: View

    /** Typing hides the shelves and shows results; an empty box brings the shelves back. */
    private fun setSearchTyping(typing: Boolean) {
        if (typing == isSearchTyping) return
        isSearchTyping = typing
        searchDiscover.visibility = if (typing) View.GONE else View.VISIBLE
        searchResultsList.visibility = if (typing) View.VISIBLE else View.GONE
        if (!typing) {
            searchStatus.visibility = View.GONE
            if (animationsOn() && isSearchOpen) {
                searchDiscover.alpha = 0f
                searchDiscover.animate().alpha(1f).setDuration(180L).start()
            }
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

    /**
     * 5.7.0: a shelf opened from the Search page closes Search (the shelf page sits under it,
     * like the Artist page) and Back brings Search back with the shelves where they were.
     */
    private var shelfFromSearch = false

    private fun openShelfPage(shelf: com.minim.music.data.discover.Shelf, fromSearch: Boolean = isSearchOpen) {
        if (isSearchOpen) closeSearch()
        shelfFromSearch = fromSearch
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

    private fun closeShelf(backToSearch: Boolean = true) {
        openShelf = null
        val toSearch = shelfFromSearch && backToSearch
        shelfFromSearch = false
        screenShelf.animate().cancel()
        screenShelf.alpha = 1f
        screenShelf.translationX = 0f
        screenShelf.visibility = View.GONE
        if (toSearch) {
            screenSearch.visibility = View.VISIBLE
            settleSearchRow()
        }
        updateChrome()
    }

    // ---- v4.6 Artist page --------------------------------------------------------------------

    /** Where the Artist page was opened from, put back when it closes. */
    private data class ArtistReturn(
        val search: Boolean,
        val listId: Long?,
        val listName: String,
        val shelf: com.minim.music.data.discover.Shelf?,
        val listSource: String? = null,
        /** u48: Back to Liked > Artists lands on Artists again, not Songs. */
        val listKind: com.minim.music.data.stories.LibraryKind = com.minim.music.data.stories.LibraryKind.SONGS
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
    private lateinit var artistLike: ImageView
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
        artistLike = findViewById(R.id.artist_like)
        // 5.7.0 u48/u50: the Artist page heart gets the same ink, spring and burst as the others
        if (::heartFx.isInitialized) heartFx.attach(artistLike)
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
            openSearch(it)
        }
        artistLike.setOnClickListener { tapArtistHeart(it as ImageView) }
        artistShuffle.setOnClickListener {
            if (channelMode != null) {
                haptic(it)
                toggleFollowChannel()
                return@setOnClickListener
            }
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
            val current = app.playbackController.uiState.value.currentTrack ?: return@setOnClickListener
            // 5.7.0 u47: a tape's channel name opens its Channel page
            if (com.minim.music.data.stories.StoryTracks.isStory(current)) {
                val ch = com.minim.music.data.stories.ChannelVideos.channelFor(current, app.storiesStore.channels)
                    ?: return@setOnClickListener
                haptic(it)
                collapseNowPlaying()
                openChannelPage(ch)
                return@setOnClickListener
            }
            val line = current.artist
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
        leaveChannelMode()
        showArtistScreen(fromSearch)
        if (artistPage?.url == ref.url && !artistHasError) {
            updateArtistHeart(animate = false)
            return
        }
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
        if (isGeminiOpen) geminiChat.close()
        leaveChannelMode()
        val name = MusicSourceAdapter.firstArtistName(line)
        showArtistScreen(fromSearch = false)
        val shown = artistPage
        if (shown != null && !artistHasError &&
            MusicSourceAdapter.normName(shown.name) == MusicSourceAdapter.normName(name)
        ) {
            updateArtistHeart(animate = false)
            return
        }
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
        if (channelMode != null) return
        // v4.9.9: the shelves' songs and albums rise in when the page loads, not on a redraw
        val previous = artistPage
        val newSongs = previous == null || previous.songs != page.songs
        val newAlbums = previous == null || previous.albums != page.albums
        artistPage = page
        showArtistHeader(page.name, formatArtistCount(resources, page.count, page.countKind), page.photoUrl)
        // u48: a liked artist keeps the freshest photo and count for Liked > Artists
        likeRefForArtist()?.let { app.likedArtists.refresh(it) }
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
        artistShuffle.visibility = if (channelMode != null) View.VISIBLE else View.GONE
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
        artistShuffle.contentDescription = if (channelMode != null) artistShuffle.text else getString(R.string.artist_shuffle_desc)
        updateArtistHeart(animate = false)
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
        val themeLayerView = findViewById<View>(R.id.home_theme)
        barCovers = findViewById(R.id.home_bar_covers)
        barCoverTop = findViewById(R.id.home_bar_cover_top)
        barCoverBottom = findViewById(R.id.home_bar_cover_bottom)
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
            // 5.5.2 (39): the festival wallpaper fills the whole screen, behind both system bars;
            // Home's own content stays inside the root's padding as before
            navInset = bars.bottom
            (themeLayerView.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
                val m = ThemeBars.edgeToEdgeMargins(bars.left, bars.top, bars.right, bars.bottom)
                if (lp.leftMargin != m[0] || lp.topMargin != m[1] || lp.rightMargin != m[2] || lp.bottomMargin != m[3]) {
                    lp.setMargins(m[0], m[1], m[2], m[3])
                    themeLayerView.layoutParams = lp
                }
            }
            readThemeBarTones()
            // 5.5.2 (update 40): the bar strips sit edge to edge too, one per system bar
            (barCovers.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
                val m = ThemeBars.edgeToEdgeMargins(bars.left, bars.top, bars.right, bars.bottom)
                if (lp.leftMargin != m[0] || lp.topMargin != m[1] || lp.rightMargin != m[2] || lp.bottomMargin != m[3]) {
                    lp.setMargins(m[0], m[1], m[2], m[3])
                    barCovers.layoutParams = lp
                }
            }
            fun stripHeight(view: View, h: Int) {
                val lp = view.layoutParams
                if (lp.height != h) { lp.height = h; view.layoutParams = lp }
            }
            stripHeight(barCoverTop, bars.top)
            stripHeight(barCoverBottom, bars.bottom)
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
            if (::geminiChat.isInitialized) geminiChat.applyInsets(
                bars.top, bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom, bars.left, bars.right
            )
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
        // 5.5.2 (update 40): every frame, the bar strips follow whatever is opening or closing
        // over Home (the morphs move views each frame, so this runs right through them)
        root.viewTreeObserver.addOnPreDrawListener {
            syncBarCovers()
            true
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
        // 5.5.2: over the festival wallpaper the bars follow it, and the nav bar is see-through
        val wallOnTop = themeWallpaperOnTop()
        val barsPlan = ThemeBars.plan(
            wallOnTop, themeStatusLight, themeNavLight, lightStatus, lightNav, baseNavContrast
        )
        lightStatus = barsPlan.lightStatus
        lightNav = barsPlan.lightNav
        if (wallOnTop != themeNavClear && !isTakeoverActive) {
            themeNavClear = wallOnTop
            window.navigationBarColor = if (barsPlan.navTransparent) android.graphics.Color.TRANSPARENT else pal.bg
        }
        // 5.5.2 (39): no grey scrim over the wallpaper behind 3-button nav; back to normal elsewhere
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && barsPlan.navContrastEnforced != themeNavContrast) {
            themeNavContrast = barsPlan.navContrastEnforced
            window.isNavigationBarContrastEnforced = barsPlan.navContrastEnforced
        }
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
            pill(artistLike, lightTone)
            artistLike.imageTintList = android.content.res.ColorStateList.valueOf(darkTone)
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
            pill(artistLike, p.bg)
            artistLike.imageTintList = android.content.res.ColorStateList.valueOf(p.fg)
        }
    }

    // ---- 5.7.0 u48: like an artist -------------------------------------------------------------------

    /**
     * The artist on the page as it should be saved: the link it was opened with (so the key stays
     * the same), with the loaded page's name, photo and count when they're in. Null on a Channel
     * page or while the artist is still being looked up.
     */
    private fun likeRefForArtist(): ArtistRef? {
        if (channelMode != null) return null
        val ref = artistRef ?: return null
        val page = artistPage ?: return ref
        return ArtistRef(
            url = ref.url,
            name = page.name.ifBlank { ref.name },
            count = if (page.count >= 0) page.count else ref.count,
            thumbnailUrl = page.photoUrl.ifBlank { ref.thumbnailUrl },
            countKind = if (page.count >= 0) page.countKind else ref.countKind
        )
    }

    /** Shows the heart (filled when liked) once the artist is known; hidden on Channel pages. */
    private fun updateArtistHeart(animate: Boolean) {
        if (!::artistLike.isInitialized) return
        val ref = likeRefForArtist()
        if (ref == null) {
            artistLike.visibility = View.GONE
            return
        }
        val liked = app.likedArtists.isLiked(ref)
        artistLike.visibility = View.VISIBLE
        artistLike.contentDescription = getString(if (liked) R.string.artist_unlike_cd else R.string.artist_like_cd, ref.name)
        if (::heartFx.isInitialized) heartFx.setLiked(artistLike, liked, animate)
    }

    private fun tapArtistHeart(view: ImageView) {
        val ref = likeRefForArtist() ?: return
        val liked = app.likedArtists.toggle(ref)
        heartFx.play(view, liked, heartBurstColor())
        heartHaptic(view, liked)
        view.contentDescription = getString(if (liked) R.string.artist_unlike_cd else R.string.artist_like_cd, ref.name)
        view.announceForAccessibility(getString(if (liked) R.string.artist_liked_note else R.string.artist_unliked_note, ref.name))
    }

    private fun showArtistScreen(fromSearch: Boolean) {
        if (!isArtistOpen) {
            artistReturn = ArtistReturn(
                search = fromSearch || isSearchOpen || (isShelfOpen && shelfFromSearch),
                listId = if (isListOpen) openListId else null,
                listName = openListName,
                listSource = openListSource,
                listKind = listKind,
                shelf = if (isShelfOpen) openShelf?.takeIf { !it.key.startsWith(ARTIST_SHELF) } else null
            )
        }
        // The Artist page sits under Search, the song list and the shelf grid: close them
        if (isSearchOpen) closeSearch()
        if (isListOpen) closeList()
        if (isShelfOpen) closeShelf(backToSearch = false)
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
        if (channelFailed) channelMode = null
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
            back.shelf?.let { openShelfPage(it, fromSearch = back.search) }
            back.listId?.let { openList(it, back.listName, back.listSource, back.listKind) }
            if (back.search && back.shelf == null) {
                screenSearch.visibility = View.VISIBLE
                settleSearchRow()
            }
        }
        updateChrome()
    }

    // ---- 5.7.0 u47: Channel pages, on the Artist page's screen ------------------------------------

    /** The channel shown on the Artist screen, or null while it shows an artist. */
    private var channelMode: com.minim.music.data.stories.StoryChannel? = null
    private var channelData: com.minim.music.data.source.MusicSourceAdapter.ChannelPageData? = null
    private var channelSort = com.minim.music.data.stories.ChannelSort.LATEST
    private var channelPopular: List<com.minim.music.data.stories.StoryUpload>? = null
    private var channelLoadingMore = false
    private var channelFailed = false
    private var channelJob: kotlinx.coroutines.Job? = null
    private lateinit var channelVideos: com.minim.music.ui.adapter.ChannelVideosAdapter
    private var channelSetUp = false

    private fun setupChannelPage() {
        if (channelSetUp) return
        channelSetUp = true
        channelVideos = com.minim.music.ui.adapter.ChannelVideosAdapter(
            app.artworkPipeline,
            onUpload = { track, all -> storiesScreen.play(track, all) },
            onUploadLong = { anchor, track -> showTrackOptions(anchor, track) },
            meta = { u -> storiesScreen.pageMeta(u) }
        )
        val list = findViewById<RecyclerView>(R.id.channel_videos)
        list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        list.adapter = channelVideos
        list.itemAnimator = null
        findViewById<TextView>(R.id.channel_sort_latest).setOnClickListener { setChannelSort(it, com.minim.music.data.stories.ChannelSort.LATEST) }
        findViewById<TextView>(R.id.channel_sort_popular).setOnClickListener { setChannelSort(it, com.minim.music.data.stories.ChannelSort.POPULAR) }
        findViewById<TextView>(R.id.channel_note).setOnClickListener {
            val c = channelMode ?: return@setOnClickListener
            if (!channelFailed) return@setOnClickListener
            haptic(it)
            loadChannel(c)
        }
    }

    /** Opens a channel's page: from its photo on tapes, from Now Playing, or from a tape's menu. */
    private fun openChannelPage(c: com.minim.music.data.stories.StoryChannel) {
        setupChannelPage()
        if (isGeminiOpen) geminiChat.close()
        showArtistScreen(fromSearch = false)
        findViewById<View>(R.id.channel_block).visibility = View.VISIBLE
        hideArtistShelves()
        if (channelMode?.id == c.id && channelData != null && !channelFailed) {
            artistMessage.visibility = View.GONE
            renderChannel()
            return
        }
        artistJob?.cancel()
        artistRef = null
        artistPage = null
        artistLookupName = null
        artistHasError = false
        artistMessage.visibility = View.GONE
        channelMode = c
        channelData = null
        channelPopular = null
        channelSort = com.minim.music.data.stories.ChannelSort.LATEST
        artistScroll.scrollTo(0, 0)
        showArtistHeader(c.name, getString(R.string.channel_label), MusicSourceAdapter.upscaleYtImage(c.avatarUrl))
        channelVideos.submit(emptyList(), emptyMap())
        renderChannel()
        loadChannel(c)
    }

    /** An artist opens on the same screen: the channel steps aside. */
    private fun leaveChannelMode() {
        if (channelMode == null) return
        channelJob?.cancel()
        channelMode = null
        channelData = null
        channelPopular = null
        findViewById<View>(R.id.channel_block).visibility = View.GONE
        artistShuffle.text = getString(R.string.artist_shuffle)
        artistPhotoUrl = null
    }

    private fun loadChannel(c: com.minim.music.data.stories.StoryChannel) {
        channelJob?.cancel()
        channelFailed = false
        channelLoadingMore = false
        renderChannel()
        channelJob = lifecycleScope.launch {
            val data = app.sourceAdapter.fetchChannelPage(c)
            if (channelMode?.id != c.id) return@launch
            if (data == null) {
                channelFailed = true
                renderChannel()
                return@launch
            }
            channelData = data
            // the channel row keeps its photo current
            if (app.storiesStore.channels.any { it.id == data.channel.id } && data.channel.avatarUrl.isNotBlank()) {
                app.storiesStore.updateAvatar(data.channel.id, data.channel.avatarUrl)
            }
            showArtistHeader(
                data.channel.name,
                formatArtistCount(resources, data.subscriberCount, com.minim.music.data.model.ArtistCountKind.SUBSCRIBERS),
                MusicSourceAdapter.upscaleYtImage(data.channel.avatarUrl)
            )
            renderChannel()
            if (channelSort == com.minim.music.data.stories.ChannelSort.POPULAR) loadChannelPopular()
        }
    }

    private fun setChannelSort(v: View, sort: com.minim.music.data.stories.ChannelSort) {
        if (channelMode == null || channelSort == sort) return
        haptic(v)
        channelSort = sort
        renderChannel()
        if (sort == com.minim.music.data.stories.ChannelSort.POPULAR && channelPopular == null && channelData != null) loadChannelPopular()
    }

    /** Popular reads a few more pages of uploads and sorts them all by views. */
    private fun loadChannelPopular() {
        val data = channelData ?: return
        if (channelLoadingMore) return
        channelLoadingMore = true
        renderChannel()
        val id = data.channel.id
        lifecycleScope.launch {
            val more = app.sourceAdapter.fetchChannelMore(id, data.channel.name)
            channelLoadingMore = false
            if (channelMode == null || channelData?.channel?.id != id) return@launch
            channelPopular = (data.latest + more.orEmpty()).distinctBy { it.videoId }
            renderChannel()
        }
    }

    private fun renderChannel() {
        val c = channelMode ?: return
        val followed = com.minim.music.data.stories.ChannelVideos.isFollowed(channelData?.channel?.id ?: c.id, c.name, app.storiesStore.channels)
        artistShuffle.text = getString(if (followed) R.string.channel_following else R.string.channel_follow)
        artistShuffle.contentDescription = artistShuffle.text
        artistShuffle.visibility = View.VISIBLE
        artistLike.visibility = View.GONE
        ChipStyle.apply(findViewById(R.id.channel_sort_latest), channelSort == com.minim.music.data.stories.ChannelSort.LATEST, pal)
        ChipStyle.apply(findViewById(R.id.channel_sort_popular), channelSort == com.minim.music.data.stories.ChannelSort.POPULAR, pal)
        val data = channelData
        val list = when {
            data == null -> emptyList()
            channelSort == com.minim.music.data.stories.ChannelSort.POPULAR ->
                com.minim.music.data.stories.ChannelVideos.sort(channelPopular ?: if (channelLoadingMore) emptyList() else data.latest, channelSort)
            else -> com.minim.music.data.stories.ChannelVideos.sort(data.latest, channelSort)
        }
        val note = findViewById<TextView>(R.id.channel_note)
        val text = when {
            channelFailed -> getString(R.string.channel_error)
            data == null || (list.isEmpty() && channelLoadingMore) -> getString(R.string.channel_loading)
            list.isEmpty() -> getString(R.string.channel_nothing)
            else -> null
        }
        note.text = text
        note.visibility = if (text == null) View.GONE else View.VISIBLE
        channelVideos.setPalette(pal)
        channelVideos.setActive(app.playbackController.uiState.value.currentTrack?.id)
        channelVideos.submit(list, app.storyProgress.all())
        Painter.paint(findViewById(R.id.channel_block), pal)
    }

    /** Follow / Following: adds the channel to the tapes row, or takes it out. */
    private fun toggleFollowChannel() {
        val c = channelMode ?: return
        val ch = channelData?.channel ?: c
        val store = app.storiesStore
        if (com.minim.music.data.stories.ChannelVideos.isFollowed(ch.id, ch.name, store.channels)) {
            store.remove(store.channels.firstOrNull { it.id == ch.id }?.id ?: ch.id)
            showNote(getString(R.string.note_channel_unfollowed, ch.name))
        } else {
            store.add(ch)
            showNote(getString(R.string.note_channel_followed, ch.name))
        }
        renderChannel()
        if (::storiesScreen.isInitialized) storiesScreen.onChannelsChanged()
    }

    /** An album from the Artist page, in the playlist screen. */
    private fun openAlbum(album: Album) {
        haptic(screenList)
        openListId = null
        openAlbum = album
        cancelRename()
        styleListFilter()
        listTitle.text = album.title
        listSubtitle.text = album.subtitle
        updateListHeader()
        showArtistRows(false)
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
            // 5.7.0: the Gemini shelf drops it too
            renderSearchShelves()
            showNote(getString(R.string.not_interested_done))
        }
    }

    private fun openList(
        id: Long,
        name: String,
        source: String? = null,
        kind: com.minim.music.data.stories.LibraryKind? = null
    ) {
        openListSource = source
        openAlbum = null
        albumJob?.cancel()
        if (kind != null) listKind = kind
        else if (openListId != id) listKind = com.minim.music.data.stories.LibraryKind.SONGS
        openListId = id
        openListName = name
        cancelRename()
        listTitle.text = name
        listSubtitle.text = ""
        updateListHeader()
        showArtistRows(false)
        listAdapter.submitList(emptyList())
        listEmpty.visibility = View.GONE
        styleListFilter()
        listEntrance.expect()
        screenList.visibility = View.VISIBLE
        updateChrome()
        lifecycleScope.launch { refreshOpenList() }
    }

    // ==========================================================================================
    // 5.3.2: playlist header: tap the title to rename, "+ Add songs", "from [logo]"
    // ==========================================================================================

    /** A playlist you made or imported (not Liked Songs, Recents or an album). */
    private val isOwnPlaylistOpen get() = (openListId ?: -1L) >= 0L && openAlbum == null

    private fun setupListHeader() {
        setupListFilter()
        listTitleEdit = findViewById(R.id.list_title_edit)
        listAddSongs = findViewById(R.id.list_add_songs)
        listSubtitleRow = findViewById(R.id.list_subtitle_row)
        listSourceFrom = findViewById(R.id.list_source_from)
        listSourceLogo = findViewById(R.id.list_source_logo)
        listAddSongs.contentDescription = getString(R.string.add_songs_desc)
        listAddSongs.setOnClickListener { v ->
            if (!isOwnPlaylistOpen) return@setOnClickListener
            haptic(v)
            if (isRenaming) cancelRename()
            showAddSongsSheet()
        }
        listTitle.setOnClickListener { if (isOwnPlaylistOpen && !isRenaming) startRename() }
        listTitleEdit.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                finishRename()
                true
            } else false
        }
    }

    /** Shows the pill and the tap-to-rename only on your own playlists. */
    private fun updateListHeader() {
        val own = isOwnPlaylistOpen
        listAddSongs.visibility = if (own) View.VISIBLE else View.GONE
        listTitle.isClickable = own
        listTitle.isFocusable = own
        listTitle.contentDescription = if (own) getString(R.string.rename_desc, openListName) else null
        if (!own) {
            listSourceFrom.visibility = View.GONE
            listSourceLogo.visibility = View.GONE
            listSubtitleRow.contentDescription = null
            listSubtitleRow.isFocusable = false
            listSubtitle.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    /** "from [logo] · 25 songs" (Spotify, YouTube Music, YouTube), else "from a list · …" or just the count. */
    private fun showListSubtitle(origin: com.minim.music.data.importer.ImportOrigin?, count: String) {
        val logo = SourceLogos.wordmark(origin)
        if (logo != null) {
            // 5.4.1: the logo's letters as tall as the capitals of "from" (same for all three logos)
            SourceLogos.show(listSourceLogo, logo, listSourceFrom)
            listSourceFrom.visibility = View.VISIBLE
            listSourceLogo.visibility = View.VISIBLE
            listSubtitle.text = "· $count"
            val name = sourceName(origin!!)
            listSourceLogo.contentDescription = name
            // TalkBack reads the line as one: "from Spotify, 25 songs"
            listSubtitleRow.contentDescription = getString(R.string.list_from_desc, name, count)
            listSubtitleRow.isFocusable = true
            listSubtitle.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } else {
            listSourceFrom.visibility = View.GONE
            listSourceLogo.visibility = View.GONE
            listSubtitle.text = listOfNotNull(origin?.label, count).joinToString(" · ")
            listSubtitleRow.contentDescription = null
            listSubtitleRow.isFocusable = false
            listSubtitle.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        // Tinted with the app colour right away (and on every colour change after)
        Painter.paint(listSubtitleRow, pal)
    }

    private fun sourceName(o: com.minim.music.data.importer.ImportOrigin): String = when (o) {
        com.minim.music.data.importer.ImportOrigin.SPOTIFY -> "Spotify"
        com.minim.music.data.importer.ImportOrigin.YOUTUBE_MUSIC -> "YouTube Music"
        com.minim.music.data.importer.ImportOrigin.YOUTUBE -> "YouTube"
        com.minim.music.data.importer.ImportOrigin.TEXT -> getString(R.string.import_source_list)
    }

    private fun startRename() {
        if (!isOwnPlaylistOpen) return
        haptic(listTitle)
        isRenaming = true
        listTitleEdit.setText(openListName)
        listTitleEdit.visibility = View.VISIBLE
        listTitle.visibility = View.INVISIBLE
        Painter.paint(listTitleEdit, pal)
        listTitleEdit.requestFocus()
        listTitleEdit.selectAll()
        showKeyboard(listTitleEdit)
    }

    /** Back, a blank name, or leaving the playlist: the old name stays. */
    private fun cancelRename() {
        if (!isRenaming) return
        isRenaming = false
        if (currentFocus === listTitleEdit) hideKeyboard()
        listTitleEdit.clearFocus()
        listTitleEdit.visibility = View.GONE
        listTitle.visibility = View.VISIBLE
    }

    /** Done: saves the new name. A name another playlist has keeps the box open and says so. */
    private fun finishRename() {
        if (!isRenaming) return
        val id = openListId?.takeIf { it >= 0L } ?: run { cancelRename(); return }
        val name = listTitleEdit.text?.toString()?.trim().orEmpty()
        if (name.isEmpty() || name == openListName) { cancelRename(); return }
        lifecycleScope.launch {
            when (app.database.renamePlaylist(id, name)) {
                com.minim.music.data.local.LibraryDatabase.RenameResult.RENAMED -> {
                    if (openListId != id) return@launch
                    haptic(listTitle)
                    openListName = name
                    listTitle.text = name
                    cancelRename()
                    updateListHeader()
                    refreshData()
                    showNote(getString(R.string.rename_done, name))
                }
                com.minim.music.data.local.LibraryDatabase.RenameResult.TAKEN ->
                    showNote(getString(R.string.rename_taken, name))
                else -> cancelRename()
            }
        }
    }

    /**
     * "+ Add songs": search and tap + to add to this playlist. The sheet stays open so you can add
     * several in a row, and the playlist behind it updates as you go.
     */
    private fun showAddSongsSheet() {
        val playlistId = openListId?.takeIf { it >= 0L } ?: return
        val playlistName = openListName
        lifecycleScope.launch {
            val inPlaylist = HashSet<String>()
            app.database.getPlaylistTracks(playlistId).mapTo(inPlaylist) { it.id }
            if (openListId != playlistId) return@launch
            var searchJob: Job? = null
            var generation = 0
            openSheet { box ->
                sheetHeading(box, getString(R.string.add_songs_title, playlistName))
                val v = layoutInflater.inflate(R.layout.sheet_add_songs, box, false)
                val input = v.findViewById<EditText>(R.id.add_songs_input)
                val status = v.findViewById<TextView>(R.id.add_songs_status)
                val results = v.findViewById<android.widget.LinearLayout>(R.id.add_songs_results)
                box.addView(v)

                fun setStatus(text: String?) {
                    if (text == null) status.visibility = View.GONE
                    else { status.text = text; status.visibility = View.VISIBLE }
                }

                fun label(text: String) {
                    val t = TextView(this@MainActivity).apply {
                        this.text = text
                        textSize = 13f
                        alpha = 0.7f
                        tag = "text:fg"
                        typeface = listSubtitle.typeface
                        setPadding(0, dp(14f).toInt(), 0, dp(8f).toInt())
                        isFocusable = false
                    }
                    results.addView(t)
                    Painter.paint(t, pal)
                }

                fun row(t: Track) {
                    val row = layoutInflater.inflate(R.layout.item_import_row, results, false)
                    row.setPadding(0, 0, 0, row.paddingBottom)
                    val card = row.findViewById<View>(R.id.track_card).apply { clipToOutline = true }
                    val art = row.findViewById<ImageView>(R.id.track_art).apply { clipToOutline = true }
                    row.findViewById<TextView>(R.id.track_title).text = t.title
                    row.findViewById<TextView>(R.id.track_artist).text = t.artist
                    val mark = row.findViewById<ImageView>(R.id.import_mark)
                    fun showMark() {
                        val added = t.id in inPlaylist
                        mark.setImageResource(if (added) R.drawable.ic_check else R.drawable.ic_add)
                        mark.contentDescription = if (added) getString(R.string.add_songs_added, t.title)
                        else getString(R.string.add_songs_add, t.title)
                        card.contentDescription = listOf(t.title, t.artist).filter { it.isNotBlank() }
                            .joinToString(", ") + ". " + mark.contentDescription
                    }
                    showMark()
                    val add = View.OnClickListener {
                        if (sheetClosing || t.id in inPlaylist) return@OnClickListener
                        inPlaylist.add(t.id)
                        haptic(mark, HapticFeedbackConstants.KEYBOARD_TAP)
                        showMark()
                        if (animationsOn()) {
                            mark.scaleX = 0.6f; mark.scaleY = 0.6f
                            mark.animate().scaleX(1f).scaleY(1f).setDuration(180L)
                                .setInterpolator(DecelerateInterpolator()).start()
                        }
                        lifecycleScope.launch {
                            app.database.addTrackToPlaylist(playlistId, t)
                            if (openListId == playlistId) refreshOpenList()
                            refreshData()
                        }
                    }
                    mark.setOnClickListener(add)
                    card.setOnClickListener(add)
                    app.artworkPipeline.loadInto(art, t.thumbnailUrl, dp(44f).toInt())
                    results.addView(row)
                    Painter.paint(row, pal)
                    PressScale.applyTree(row)
                }

                fun showStart() {
                    val gen = ++generation
                    searchJob?.cancel()
                    results.removeAllViews()
                    setStatus(null)
                    searchJob = lifecycleScope.launch {
                        val recent = app.database.getRecentTracks(12)
                        val liked = app.database.getLikedSongs().take(30)
                        if (gen != generation) return@launch
                        if (recent.isEmpty() && liked.isEmpty()) {
                            setStatus(getString(R.string.add_songs_nothing_yet))
                            return@launch
                        }
                        if (recent.isNotEmpty()) {
                            label(getString(R.string.add_songs_recent))
                            recent.forEach { row(it) }
                        }
                        if (liked.isNotEmpty()) {
                            label(getString(R.string.add_songs_liked))
                            liked.forEach { row(it) }
                        }
                    }
                }

                fun search(query: String, delayMs: Long) {
                    val q = query.trim()
                    if (q.isEmpty()) { showStart(); return }
                    val gen = ++generation
                    searchJob?.cancel()
                    searchJob = lifecycleScope.launch {
                        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                        if (gen != generation) return@launch
                        if (results.childCount == 0) setStatus(getString(R.string.add_songs_searching))
                        val r = app.sourceAdapter.searchTracks(q)
                        if (gen != generation) return@launch
                        results.removeAllViews()
                        when (r) {
                            is SourceResult.Success -> {
                                setStatus(if (r.data.isEmpty()) getString(R.string.add_songs_none) else null)
                                r.data.forEach { row(it) }
                            }
                            is SourceResult.Error -> setStatus(getString(R.string.add_songs_error))
                        }
                    }
                }

                input.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        search(s?.toString().orEmpty(), 450L)
                    }
                })
                input.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        search(input.text?.toString().orEmpty(), 0L)
                        hideKeyboard()
                        true
                    } else false
                }
                showStart()
            }
        }
    }

    private fun closeList() {
        cancelRename()
        showArtistRows(false)
        openListId = null
        openAlbum = null
        albumJob?.cancel()
        screenList.visibility = View.GONE
        updateChrome()
    }

    // ---- 5.7.0 u47: Liked and Offline show Songs or Tapes ------------------------------------------

    private var listKind = com.minim.music.data.stories.LibraryKind.SONGS
    private val listHasFilter get() = openAlbum == null && (openListId == LIKED_LIST || openListId == OFFLINE_LIST)

    private fun setupListFilter() {
        val songs = findViewById<TextView>(R.id.list_filter_songs)
        val tapes = findViewById<TextView>(R.id.list_filter_tapes)
        val artists = findViewById<TextView>(R.id.list_filter_artists)
        for ((chip, kind) in listOf(songs to com.minim.music.data.stories.LibraryKind.SONGS, tapes to com.minim.music.data.stories.LibraryKind.TAPES, artists to com.minim.music.data.stories.LibraryKind.ARTISTS)) {
            chip.setOnClickListener {
                if (!listHasFilter || listKind == kind) return@setOnClickListener
                if (kind == com.minim.music.data.stories.LibraryKind.ARTISTS && openListId != LIKED_LIST) return@setOnClickListener
                haptic(it)
                listKind = kind
                styleListFilter()
                findViewById<RecyclerView>(R.id.list_tracks).scrollToPosition(0)
                lifecycleScope.launch { refreshOpenList() }
            }
        }
    }

    private fun styleListFilter() {
        val row = findViewById<View>(R.id.list_filter_row) ?: return
        row.visibility = if (listHasFilter) View.VISIBLE else View.GONE
        if (!listHasFilter) return
        ChipStyle.apply(findViewById(R.id.list_filter_songs), listKind == com.minim.music.data.stories.LibraryKind.SONGS, pal)
        ChipStyle.apply(findViewById(R.id.list_filter_tapes), listKind == com.minim.music.data.stories.LibraryKind.TAPES, pal)
        // 5.7.0 u48: Artists only on Liked (Offline has no artists)
        val artists = findViewById<TextView>(R.id.list_filter_artists)
        artists.visibility = if (openListId == LIKED_LIST) View.VISIBLE else View.GONE
        ChipStyle.apply(artists, listKind == com.minim.music.data.stories.LibraryKind.ARTISTS, pal)
    }

    /** 5.7.0 u48: the list shows song rows, or liked-artist rows on Liked > Artists. */
    private fun showArtistRows(on: Boolean) {
        if (!::likedArtistAdapter.isInitialized) return
        val rv = findViewById<RecyclerView>(R.id.list_tracks)
        val want: RecyclerView.Adapter<*> = if (on) likedArtistAdapter else listAdapter
        if (rv.adapter !== want) rv.adapter = want
    }

    private val likedArtistsOpen get() =
        openAlbum == null && openListId == LIKED_LIST && listKind == com.minim.music.data.stories.LibraryKind.ARTISTS

    private fun artistCount(n: Int): String = resources.getQuantityString(R.plurals.artist_count, n, n)

    private fun tapeCount(n: Int): String = resources.getQuantityString(R.plurals.tape_count, n, n)

    private suspend fun refreshOpenList() {
        val id = openListId ?: return
        // 5.7.0 u48: Liked > Artists, from the liked-artists store (no database read)
        if (id == LIKED_LIST && listKind == com.minim.music.data.stories.LibraryKind.ARTISTS) {
            val artists = app.likedArtists.artists.value
            styleListFilter()
            showArtistRows(true)
            likedArtistAdapter.submit(artists)
            listEntrance.loaded(artists.size)
            showListSubtitle(null, artistCount(artists.size))
            listEmpty.text = getString(R.string.liked_empty_artists)
            listEmpty.visibility = if (artists.isEmpty()) View.VISIBLE else View.GONE
            return
        }
        showArtistRows(false)
        val all = when (id) {
            LIKED_LIST -> app.database.getLikedTracks()
            RECENTS_LIST -> app.database.getRecentTracks(50)
            OFFLINE_LIST -> app.offlineStore.saved.value.map { it.track }
            else -> app.database.getPlaylistTracks(id)
        }
        if (openListId != id || likedArtistsOpen) return
        val filtered = id == LIKED_LIST || id == OFFLINE_LIST
        val tracks = if (filtered) com.minim.music.data.stories.LibraryFilter.split(all, listKind) else all
        val tapesOn = filtered && listKind == com.minim.music.data.stories.LibraryKind.TAPES
        styleListFilter()
        listAdapter.submitList(tracks)
        // v4.9.9: rises in on opening; later refreshes (a like, a play) just update
        listEntrance.loaded(tracks.size)
        // 5.3.0: "from Spotify · 84 songs" for an imported playlist
        // 5.3.2: "from [logo] · 84 songs", the logo tinted with the app colour; a pasted list keeps words
        val origin = com.minim.music.data.importer.ImportOrigin.fromCode(openListSource)
            ?.takeIf { id >= 0L }
        showListSubtitle(origin, if (tapesOn) tapeCount(tracks.size) else songCount(tracks.size))
        listEmpty.text = getString(
            when (id) {
                LIKED_LIST -> if (tapesOn) R.string.liked_empty_tapes else R.string.liked_empty
                OFFLINE_LIST -> if (tapesOn) R.string.offline_empty_tapes else R.string.offline_empty
                RECENTS_LIST -> R.string.recents_empty
                else -> R.string.playlist_empty
            }
        )
        listEmpty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun songCount(n: Int): String =
        if (n == 1) getString(R.string.song_count_one) else getString(R.string.song_count, n)

    // ---- 5.8.5 u57: Library's "+ Add playlists" and "Search Playlists" pills ------------------

    private fun setupLibraryPills() {
        val add = findViewById<View>(R.id.library_add_pill)
        val searchPill = findViewById<View>(R.id.library_search_pill)
        val input = findViewById<EditText>(R.id.library_search_input)
        add.setOnClickListener { v -> haptic(v); showAddPlaylistSheet() }
        searchPill.setOnClickListener { v -> if (!libraryFilterOpen) { haptic(v); openLibrarySearch() } }
        findViewById<View>(R.id.library_search_close).setOnClickListener { v -> haptic(v); closeLibrarySearch(animate = true) }
        PressScale.apply(add)
        PressScale.apply(searchPill)
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!libraryFilterOpen) return
                filterLibrary(s?.toString().orEmpty())
            }
        })
        input.setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
    }

    private fun filterLibrary(q: String) {
        if (q == libraryQuery) return
        val wasActive = LibraryFilter.isActive(libraryQuery)
        libraryQuery = q
        val shown = LibraryFilter.apply(libraryTiles, q)
        if (animationsOn()) libraryAdapter.submitAnimated(shown) else libraryAdapter.submit(shown)
        if (LibraryFilter.isActive(q) != wasActive) findViewById<RecyclerView>(R.id.library_grid).scrollToPosition(0)
        showLibraryFilterEmpty()
    }

    private fun showLibraryFilterEmpty() {
        val empty = findViewById<TextView>(R.id.library_filter_empty) ?: return
        val none = LibraryFilter.isActive(libraryQuery) && LibraryFilter.apply(libraryTiles, libraryQuery).isEmpty()
        if (none) empty.text = getString(R.string.library_search_empty, libraryQuery.trim())
        val v = if (none) View.VISIBLE else View.GONE
        if (empty.visibility == v) return
        empty.animate().cancel()
        if (none && animationsOn()) {
            empty.alpha = 0f
            empty.visibility = View.VISIBLE
            empty.animate().alpha(0.6f).setStartDelay(80L).setDuration(180L).start()
        } else {
            empty.alpha = 0.6f
            empty.visibility = v
        }
    }

    /** The pill row's grow / shrink: the search pill widens across, the add pill slides away. */
    private fun libraryPillsTransition() {
        if (!animationsOn()) return
        val set = android.transition.TransitionSet()
            .addTransition(android.transition.ChangeBounds())
            .addTransition(android.transition.Fade())
            .setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator(1.5f))
        android.transition.TransitionManager.beginDelayedTransition(findViewById(R.id.library_pills), set)
    }

    private fun openLibrarySearch() {
        if (libraryFilterOpen) return
        libraryFilterOpen = true
        val add = findViewById<View>(R.id.library_add_pill)
        val label = findViewById<View>(R.id.library_search_label)
        val box = findViewById<View>(R.id.library_search_box)
        val input = findViewById<EditText>(R.id.library_search_input)
        val pill = findViewById<View>(R.id.library_search_pill)
        libraryPillsTransition()
        add.visibility = View.GONE
        pill.isClickable = false
        pill.contentDescription = null
        label.animate().cancel(); box.animate().cancel()
        box.visibility = View.VISIBLE
        if (animationsOn()) {
            label.animate().alpha(0f).setDuration(120L).withEndAction { label.visibility = View.INVISIBLE }.start()
            box.alpha = 0f
            box.animate().alpha(1f).setStartDelay(90L).setDuration(200L).start()
        } else {
            label.alpha = 0f; label.visibility = View.INVISIBLE; box.alpha = 1f
        }
        input.setText("")
        input.requestFocus()
        showKeyboard(input)
    }

    /** Closes the filter: everything shows again ([animate] false when leaving Library). */
    private fun closeLibrarySearch(animate: Boolean) {
        if (!libraryFilterOpen) return
        val add = findViewById<View>(R.id.library_add_pill)
        val label = findViewById<View>(R.id.library_search_label)
        val box = findViewById<View>(R.id.library_search_box)
        val input = findViewById<EditText>(R.id.library_search_input)
        val pill = findViewById<View>(R.id.library_search_pill)
        if (input.hasFocus()) hideKeyboard()
        input.clearFocus()
        val anim = animate && animationsOn()
        if (anim) {
            filterLibrary("")
            libraryPillsTransition()
        } else {
            libraryQuery = ""
            libraryAdapter.submit(libraryTiles)
            showLibraryFilterEmpty()
        }
        libraryFilterOpen = false
        input.setText("")
        add.visibility = View.VISIBLE
        pill.isClickable = true
        pill.contentDescription = getString(R.string.library_search_playlists)
        label.animate().cancel(); box.animate().cancel()
        label.visibility = View.VISIBLE
        if (anim) {
            box.animate().alpha(0f).setDuration(110L).withEndAction { box.visibility = View.GONE }.start()
            label.alpha = 0f
            label.animate().alpha(1f).setStartDelay(100L).setDuration(200L).start()
        } else {
            box.alpha = 0f; box.visibility = View.GONE; label.alpha = 1f
        }
    }

    /** Reloads Recents, Library and the open list from the database. */
    private suspend fun refreshData() {
        val recent = app.database.getRecentTracks(50)
        recentTracks = recent

        val liked = app.database.getLikedTracks()
        val playlists = app.database.getPlaylists()
        val tiles = ArrayList<GridTile>(playlists.size + 2)
        // Design order: Liked Songs, Recents (v4.9, was a tab), Offline, then your playlists.
        // 5.8.5 u57: no "+" tile in the grid any more: "+ Add playlists" is a pill above it.
        tiles.add(GridTile.Liked(liked.size, liked.firstOrNull()?.thumbnailUrl))
        tiles.add(GridTile.RecentsTile(recent.size))
        // 5.4.0: pinned after Recents: the songs saved offline
        tiles.add(GridTile.OfflineTile(app.offlineStore.saved.value.size))
        for (p in playlists) {
            val cover = app.database.getPlaylistTracks(p.id).firstOrNull()?.thumbnailUrl
            tiles.add(GridTile.PlaylistTile(p, cover))
        }
        libraryTiles = tiles
        // 5.8.5 u57: while "Search Playlists" has a name typed, only the matching playlists show
        libraryAdapter.submit(LibraryFilter.apply(tiles, libraryQuery))
        showLibraryFilterEmpty()
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
            // 5.5.0: the pill turns into Now Playing: tap plays it, a swipe up follows the finger
            mini.root.setOnClickListener { openNowPlayingFrom(mini) }
            mini.root.setOnTouchListener(MiniDrag(mini))
            mini.play.setOnClickListener {
                haptic(it)
                app.playbackController.togglePlayPause()
            }
            mini.next.setOnClickListener {
                haptic(it)
                app.playbackController.skipNext()
            }
            mini.like.setOnClickListener { tapHeart(mini.like) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupNowPlaying() {
        findViewById<View>(R.id.np_back).setOnClickListener { collapseNowPlaying(animate = true) }
        setupNowPlayingMorph()
        // v4.9.7: 3 dot menu at the top right (Add to playlist, Go to artist, Share, Sleep timer)
        findViewById<View>(R.id.np_more).setOnClickListener { v ->
            haptic(v)
            showNowPlayingMenu()
        }
        // 5.7.0 u46: CC (stories uploads only) opens their captions in Full Screen Lyrics
        // 5.7.0 u47: the countdown opens the sleep timer; the download button saves the tape
        findViewById<View>(R.id.np_sleep).setOnClickListener { v ->
            haptic(v)
            showSleepTimerSheet()
        }
        findViewById<View>(R.id.np_download).setOnClickListener { v ->
            val track = app.playbackController.uiState.value.currentTrack ?: return@setOnClickListener
            if (!com.minim.music.data.stories.StoryTracks.isStory(track)) return@setOnClickListener
            haptic(v)
            val on = app.offlineStore.isSaved(track.id) || app.offline.isSaving(track.id)
            if (on) removeOffline(listOf(track.id)) else saveOffline(listOf(track))
            renderNpDownload()
        }
        findViewById<View>(R.id.np_cc).setOnClickListener { v ->
            haptic(v, HapticFeedbackConstants.KEYBOARD_TAP)
            openFullscreenLyrics()
        }
        // v4.9.8: "Up Next" pill (was "swipe up for lyrics"; the long-press for Up next is gone)
        findViewById<View>(R.id.np_up_next).setOnClickListener { v ->
            haptic(v)
            openQueue(from = v)
        }
        // v4.9.8: tap the lyric line for full screen lyrics (only while the song has lyrics)
        // 5.8.0 (update 52): any song now, lyrics or not (the colour field); tapes still need captions
        npLyric.setOnClickListener { v ->
            if (!npLyricOpens()) return@setOnClickListener
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
                    AppPreferences.GESTURE_DTAP_LIKE -> {
                        // 5.5.0: the big heart floats up over the cover and the small heart fills;
                        // a second double tap keeps the song liked (it never unlikes)
                        doubleTapLike()
                        return true
                    }
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
            override fun seekLabel(fraction: Float): String =
                SeekMotion.label(SeekMotion.targetMs(fraction, app.playbackController.uiState.value.durationMs))
            override fun edgeTick(v: View) = haptic(v, HapticFeedbackConstants.CLOCK_TICK)
            override fun seekToLyric(lineStartMs: Long) = seekToLyricLine(lineStartMs)
            override fun onChromeChanged() = applyFsLyricsChrome()
            override suspend fun savedTranslation(data: LyricsData): List<String?>? =
                app.lyricsTranslator.cached(data.trackId, data.lines.map { it.text.trim() })
            override suspend fun translateLyrics(data: LyricsData): List<String?> {
                val track = app.playbackController.uiState.value.currentTrack?.takeIf { it.id == data.trackId }
                val ctx = applicationContext
                val settings = app.geminiSettings
                return app.lyricsTranslator.translate(
                    data.trackId, data.lines.map { it.text.trim() }, track?.title, track?.artist
                ) { GeminiRoute.endpoint(ctx, settings) }
            }
            override fun tapHaptic(v: View) = haptic(v, HapticFeedbackConstants.CLOCK_TICK)
            override val aiLyrics: FullscreenAiLyrics.Host = aiLyricsHost
        })
        fsLyrics.flowStyle = app.preferences.isFlowLyrics
        npLyric.flowStyle = app.preferences.isFlowLyrics
        npLyric.animationsOn = { animationsOn() }
    }

    /**
     * 5.8.1 (update 53): Gemini lyrics. Only with your own key from Settings (never Minim's
     * built-in route: the song's audio goes to your Google account, not Minim's), only on a tap.
     * The request runs in the app, so it isn't lost if you close full screen lyrics.
     */
    private val aiLyricsHost = object : FullscreenAiLyrics.Host {
        override suspend fun requestAiLyrics(songId: String, regenerate: Boolean): AiLyricsResult {
            val settings = app.geminiSettings
            val endpoint = settings.apiKey()?.takeIf { it.isNotBlank() }?.let { GeminiEndpoint.direct(settings.model, it) }
            if (endpoint == null) {
                // a copy saved earlier still shows without a key; a new one needs it
                if (!regenerate) app.aiLyrics.saved(songId)?.let { saved ->
                    app.playbackController.showAiLyrics(songId, saved)
                    return AiLyricsResult.Done(saved)
                }
                return AiLyricsResult.NoKey
            }
            val track = app.playbackController.uiState.value.currentTrack?.takeIf { it.id == songId }
                ?: aiSongs[songId]
                ?: return AiLyricsResult.Unusable
            val durMs = app.playbackController.uiState.value.durationMs.takeIf { it > 0 && track.id == app.playbackController.uiState.value.currentTrack?.id }
                ?: track.durationSec * 1000L
            val result = app.aiLyrics.generate(
                AiLyricsSong(track.id, track.title, track.artist, durMs), endpoint, regenerate
            )
            if (result is AiLyricsResult.Done) app.playbackController.showAiLyrics(songId, result.lyrics)
            return result
        }
        override fun isAiLyricsWorking(songId: String): Boolean = app.aiLyrics.isWorking(songId)
        override fun cancelAiLyrics(songId: String) = app.aiLyrics.cancel(songId)
        override fun openGeminiKeySettings() = openGeminiKeyFromLyrics()
        override fun showNote(text: String) = this@MainActivity.showNote(text)
        override fun tapHaptic(v: View) = haptic(v, HapticFeedbackConstants.CLOCK_TICK)
        override fun animationsOn(): Boolean = this@MainActivity.animationsOn()
        override fun canTap(): Boolean = isLyricsOpen
    }

    /** 5.8.1: songs the Gemini offer was shown for (title / artist / length for the request). */
    private val aiSongs = HashMap<String, Track>()

    /**
     * 5.8.1: the Gemini offer is for the playing song when it's a song (not a tape) whose lookup
     * found no lyrics, or one already showing AI lyrics (for Regenerate).
     */
    private fun updateAiLyricsSong(data: LyricsData?) {
        val track = app.playbackController.uiState.value.currentTrack
        val id = track?.takeIf {
            data != null && data.trackId == it.id &&
                !com.minim.music.data.stories.StoryTracks.isStory(it) &&
                !com.minim.music.data.captions.CaptionLyrics.isCaptions(data) &&
                (NowPlayingLyricSlot.mode(data) == NowPlayingLyricSlot.Mode.NONE || data.isAiLyrics())
        }?.id
        if (id != null && track != null) aiSongs[id] = track
        fsLyrics.setAiSong(id)
    }

    /** 5.8.1: "Add key in Settings" from full screen lyrics: Settings, at the Gemini key. */
    private fun openGeminiKeyFromLyrics() {
        val needsKey = !app.geminiSettings.hasApiKey
        closeLyrics(animate = false)
        openSettings()
        scrollSettingsTo(findViewById(R.id.set_gemini_header))
        if (needsKey) showGeminiKeySheet()
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
        c.like.setOnClickListener { tapHeart(c.like) }
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
        // 5.5.0: the animated bar: thickens and grows a thumb when grabbed, a time pill above
        // the finger, a light tick at either end, smooth fill and the buffered part
        c.progress.expressive = true
        c.progress.onScrub = { fraction, shown -> showSeekPill(c.progress, fraction, shown) }
        c.progress.onEdge = { haptic(c.progress, HapticFeedbackConstants.CLOCK_TICK) }
    }

    private val seekPill: TextView by lazy { findViewById(R.id.np_seek_pill) }
    private var seekPillShown = false

    /** 5.5.0: the time pill floating above the finger while the bar is dragged. */
    private fun showSeekPill(bar: ProgressLine, fraction: Float, shown: Boolean) {
        val pill = seekPill
        if (!shown) {
            if (seekPillShown) {
                seekPillShown = false
                pill.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).translationYBy(dp(4f))
                    .setDuration(140L).withEndAction { pill.visibility = View.INVISIBLE }.start()
            }
            return
        }
        val durationMs = app.playbackController.uiState.value.durationMs
        pill.text = SeekMotion.label(SeekMotion.targetMs(fraction, durationMs))
        val barLoc = IntArray(2)
        val hostLoc = IntArray(2)
        bar.getLocationInWindow(barLoc)
        screenNowPlaying.getLocationInWindow(hostLoc)
        val half = 2.5f * resources.displayMetrics.density
        val left = bar.paddingLeft + half
        val right = bar.width - bar.paddingRight - half
        val xInBar = left + (right - left) * fraction
        val pw = if (pill.width > 0) pill.width.toFloat() else dp(52f)
        val ph = if (pill.height > 0) pill.height.toFloat() else dp(28f)
        val margin = dp(8f)
        val x = (barLoc[0] - hostLoc[0] + xInBar - pw / 2f)
            .coerceIn(margin, screenNowPlaying.width - pw - margin)
        val y = barLoc[1] - hostLoc[1] + bar.height / 2f - ph - dp(16f)
        pill.translationX = x
        if (!seekPillShown) {
            seekPillShown = true
            pill.animate().cancel()
            pill.translationY = y + dp(6f)
            pill.alpha = 0f
            pill.scaleX = 0.85f
            pill.scaleY = 0.85f
            pill.visibility = View.VISIBLE
            pill.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(y)
                .setInterpolator(DecelerateInterpolator()).setDuration(160L).withEndAction(null).start()
        } else if (pill.animation == null) {
            pill.translationY = y
        }
    }

    /** 5.5.0: after a bar seek, older positions still arriving don't pull the bar back. */
    private var seekHoldTarget = -1L
    private var seekHoldAt = 0L

    /** Dragging either progress bar (Now Playing's or the full screen lyrics' one) seeks. */
    /**
     * 5.1.0: a tapped lyric line. The line shows at song time + offsets, so the song goes to the
     * line's time minus the file's [offset:] and the Lyrics timing setting (never before 0 or past
     * the end), with a light tick. The lines and bars update now instead of on the next tick.
     */
    private fun seekToLyricLine(lineStartMs: Long) {
        val data = lyrics ?: return
        if (!data.isSynced) return
        val durationMs = app.playbackController.uiState.value.durationMs
        var target = FlowTiming.seekTargetForLine(lineStartMs, data.offsetMs, app.preferences.lyricsOffsetMs)
        if (durationMs > 0L) target = target.coerceAtMost(durationMs - 1L)
        isSeeking = false
        app.playbackController.seekTo(target)
        haptic(screenFsLyrics, HapticFeedbackConstants.CLOCK_TICK)
        if (durationMs > 0L) {
            val fraction = (target.toFloat() / durationMs).coerceIn(0f, 1f)
            npControls.elapsed.text = ArtworkPipeline.formatDurationSec(target / 1000L)
            npControls.remaining.text = formatRemaining(durationMs, target)
            npControls.progress.setFraction(fraction)
            fsLyrics.bar.setFraction(fraction)
        }
        updateLyricLine(target, animate = true)
    }

    private fun seekFromBar(fraction: Float, finished: Boolean, source: ProgressLine) {
        val durationMs = app.playbackController.uiState.value.durationMs
        if (durationMs > 0L) {
            val target = (fraction * durationMs).toLong()
            if (finished) {
                isSeeking = false
                if (source === npControls.progress || source === fsLyrics.bar) {
                    // 5.5.0: a quick dip in the sound instead of a hard cut (5.7.0: from the
                    // full screen lyrics bar too)
                    seekHoldTarget = target
                    seekHoldAt = android.os.SystemClock.uptimeMillis()
                    app.playbackController.seekToSmooth(target)
                } else {
                    app.playbackController.seekTo(target)
                }
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

    /**
     * Hides Now Playing. [animate] (the back pill and Back) shrinks it back into the mini player
     * it came from; everything else (the song ending, going to an artist) stays instant.
     */
    private fun collapseNowPlaying(animate: Boolean = false) {
        if (::npMorph.isInitialized) {
            if (animate && animationsOn() && isNowPlayingOpen && !isLyricsOpen) {
                if (npMorph.isActive || beginMorphClose()) {
                    npMorph.animateTo(0f)
                    return
                }
            }
            npMorph.abort()
        }
        miniHeldForMorph = false
        fsLyrics.close(animate = false)
        screenNowPlaying.visibility = View.GONE
        updateChrome()
    }

    // ---- 5.5.0: mini player <-> Now Playing morph ---------------------------------------------

    private fun setupNowPlayingMorph() {
        val controlsRow = npControls.like.parent as View
        val fadingControls = buildList {
            add(npControls.progress)
            add(npControls.elapsed.parent as View)
            // Every part of the controls row except the heart, which travels instead
            if (controlsRow is ViewGroup) {
                for (i in 0 until controlsRow.childCount) {
                    val c = controlsRow.getChildAt(i)
                    if (c !== npControls.like) add(c)
                }
            }
        }
        npMorph = NowPlayingMorph(
            screen = screenNowPlaying as ViewGroup,
            art = npArt,
            title = npTitle,
            artist = npArtist,
            heart = npControls.like,
            header = listOf(findViewById<View>(R.id.np_back).parent as View, findViewById(R.id.np_vibing)),
            lyric = npLyric,
            controls = fadingControls,
            pills = findViewById(R.id.np_pill_row),
            cardRadius = dp(16f),
            restoreColors = {
                Painter.paint(npTitle, pal)
                Painter.paint(npArtist, pal)
                Painter.paint(npControls.like, pal)
                screenNowPlaying.setBackgroundColor(pal.bg)
            },
            npBackground = { pal.bg },
            onLock = { haptic(screenNowPlaying, HapticFeedbackConstants.CLOCK_TICK) }
        )
        npMorph.onSettled = { open ->
            miniHeldForMorph = false
            if (open) updateChrome() else collapseNowPlaying()
        }
    }

    private fun morphSource(mini: Mini) =
        NowPlayingMorph.Source(mini.root, mini.art, mini.title, mini.artist, mini.like)

    /** The mini player Now Playing shrinks into: the one that shows once it is gone. */
    private fun morphLandingMini(): Mini? {
        if (isImportReviewOpen || isSettingsOpen || isQueueOpen || isLyricsOpen) return null
        val onHome = tab == Tab.HOME && !(isSearchOpen || isListOpen || isShelfOpen || isArtistOpen)
        return if (onHome) homeMini.takeIf { it.root.visibility == View.VISIBLE } else floatMini
    }

    /** Tapping a pill: the same morph, played on its own. */
    private fun openNowPlayingFrom(mini: Mini) {
        if (app.playbackController.uiState.value.currentTrack == null) return
        if (npMorph.isActive) {
            npMorph.animateTo(1f)
            return
        }
        if (!animationsOn() || !mini.root.isShown) {
            expandNowPlaying()
            return
        }
        if (beginMorphOpen(mini)) npMorph.animateTo(1f)
    }

    private fun beginMorphOpen(mini: Mini): Boolean {
        if (app.playbackController.uiState.value.currentTrack == null) return false
        if (npMorph.isActive) {
            npMorph.grab()
            return true
        }
        miniHeldForMorph = true
        npMorph.begin(morphSource(mini), 0f)
        expandNowPlaying()
        return true
    }

    private fun beginMorphClose(): Boolean {
        if (npMorph.isActive) {
            npMorph.grab()
            return true
        }
        val mini = morphLandingMini() ?: return false
        if (mini === floatMini) {
            // Back on screen (invisible) so it can be measured and the cover can land on it
            miniHeldForMorph = true
            updateChrome()
        }
        npMorph.begin(morphSource(mini), 1f)
        return true
    }

    private val touchLoc = IntArray(2)

    /** True when [ev] (window coordinates) is on [v], widened by [pad]. */
    private fun isTouchOn(v: View, ev: MotionEvent, pad: Float = 0f): Boolean {
        if (!v.isShown || v.width == 0) return false
        v.getLocationInWindow(touchLoc)
        return ev.x >= touchLoc[0] - pad && ev.x <= touchLoc[0] + v.width + pad &&
            ev.y >= touchLoc[1] - pad && ev.y <= touchLoc[1] + v.height + pad
    }

    /** Only from Now Playing itself, with nothing over it and not on the seek bar. */
    private fun canSwipeNowPlayingDown(ev: MotionEvent): Boolean =
        ::npMorph.isInitialized && isNowPlayingOpen && !npMorph.isActive && animationsOn() &&
            !isLyricsOpen && !isQueueOpen && !isSettingsOpen && !isImportReviewOpen && !isGeminiShowing &&
            !isWelcomeOpen && recapStory == null && !isSeeking &&
            !(::sheetRoot.isInitialized && sheetRoot.visibility == View.VISIBLE) &&
            !isTouchOn(npControls.progress, ev, dp(14f))

    /**
     * Swipe down on Now Playing: once the finger has clearly moved down, the touch is taken from
     * whatever it started on (cancelled there) and drives the morph back into the pill.
     */
    private fun handleNowPlayingSwipe(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                npSwiping = false
                npSwipeCandidate = canSwipeNowPlayingDown(ev)
                if (npSwipeCandidate) {
                    npSwipeDownX = ev.rawX
                    npSwipeDownY = ev.rawY
                    npSwipeVelocity?.recycle()
                    npSwipeVelocity = android.view.VelocityTracker.obtain().also { it.addMovement(ev) }
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!npSwipeCandidate) return false
                npSwipeVelocity?.addMovement(ev)
                if (!npSwiping) {
                    if (isSeeking) {
                        npSwipeCandidate = false
                        return false
                    }
                    val dy = ev.rawY - npSwipeDownY
                    val dx = ev.rawX - npSwipeDownX
                    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                    if (dy > slop && dy > abs(dx) * 1.5f) {
                        if (!beginMorphClose()) {
                            npSwipeCandidate = false
                            return false
                        }
                        npSwiping = true
                        npSwipeDownY = ev.rawY
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    } else {
                        return false
                    }
                }
                npMorph.drag(MorphMotion.dragProgress(1f, ev.rawY - npSwipeDownY, npMorph.travelPx()))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasSwiping = npSwiping
                if (wasSwiping) {
                    val vt = npSwipeVelocity
                    vt?.addMovement(ev)
                    vt?.computeCurrentVelocity(1000)
                    val vy = if (ev.actionMasked == MotionEvent.ACTION_UP) (vt?.yVelocity ?: 0f) / resources.displayMetrics.density else 0f
                    npMorph.release(vy, startedOpen = true)
                }
                npSwiping = false
                npSwipeCandidate = false
                npSwipeVelocity?.recycle()
                npSwipeVelocity = null
                return wasSwiping
            }
        }
        return npSwiping
    }

    /** A swipe up on a mini player pill opens Now Playing by the finger (a tap still opens it). */
    private inner class MiniDrag(private val mini: Mini) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startY = 0f
        private var startP = 0f
        private var dragging = false
        private var opened = false
        private var velocity: android.view.VelocityTracker? = null

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    dragging = false
                    opened = false
                    velocity?.recycle()
                    velocity = android.view.VelocityTracker.obtain().also { it.addMovement(ev) }
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(ev)
                    if (opened) return true
                    if (!dragging) {
                        val dy = ev.rawY - downY
                        val dx = ev.rawX - downX
                        val slop = android.view.ViewConfiguration.get(this@MainActivity).scaledTouchSlop
                        if (-dy <= slop || abs(dy) <= abs(dx) * 1.2f) return false
                        if (app.playbackController.uiState.value.currentTrack == null) return false
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        v.onTouchEvent(cancel)
                        cancel.recycle()
                        if (!animationsOn()) {
                            // No animations: the swipe just opens it
                            opened = true
                            expandNowPlaying()
                            return true
                        }
                        if (!beginMorphOpen(mini)) return false
                        dragging = true
                        startY = ev.rawY
                        startP = npMorph.progress
                    }
                    npMorph.drag(MorphMotion.dragProgress(startP, ev.rawY - startY, npMorph.travelPx()))
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val handled = dragging || opened
                    if (dragging) {
                        val vt = velocity
                        vt?.addMovement(ev)
                        vt?.computeCurrentVelocity(1000)
                        val vy = if (ev.actionMasked == MotionEvent.ACTION_UP) (vt?.yVelocity ?: 0f) / resources.displayMetrics.density else 0f
                        npMorph.release(vy, startedOpen = startP >= 0.5f)
                    }
                    dragging = false
                    opened = false
                    velocity?.recycle()
                    velocity = null
                    return handled
                }
            }
            return dragging
        }
    }

    /**
     * v4.9.8 entry point for the full screen lyrics: the tap on the Now Playing lyric line and
     * the Lyrics long press. 5.8.1: songs without lyrics get here too (the Gemini offer); tapes
     * without captions don't (NowPlayingLyricSlot blocks them).
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
            // 5.5.1: drag by the handle / tap the x; worked out against the queue as it is now
            onMove = { id, fromHint, toShown ->
                val st = app.playbackController.uiState.value
                QueueEdit.resolveMove(st.queue.map { it.id }, st.currentIndex, id, fromHint, toShown)?.let { (from, to) ->
                    app.playbackController.moveQueueItem(from, to)
                }
                val now = app.playbackController.uiState.value
                queueAdapter.submitQueue(now.queue, now.currentIndex)
            },
            onRemove = { id, hint ->
                val st = app.playbackController.uiState.value
                val at = QueueEdit.resolveRemove(st.queue.map { it.id }, st.currentIndex, id, hint)
                if (at != null) app.playbackController.removeQueueItem(at)
                val now = app.playbackController.uiState.value
                if (at == null || now.queue == st.queue) queueAdapter.redraw()
                else queueAdapter.submitQueue(now.queue, now.currentIndex)
            },
            onLongClick = { index ->
                app.playbackController.uiState.value.queue.getOrNull(index)?.let { t ->
                    showTrackOptions(null, t, queueIndex = index)
                }
            },
            haptic = { v, type -> haptic(v, type) }
        )
        queueList = findViewById<RecyclerView>(R.id.queue_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = queueAdapter
            // v4.9.9: moved rows glide, removed rows fade and the gap closes
            itemAnimator = QueueItemAnimator()
        }
        findViewById<View>(R.id.queue_close).setOnClickListener { closeQueue(animate = true) }
        // 5.5.1: the Up Next pill stretches into this sheet, and the sheet springs back into it
        val sheet = screenQueue as ViewGroup
        queueMorph = QueueMorph(
            screen = sheet,
            header = sheet.getChildAt(0),
            content = sheet.getChildAt(1),
            list = queueList,
            behind = screenNowPlaying,
            colors = { pal.fg to pal.bg },
            onLock = { haptic(screenQueue, HapticFeedbackConstants.CLOCK_TICK) }
        )
        queueMorph.onSettled = { open ->
            if (!open) {
                screenQueue.visibility = View.GONE
                updateChrome()
            }
        }
    }

    /** 5.5.1: from the Up Next pill ([from]) the pill stretches into the sheet; otherwise it just shows. */
    private fun openQueue(from: View? = null) {
        queueMorph.abort()
        screenQueue.visibility = View.VISIBLE
        updateChrome()
        if (from != null && animationsOn()) queueMorph.open(from)
    }

    /** [animate]: the sheet shrinks back into the Up Next pill (Back, the close button). */
    private fun closeQueue(animate: Boolean = false) {
        if (animate && animationsOn() && isQueueOpen && queueMorph.beginClose()) {
            queueMorph.animateTo(0f)
            return
        }
        queueMorph.abort()
        screenQueue.visibility = View.GONE
        updateChrome()
    }

    // 5.5.1: dragging the Up Next sheet down closes it by the finger
    private var qSwipeCandidate = false
    private var qSwiping = false
    private var qSwipeOnHeader = false
    private var qSwipeDownX = 0f
    private var qSwipeDownY = 0f
    private var qSwipeVelocity: android.view.VelocityTracker? = null

    private fun handleQueueSwipe(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                qSwiping = false
                qSwipeCandidate = isQueueOpen && animationsOn() && !queueAdapter.isDragging &&
                    !isGeminiShowing && !isTakeoverActive &&
                    !(::sheetRoot.isInitialized && sheetRoot.visibility == View.VISIBLE) &&
                    !queueMorph.isActive && queueMorph.canCloseIntoPill
                if (qSwipeCandidate) {
                    qSwipeOnHeader = isTouchOn((screenQueue as ViewGroup).getChildAt(0), ev)
                    qSwipeDownX = ev.rawX
                    qSwipeDownY = ev.rawY
                    qSwipeVelocity?.recycle()
                    qSwipeVelocity = android.view.VelocityTracker.obtain().also { it.addMovement(ev) }
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!qSwipeCandidate) return false
                qSwipeVelocity?.addMovement(ev)
                if (!qSwiping) {
                    val dy = ev.rawY - qSwipeDownY
                    val dx = ev.rawX - qSwipeDownX
                    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
                    val listAtTop = !queueList.canScrollVertically(-1)
                    if (QueueMotion.startsClose(dx, dy, slop, qSwipeOnHeader, listAtTop, queueAdapter.isDragging)) {
                        if (!queueMorph.beginClose()) {
                            qSwipeCandidate = false
                            return false
                        }
                        qSwiping = true
                        qSwipeDownY = ev.rawY
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    } else {
                        // A scroll, a sideways move or a row drag: the list keeps the finger
                        if (queueAdapter.isDragging || abs(dx) > slop || abs(dy) > slop) qSwipeCandidate = false
                        return false
                    }
                }
                queueMorph.drag(QueueMotion.dragProgress(ev.rawY - qSwipeDownY, queueMorph.travelPx()))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasSwiping = qSwiping
                if (wasSwiping) {
                    val vt = qSwipeVelocity
                    vt?.addMovement(ev)
                    vt?.computeCurrentVelocity(1000)
                    val vy = if (ev.actionMasked == MotionEvent.ACTION_UP) (vt?.yVelocity ?: 0f) / resources.displayMetrics.density else 0f
                    queueMorph.release(vy)
                }
                qSwiping = false
                qSwipeCandidate = false
                qSwipeVelocity?.recycle()
                qSwipeVelocity = null
                return wasSwiping
            }
        }
        return qSwiping
    }

    private fun toggleLikeCurrent() {
        val track = app.playbackController.uiState.value.currentTrack ?: return
        lifecycleScope.launch {
            app.database.toggleLike(track)
            refreshData()
        }
    }

    /** 5.5.0: is the playing song liked right now (what the hearts show). */
    private fun currentLiked(): Boolean {
        val id = app.playbackController.uiState.value.currentTrack?.id ?: return false
        return app.database.likedIds.value.contains(id)
    }

    private fun heartBurstColor(): Int = HeartFx.burstColor(paletteSeed, pal.muted)

    /**
     * 5.5.0: a tap on any heart. It animates at once (spring, ink and burst for a like; drain and
     * shrink for an unlike), with a crisp tick at the pop or a softer one for an unlike, then
     * saves. The other hearts follow when the like lands (renderLikes), so they stay in sync.
     */
    private fun tapHeart(view: ImageView) {
        if (app.playbackController.uiState.value.currentTrack == null) return
        val liked = HeartMotion.afterTap(currentLiked())
        heartFx.play(view, liked, heartBurstColor())
        heartHaptic(view, liked)
        toggleLikeCurrent()
    }

    /** 5.5.0: double tap on the Now Playing cover with the Like gesture: only ever likes. */
    private fun doubleTapLike() {
        val track = app.playbackController.uiState.value.currentTrack ?: return
        val was = currentLiked()
        heartFx.bigHeart(npArt)
        if (HeartMotion.doubleTapChanges(was)) {
            heartFx.play(npControls.like, true, heartBurstColor())
            heartHaptic(npArt, true)
            lifecycleScope.launch {
                if (!app.database.likedIds.value.contains(track.id)) app.database.toggleLike(track)
                refreshData()
            }
        } else {
            haptic(npArt, HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun heartHaptic(v: View, liked: Boolean) {
        val type = if (liked) {
            if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        haptic(v, type)
    }

    // ==========================================================================================
    // Settings
    // ==========================================================================================

    private fun setupSettings() {
        findViewById<View>(R.id.settings_close).setOnClickListener { closeSettings(animate = true) }
        settingsReveal = SettingsReveal(screenSettings as ViewGroup, findViewById(R.id.settings_content), root)
        // The circle runs off the main thread, so its own curve drives the bar strips
        settingsReveal.onCoverage = { syncBarCovers() }
        setupAccount()
        setupVibe()

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
        option(R.id.set_bass_off) { p.bassBoost = AppPreferences.BASS_OFF }
        option(R.id.set_bass_warm) { p.bassBoost = AppPreferences.BASS_WARM }
        option(R.id.set_bass_punchy) { p.bassBoost = AppPreferences.BASS_PUNCHY }
        option(R.id.set_bass_heavy) { p.bassBoost = AppPreferences.BASS_HEAVY }
        option(R.id.set_fade_off) { p.crossfadeSeconds = 0 }
        option(R.id.set_fade_2) { p.crossfadeSeconds = 2 }
        option(R.id.set_fade_4) { p.crossfadeSeconds = 4 }
        // The tick for turning haptics on comes from option() itself, once it's on
        option(R.id.set_haptics_on) { p.hapticsEnabled = true }
        option(R.id.set_haptics_off) { p.hapticsEnabled = false }

        // Lyrics timing: - / + in 50 ms steps (hold to repeat), tap the value to reset to +300 ms
        setupLyricsStep(findViewById(R.id.set_lyrics_minus), -AppPreferences.LYRICS_OFFSET_STEP)
        setupLyricsStep(findViewById(R.id.set_lyrics_plus), AppPreferences.LYRICS_OFFSET_STEP)
        findViewById<View>(R.id.set_lyrics_value).setOnClickListener { v ->
            if (p.lyricsOffsetMs != AppPreferences.LYRICS_OFFSET_DEFAULT) haptic(v, HapticFeedbackConstants.CLOCK_TICK)
            setLyricsOffset(AppPreferences.LYRICS_OFFSET_DEFAULT)
        }

        findViewById<View>(R.id.set_offline_remove_all).setOnClickListener { confirmRemoveAllOffline() }

        findViewById<View>(R.id.set_clear_cache).setOnClickListener {
            app.artworkPipeline.clearAllCaches()
            app.lyricsProvider.clearCache()
            haptic(it)
            showNote(getString(R.string.cache_cleared))
        }

        // 5.10.0: clear listening history (reaches backups, other phones and Firestore)
        findViewById<View>(R.id.set_clear_history).setOnClickListener {
            haptic(it)
            confirmClearHistory()
        }

        // 5.10.0: backups to a folder (Fermata)
        setupBackupSettings()

        // 5.8.4 u56: the long privacy notes fold away under a short "What ..." line
        setupFold(R.id.account_privacy_toggle, R.id.account_privacy_chev, R.id.account_privacy)
        setupFold(R.id.set_gemini_privacy_toggle, R.id.set_gemini_privacy_chev, R.id.set_gemini_privacy)

        // "4.1.0 (build 5)": the version without the "-debug" ending, then the build number
        var version = ""
        var build = 0
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            version = info.versionName?.substringBefore('-').orEmpty()
            build = info.longVersionCode.toInt()
        } catch (_: Exception) {
        }
        // 5.8.4 u56: "Minim 5.8.4", with the build and sources under it, and the update check beside
        findViewById<TextView>(R.id.set_about).text = getString(R.string.about_version, version)
        findViewById<TextView>(R.id.set_about_build).text = getString(R.string.about_build, build)
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

    /**
     * A settings choice (one option of a SegmentRow): runs [action], then refreshes which options
     * are selected, which slides the thumb. 5.8.4 u56: a light tick when the choice changes, the
     * same tick as the lyrics timing steps (none when haptics were just turned off).
     */
    private fun option(id: Int, action: () -> Unit) {
        findViewById<View>(id).setOnClickListener { v ->
            val changed = !v.isSelected
            action()
            renderSettingsOptions()
            if (changed) haptic(v, HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /** 5.8.4 u56: a short line that folds a longer note open and shut, chevron turning with it. */
    private fun setupFold(toggleId: Int, chevId: Int, bodyId: Int) {
        val toggle = findViewById<View>(toggleId)
        val chev = findViewById<View>(chevId)
        val body = findViewById<View>(bodyId)
        fun describe(open: Boolean) {
            androidx.core.view.ViewCompat.setStateDescription(
                toggle, getString(if (open) R.string.fold_open else R.string.fold_closed)
            )
        }
        describe(false)
        toggle.setOnClickListener { v ->
            val open = body.visibility != View.VISIBLE
            haptic(v)
            if (animationsOn()) {
                android.transition.TransitionManager.beginDelayedTransition(
                    findViewById(R.id.settings_content),
                    android.transition.AutoTransition().apply {
                        duration = 220L
                        interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                    }
                )
                chev.animate().rotation(if (open) 180f else 0f).setDuration(220L).start()
            } else {
                chev.rotation = if (open) 180f else 0f
            }
            body.visibility = if (open) View.VISIBLE else View.GONE
            describe(open)
        }
    }

    /** 5.8.4 u56: scrolls Settings so [target] (anywhere inside it) sits near the top. */
    private fun scrollSettingsTo(target: View) {
        screenSettings.post {
            val content = findViewById<View>(R.id.settings_content)
            var y = 0
            var v: View? = target
            while (v != null && v !== content) {
                y += v.top
                v = v.parent as? View
            }
            (screenSettings as? ScrollView)?.smoothScrollTo(0, (y - dp(24f).toInt()).coerceAtLeast(0))
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
        sel(R.id.set_bass_off, p.bassBoost == AppPreferences.BASS_OFF)
        sel(R.id.set_bass_warm, p.bassBoost == AppPreferences.BASS_WARM)
        sel(R.id.set_bass_punchy, p.bassBoost == AppPreferences.BASS_PUNCHY)
        sel(R.id.set_bass_heavy, p.bassBoost == AppPreferences.BASS_HEAVY)
        sel(R.id.set_fade_off, p.crossfadeSeconds == 0)
        sel(R.id.set_fade_2, p.crossfadeSeconds == 2)
        sel(R.id.set_fade_4, p.crossfadeSeconds == 4)
        sel(R.id.set_haptics_on, p.hapticsEnabled)
        sel(R.id.set_haptics_off, !p.hapticsEnabled)
        renderLyricsOffset()
        renderGeminiSettings()
        Painter.paint(screenSettings, pal)
    }

    /** 5.5.1: from the avatar ([from]) the circle grows into Settings; other ways just show it. */
    private fun openSettings(from: View? = null) {
        settingsReveal.abort()
        settingsName.setText(app.preferences.userName)
        renderSettingsOptions()
        renderOfflineStorage()
        renderBackupSettings()
        screenSettings.visibility = View.VISIBLE
        if (from != null && animationsOn() && settingsReveal.open(from) { updateChrome() }) {
            // The floating mini player goes once the circle has covered the screen
            updateChrome()
            return
        }
        updateChrome()
    }

    /** [animate]: Settings shrinks back into the avatar it grew from (Back, the close button). */
    private fun closeSettings(animate: Boolean = false) {
        if (settingsReveal.isClosing && animate) return
        saveName()
        settingsName.clearFocus()
        hideKeyboard()
        if (animate && animationsOn() && settingsReveal.close {
                screenSettings.visibility = View.GONE
                updateChrome()
            }
        ) {
            renderGreeting(animate = false)
            updateChrome()
            return
        }
        settingsReveal.abort()
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
                launch { app.playbackController.captionChoice.collect { renderCaptionPill(it) } }
                // v4.9.6: update download progress / done / failed
                launch { app.updates.state.collect { onUpdateFlowState(it) } }
                launch { app.database.likedIds.collect { renderLikes(it); greetingFactsAt = 0L } }
                // 5.3.0: the background playlist import's card and review screen
                launch { app.importer.state.collect { renderImportState(it) } }
                // 5.4.0: Save offline: faint marks and rings on rows, rings on tiles, messages
                launch {
                    kotlinx.coroutines.flow.combine(app.offlineStore.saved, app.offline.progress) { saved, progress ->
                        com.minim.music.ui.adapter.OfflineMarks(saved.mapTo(HashSet()) { it.track.id }, progress)
                    }.collect {
                        listAdapter.setOffline(it)
                        // 5.7.0 u47: tapes rows show saved ones too (not the running percentage)
                        val savedOnly = com.minim.music.ui.adapter.OfflineMarks(it.saved, emptyMap())
                        if (::storiesScreen.isInitialized) storiesScreen.setOffline(savedOnly)
                        if (::channelVideos.isInitialized) channelVideos.offline = savedOnly
                        renderNpDownload()
                    }
                }
                launch {
                    kotlinx.coroutines.flow.combine(
                        app.offline.groups, app.offline.progress, app.offline.session
                    ) { groups, progress, session ->
                        val rings = HashMap<String, Float>()
                        groups.forEach { (k, g) -> rings[k] = com.minim.music.data.offline.OfflineRings.group(g, progress) }
                        com.minim.music.data.offline.OfflineRings.session(session, progress)?.let { rings["offline"] = it }
                        rings as Map<String, Float>
                    }.collect { libraryAdapter.setSaving(it) }
                }
                launch {
                    var last = -1
                    app.offlineStore.saved.collect { list ->
                        if (last != -1 && list.size != last) {
                            scheduleRefresh(150L)
                            if (isSettingsOpen) renderOfflineStorage()
                        }
                        last = list.size
                    }
                }
                launch { app.offline.events.collect { onOfflineEvent(it) } }
                // 5.7.0 u48: a heart on an Artist page updates Liked > Artists if it's showing
                launch {
                    app.likedArtists.artists.collect {
                        if (isListOpen && likedArtistsOpen) refreshOpenList()
                        if (isArtistOpen) updateArtistHeart(animate = true)
                    }
                }
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
                    // 5.7.0 u47: the countdown in Now Playing's top left
                    kotlinx.coroutines.flow.combine(
                        app.sleepTimerManager.mode, app.sleepTimerManager.remainingTimeMs
                    ) { m, ms -> m to ms }.collect { (m, ms) -> renderSleepCountdown(m, ms) }
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
        if (::storiesScreen.isInitialized) storiesScreen.setActiveTrack(track?.id)
        shelfAdapter.setActiveTrack(track?.id)
        shelfGridAdapter.setActiveTrack(track?.id)
        artistSongs.setActiveTrack(track?.id)
        queueAdapter.submitQueue(state.queue, state.currentIndex)
        queueEmpty.visibility = if (state.queue.isEmpty()) View.VISIBLE else View.GONE

        if (track == null) {
            renderedTrackId = null
            homeMini.sweep?.clear()
            floatMini.sweep?.clear()
            if (isNowPlayingOpen || isLyricsOpen || isQueueOpen) {
                closeQueue()
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
        // 5.7.0 u44: "You're listening to" for a stories upload, "You're vibing to" for songs
        // (only the text changes while Now Playing is closed: its opening motion owns the alpha)
        val vibe = getString(com.minim.music.data.stories.NowPlayingLabel.forTrack(track))
        if (npVibing.text.toString() != vibe) {
            if (isNowPlayingOpen) textFade.set(npVibing, vibe, fadeText) else npVibing.text = vibe
        }
        // 5.7.0 u46: the CC button and captions mode, for stories uploads only
        val story = com.minim.music.data.stories.NowPlayingCaptions.showCcButton(track)
        val ccVis = if (story) View.VISIBLE else View.GONE
        val cc = findViewById<View>(R.id.np_cc)
        if (cc.visibility != ccVis) cc.visibility = ccVis
        renderNpDownload()
        if (channelSetUp) channelVideos.setActive(track.id)
        if (fsLyrics.captionsMode != story) {
            fsLyrics.captionsMode = story
            renderCaptionPill(app.playbackController.captionChoice.value)
            // 5.8.0 (update 52): songs open full screen lyrics without lyrics, tapes don't
            applyNpLyricMode()
        }
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
        // 5.7.0 u48: the pills' fill sweep (songs and tapes; a new track slides back to zero)
        val sweepSpeed = app.playbackController.playbackSpeed
        for (mini in listOf(homeMini, floatMini)) {
            mini.sweep?.setPlayback(
                track.id, state.positionMs, state.durationMs,
                state.isPlaying && !state.isBuffering, sweepSpeed
            )
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
        for (c in listOf(npControls)) {
            c.play.setText(playLabel)
            c.repeat.setImageResource(repeatIcon)
            c.repeat.contentDescription = getString(repeatDesc)
            // Off: faded icon. All / One: full icon (One has its own "1" icon)
            c.repeat.imageAlpha = if (state.repeatMode == Player.REPEAT_MODE_OFF) 110 else 255
            val held = seekHoldTarget >= 0L && !SeekMotion.acceptsPosition(
                state.positionMs, seekHoldTarget, android.os.SystemClock.uptimeMillis() - seekHoldAt
            )
            if (!held) seekHoldTarget = -1L
            if (!isSeeking && !held) {
                c.elapsed.text = ArtworkPipeline.formatDurationSec(state.positionMs / 1000L)
                c.remaining.text = formatRemaining(state.durationMs, state.positionMs)
                c.progress.setPlayback(
                    state.positionMs, state.durationMs,
                    state.isPlaying && !state.isBuffering, state.bufferedPositionMs
                )
                // 5.7.0: the full screen lyrics bar is the same animated bar now
                fsLyrics.setPlayback(
                    state.positionMs, state.durationMs,
                    state.isPlaying && !state.isBuffering, state.bufferedPositionMs
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
        val desc = getString(if (liked) R.string.unlike else R.string.like)
        // 5.5.0: the same song changing state animates the ink on every visible heart (the
        // tapped one already did and is left alone); a new song just shows its state
        val sameSong = id == lastLikeRenderId
        lastLikeRenderId = id
        for (v in listOf(homeMini.like, floatMini.like, npControls.like)) {
            heartFx.setLiked(v, liked, animate = sameSong)
            v.contentDescription = desc
        }
    }

    private var lastLikeRenderId: String? = null

    private fun renderLyrics(data: LyricsData?) {
        lyrics = data
        activeLyric = -1
        // Now Playing's line slot: empty until a synced line starts (and for unsynced lyrics);
        // v4.9.8: a faint "Lyrics not available" when the song has none
        // 5.8.1: Gemini lyrics stay in full screen lyrics (where their AI line always shows); the
        // Now Playing slot just says they're there, and a tap opens them
        val ai = data.isAiLyrics()
        val npData = if (ai && data != null) LyricsData(data.trackId, emptyList(), false, false, "none") else data
        npLyricMode = NowPlayingLyricSlot.mode(npData)
        applyNpLyricMode()
        // v4.9.9: the line view fades out the old song's line in place (instant when it can't be seen)
        npLyric.setLyrics(npData, getString(
            when {
                ai -> R.string.ai_lyrics_np_hint
                com.minim.music.data.captions.CaptionLyrics.isCaptions(data) -> R.string.captions_none
                else -> R.string.np_lyrics_unavailable
            }
        ), animate = true)
        // v4.9.8: the full screen lyrics get the same lyrics (untimed ones show without highlighting)
        fsLyrics.setLyrics(data)
        updateAiLyricsSong(data)
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
    /** 5.8.0 (update 52): the lyric area opens full screen lyrics: lyrics, or any song (not a tape). */
    private fun npLyricOpens(): Boolean {
        if (NowPlayingLyricSlot.isTappable(npLyricMode)) return true
        if (app.playbackController.uiState.value.currentTrack == null) return false
        val story = ::fsLyrics.isInitialized && fsLyrics.captionsMode
        return NowPlayingLyricSlot.slotOpensFullscreen(npLyricMode, story)
    }

    private fun applyNpLyricMode() {
        val tappable = npLyricOpens()
        npLyric.isClickable = tappable
        npLyric.isFocusable = tappable
        npLyric.contentDescription = when {
            NowPlayingLyricSlot.isTappable(npLyricMode) -> getString(R.string.np_open_lyrics)
            // 5.8.1: AI lyrics open in full screen lyrics
            tappable && npLyricMode == NowPlayingLyricSlot.Mode.NONE && lyrics.isAiLyrics() -> getString(R.string.ai_lyrics_np_hint)
            // 5.8.0 (update 52): a song without lyrics still opens full screen lyrics (5.8.1: the Gemini offer)
            tappable && npLyricMode == NowPlayingLyricSlot.Mode.NONE -> getString(R.string.np_lyrics_unavailable) + ". " + getString(R.string.np_open_lyrics)
            tappable -> getString(R.string.np_open_lyrics)
            // v4.9.9: the slot is a layout now, so TalkBack reads the message from here
            npLyricMode == NowPlayingLyricSlot.Mode.NONE -> getString(
                if (com.minim.music.data.captions.CaptionLyrics.isCaptions(lyrics)) R.string.captions_none else R.string.np_lyrics_unavailable
            )
            else -> null
        }
        npLyric.importantForAccessibility =
            if (tappable || npLyricMode == NowPlayingLyricSlot.Mode.NONE) View.IMPORTANT_FOR_ACCESSIBILITY_YES
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** A light haptic tick, if it's on in Settings (the phone's own haptics setting also applies). */
    /** 5.7.0 u46: the caption switch pill in Full Screen Lyrics (only with two or more tracks). */
    private fun renderCaptionPill(choice: com.minim.music.playback.PlaybackController.CaptionChoice?) {
        val current = app.playbackController.uiState.value.currentTrack
        val t = choice?.options?.getOrNull(choice.selected)
        if (choice == null || t == null || current?.id != choice.trackId ||
            !com.minim.music.data.stories.NowPlayingCaptions.showPill(choice.options.size)) {
            fsLyrics.setCaptionChoice(null)
            return
        }
        fsLyrics.setCaptionChoice(com.minim.music.data.stories.NowPlayingCaptions.pillLabel(t.languageName, t.auto))
    }

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
        // v4.9.2: today's live boxes are tappable. 5.7.0 u51: so are tapes, channels, listened,
        // the top channel card, and the same boxes and cards in the recap.
        statsScreen.todaySongsBox.setOnClickListener {
            if ((todayStats?.songs ?: 0) <= 0) return@setOnClickListener
            haptic(it)
            showStatsSongsSheet(todayStats?.day ?: return@setOnClickListener)
        }
        statsScreen.todayArtistsBox.setOnClickListener {
            if ((todayStats?.artists ?: 0) <= 0) return@setOnClickListener
            haptic(it)
            showStatsArtistsSheet(todayStats?.day ?: return@setOnClickListener)
        }
        statsScreen.todayTapesBox.setOnClickListener {
            if ((todayStats?.tapes?.tapes ?: 0) <= 0) return@setOnClickListener
            haptic(it)
            showStatsTapesSheet(todayStats?.day ?: return@setOnClickListener)
        }
        statsScreen.todayChannelsBox.setOnClickListener {
            if ((todayStats?.tapes?.channels ?: 0) <= 0) return@setOnClickListener
            haptic(it)
            showStatsChannelsSheet(todayStats?.day ?: return@setOnClickListener)
        }
        statsScreen.todayTimeBox.setOnClickListener {
            val s = todayStats ?: return@setOnClickListener
            if (s.totalMs <= 0L) return@setOnClickListener
            haptic(it)
            showStatsListenedSheet(s)
        }
        statsScreen.todayArtistCard.setOnClickListener {
            val name = statsScreen.todayTopArtistName ?: return@setOnClickListener
            haptic(it)
            openArtistByName(name)
        }
        statsScreen.todayChannelCard.setOnClickListener {
            val c = statsScreen.todayTopChannelItem ?: return@setOnClickListener
            haptic(it)
            openStatsChannel(c.name, c.channelId)
        }
        statsScreen.recapSongsBox.setOnClickListener {
            val s = recapStats ?: return@setOnClickListener
            if (s.songs <= 0) return@setOnClickListener
            haptic(it)
            showStatsSongsSheet(s.day)
        }
        statsScreen.recapArtistsBox.setOnClickListener {
            val s = recapStats ?: return@setOnClickListener
            if (s.artists <= 0) return@setOnClickListener
            haptic(it)
            showStatsArtistsSheet(s.day)
        }
        statsScreen.recapTapesBox.setOnClickListener {
            val s = recapStats ?: return@setOnClickListener
            if (s.tapes.tapes <= 0) return@setOnClickListener
            haptic(it)
            showStatsTapesSheet(s.day)
        }
        statsScreen.recapChannelsBox.setOnClickListener {
            val s = recapStats ?: return@setOnClickListener
            if (s.tapes.channels <= 0) return@setOnClickListener
            haptic(it)
            showStatsChannelsSheet(s.day)
        }
        statsScreen.recapTopSongCard.setOnClickListener {
            val song = statsScreen.recapTopSong ?: return@setOnClickListener
            haptic(it)
            lifecycleScope.launch {
                val track = try { app.database.getTracksByIds(listOf(song.trackId)).firstOrNull() } catch (_: Exception) { null }
                    ?: return@launch
                app.playbackController.playRadio(track, listOf(track))
            }
        }
        statsScreen.recapTopArtistCard.setOnClickListener {
            val name = statsScreen.recapTopArtistName ?: return@setOnClickListener
            haptic(it)
            openArtistByName(name)
        }
        statsScreen.recapTopChannelCard.setOnClickListener {
            val c = statsScreen.recapTopChannelItem ?: return@setOnClickListener
            haptic(it)
            openStatsChannel(c.name, c.channelId)
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
                // 5.7.0 u51: tapes count toward the streak too
                val tapeTotals = try { app.database.getTapeDayTotals(streakFrom, today) } catch (_: Exception) { emptyMap() }
                statsScreen.renderStreak(DayStreak.count(today, DayStreak.merge(app.database.getDayTotals(streakFrom, today), tapeTotals)))
                statsScreen.renderQuietDay(pick.quietDay)
                statsScreen.renderRecap(recapS)
                recapS?.topArtist?.name?.let { loadRecapArtistPhoto(it) }
                todayS.tapes.topChannel?.let { loadStatsChannelPhoto(it.key, it.name, it.channelId) }
                recapS?.tapes?.topChannel?.let { loadStatsChannelPhoto(it.key, it.name, it.channelId) }
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

    // 5.7.0 u44: the stories tab (StoriesScreen does the work)
    private lateinit var storiesScreen: StoriesScreen

    private fun setupStories() {
        storiesScreen = StoriesScreen(screenStories, app, lifecycleScope, object : StoriesScreen.Host {
            override fun openSheet(build: (android.widget.LinearLayout) -> Unit) = this@MainActivity.openSheet(build)
            override fun closeSheet() = this@MainActivity.closeSheet()
            override fun sheetHeading(parent: android.widget.LinearLayout, title: String, sub: String?) =
                this@MainActivity.sheetHeading(parent, title, sub, wrapSub = true)
            override fun sheetClosing(): Boolean = this@MainActivity.sheetClosing
            override fun confirmSheet(parent: android.widget.LinearLayout, okText: String, onOk: () -> Unit) {
                val v = layoutInflater.inflate(R.layout.sheet_confirm, parent, false)
                v.findViewById<TextView>(R.id.sheet_confirm_ok).text = okText
                v.findViewById<TextView>(R.id.sheet_confirm_cancel).setOnClickListener { this@MainActivity.closeSheet() }
                v.findViewById<TextView>(R.id.sheet_confirm_ok).setOnClickListener {
                    if (this@MainActivity.sheetClosing) return@setOnClickListener
                    haptic(it)
                    this@MainActivity.closeSheet()
                    onOk()
                }
                parent.addView(v)
            }
            override fun showNote(text: String) = this@MainActivity.showNote(text)
            override fun haptic(v: View) = this@MainActivity.haptic(v)
            override fun animationsOn(): Boolean = this@MainActivity.animationsOn()
            override fun showKeyboard(v: View) = this@MainActivity.showKeyboard(v)
            override fun hideKeyboard() = this@MainActivity.hideKeyboard()
            override fun showUploadOptions(anchor: View, track: Track) = showTrackOptions(anchor, track)
            override fun palette(): Pal = pal
            override fun openChannel(c: com.minim.music.data.stories.StoryChannel) {
                haptic(screenStories)
                openChannelPage(c)
            }
        })
    }

    /** The first tap on stories takes its "new" tag away for good. */
    private fun onStoriesShown() {
        val prefs = app.preferences
        if (!prefs.storiesTabSeen) {
            prefs.storiesTabSeen = true
            for (row in allRows) row.storiesNew.visibility = View.GONE
            fitTopRows()
        }
        if (::storiesScreen.isInitialized) storiesScreen.onShown()
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
        for (row in allRows) {
            row.statsNew.visibility = if (showNew) View.VISIBLE else View.GONE
            row.statsDot.visibility = if (showDot) View.VISIBLE else View.GONE
        }
    }

    /** The recap's top artist photo: cached, else found like the Artist page finds it. */
    /** 5.7.0 u51: a channel's photo for the stats cards (keyed by its name key). */
    private val statsChannelPhotos = HashMap<String, String>()
    private val statsChannelPhotoTried = HashSet<String>()

    /** 5.7.0 u51: the photo of a channel from the Stats tab: a followed channel's, else its page's. */
    private fun loadStatsChannelPhoto(key: String, name: String, channelId: String, onFound: ((String) -> Unit)? = null) {
        val known = statsChannelPhotos[key]
            ?: com.minim.music.data.stories.ChannelVideos.channelFor(name, channelId, app.storiesStore.channels)
                ?.avatarUrl?.takeIf { it.isNotBlank() }
        if (known != null) {
            statsChannelPhotos[key] = known
            if (::statsScreen.isInitialized) statsScreen.showChannelPhoto(key, known)
            onFound?.invoke(known)
            return
        }
        val ch = com.minim.music.data.stories.ChannelVideos.channelFor(name, channelId, app.storiesStore.channels) ?: return
        if (!statsChannelPhotoTried.add(key)) return
        lifecycleScope.launch {
            val data = try { app.sourceAdapter.fetchChannelPage(ch) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            val url = data?.channel?.avatarUrl?.takeIf { it.isNotBlank() } ?: return@launch
            statsChannelPhotos[key] = url
            if (::statsScreen.isInitialized) statsScreen.showChannelPhoto(key, url)
            onFound?.invoke(url)
        }
    }

    /** 5.7.0 u51: opens a channel from the Stats tab on its Channel page. */
    private fun openStatsChannel(name: String, channelId: String) {
        val ch = com.minim.music.data.stories.ChannelVideos.channelFor(name, channelId, app.storiesStore.channels)
        if (ch == null) {
            showNote(getString(R.string.stats_channel_unknown))
            return
        }
        openChannelPage(ch)
    }

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
        // (5.5.2: during a festival, its own top artist lines)
        val set = Greetings.themeSet
        val themedLines = set?.takeIf { it.topArtist.isNotEmpty() }?.let {
            val i = takeoverPicker.next(it.topArtist.size)
            val user = app.preferences.userName
            it.takeoverLine(i, top.name, user, top.ms) ?: it.takeoverLine(i, ArtistTakeover.shortName(top.name), user, top.ms)
        }
        val lines = themedLines ?: ArtistTakeover.lines(takeoverPicker.next(), top.name, top.ms)
        val takeoverMs = ArtistTakeover.durationMs(Greetings.themeOn)
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
        takeoverAnim = ValueAnimator.ofFloat(0f, takeoverMs.toFloat()).apply {
            duration = takeoverMs
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                val el = a.animatedValue as Float
                val inT = (el / ArtistTakeover.FADE_IN_MS).coerceIn(0f, 1f)
                val outT = ((takeoverMs - el) / ArtistTakeover.FADE_OUT_MS).coerceIn(0f, 1f)
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
        // Home back in the song's colours exactly (5.5.2: or the festival wallpaper's)
        Painter.paint(screenHome, pal)
        applyHomeOverrides()
        window.navigationBarColor = if (themeWallpaperOnTop()) android.graphics.Color.TRANSPARENT else pal.bg
        themeNavClear = themeWallpaperOnTop()
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
        // 5.5.2: during a festival theme it blends from the wallpaper's colours, not the album's
        Painter.paint(screenHome, homeBasePal().lerp(homePal, t))
        themePillPal?.let { if (it != themeHomePal) Painter.paint(homeSearch, it.lerp(homePal, t)) }
        takeoverMiniPal?.let {
            val miniPal = miniBasePal().lerp(it, t)
            Painter.paint(homeMini.root, miniPal)
            Painter.paint(homeMini.actions, miniPal)
        }
        // Home goes see-through so the photo underneath shows; the root behind it is the song colour
        screenHome.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // (5.5.2: with a festival wallpaper the nav bar stays see-through, the wallpaper is behind it)
        window.navigationBarColor = if (themeHomePal != null) android.graphics.Color.TRANSPARENT else
            androidx.core.graphics.ColorUtils.setAlphaComponent(pal.bg, ((1f - t) * 255f).toInt().coerceIn(0, 255))
    }

    // ------------------------------------------------------------------------------------------
    // 5.5.2: festival theme on Home (wallpaper, Home's colours from it, themed like burst)
    // ------------------------------------------------------------------------------------------

    private lateinit var themeLayer: View
    private lateinit var themePhoto: ImageView

    /** Home's colours picked from the wallpaper, or null when no wallpaper is up. */
    private var themeHomePal: Pal? = null

    /** The search pill's colours when the festival sets `pill` / `pillText`, else null. */
    private var themePillPal: Pal? = null

    /** Whether the wallpaper is light (dark clock and battery icons over it). */
    private var themeLight = false
    private var themeWallpaperKey: String? = null
    private var themeGreetingsSeen: Any? = null
    private var themeJob: Job? = null
    private var themeNavClear = false

    /** 5.5.2 (39): light picture behind the status bar / nav bar (dark icons there). */
    private var themeStatusLight = false
    private var themeNavLight = false
    private var themeBitmap: android.graphics.Bitmap? = null
    private var navInset = 0
    private var baseNavContrast = true
    private var themeNavContrast = true
    private var versionTaps = 0
    private var versionTapAt = 0L

    private fun setupTheme() {
        themeLayer = findViewById(R.id.home_theme)
        themePhoto = findViewById(R.id.home_theme_photo)
        lifecycleScope.launch {
            app.themeState.collect { s ->
                themeJob?.cancel()
                themeJob = lifecycleScope.launch { applyThemeState(s) }
            }
        }
        // 7 taps on the version: preview the next festival; long press during a preview: its big day
        val about = findViewById<TextView>(R.id.set_about)
        about.setOnClickListener { onVersionTapped() }
        about.setOnLongClickListener { v ->
            haptic(v)
            app.toggleThemeBigDay { showPreviewToast(it) }
            true
        }
    }

    private fun onVersionTapped() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - versionTapAt > VERSION_TAP_GAP_MS) versionTaps = 0
        versionTapAt = now
        versionTaps++
        if (versionTaps >= VERSION_TAPS) {
            versionTaps = 0
            haptic(findViewById(R.id.set_about))
            app.toggleThemePreview { showPreviewToast(it) }
        }
    }

    private fun showPreviewToast(r: com.minim.music.data.themes.PreviewResult) {
        val msg = ThemePreviewText.message(r)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private suspend fun applyThemeState(s: com.minim.music.data.themes.ThemeState) {
        heartFx.burstStyle = s.theme?.burst
        // New festival greetings (or back to the regular ones): start again from a fitting opener
        val greet: Any? = s.greetings?.file
        if (greet !== themeGreetingsSeen) {
            val first = themeGreetingsSeen == null && greet == null
            themeGreetingsSeen = greet
            if (!first && greetingOverride == null && ::homeGreeting.isInitialized) {
                pickOpener()
                renderGreeting(animate = isHomeShowing)
            }
        }
        val file = s.wallpaper
        val key = file?.let { "${it.path}:${it.length()}:${it.lastModified()}:${s.theme?.colors}" }
        if (key == themeWallpaperKey) return
        val theme = s.theme
        if (file == null || theme == null) {
            themeWallpaperKey = null
            clearThemeWallpaper()
            return
        }
        // 5.5.2 (39): decoded for the full screen, bars included (the layer reaches behind them)
        val (screenW, screenH) = fullScreenSize()
        val w = screenW.coerceIn(480, 1440)
        val h = screenH.coerceIn(800, 3200)
        val decoded = withContext(Dispatchers.IO) { ThemeWallpaper.decode(file, w, h) }
        if (decoded == null) {
            themeWallpaperKey = null
            clearThemeWallpaper()
            return
        }
        themeWallpaperKey = key
        val (bmp, look) = decoded
        themeLight = look.light
        val c = theme.colors
        val ink = c.text ?: look.ink
        val base = look.bg
        themeHomePal = Pal(bg = base, fg = ink, muted = androidx.core.graphics.ColorUtils.setAlphaComponent(ink, 0x99))
        themePillPal = if (c.pill != null || c.pillText != null) {
            val fill = c.pill ?: ink
            val text = c.pillText ?: (if (Painter.isLight(fill)) 0xFF111111.toInt() else 0xFFFFFFFF.toInt())
            Pal(bg = text, fg = fill, muted = androidx.core.graphics.ColorUtils.setAlphaComponent(fill, 0x99))
        } else null
        themePhoto.setImageBitmap(bmp)
        themeBitmap = bmp
        readThemeBarTones()
        syncThemeLayer(animate = false)
        applyHomeOverrides()
        updateSystemBars()
    }

    private fun clearThemeWallpaper() {
        if (themeHomePal == null && !::themeLayer.isInitialized) return
        themeHomePal = null
        themePillPal = null
        themeBitmap = null
        if (::themeLayer.isInitialized) {
            themeLayer.animate().cancel()
            themeLayer.visibility = View.GONE
            themePhoto.setImageDrawable(null)
        }
        if (::screenHome.isInitialized) {
            Painter.paint(screenHome, pal)
            applyHomeOverrides()
        }
        updateSystemBars()
    }

    /** Home's colours on top of the album palette: the takeover's, else the wallpaper's, else nothing. */
    private fun applyHomeOverrides() {
        if (!::screenHome.isInitialized) return
        if (isTakeoverActive) {
            applyTakeoverColors()
            return
        }
        val tp = themeHomePal
        if (tp == null) {
            screenHome.setBackgroundColor(pal.bg)
            return
        }
        Painter.paint(screenHome, tp)
        themePillPal?.let { Painter.paint(homeSearch, it) }
        // The mini player keeps the song's colours (it grows into Now Playing)
        Painter.paint(homeMini.root, pal)
        Painter.paint(homeMini.actions, pal)
        screenHome.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private fun homeBasePal(): Pal = themeHomePal ?: pal

    private fun miniBasePal(): Pal = pal

    /** The wallpaper only shows under Home; [animate] fades it on tab switches. */
    private fun syncThemeLayer(animate: Boolean) {
        if (!::themeLayer.isInitialized || !::screenHome.isInitialized) return
        if (tab == Tab.HOME) applyHomeOverrides()
        val show = themeHomePal != null && tab == Tab.HOME
        themeLayer.animate().cancel()
        if (show) {
            if (themeLayer.visibility != View.VISIBLE) {
                themeLayer.alpha = if (animate && animationsOn()) 0f else 1f
                themeLayer.visibility = View.VISIBLE
            }
            if (themeLayer.alpha < 1f) themeLayer.animate().alpha(1f).setDuration(THEME_FADE_MS).start()
        } else if (themeLayer.visibility == View.VISIBLE) {
            if (animate && animationsOn()) {
                themeLayer.animate().alpha(0f).setDuration(THEME_FADE_MS).withEndAction {
                    if (!(themeHomePal != null && tab == Tab.HOME)) themeLayer.visibility = View.GONE
                }.start()
            } else {
                themeLayer.visibility = View.GONE
            }
        }
    }

    /** The whole screen in pixels, behind the status and navigation bars too. */
    private fun fullScreenSize(): Pair<Int, Int> {
        val d = window.decorView
        if (d.width > 0 && d.height > 0) return d.width to d.height
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    /**
     * 5.5.2 (39): how light the wallpaper is right behind the status bar and behind the nav bar,
     * read from the part the full-screen centre-crop really shows (like the Artist page photo).
     */
    private fun readThemeBarTones() {
        val bmp = themeBitmap ?: return
        if (bmp.isRecycled) return
        val (viewW, viewH) = fullScreenSize()
        fun light(strip: Pair<Float, Float>): Boolean {
            val box = ThemeBars.cropBox(bmp.width, bmp.height, viewW, viewH, strip.first, strip.second)
            val step = maxOf(1, minOf(bmp.width, bmp.height) / 90)
            var sum = 0.0
            var n = 0
            var y = box[1]
            while (y < box[3]) {
                var x = box[0]
                while (x < box[2]) {
                    sum += androidx.core.graphics.ColorUtils.calculateLuminance(bmp.getPixel(x, y) or 0xFF000000.toInt())
                    n++
                    x += step
                }
                y += maxOf(1, minOf(step, (box[3] - box[1]) / 4))
            }
            return ThemeBars.isLight(if (n == 0) 0.0 else sum / n)
        }
        val s = light(ThemeBars.statusStrip(statusInset, viewH))
        val nv = light(ThemeBars.navStrip(navInset, viewH))
        if (s != themeStatusLight || nv != themeNavLight) {
            themeStatusLight = s
            themeNavLight = nv
            updateSystemBars()
        }
    }

    /**
     * Whether Home with its wallpaper is what's on screen (bars follow the wallpaper).
     * 5.5.2 (update 40): a screen opening over Home takes the bars over once it's half way in,
     * the same moment its strips behind the bars are half faded in (see [syncBarCovers]).
     */
    private fun themeWallpaperOnTop(): Boolean =
        themeHomePal != null && tab == Tab.HOME && !isWelcomeOpen && overlayCoverage() < 0.5f

    private lateinit var barCovers: View
    private lateinit var barCoverTop: View
    private lateinit var barCoverBottom: View
    private var barCoverWallOnTop: Boolean? = null

    /**
     * 5.5.2 (update 40): how much of Home is covered by a screen opened over it, 0..1, following
     * each screen's own animation: Now Playing's morph, Settings' reveal, Search's backdrop, and
     * the others' fade. 1 as soon as one of them is fully there.
     */
    private fun overlayCoverage(): Float {
        var c = 0f
        fun take(v: Float) { if (v > c) c = v }
        fun shown(v: View) = v.visibility == View.VISIBLE
        if (::screenNowPlaying.isInitialized && shown(screenNowPlaying)) {
            take(if (::npMorph.isInitialized && npMorph.isActive) npMorph.progress else screenNowPlaying.alpha)
        }
        if (::screenSettings.isInitialized && shown(screenSettings)) {
            take(if (::settingsReveal.isInitialized && settingsReveal.isRunning) settingsReveal.coverage else screenSettings.alpha)
        }
        if (::screenSearch.isInitialized && shown(screenSearch)) {
            val back = (screenSearch.background?.alpha ?: 255) / 255f
            take(minOf(screenSearch.alpha, back))
        }
        if (::screenQueue.isInitialized && shown(screenQueue)) take(screenQueue.alpha)
        if (::screenArtist.isInitialized && shown(screenArtist)) take(screenArtist.alpha)
        if (::screenShelf.isInitialized && shown(screenShelf)) take(screenShelf.alpha)
        if (::screenList.isInitialized && shown(screenList)) take(screenList.alpha)
        if (isImportReviewOpen) take(screenImport.alpha)
        if (isLyricsOpen) take(1f)
        return c.coerceIn(0f, 1f)
    }

    /**
     * 5.5.2 (update 40): the strips behind the status bar and the nav bar. Over the wallpaper (or
     * the takeover photo) they fade in, in the app's colour, as a screen opens over Home and out
     * as it closes, so the wallpaper only ever shows behind the bars on Home itself. Home alone
     * (update 39) is untouched: with nothing over it they're gone.
     */
    private fun syncBarCovers() {
        if (!::barCovers.isInitialized || !::themeLayer.isInitialized || !::takeoverRoot.isInitialized) return
        val photoBehind = themeLayer.visibility == View.VISIBLE || takeoverRoot.visibility == View.VISIBLE
        val cover = if (photoBehind) overlayCoverage() else 0f
        if (cover <= 0f) {
            if (barCovers.visibility != View.GONE) barCovers.visibility = View.GONE
        } else {
            val bg = pal.bg
            if ((barCoverTop.background as? android.graphics.drawable.ColorDrawable)?.color != bg) barCoverTop.setBackgroundColor(bg)
            if ((barCoverBottom.background as? android.graphics.drawable.ColorDrawable)?.color != bg) barCoverBottom.setBackgroundColor(bg)
            if (barCovers.alpha != cover) barCovers.alpha = cover
            if (barCovers.visibility != View.VISIBLE) barCovers.visibility = View.VISIBLE
        }
        // The clock, battery and nav icons switch with the strips, half way
        val wallOnTop = themeWallpaperOnTop()
        if (barCoverWallOnTop != wallOnTop) {
            barCoverWallOnTop = wallOnTop
            updateSystemBars()
        }
    }

    // ------------------------------------------------------------------------------------------
    // v4.9.2: today's songs and artists sheets (from the Stats tab's live boxes)
    // ------------------------------------------------------------------------------------------

    private var todaySheetJob: Job? = null

    private fun isTodayKey(day: Int): Boolean = day == Days.keyOf(System.currentTimeMillis())

    /** A stats sheet's title: "today's songs" for today, "songs" for the recap day. */
    private fun statsSheetTitle(day: Int, todayRes: Int, otherRes: Int): String =
        getString(if (isTodayKey(day)) todayRes else otherRes)

    /** The songs played on [day] with how many times each was played; tapping one plays it. */
    private fun showStatsSongsSheet(day: Int) {
        todaySheetJob?.cancel()
        todaySheetJob = lifecycleScope.launch {
            val songs = try {
                TodayLists.songs(app.database.getDayPlays(day), app.database.getDayListens(day))
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
                    box, statsSheetTitle(day, R.string.stats_today_songs_sheet, R.string.stats_songs_sheet),
                    "${Days.label(day)} · ${statsScreen.playsText(total)}"
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

    /** The artists heard on [day] with how long each was heard; tapping one opens their Artist page. */
    private fun showStatsArtistsSheet(day: Int) {
        todaySheetJob?.cancel()
        todaySheetJob = lifecycleScope.launch {
            val artists = try {
                TodayLists.artists(app.database.getDayPlays(day), app.database.getDayListens(day))
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
                    box, statsSheetTitle(day, R.string.stats_today_artists_sheet, R.string.stats_artists_sheet),
                    "${Days.label(day)} · $count ${getString(if (count == 1) R.string.stats_artist_one else R.string.stats_artists)}"
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

    /**
     * 5.7.0 u51: the tapes played on [day], most time first, with the time heard. Tapping one plays
     * it (picking up where you stopped), with the tapes after it in the list queued next.
     */
    private fun showStatsTapesSheet(day: Int) {
        todaySheetJob?.cancel()
        todaySheetJob = lifecycleScope.launch {
            val tapes = try {
                com.minim.music.data.stats.TapeStats.tapes(app.database.getDayTapePlays(day), app.database.getDayTapeListens(day))
                    .take(TODAY_SHEET_MAX)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (tapes.isEmpty()) return@launch
            val tracks = tapes.map {
                com.minim.music.data.stories.StoryTracks.track(it.videoId, it.title, it.channel, it.durationSec, it.channelId.ifBlank { null })
            }
            val rows = ArrayList<View>()
            openSheet { box ->
                sheetHeading(
                    box, statsSheetTitle(day, R.string.stats_today_tapes_sheet, R.string.stats_tapes_sheet),
                    "${Days.label(day)} · ${statsScreen.tapesText(tapes.size)}"
                )
                tapes.forEachIndexed { i, t ->
                    val row = layoutInflater.inflate(R.layout.item_sheet_song, box, false)
                    val art = row.findViewById<ImageView>(R.id.song_art).apply { clipToOutline = true }
                    row.findViewById<TextView>(R.id.song_title).text = t.title
                    row.findViewById<TextView>(R.id.song_artist).text = t.channel
                    val heard = StatsFormat.duration(t.ms)
                    row.findViewById<TextView>(R.id.song_plays).text = heard
                    row.contentDescription = "${t.title}, ${t.channel}, ${getString(R.string.stats_tape_heard, heard)}"
                    app.artworkPipeline.loadInto(art, t.thumbnailUrl.ifBlank { com.minim.music.data.stories.StoryTracks.thumbnailUrl(t.videoId) }, dp(44f).toInt())
                    row.clipToOutline = true
                    row.setOnClickListener { v ->
                        if (sheetClosing) return@setOnClickListener
                        haptic(v)
                        closeSheet { app.playbackController.playTrack(tracks[i], tracks.drop(i), com.minim.music.data.fermata.FermataSource.TAPE) }
                    }
                    box.addView(row)
                    rows += row
                }
            }
            RowEntrance.playViews(rows)
        }
    }

    /** 5.7.0 u51: the channels listened to on [day], with the time; tapping one opens its Channel page. */
    private fun showStatsChannelsSheet(day: Int) {
        todaySheetJob?.cancel()
        todaySheetJob = lifecycleScope.launch {
            val channels = try {
                com.minim.music.data.stats.TapeStats.channels(app.database.getDayTapePlays(day), app.database.getDayTapeListens(day))
                    .take(TODAY_SHEET_MAX)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (channels.isEmpty()) return@launch
            val count = channels.size
            val rows = ArrayList<View>()
            openSheet { box ->
                sheetHeading(
                    box, statsSheetTitle(day, R.string.stats_today_channels_sheet, R.string.stats_channels_sheet),
                    "${Days.label(day)} · $count ${getString(if (count == 1) R.string.stats_channel_one else R.string.stats_channels)}"
                )
                for (c in channels) {
                    val row = sheetAction(box, R.drawable.ic_person, c.name, trailing = StatsFormat.duration(c.ms)) {
                        closeSheet { openStatsChannel(c.name, c.channelId) }
                    }
                    showSheetChannelPhoto(row, c)
                    rows += row
                }
            }
            RowEntrance.playViews(rows)
        }
    }

    /** 5.7.0 u51: "listened" tapped: songs and tapes time apart, and together. */
    private fun showStatsListenedSheet(s: DayStats) {
        if (s.totalMs <= 0L) return
        val t = s.tapes
        openSheet { box ->
            sheetHeading(
                box, statsSheetTitle(s.day, R.string.stats_today_listened_sheet, R.string.stats_listened_sheet),
                listOfNotNull(Days.label(s.day), com.minim.music.data.stats.TapeStats.savedLine(t)).joinToString(" · ")
            )
            val rows = ArrayList<View>()
            rows += sheetAction(box, R.drawable.ic_music_note, getString(R.string.stats_split_songs), trailing = StatsFormat.duration(s.timeMs)) {
                if (s.songs > 0) closeSheet { showStatsSongsSheet(s.day) }
            }
            rows += sheetAction(box, R.drawable.ic_tapes, getString(R.string.stats_split_tapes), trailing = StatsFormat.duration(t.ms)) {
                if (t.tapes > 0) closeSheet { showStatsTapesSheet(s.day) }
            }
            rows += sheetAction(box, R.drawable.ic_history, getString(R.string.stats_split_total), trailing = StatsFormat.duration(s.totalMs)) {
                closeSheet()
            }
            RowEntrance.playViews(rows)
        }
    }

    /** 5.7.0 u51: a channel's round photo in a sheet row's icon spot, when one can be found. */
    private fun showSheetChannelPhoto(row: View, c: com.minim.music.data.stats.ChannelItem) {
        loadStatsChannelPhoto(c.key, c.name, c.channelId) { url -> showSheetPhotoUrl(row, url) }
    }

    /** v4.9.3: puts [name]'s round photo in a sheet row's icon spot, fading in once it's found. */
    private fun showSheetArtistPhoto(row: View, name: String) {
        lifecycleScope.launch {
            val url = artistPhotoUrl(name) ?: return@launch
            showSheetPhotoUrl(row, url)
        }
    }

    /** v4.9.3 (5.7.0 u51: shared with channel rows): a round photo from [url] in a sheet row's icon spot. */
    private fun showSheetPhotoUrl(row: View, url: String) {
        val icon = row.findViewById<ImageView>(R.id.action_icon)
        val label = row.findViewById<TextView>(R.id.action_label)
        val sizePx = dp(30f).toInt()
        if (icon.getTag(R.id.action_icon) == url) return
        icon.setTag(R.id.action_icon, url)
        lifecycleScope.launch {
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
        // 5.0.0 part 3: the update lock comes before everything else
        if (::sheetRoot.isInitialized && ::root.isInitialized && checkVibeLock()) return
        checkRecapPopupOnly()
        // v4.9.6: the update pop-up waits for the same things (sheets, the recap story)
        checkUpdatePrompt()
        // 5.0.0 part 3: a cloud announcement goes last, once Home is quiet
        checkAnnouncement()
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
                var songs = if (recap.songs == 1) "1 ${getString(R.string.stats_song_one)}"
                else "${recap.songs} ${getString(R.string.stats_songs)}"
                // 5.7.0 u51: songs and tapes together
                if (recap.tapes.tapes > 0) songs += ", ${statsScreen.tapesText(recap.tapes.tapes)}"
                text = getString(R.string.recap_popup_line, StatsFormat.duration(recap.totalMs), songs)
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
        // 5.0.0 part 3: the cloud config can turn the quiet pop-up off (Settings' check still works)
        if (!vibe.isOn(VibeConfig.SWITCH_UPDATE_PROMPT)) return
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
        val locked = lockUpdate != null && update == lockUpdate
        openSheet { box ->
            sheetHeading(
                box,
                getString(R.string.update_title, update.version.toString()),
                if (locked) vibe.lock?.message ?: getString(R.string.vibe_lock_sub, app.updates.installedVersionLabel)
                else getString(R.string.update_sub, app.updates.installedVersionLabel),
                wrapSub = locked
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
                onUpdatePrimary(p.getTag(R.id.update_primary) as? UpdatePrimary ?: UpdatePrimary.DOWNLOAD)
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
                        // 5.4.0: keeps the download going on mobile data when Minim is in the background
                        com.minim.music.playback.TransferService.ensureRunning(this)
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
            // 5.4.0: what the button does lives under its own key. Until 5.3.2 it replaced the
            // view's colour tag ("fill:fg text:bg"), so the button was never coloured and
            // stayed a plain white pill that was hard to see on light themes.
            v.primary.setTag(R.id.update_primary, tag)
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
        // 5.0.0 part 3: the locked pop-up has no Later and no Hide (Cancel while downloading stays)
        if (lockUpdate != null && update == lockUpdate) {
            v.primary.visibility = if (v.primary.getTag(R.id.update_primary) == UpdatePrimary.HIDE) View.GONE else View.VISIBLE
            v.secondary.visibility = if (mine && st is UpdateFlowState.Downloading) View.VISIBLE else View.GONE
        } else {
            v.primary.visibility = View.VISIBLE
            v.secondary.visibility = View.VISIBLE
        }
        Painter.paint(v.root, pal)
        styleUpdatePrimary(v.primary)
    }

    /**
     * 5.4.0: the main button of the update pop-up (Download now, Install now, Try again...) is a
     * solid pill in the theme's text colour with the background colour on it, bold, so it stands
     * out on any album-art colour (the two colours are always far apart, see [Pal]).
     */
    private fun styleUpdatePrimary(b: TextView) {
        b.tag = "fill:fg text:bg"
        b.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_pill)?.mutate()
        b.backgroundTintList = android.content.res.ColorStateList.valueOf(pal.fg)
        b.setTextColor(pal.bg)
        b.alpha = 1f
        b.typeface = android.graphics.Typeface.create(b.typeface, android.graphics.Typeface.BOLD)
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
            // 5.4.0: keeps the download going on mobile data when Minim is in the background
            com.minim.music.playback.TransferService.ensureRunning(this)
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

    /** 5.10.0: Settings → Storage → Backup folder (and the fresh-install restore offer). */
    private val backupFolderPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri -> onBackupFolderPicked(uri) }

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

    /** 5.8.2: the top song behind the story, and whether the main player was playing before it. */
    private var recapMusic: com.minim.music.playback.RecapMusic? = null
    private var recapResumeMain = false

    /**
     * Opens the full-screen story for the current recap (or straight on its summary card). It
     * stays reachable from the Stats tab until a newer day replaces the recap.
     *
     * 5.8.2 (update 54): also works out the story's extra data (the day in hours, first and last
     * song, the top five, the personality) and each slide's colours from its own cover or photo.
     */
    private fun openRecapStory(atCard: Boolean) {
        if (recapStory != null || recapStoryOpening) return
        val recap = recapStats ?: return
        markRecapViewed(recap)
        recapStoryOpening = true
        lifecycleScope.launch {
            val dark = app.preferences.isDarkTheme
            val prep = try {
                prepareRecapStory(recap, dark)
            } catch (e: kotlinx.coroutines.CancellationException) {
                recapStoryOpening = false
                throw e
            } catch (_: Exception) {
                RecapPrep(Pal.build(0, dark), RecapDay.NONE, emptyMap(), null, com.minim.music.data.stats.RecapMore.NONE)
            }
            recapStoryOpening = false
            if (isFinishing || isDestroyed || recapStory != null) return@launch
            showRecapStory(recap, prep, atCard)
        }
    }

    private class RecapPrep(
        val pal: Pal,
        val day: RecapDay,
        val pals: Map<String, Pal>,
        val topTrack: Track?,
        /** 5.8.3 u55: first listens, hearted today, longest session, yesterday, streak, all-time. */
        val more: com.minim.music.data.stats.RecapMore
    )

    private suspend fun prepareRecapStory(recap: DayStats, dark: Boolean): RecapPrep {
        val artwork = app.artworkPipeline
        // Colours from the day's top song cover (black and white when there's no cover)
        val storyPal = try { RecapCard.palette(artwork, recap, dark) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { Pal.build(0, dark) }
        val db = app.database
        val day = try {
            val plays = db.getDayPlays(recap.day)
            val listens = db.getDayListens(recap.day)
            val tapeListens = try { db.getDayTapeListens(recap.day) } catch (_: Exception) { emptyList() }
            RecapDay.compute(recap, plays, listens, tapeListens)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { RecapDay.NONE }
        val more = try { recapMore(recap) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { com.minim.music.data.stats.RecapMore.NONE }
        // Each slide's own colours: from its cover or photo
        val urls = LinkedHashSet<String>()
        day.first?.thumbnailUrl?.let { urls.add(it) }
        more.firstListens.firstOrNull()?.thumbnailUrl?.let { urls.add(it) }
        more.hearted.firstOrNull()?.thumbnailUrl?.let { urls.add(it) }
        more.session?.firstThumb?.let { urls.add(it) }
        recap.topArtist?.name?.let { app.preferences.artistPhoto(it) }?.let { urls.add(it) }
        recap.tapes.topChannel?.let { statsChannelPhotos[it.key] }?.let { urls.add(it) }
        recap.tapes.topTape?.thumbnailUrl?.let { urls.add(it) }
        val pals = HashMap<String, Pal>()
        for (u in urls) {
            if (u.isBlank()) continue
            try {
                val seed = artwork.extractAccentForUrl(u)
                if (seed != 0) pals[u] = Pal.build(seed, dark)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        }
        // 5.8.3 u55: no blurred cover anywhere in the recap, only solid colours from the covers
        val top = recap.topSong?.let { s ->
            val saved = try { db.getTracksByIds(listOf(s.trackId)).firstOrNull() } catch (_: Exception) { null }
            saved ?: Track(s.trackId, s.title, s.artist, 0L, s.thumbnailUrl, "https://music.youtube.com/watch?v=${s.trackId}")
        }
        return RecapPrep(storyPal, day, pals, top, more)
    }

    /**
     * 5.8.3 u55: the numbers behind the new recap slides. Each part fails on its own, so one bad
     * query only hides its own slide.
     */
    private suspend fun recapMore(recap: DayStats): com.minim.music.data.stats.RecapMore {
        val db = app.database
        val dayKey = recap.day
        val start = Days.startMs(dayKey)
        val end = Days.endMs(dayKey)
        val plays = try { db.getDayPlays(dayKey) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
        val firstListens = try {
            val before = db.getTracksPlayedBefore(plays.map { it.trackId }, start)
            com.minim.music.data.stats.RecapMoreMath.firstListens(plays, before)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
        val hearted = try { db.getHeartedBetween(start, end) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
        val session = com.minim.music.data.stats.RecapMoreMath.sessionForSlide(
            com.minim.music.data.stats.RecapMoreMath.longestSession(plays)
        )
        val compare = try {
            com.minim.music.data.stats.RecapMoreMath.compare(recap, db.getDayStats(Days.previous(dayKey)))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val streak = try {
            // The same day streak Stats shows (songs and tapes, 10+ minutes a day), as of that day
            val from = Days.key(Days.date(dayKey).minusDays(DayStreak.MAX_DAYS.toLong()))
            val tapeTotals = try { db.getTapeDayTotals(from, dayKey) } catch (_: Exception) { emptyMap() }
            com.minim.music.data.stats.RecapMoreMath.streakAsOf(dayKey, DayStreak.merge(db.getDayTotals(from, dayKey), tapeTotals))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { 0 }
        val allTime = recap.topSong?.let { s ->
            try { db.countPlaysBefore(s.trackId, end) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { 0 }
        } ?: 0
        return com.minim.music.data.stats.RecapMore(firstListens, hearted, session, compare, streak, allTime)
    }

    private fun showRecapStory(recap: DayStats, prep: RecapPrep, atCard: Boolean) {
        val host = findViewById<ViewGroup>(android.R.id.content) ?: return
        if (::sheetRoot.isInitialized && sheetRoot.visibility == View.VISIBLE) closeSheet()
        hideKeyboard()
        val storyPal = prep.pal
        val photo = recap.topArtist?.name?.let { app.preferences.artistPhoto(it) }
        val persona = RecapPersonality.pick(recap, prep.day)
        val top = prep.topTrack
        val soundOn = app.preferences.recapSound
        var saving = false
        var savedPlaylist: String? = null
        val story = RecapStory(
            context = this,
            stats = recap,
            day = prep.day,
            pal = storyPal,
            pals = prep.pals,
            more = prep.more,
            artwork = app.artworkPipeline,
            artistPhotoUrl = photo,
            channelPhotoUrl = recap.tapes.topChannel?.let { statsChannelPhotos[it.key] },
            roast = RecapRoast.line(recap),
            persona = persona,
            startAtCard = atCard,
            soundOn = if (top != null) soundOn else null,
            tick = { haptic(it, HapticFeedbackConstants.CLOCK_TICK) },
            onBars = { light ->
                recapStoryLightBars = light
                updateSystemBars()
            },
            onShare = { bmp -> shareRecapCard(bmp, recap.day) },
            onSave = { bmp -> requestSaveRecapCard(bmp, recap.day) },
            onSavePlaylist = {
                val done = savedPlaylist
                if (done != null) {
                    recapStoryNote(getString(R.string.recap_playlist_saved, done))
                } else if (!saving) {
                    saving = true
                    lifecycleScope.launch {
                        val name = saveRecapPlaylist(recap, prep.day)
                        saving = false
                        if (name != null) savedPlaylist = name
                        recapStoryNote(if (name != null) getString(R.string.recap_playlist_saved, name) else getString(R.string.recap_playlist_failed))
                    }
                }
            },
            onPlayTop = {
                val t = top ?: return@RecapStory
                // The song for real: the story closes and it plays from the start, with its radio
                recapResumeMain = false
                stopRecapMusic()
                recapStory?.close()
                app.playbackController.playRadio(t, listOf(t))
            },
            onOpenArtist = {
                val name = recap.topArtist?.name ?: return@RecapStory
                recapStory?.close()
                openArtistByName(name)
            },
            onSound = { on ->
                app.preferences.recapSound = on
                recapMusic?.setMuted(!on)
            },
            onClosed = { onRecapStoryClosed() }
        )
        recapStory = story
        // Above everything (root's screens, sheets and notes), right up behind the system bars
        host.addView(story, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        startRecapMusic(top, soundOn)
        recapCardJob?.cancel()
        recapCardJob = lifecycleScope.launch {
            val bmp = try {
                RecapCard.build(
                    this@MainActivity, app.artworkPipeline, recap, storyPal,
                    if (recap.topArtist == null) recap.tapes.topChannel?.let { statsChannelPhotos[it.key] } else photo,
                    persona
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (bmp != null && recapStory === story) story.setCard(bmp)
        }
    }

    /** 5.8.2: pauses what was playing and plays the top song behind the story. */
    private fun startRecapMusic(top: Track?, soundOn: Boolean) {
        stopRecapMusic()
        if (top == null) return
        val pc = app.playbackController
        recapResumeMain = pc.uiState.value.isPlaying
        if (recapResumeMain) pc.pause()
        val m = try { com.minim.music.playback.RecapMusic(this) } catch (_: Exception) { null } ?: return
        recapMusic = m
        m.setMuted(!soundOn)
        m.play(top)
    }

    /** Fades the story's song out; whatever was playing before picks back up after it. */
    private fun stopRecapMusic() {
        val m = recapMusic ?: return
        recapMusic = null
        val resume = recapResumeMain
        recapResumeMain = false
        m.stop {
            val pc = app.playbackController
            if (resume && !pc.uiState.value.isPlaying) pc.togglePlayPause()
        }
    }

    /**
     * 5.8.2: "save as playlist": the day's top songs (best first, up to 25) as a new playlist in
     * the library. Returns its name, or null when it couldn't be made.
     */
    private suspend fun saveRecapPlaylist(recap: DayStats, day: RecapDay): String? {
        val ids = day.ranked.ifEmpty { listOfNotNull(recap.topSong?.trackId) }
        if (ids.isEmpty()) return null
        return try {
            val tracks = app.database.getTracksByIds(ids)
            if (tracks.isEmpty()) return null
            val base = RecapDay.playlistName(recap.day)
            var name = base
            var id = app.database.createPlaylist(name)
            var n = 2
            while (id == -1L && n <= 20) {
                name = "$base $n"
                id = app.database.createPlaylist(name)
                n++
            }
            if (id == -1L) return null
            app.database.addTracksToPlaylist(id, tracks)
            refreshData()
            name
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Swipe down or Back: back where they were, with the app's own status bar and colours. */
    private fun onRecapStoryClosed() {
        recapStory = null
        recapStoryLightBars = null
        recapCardJob?.cancel()
        recapCardJob = null
        pendingRecapSave = null
        stopRecapMusic()
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
        ColourMode.seedFor(paletteSeed, app.preferences.isDynamicColours, festivalSeed),
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
        themeNavClear = false // 5.5.2: updateSystemBars clears it again over the wallpaper
        // v4.9.3: a takeover on Home keeps its colours through a song change
        // (5.5.2: so does the festival wallpaper's Home)
        if (isTakeoverActive || themeHomePal != null) applyHomeOverrides()
        // 5.2.0: the Gemini chat crossfades with everything else (Painter covers its tagged views)
        if (::geminiChat.isInitialized) geminiChat.onPalette(p)
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
            if (::likedArtistAdapter.isInitialized) likedArtistAdapter.setPalette(p)
            if (::importAdapter.isInitialized) importAdapter.setPalette(p)
            searchAdapter.setPalette(p)
            queueAdapter.setPalette(p)
            if (::storiesScreen.isInitialized) storiesScreen.setPalette(p)
            styleListFilter()
            renderNpDownload()
            if (channelMode != null) renderChannel()
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
        sheetScrim.setOnClickListener { if (!isLockSheetOpen) closeSheet() }
        sheetPanel.listener = object : SheetPanel.Listener {
            override fun onDrag(fraction: Float) {
                sheetScrim.alpha = (1f - fraction).coerceIn(0f, 1f)
            }
            override fun onDragDismiss() {
                // 5.0.0 part 3: the locked update pop-up springs back instead
                if (isLockSheetOpen) onDragCancel() else closeSheet()
            }
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

    private fun sheetHeading(parent: android.widget.LinearLayout, title: String, sub: String? = null, wrapSub: Boolean = false) {
        val v = layoutInflater.inflate(R.layout.sheet_heading, parent, false)
        v.findViewById<TextView>(R.id.sheet_heading).text = title
        v.findViewById<TextView>(R.id.sheet_heading_sub).apply {
            if (sub.isNullOrBlank()) visibility = View.GONE else { text = sub; visibility = View.VISIBLE }
            // 5.0.0: a sentence or two that explains a choice (the account switch sheet)
            if (wrapSub) {
                isSingleLine = false
                maxLines = 4
                setLineSpacing(dp(3f), 1f)
            }
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
        // 5.7.0 u44: a stories upload's "artist" is its channel, which has no Artist page
        val hasArtist = MusicSourceAdapter.firstArtistName(artistLine).isNotBlank() &&
            !com.minim.music.data.stories.StoryTracks.isStory(track)
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
                        app.playbackController.playNext(track, byHand = true)
                        showNote(getString(R.string.note_playing_next))
                    }
                }
            }
            if (queueIndex == null && !isPlayingNow) {
                sheetAction(box, R.drawable.ic_queue_add, getString(R.string.sheet_add_to_queue)) {
                    closeSheet()
                    app.playbackController.addToQueue(track, byHand = true)
                    showNote(getString(R.string.note_added_queue))
                }
            }
            sheetAction(box, R.drawable.ic_playlist_add, getString(R.string.add_to_playlist)) {
                showAddToPlaylistSheet(track)
            }
            // 5.4.0: Save offline / Remove from offline (also stops a save that's running)
            val offlineOn = app.offlineStore.isSaved(track.id) || app.offline.isSaving(track.id)
            sheetAction(
                box,
                if (offlineOn) R.drawable.ic_offline_filled else R.drawable.ic_offline,
                getString(if (offlineOn) R.string.offline_remove else R.string.offline_save)
            ) {
                closeSheet()
                if (offlineOn) removeOffline(listOf(track.id)) else saveOffline(listOf(track))
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
            // 5.7.0 u47: a tape's channel page
            val tapeChannel = if (com.minim.music.data.stories.StoryTracks.isStory(track))
                com.minim.music.data.stories.ChannelVideos.channelFor(track, app.storiesStore.channels) else null
            if (tapeChannel != null && !(isArtistOnTop() && channelMode?.id == tapeChannel.id)) {
                sheetAction(box, R.drawable.ic_person, getString(R.string.sheet_go_to_channel)) {
                    closeSheet()
                    if (isQueueOpen) closeQueue()
                    if (isLyricsOpen) closeLyrics(animate = false)
                    if (isSettingsOpen) closeSettings()
                    if (isNowPlayingOpen) collapseNowPlaying()
                    openChannelPage(tapeChannel)
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
     * Save offline (5.4.1), Go to artist (the first one when there are several), Share and Sleep timer.
     */
    private fun showNowPlayingMenu() {
        val track = app.playbackController.uiState.value.currentTrack ?: return
        val isTape = com.minim.music.data.stories.StoryTracks.isStory(track)
        val hasArtist = !isTape && MusicSourceAdapter.firstArtistName(track.artist).isNotBlank()
        val channel = if (isTape) com.minim.music.data.stories.ChannelVideos.channelFor(track, app.storiesStore.channels) else null
        openSheet { box ->
            sheetSongHeader(box, track)
            sheetAction(box, R.drawable.ic_playlist_add, getString(R.string.add_to_playlist)) {
                showAddToPlaylistSheet(track)
            }
            nowPlayingOfflineRow(box, track)
            if (hasArtist) {
                sheetAction(box, R.drawable.ic_person, getString(R.string.sheet_go_to_artist)) {
                    closeSheet()
                    goToArtist(track.artist)
                }
            }
            if (channel != null) {
                sheetAction(box, R.drawable.ic_person, getString(R.string.sheet_go_to_channel)) {
                    closeSheet()
                    collapseNowPlaying()
                    openChannelPage(channel)
                }
            }
            sheetAction(box, R.drawable.ic_share, getString(R.string.sheet_share)) {
                closeSheet { shareTrack(track) }
            }
            // 5.7.0 u47: playback speed, tapes only (songs always play at 1x)
            if (isTape) {
                sheetAction(
                    box,
                    R.drawable.ic_speed,
                    getString(R.string.np_speed),
                    trailing = com.minim.music.data.stories.TapeSpeed.label(app.playbackController.playbackSpeed)
                ) {
                    showSpeedSheet(track)
                }
            }
            sheetAction(
                box,
                R.drawable.ic_bedtime,
                getString(R.string.settings_sleep),
                trailing = sleepTrailing()
            ) {
                showSleepTimerSheet()
            }
        }
    }

    /** 5.7.0 u47: 1x, 1.25x, 1.5x, 2x for the tape playing, remembered for its channel. */
    private fun showSpeedSheet(track: Track) {
        val now = app.playbackController.playbackSpeed
        openSheet { box ->
            sheetHeading(box, getString(R.string.np_speed), getString(R.string.np_speed_sub, track.artist))
            for (speed in com.minim.music.data.stories.TapeSpeed.OPTIONS) {
                val label = com.minim.music.data.stories.TapeSpeed.label(speed)
                sheetAction(
                    box,
                    R.drawable.ic_speed,
                    label,
                    trailingIcon = if (speed == now) R.drawable.ic_check else null
                ) {
                    closeSheet()
                    app.playbackController.setTapeSpeed(speed)
                    showNote(getString(R.string.note_speed, label, track.artist))
                }
            }
        }
    }

    /** What the sleep row in the menu says on its right: the time left, or Off. */
    private fun sleepTrailing(): String {
        val m = app.sleepTimerManager.mode.value
        if (m == com.minim.music.playback.SleepTimerManager.Mode.OFF) return getString(R.string.opt_off)
        return sleepRemainingLabel() ?: getString(R.string.opt_off)
    }

    /** 5.7.0 u47: the live countdown in Now Playing's top left; gone while the timer is off. */
    private fun renderSleepCountdown(mode: com.minim.music.playback.SleepTimerManager.Mode, ms: Long) {
        val box = findViewById<View>(R.id.np_sleep) ?: return
        val on = mode != com.minim.music.playback.SleepTimerManager.Mode.OFF
        val vis = if (on) View.VISIBLE else View.GONE
        if (box.visibility != vis) box.visibility = vis
        if (!on) return
        val text = com.minim.music.data.stories.SleepOptions.countdown(ms)
        val tv = findViewById<TextView>(R.id.np_sleep_text)
        if (tv.text?.toString() != text) tv.text = text
        box.contentDescription = getString(R.string.np_sleep_cd, text)
    }

    /** 5.7.0 u47: Now Playing's download button, tapes only: save, saving (ring), saved. */
    private fun renderNpDownload() {
        val btn = findViewById<View>(R.id.np_download) ?: return
        val track = app.playbackController.uiState.value.currentTrack
        val isTape = com.minim.music.data.stories.StoryTracks.isStory(track)
        val vis = if (isTape) View.VISIBLE else View.GONE
        if (btn.visibility != vis) btn.visibility = vis
        if (!isTape) return
        val l = SongMenuLogic.offlineRowLook(app.offlineStore.isSaved(track!!.id), app.offline.progress.value[track.id])
        val icon = findViewById<ImageView>(R.id.np_download_icon)
        val ring = findViewById<ProgressRing>(R.id.np_download_ring)
        val saved = app.offlineStore.isSaved(track.id)
        icon.setImageResource(if (saved) R.drawable.ic_offline_filled else R.drawable.ic_offline)
        if (l.ring != null) {
            ring.setColor(pal.fg)
            ring.setProgress(l.ring)
            ring.visibility = View.VISIBLE
            icon.alpha = 0.35f
        } else {
            ring.visibility = View.GONE
            icon.alpha = 1f
        }
        btn.contentDescription = getString(if (saved) R.string.np_saved_offline else R.string.np_download)
    }

    /** 5.4.1: the live Save offline row in the Now Playing menu (see [nowPlayingOfflineRow]). */
    private var npOfflineJob: kotlinx.coroutines.Job? = null

    /**
     * 5.4.1: "Save offline" in the Now Playing menu, right under Add to playlist. While the song is
     * saving it shows the small ring from the song lists, and once saving or saved it reads
     * "Remove from offline", which works like the long-press menu (it also stops a save that's
     * running). The row follows the saving live while the menu is open.
     */
    private fun nowPlayingOfflineRow(box: android.widget.LinearLayout, track: Track) {
        npOfflineJob?.cancel()
        fun look() = SongMenuLogic.offlineRowLook(
            app.offlineStore.isSaved(track.id), app.offline.progress.value[track.id]
        )
        val first = look()
        val row = sheetAction(box, first.icon, getString(first.label)) {
            // read it again: the save may have finished or started since the menu opened
            val on = look().ring != null || app.offlineStore.isSaved(track.id)
            closeSheet()
            if (on) removeOffline(listOf(track.id)) else saveOffline(listOf(track))
        }
        val icon = row.findViewById<ImageView>(R.id.action_icon)
        val label = row.findViewById<TextView>(R.id.action_label)
        val ring = row.findViewById<ProgressRing>(R.id.action_ring)
        fun render(l: SongMenuLogic.OfflineRowLook) {
            icon.setImageResource(l.icon)
            label.setText(l.label)
            if (l.ring != null) {
                ring.setProgress(l.ring)
                ring.visibility = View.VISIBLE
                row.contentDescription = getString(l.label) + ", " + getString(R.string.offline_saving_cd)
            } else {
                ring.visibility = View.GONE
                row.contentDescription = getString(l.label)
            }
        }
        render(first)
        val job = lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(app.offlineStore.saved, app.offline.progress) { saved, progress ->
                SongMenuLogic.offlineRowLook(saved.any { it.track.id == track.id }, progress[track.id])
            }.distinctUntilChanged().collect { l ->
                if (!isSheetOpen || row.parent == null) {
                    npOfflineJob?.cancel()
                    return@collect
                }
                render(l)
            }
        }
        npOfflineJob = job
        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { job.cancel() }
        })
    }

    /** "12:34" left on the sleep timer, or null when it's off. */
    private fun sleepRemainingLabel(): String? {
        val ms = app.sleepTimerManager.remainingTimeMs.value
        if (ms <= 0L) return null
        return ArtworkPipeline.formatDurationSec((ms + 999L) / 1000L)
    }

    /**
     * 5.7.0 u47: the sleep timer, from Now Playing's menu only: Off, 15 / 30 / 45 / 60 min, Custom,
     * and End of this song (End of this tape while a tape plays).
     */
    private fun showSleepTimerSheet() {
        val mgr = app.sleepTimerManager
        val mode = mgr.mode.value
        val left = if (mode == com.minim.music.playback.SleepTimerManager.Mode.OFF) null else sleepRemainingLabel()
        val isTape = com.minim.music.data.stories.StoryTracks.isStory(app.playbackController.uiState.value.currentTrack)
        val presets = com.minim.music.data.stories.SleepOptions.PRESET_MINUTES
        openSheet { box ->
            sheetHeading(box, getString(R.string.settings_sleep), left?.let { getString(R.string.sleep_status, it) })
            sheetAction(
                box, R.drawable.ic_bedtime, getString(R.string.opt_off),
                trailingIcon = if (mode == com.minim.music.playback.SleepTimerManager.Mode.OFF) R.drawable.ic_check else null
            ) {
                closeSheet()
                val wasOn = mgr.isRunning
                mgr.cancelTimer()
                if (wasOn) showNote(getString(R.string.note_sleep_off))
            }
            for (minutes in presets) {
                val label = getString(R.string.opt_minutes, minutes)
                val picked = mode == com.minim.music.playback.SleepTimerManager.Mode.MINUTES && mgr.minutesChosen == minutes
                sheetAction(box, R.drawable.ic_bedtime, label, trailingIcon = if (picked) R.drawable.ic_check else null) {
                    closeSheet()
                    mgr.startTimer(minutes)
                    showNote(getString(R.string.note_sleep_set, label))
                }
            }
            val customPicked = mode == com.minim.music.playback.SleepTimerManager.Mode.MINUTES && mgr.minutesChosen !in presets
            sheetAction(
                box, R.drawable.ic_bedtime, getString(R.string.sleep_custom),
                trailing = if (customPicked) getString(R.string.opt_minutes, mgr.minutesChosen) else null,
                trailingIcon = if (customPicked) R.drawable.ic_check else null
            ) {
                showCustomSleepSheet()
            }
            sheetAction(
                box, R.drawable.ic_bedtime, getString(if (isTape) R.string.sleep_end_tape else R.string.sleep_end_song),
                trailingIcon = if (mode == com.minim.music.playback.SleepTimerManager.Mode.END_OF_TRACK) R.drawable.ic_check else null
            ) {
                closeSheet()
                mgr.startEndOfTrack()
                showNote(getString(if (isTape) R.string.note_sleep_end_tape else R.string.note_sleep_end_song))
            }
        }
    }

    /** 5.7.0 u47: Custom: type the minutes (1 to 720). */
    private fun showCustomSleepSheet() {
        openSheet { box ->
            sheetHeading(box, getString(R.string.sleep_custom), getString(R.string.sleep_custom_sub))
            val v = layoutInflater.inflate(R.layout.sheet_create_playlist, box, false)
            val input = v.findViewById<EditText>(R.id.sheet_name_input)
            val error = v.findViewById<TextView>(R.id.sheet_name_error)
            val ok = v.findViewById<TextView>(R.id.sheet_create)
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            input.filters = arrayOf(android.text.InputFilter.LengthFilter(3))
            input.hint = getString(R.string.sleep_custom_hint)
            ok.text = getString(R.string.sleep_custom_ok)
            ok.isEnabled = true
            ok.alpha = 1f
            fun submit() {
                if (sheetClosing) return
                val minutes = com.minim.music.data.stories.SleepOptions.parseCustom(input.text?.toString())
                if (minutes == null) {
                    error.setText(R.string.sleep_custom_bad)
                    error.visibility = View.VISIBLE
                    return
                }
                haptic(ok)
                hideKeyboard()
                closeSheet()
                app.sleepTimerManager.startTimer(minutes)
                showNote(getString(R.string.note_sleep_set, getString(R.string.opt_minutes, minutes)))
            }
            ok.setOnClickListener { submit() }
            input.setOnEditorActionListener { _, _, _ -> submit(); true }
            box.addView(v)
            input.post {
                input.requestFocus()
                showKeyboard(input)
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
        // 5.9.0: Play next on a song already in the queue is still you queueing it by hand
        state.queue.getOrNull(index)?.let { app.playbackController.noteQueuedByHand(it) }
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
    // ==========================================================================================
    // 5.4.0: Save offline. app.offline saves 3 songs at a time (each in 4 ranged parts) into the
    // app's private files folder; the rows get a faint mark, the tiles a ring while saving.
    // ==========================================================================================

    /** Queues [tracks] (ones not saved yet) and keeps the save going in the background. */
    private fun saveOffline(tracks: List<Track>, groupKey: String? = null, groupName: String = "") {
        if (tracks.isEmpty()) {
            showNote(getString(R.string.offline_nothing_to_save))
            return
        }
        val queued = app.offline.save(tracks, groupKey, groupName)
        if (queued == 0) {
            // Everything was saved already, or is saving right now
            if (tracks.all { app.offlineStore.isSaved(it.id) }) showNote(getString(R.string.offline_note_already))
            return
        }
        com.minim.music.playback.TransferService.ensureRunning(this)
        showNote(
            if (queued == 1) getString(R.string.offline_note_saving_one)
            else getString(R.string.offline_note_saving, queued)
        )
    }

    /** Stops any save of these songs and deletes their saved files. */
    private fun removeOffline(ids: Collection<String>) {
        val n = ids.distinct().count { app.offlineStore.isSaved(it) || app.offline.isSaving(it) }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { app.offline.remove(ids.distinct()) }
            showNote(
                if (n <= 1) getString(R.string.offline_note_removed_one)
                else getString(R.string.offline_note_removed, n)
            )
        }
    }

    /** Save offline (with "12 left" when some are saved already) and Remove from offline. */
    private fun offlineGroupActions(box: android.widget.LinearLayout, tracks: List<Track>, key: String, name: String) {
        val ids = tracks.map { it.id }.distinct()
        val on = ids.count { app.offlineStore.isSaved(it) || app.offline.isSaving(it) }
        val left = ids.size - on
        if (left > 0 || ids.isEmpty()) {
            sheetAction(
                box, R.drawable.ic_offline, getString(R.string.offline_save),
                trailing = if (on > 0) getString(R.string.offline_left, left) else null
            ) {
                closeSheet()
                saveOffline(tracks, key, name)
            }
        }
        if (on > 0) {
            sheetAction(box, R.drawable.ic_offline_filled, getString(R.string.offline_remove)) {
                closeSheet()
                removeOffline(ids)
            }
        }
    }

    /** Long-press on a playlist tile: Save offline / Remove from offline, then Delete playlist. */
    private fun showPlaylistOptions(playlist: Playlist) {
        lifecycleScope.launch {
            val tracks = app.database.getPlaylistTracks(playlist.id)
            openSheet { box ->
                sheetHeading(box, playlist.name, songCount(tracks.size))
                offlineGroupActions(box, tracks, "pl:${playlist.id}", playlist.name)
                // Delete keeps its own "Are you sure?" step
                sheetAction(box, R.drawable.ic_delete, getString(R.string.delete_playlist)) {
                    confirmDeletePlaylist(playlist)
                }
            }
        }
    }

    /** Long-press on Liked Songs: Save offline / Remove from offline. */
    private fun showLikedOptions() {
        lifecycleScope.launch {
            val tracks = app.database.getLikedTracks()
            val name = getString(R.string.liked_songs)
            openSheet { box ->
                sheetHeading(box, name, songCount(tracks.size))
                offlineGroupActions(box, tracks, "liked", name)
            }
        }
    }

    /** Short messages when a playlist finishes saving, or a song on its own fails. */
    private fun onOfflineEvent(e: com.minim.music.data.offline.OfflineEvent) {
        when (e) {
            is com.minim.music.data.offline.OfflineEvent.GroupFinished -> showNote(
                when {
                    e.failed == 0 -> getString(R.string.offline_group_done, e.name)
                    e.saved == 0 -> getString(R.string.offline_group_failed, e.name)
                    else -> getString(R.string.offline_group_done_some, e.name, e.failed)
                }
            )
            is com.minim.music.data.offline.OfflineEvent.SongFailed ->
                if (!e.inGroup) showNote(getString(R.string.offline_song_failed, e.track.title))
        }
    }

    /** Settings: "Offline songs · 412 MB" and Remove all (hidden when nothing is saved). */
    private fun renderOfflineStorage() {
        if (!::screenSettings.isInitialized) return
        val label = findViewById<TextView>(R.id.set_offline_storage)
        val removeAll = findViewById<View>(R.id.set_offline_remove_all)
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { app.offlineStore.bytesUsed() }
            val any = app.offlineStore.saved.value.isNotEmpty() || app.offline.busy
            label.text = if (app.offlineStore.saved.value.isEmpty()) getString(R.string.offline_size_none)
            else android.text.format.Formatter.formatShortFileSize(this@MainActivity, bytes)
            removeAll.visibility = if (any) View.VISIBLE else View.GONE
        }
    }

    private fun confirmRemoveAllOffline() {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { app.offlineStore.bytesUsed() }
            openSheet { box ->
                sheetHeading(
                    box, getString(R.string.offline_remove_all_title),
                    getString(R.string.offline_remove_all_sub, android.text.format.Formatter.formatShortFileSize(this@MainActivity, bytes)),
                    wrapSub = true
                )
                val v = layoutInflater.inflate(R.layout.sheet_confirm, box, false)
                v.findViewById<TextView>(R.id.sheet_confirm_cancel).setOnClickListener { closeSheet() }
                v.findViewById<TextView>(R.id.sheet_confirm_ok).apply {
                    setText(R.string.offline_remove_all_ok)
                    setOnClickListener {
                        if (sheetClosing) return@setOnClickListener
                        haptic(it)
                        closeSheet()
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { app.offline.removeAll() }
                            renderOfflineStorage()
                            showNote(getString(R.string.offline_note_removed_all))
                        }
                    }
                }
                box.addView(v)
            }
        }
    }

    /**
     * 5.10.0: Settings → Clear listening history. Records Fermata's history clear (merged by every
     * phone, so older history goes everywhere), deletes the cleared days from Firestore for a
     * signed-in account, and backs up at once replacing this phone's previous backup too.
     */
    private fun confirmClearHistory() {
        openSheet { box ->
            sheetHeading(box, getString(R.string.history_clear_title), getString(R.string.history_clear_sub), wrapSub = true)
            val v = layoutInflater.inflate(R.layout.sheet_confirm, box, false)
            v.findViewById<TextView>(R.id.sheet_confirm_cancel).setOnClickListener { closeSheet() }
            v.findViewById<TextView>(R.id.sheet_confirm_ok).apply {
                setText(R.string.history_clear_ok)
                setOnClickListener {
                    if (sheetClosing) return@setOnClickListener
                    haptic(it)
                    closeSheet()
                    lifecycleScope.launch {
                        // a failed clear is rolled back as a whole: say so, never crash
                        val cleared = try {
                            app.database.clearHistory()
                            true
                        } catch (c: kotlinx.coroutines.CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            android.util.Log.w("MainActivity", "clear history failed", e)
                            false
                        }
                        refreshData()
                        showNote(getString(if (cleared) R.string.history_cleared else R.string.history_clear_failed))
                    }
                }
            }
            box.addView(v)
        }
    }

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
    // 5.3.0: playlist import. The "+" tile offers New Playlist and Import Playlist; the import
    // runs in the background (app.importer) with a slim card on Library, then a review screen.
    // ==========================================================================================

    private lateinit var screenImport: View
    private lateinit var importTitle: TextView
    private lateinit var importSubtitle: TextView
    private lateinit var importNote: TextView
    private lateinit var importSave: TextView
    private lateinit var importAdapter: com.minim.music.ui.adapter.ImportReviewAdapter
    private lateinit var importCard: View
    private lateinit var importCardFill: View
    private lateinit var importCardText: TextView
    private var importSaving = false
    /** A shared link that arrived while the welcome screens were up; opened once they're done. */
    private var pendingImportText: String? = null
    private val isImportReviewOpen get() = ::screenImport.isInitialized && screenImport.visibility == View.VISIBLE

    private fun setupImport() {
        screenImport = findViewById(R.id.screen_import)
        importTitle = findViewById(R.id.import_title)
        importSubtitle = findViewById(R.id.import_subtitle)
        importNote = findViewById(R.id.import_note)
        importSave = findViewById(R.id.import_save)
        importCard = findViewById(R.id.library_import_card)
        importCardFill = findViewById(R.id.library_import_fill)
        importCardText = findViewById(R.id.library_import_text)
        importAdapter = com.minim.music.ui.adapter.ImportReviewAdapter(
            artwork = app.artworkPipeline,
            onRow = { i -> onImportRowTapped(i) },
            onMark = { i -> haptic(importSave, HapticFeedbackConstants.CLOCK_TICK); app.importer.toggle(i) }
        )
        findViewById<RecyclerView>(R.id.import_tracks).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = importAdapter
            itemAnimator = null // a tick changes in place, no flashing row
        }
        importSave.setOnClickListener { saveImport() }
        importCard.setOnClickListener { onImportCardTapped() }
        findViewById<View>(R.id.library_import_close).setOnClickListener {
            haptic(it)
            val wasBusy = app.importer.isBusy
            if (wasBusy) app.importer.cancel() else app.importer.clear()
            if (isImportReviewOpen) closeImportReview()
            if (wasBusy) showNote(getString(R.string.import_cancelled))
        }
    }

    /** The "+" tile: New Playlist or Import Playlist. */
    private fun showAddPlaylistSheet() {
        openSheet { box ->
            sheetHeading(box, getString(R.string.sheet_add_playlist))
            sheetAction(box, R.drawable.ic_add, getString(R.string.new_playlist)) { showCreatePlaylistSheet(null) }
            sheetAction(box, R.drawable.ic_import, getString(R.string.sheet_import_playlist)) { showImportSheet(null) }
        }
    }

    /** One paste box; the line under it says what Minim found. [prefill] comes from Share. */
    private fun showImportSheet(prefill: String?) {
        if (isLockSheetOpen) return
        if (isWelcomeOpen) { pendingImportText = prefill; return }
        openSheet { box ->
            sheetHeading(box, getString(R.string.sheet_import_playlist), getString(R.string.sheet_import_playlist_sub))
            val v = layoutInflater.inflate(R.layout.sheet_import_playlist, box, false)
            val input = v.findViewById<EditText>(R.id.import_input)
            val detected = v.findViewById<TextView>(R.id.import_detected)
            val go = v.findViewById<TextView>(R.id.import_go)
            var found: com.minim.music.data.importer.ImportInput = com.minim.music.data.importer.ImportInput.Empty
            var ready = false
            fun setReady(on: Boolean) {
                if (on == ready) return
                ready = on
                go.isEnabled = on
                val a = if (on) 1f else 0.35f
                if (animationsOn()) go.animate().alpha(a).setDuration(180L).start() else go.alpha = a
            }
            fun onText(text: String) {
                // 5.3.1: reading the box can never crash the app; if it fails, Import stays greyed out
                found = try {
                    com.minim.music.data.importer.ImportDetect.detect(text)
                } catch (t: Throwable) {
                    com.minim.music.data.importer.ImportInput.Empty
                }
                val line = importDetectedLine(found)
                if (line == null) {
                    detected.visibility = View.GONE
                } else {
                    detected.text = line
                    detected.visibility = View.VISIBLE
                }
                setReady(found !is com.minim.music.data.importer.ImportInput.Empty &&
                    found !is com.minim.music.data.importer.ImportInput.Unsupported)
            }
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = onText(s?.toString().orEmpty())
            })
            go.setOnClickListener {
                if (!ready || sheetClosing) return@setOnClickListener
                haptic(it)
                startImport(found)
            }
            box.addView(v)
            if (!prefill.isNullOrBlank()) {
                input.setText(prefill)
                input.setSelection(input.text.length)
            } else {
                input.requestFocus()
                showKeyboard(input)
            }
        }
    }

    /** "Spotify playlist", "List of 12 songs", a friendly no, or null for an empty box. */
    private fun importDetectedLine(found: com.minim.music.data.importer.ImportInput): String? = when (found) {
        is com.minim.music.data.importer.ImportInput.YouTube ->
            when (com.minim.music.data.importer.ImportNames.youTubeKind(found.listId)) {
                "album" -> getString(R.string.import_detect_youtube_album)
                "mix" -> getString(R.string.import_detect_youtube_mix)
                else -> getString(R.string.import_detect_youtube)
            }
        is com.minim.music.data.importer.ImportInput.Spotify -> getString(
            if (found.kind == "album") R.string.import_detect_spotify_album else R.string.import_detect_spotify
        )
        is com.minim.music.data.importer.ImportInput.SpotifyShort -> getString(R.string.import_detect_short)
        is com.minim.music.data.importer.ImportInput.Lines -> getString(R.string.import_detect_lines, songCount(found.songs.size))
        is com.minim.music.data.importer.ImportInput.Unsupported -> com.minim.music.data.importer.ImportMessages.unsupported(found.why)
        com.minim.music.data.importer.ImportInput.Empty -> null
    }

    private fun startImport(found: com.minim.music.data.importer.ImportInput) {
        if (app.importer.isBusy) {
            showNote(getString(R.string.import_busy))
            return
        }
        if (isImportReviewOpen) closeImportReview()
        if (!app.importer.start(found)) {
            showNote(getString(R.string.import_busy))
            return
        }
        closeSheet {
            showLibraryForImport()
            showNote(getString(R.string.import_started))
        }
    }

    /** Brings Library (where the progress card is) to the front, unless the player covers the app. */
    private fun showLibraryForImport() {
        if (isNowPlayingOpen || isLyricsOpen || isQueueOpen || isSettingsOpen) return
        if (isSearchOpen) closeSearch()
        if (isListOpen) closeList()
        if (isShelfOpen) closeShelf(backToSearch = false)
        if (isArtistOpen) {
            artistReturn = null
            closeArtist()
        }
        if (tab != Tab.LIBRARY) showTab(Tab.LIBRARY)
    }

    /** The slim card on Library: reading, "Importing Chill Mix · 84/200", ready or failed. */
    private fun renderImportState(state: com.minim.music.data.importer.ImportState?) {
        if (!::importCard.isInitialized) return
        val fraction: Float
        when (state) {
            null -> {
                importCard.visibility = View.GONE
                if (isImportReviewOpen) closeImportReview()
                return
            }
            is com.minim.music.data.importer.ImportState.Reading -> {
                importCardText.text = if (state.read <= 0) getString(R.string.import_reading, state.name)
                else getString(R.string.import_reading_count, state.name, state.read)
                fraction = 0f
            }
            is com.minim.music.data.importer.ImportState.Matching -> {
                importCardText.text = getString(R.string.import_progress, state.name, state.done, state.total)
                fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
            }
            is com.minim.music.data.importer.ImportState.Ready -> {
                importCardText.text = getString(R.string.import_ready, state.name)
                fraction = 1f
                if (isImportReviewOpen) renderImportReview(state)
            }
            is com.minim.music.data.importer.ImportState.Failed -> {
                importCardText.text = getString(R.string.import_failed, state.name)
                fraction = 0f
                if (isImportReviewOpen) closeImportReview()
            }
        }
        importCard.contentDescription = importCardText.text
        if (importCard.visibility != View.VISIBLE) {
            importCard.visibility = View.VISIBLE
            Painter.paint(importCard, pal)
        }
        setImportFill(fraction)
    }

    private fun setImportFill(fraction: Float) {
        val w = importCard.width
        if (w <= 0) {
            importCard.post { if (importCard.width > 0) setImportFill(fraction) }
            return
        }
        val target = (w * fraction.coerceIn(0f, 1f)).toInt()
        val lp = importCardFill.layoutParams
        if (lp.width != target) {
            lp.width = target
            importCardFill.layoutParams = lp
        }
    }

    private fun onImportCardTapped() {
        when (val s = app.importer.state.value) {
            is com.minim.music.data.importer.ImportState.Ready -> openImportReview()
            is com.minim.music.data.importer.ImportState.Failed -> showImportFailure(s)
            null -> Unit
            else -> showNote(getString(R.string.import_still))
        }
    }

    private fun showImportFailure(s: com.minim.music.data.importer.ImportState.Failed) {
        openSheet { box ->
            sheetHeading(box, getString(R.string.import_failed_title, s.name), s.message, wrapSub = true)
            sheetAction(box, R.drawable.ic_import, getString(R.string.import_try_again)) {
                app.importer.clear()
                showImportSheet(null)
            }
        }
    }

    private fun openImportReview() {
        val ready = app.importer.state.value as? com.minim.music.data.importer.ImportState.Ready ?: return
        importSaving = false
        importAdapter.setPalette(pal)
        renderImportReview(ready)
        findViewById<RecyclerView>(R.id.import_tracks).scrollToPosition(0)
        screenImport.animate().cancel()
        screenImport.visibility = View.VISIBLE
        Painter.paint(screenImport, pal)
        if (animationsOn()) {
            screenImport.alpha = 0f
            screenImport.translationY = dp(24f)
            screenImport.animate().alpha(1f).translationY(0f).setDuration(240L)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        } else {
            screenImport.alpha = 1f
            screenImport.translationY = 0f
        }
        // the mini player hides while the review is open
        updateChrome()
    }

    private fun closeImportReview() {
        if (!::screenImport.isInitialized || screenImport.visibility != View.VISIBLE) return
        screenImport.animate().cancel()
        screenImport.alpha = 1f
        screenImport.translationY = 0f
        screenImport.visibility = View.GONE
        updateChrome()
    }

    private fun renderImportReview(ready: com.minim.music.data.importer.ImportState.Ready) {
        importTitle.text = ready.name
        // 5.4.0: the full logo where the playlist came from (pasted lists keep their words)
        val mark = SourceLogos.wordmark(ready.origin)
        val from = screenImport.findViewById<TextView>(R.id.import_from)
        val logo = screenImport.findViewById<ImageView>(R.id.import_source_logo)
        val rest = listOfNotNull(
            songCount(ready.items.size),
            if (ready.doubtful > 0) getString(R.string.import_to_check, ready.doubtful) else null
        )
        if (mark != null) {
            from.visibility = View.VISIBLE
            logo.visibility = View.VISIBLE
            SourceLogos.show(logo, mark, from)
            importSubtitle.text = rest.joinToString(" · ", prefix = "· ")
            importSubtitle.contentDescription =
                (listOf(getString(R.string.list_from_desc_prefix, SourceLogos.name(ready.origin))) + rest).joinToString(", ")
        } else {
            from.visibility = View.GONE
            logo.visibility = View.GONE
            importSubtitle.text = (listOf(ready.origin.label) + rest).joinToString(" · ")
            importSubtitle.contentDescription = null
        }
        if (ready.note.isNullOrBlank()) {
            importNote.visibility = View.GONE
        } else {
            importNote.text = ready.note
            importNote.visibility = View.VISIBLE
        }
        importAdapter.submit(ready.items)
        val n = ready.included
        importSave.text = getString(R.string.import_save, n)
        importSave.alpha = if (n > 0) 1f else 0.35f
    }

    /** A doubtful or missing song opens its other choices; a sure one just ticks / unticks. */
    private fun onImportRowTapped(index: Int) {
        val ready = app.importer.state.value as? com.minim.music.data.importer.ImportState.Ready ?: return
        val item = ready.items.getOrNull(index) ?: return
        if (item.source == null || (item.confidence == com.minim.music.data.importer.Confidence.SURE && item.chosen != null)) {
            haptic(importSave, HapticFeedbackConstants.CLOCK_TICK)
            app.importer.toggle(index)
        } else {
            showImportOptions(index, ready)
        }
    }

    private fun showImportOptions(index: Int, ready: com.minim.music.data.importer.ImportState.Ready) {
        val item = ready.items.getOrNull(index) ?: return
        val src = item.source ?: return
        val where = when (ready.origin) {
            com.minim.music.data.importer.ImportOrigin.SPOTIFY -> getString(R.string.import_source_spotify)
            com.minim.music.data.importer.ImportOrigin.YOUTUBE -> "YouTube"
            com.minim.music.data.importer.ImportOrigin.YOUTUBE_MUSIC -> "YouTube Music"
            com.minim.music.data.importer.ImportOrigin.TEXT -> getString(R.string.import_source_list)
        }
        val was = listOf(src.title, src.artists.joinToString(", ")).filter { it.isNotBlank() }.joinToString(" – ")
        openSheet { box ->
            val sub = getString(R.string.import_options_was, where, was) +
                if (item.options.isEmpty()) "\n" + getString(R.string.import_none_found) else ""
            sheetHeading(box, getString(R.string.import_options_title), sub, wrapSub = true)
            for (opt in item.options) {
                val t = opt.track
                val row = layoutInflater.inflate(R.layout.item_import_row, box, false)
                row.setPadding(0, 0, 0, row.paddingBottom)
                val card = row.findViewById<View>(R.id.track_card).apply { clipToOutline = true }
                val art = row.findViewById<ImageView>(R.id.track_art).apply { clipToOutline = true }
                row.findViewById<TextView>(R.id.track_title).text = t.title
                row.findViewById<TextView>(R.id.track_artist).text =
                    listOf(t.artist, com.minim.music.data.importer.ImportNames.length(t.durationSec))
                        .filter { it.isNotBlank() }.joinToString(" · ")
                val mark = row.findViewById<ImageView>(R.id.import_mark)
                val isChosen = t.id == item.chosen?.id && item.included
                mark.setImageResource(R.drawable.ic_check)
                mark.visibility = if (isChosen) View.VISIBLE else View.INVISIBLE
                mark.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                app.artworkPipeline.loadInto(art, t.thumbnailUrl, dp(44f).toInt())
                card.setOnClickListener { v ->
                    if (sheetClosing) return@setOnClickListener
                    haptic(v)
                    app.importer.choose(index, t)
                    closeSheet()
                }
                box.addView(row)
            }
            if (item.included && item.chosen != null) {
                sheetAction(box, R.drawable.ic_close, getString(R.string.import_remove), outlined = true) {
                    app.importer.remove(index)
                    closeSheet()
                }
            } else if (item.chosen != null) {
                sheetAction(box, R.drawable.ic_check, getString(R.string.import_keep), outlined = true) {
                    app.importer.toggle(index)
                    closeSheet()
                }
            }
        }
    }

    /** Saves the ticked songs as a normal playlist (with "from Spotify" under its name) and opens it. */
    private fun saveImport() {
        if (importSaving) return
        val ready = app.importer.state.value as? com.minim.music.data.importer.ImportState.Ready ?: return
        val tracks = app.importer.tracksToSave()
        if (tracks.isEmpty()) {
            showNote(getString(R.string.import_nothing))
            return
        }
        importSaving = true
        haptic(importSave)
        lifecycleScope.launch {
            try {
                var id = -1L
                var name = ready.name
                for (n in 1..50) {
                    name = com.minim.music.data.importer.ImportNames.candidate(ready.name, n)
                    id = app.database.createPlaylist(name, ready.origin.code)
                    if (id != -1L) break
                }
                if (id == -1L) {
                    showNote(getString(R.string.import_failed, ready.name))
                    return@launch
                }
                app.database.addTracksToPlaylist(id, tracks)
                app.importer.clear()
                closeImportReview()
                refreshData()
                openList(id, name, ready.origin.code)
                showNote(getString(R.string.import_saved, name))
            } finally {
                importSaving = false
            }
        }
    }

    /** Share -> Minim (ShareImportActivity): the Import sheet with the link filled in. */
    private fun handleImportIntent(intent: Intent?) {
        if (intent?.action != ShareImportActivity.ACTION_IMPORT) return
        val text = intent.getStringExtra(ShareImportActivity.EXTRA_IMPORT_TEXT)
        // handled once: a rotation or a return from the background doesn't open it again
        intent.action = null
        intent.removeExtra(ShareImportActivity.EXTRA_IMPORT_TEXT)
        if (text.isNullOrBlank()) return
        root.post {
            if (isGeminiOpen) geminiChat.close()
            showImportSheet(text)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (showingCrashReport) return
        handleImportIntent(intent)
    }

    // ==========================================================================================
    // v5.0: Account (optional Google sign-in). Minim works fully without it.
    // ==========================================================================================

    private var accountBusy = false
    private var avatarUrl: String? = null
    private var avatarJob: Job? = null

    // ==========================================================================================
    // 5.0.0 part 3: festivals, announcements, switches and the update lock, from the cloud
    // config (Firestore config/app, read by app.vibe). Missing or unreadable config: none of it.
    // ==========================================================================================

    private var vibe: VibeConfig = VibeConfig.DEFAULT
    private var festival: FestivalEvent? = null
    /** Today's festival colour, used while nothing is playing (Dynamic colours only); 0 for none. */
    private var festivalSeed = 0
    private var announcementPending: Runnable? = null
    /** The update the cloud config says this version must install first (null: not locked). */
    private var lockUpdate: AvailableUpdate? = null
    private var lockCheckJob: Job? = null
    /** The lock already checked against GitHub this session (and the answer was "no lock"). */
    private var lockCheckedFor: UpdateLock? = null
    private lateinit var homeBanner: View
    private lateinit var homeBannerText: TextView

    private fun installedVersion() =
        InstalledVersion.of(app.updates.installedVersionName, app.updates.installedVersionCode)

    private fun setupVibe() {
        homeBanner = findViewById(R.id.home_banner)
        homeBannerText = findViewById(R.id.home_banner_text)
        homeBanner.setOnClickListener { v ->
            val link = festival?.link ?: return@setOnClickListener
            haptic(v)
            openVibeLink(link)
        }
        findViewById<View>(R.id.home_banner_close).setOnClickListener { v ->
            val e = festival ?: return@setOnClickListener
            haptic(v)
            app.preferences.dismissBanner(bannerKey(e))
            renderFestival()
        }
        PressScale.apply(homeBanner)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.vibe.config.collect { onVibeConfig(it) }
            }
        }
    }

    private fun onVibeConfig(cfg: VibeConfig) {
        val lockChanged = cfg.lock != vibe.lock
        vibe = cfg
        if (lockChanged) {
            lockCheckedFor = null
            if (!cfg.lockApplies(installedVersion())) lockUpdate = null
        }
        renderFestival()
        checkRecapPopup()
    }

    /** Today's festival: the banner under the search pill and the colour while nothing plays. */
    private fun renderFestival() {
        if (!::homeBanner.isInitialized) return
        val e = vibe.activeEvent(java.util.Calendar.getInstance())
        festival = e
        val text = e?.banner
        if (e != null && text != null && !app.preferences.isBannerDismissed(bannerKey(e))) {
            homeBannerText.text = text
            homeBanner.contentDescription = text
            homeBanner.isClickable = e.link != null
            homeBanner.visibility = View.VISIBLE
        } else {
            homeBanner.visibility = View.GONE
        }
        val seed = e?.accent ?: 0
        if (seed != festivalSeed) {
            festivalSeed = seed
            animatePaletteTo(appPal())
        }
    }

    /** A closed banner stays closed for that festival this year (it can come back next year). */
    private fun bannerKey(e: FestivalEvent) = "${e.id}:${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}"

    private fun openVibeLink(link: String) {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)).addCategory(android.content.Intent.CATEGORY_BROWSABLE))
        } catch (_: Exception) {
            showNote(getString(R.string.vibe_link_failed))
        }
    }

    /**
     * The update lock. When the config's update_lock covers this version, Minim checks GitHub once;
     * only when a newer release really is there does the update pop-up open and refuse to close.
     * No newer release (a mistake in the config), or GitHub unreachable: no lock this time.
     * Returns true while the lock is on (other pop-ups then wait).
     */
    private fun checkVibeLock(): Boolean {
        val lock = vibe.lock
        if (lock == null || !vibe.lockApplies(installedVersion())) {
            lockUpdate = null
            return false
        }
        val u = lockUpdate
        if (u == null) {
            if (lockCheckedFor == lock || lockCheckJob?.isActive == true) return false
            lockCheckJob = lifecycleScope.launch {
                val result = try { app.updates.check() } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) { null }
                if (result is UpdateCheckResult.Available && vibe.lock == lock) {
                    lockUpdate = result.update
                    app.updateGate.onManualCheck(result)
                    checkRecapPopup()
                } else if (result !is UpdateCheckResult.Failed && result != null) {
                    // GitHub has nothing newer: never lock anyone out over it
                    lockCheckedFor = lock
                } // offline or failed: tried again on the next app open
            }
            return false
        }
        if (isActivityResumed && !isWelcomeOpen && app.preferences.welcomeDone && !isSheetOpen &&
            sheetRoot.visibility != View.VISIBLE && recapStory == null && !recapStoryOpening
        ) {
            updatePromptPending?.let { root.removeCallbacks(it) }
            updatePromptPending = null
            showUpdatePopup(u)
        }
        return true
    }

    /** The update pop-up is open for the locked update: it can't be swiped, tapped or backed away. */
    private val isLockSheetOpen: Boolean
        get() = lockUpdate != null && updateSheet?.root?.parent != null && updateSheetFor == lockUpdate

    /** A one-time announcement sheet, when Home is quiet (after any update or recap pop-up). */
    private fun checkAnnouncement() {
        if (!::sheetRoot.isInitialized || announcementPending != null) return
        if (!announcementQuiet() || nextAnnouncement() == null) return
        val r = Runnable {
            announcementPending = null
            val a = nextAnnouncement()
            if (a != null && announcementQuiet()) showAnnouncement(a)
        }
        announcementPending = r
        root.postDelayed(r, ANNOUNCEMENT_DELAY_MS)
    }

    private fun nextAnnouncement(): Announcement? =
        vibe.nextAnnouncement(installedVersion(), java.util.Calendar.getInstance(), app.preferences.dismissedAnnouncements)

    private fun announcementQuiet(): Boolean {
        val onHome = tab == Tab.HOME && !isSearchOpen && !isListOpen && !isShelfOpen && !isArtistOpen &&
            !isLyricsOpen && !isQueueOpen && !isSettingsOpen && !isNowPlayingOpen
        return onHome && isActivityResumed && !isWelcomeOpen && app.preferences.welcomeDone &&
            sheetRoot.visibility != View.VISIBLE && recapStory == null && !recapStoryOpening &&
            recapPopupPending == null && updatePromptPending == null && lockUpdate == null
    }

    private fun showAnnouncement(a: Announcement) {
        // Shown once: marked as soon as it's on screen
        app.preferences.dismissAnnouncement(a.id)
        openSheet { box ->
            sheetHeading(box, a.title)
            if (a.text.isNotBlank()) {
                val body = TextView(this).apply {
                    text = a.text
                    textSize = 14f
                    setLineSpacing(dp(4f), 1f)
                    tag = "text:fg"
                    setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), dp(16f).toInt())
                }
                box.addView(body)
            }
            val link = a.link
            if (link != null) {
                sheetAction(box, R.drawable.ic_open_link, a.button ?: getString(R.string.vibe_open)) {
                    closeSheet { openVibeLink(link) }
                }
            }
            sheetAction(box, R.drawable.ic_check, getString(R.string.vibe_got_it), outlined = link != null) {
                closeSheet()
            }
        }
    }

    private fun setupAccount() {
        val acc = app.account
        val section = findViewById<View>(R.id.settings_account)
        if (!acc.available) {
            // A build without google-services.json (e.g. someone building the public repo)
            section.visibility = View.GONE
            return
        }
        findViewById<View>(R.id.account_sign_in).setOnClickListener { v ->
            if (accountBusy) return@setOnClickListener
            haptic(v)
            startSignIn()
        }
        findViewById<View>(R.id.account_sign_out).setOnClickListener { v ->
            if (accountBusy) return@setOnClickListener
            haptic(v)
            accountBusy = true
            lifecycleScope.launch {
                // v5.0.0: send what's still waiting to the cloud first (a few seconds at most), then stop syncing
                try { app.sync.prepareSignOut() } catch (_: Throwable) { }
                acc.signOut()
                accountBusy = false
                showAccountMessage(null)
                showNote(getString(R.string.account_signed_out_note))
            }
        }
        findViewById<View>(R.id.account_delete).setOnClickListener { v ->
            if (accountBusy) return@setOnClickListener
            haptic(v)
            confirmDeleteAccount()
        }
        // 5.0.0 part 2c: "Sync is waiting" opens the Merge / Start fresh sheet again
        findViewById<View>(R.id.account_status).setOnClickListener { v ->
            val c = app.sync.pendingChoice.value ?: return@setOnClickListener
            haptic(v)
            showAccountChoiceSheet(c)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { acc.user.collect { renderAccount(it); renderSyncStatus(app.sync.status.value) } }
                // "Synced just now / X min ago", redrawn when it would read differently
                launch {
                    app.sync.status.collectLatest { st ->
                        while (isActive) {
                            renderSyncStatus(st)
                            delay(SyncText.nextChangeMs(System.currentTimeMillis(), st.lastSyncAt))
                        }
                    }
                }
                // Signed in with a different Google account than the phone's music came from
                launch {
                    app.sync.pendingChoice.collect { c ->
                        if (c == null) {
                            choiceShownFor = null
                            if (isChoiceSheetOpen) closeSheet()
                        } else if (c != choiceShownFor) {
                            choiceShownFor = c
                            showAccountChoiceSheet(c)
                        }
                    }
                }
                // A pull brought changes from another phone: reload what shows the library
                launch { app.sync.dataVersion.collect { onSyncedChanges(it) } }
                // 5.10.0: a merge from the backup folder or a restore changed the library
                app.fermataBridge?.let { bridge -> launch { bridge.dataVersion.collect { onFermataChanges(it) } } }
            }
        }
    }

    // ---- 5.0.0 part 2c: sync status, the account switch sheet, reloads after a pull ----------

    private var choiceShownFor: CloudSync.Choice? = null
    private var choiceSheetHeading: View? = null
    private val isChoiceSheetOpen get() = isSheetOpen && choiceSheetHeading?.parent != null
    /** The last [CloudSync.dataVersion] the screens were reloaded for (starts at the current one). */
    private var seenDataVersion = -1L

    /** The line under the name, and the error line under it. */
    private fun renderSyncStatus(st: CloudSync.Status) {
        val status = findViewById<TextView>(R.id.account_status) ?: return
        val problem = findViewById<TextView>(R.id.account_sync_problem) ?: return
        if (app.account.user.value == null) {
            problem.visibility = View.GONE
            return
        }
        val line = SyncText.statusLine(st.phase, st.lastSyncAt, System.currentTimeMillis())
        if (status.text?.toString() != line) status.text = line
        val waiting = st.phase == CloudSync.Phase.NEEDS_CHOICE
        if (status.isClickable != waiting) {
            status.isClickable = waiting
            status.isFocusable = waiting
        }
        // waiting for a choice isn't an error; the line above says what to do
        val err = if (waiting) null else SyncText.problemLine(st.problem)
        if (err == null) {
            if (problem.visibility != View.GONE) problem.visibility = View.GONE
        } else {
            if (problem.text?.toString() != err) problem.text = err
            if (problem.visibility != View.VISIBLE) problem.visibility = View.VISIBLE
        }
    }

    /** Merge / Start fresh / Cancel and sign out. Dismissing it leaves the question waiting. */
    private fun showAccountChoiceSheet(c: CloudSync.Choice) {
        val first = c.name.trim().substringBefore(' ')
        val who = if (c.name == "this" || first.isBlank()) getString(R.string.account_choice_this)
        else getString(R.string.account_choice_named, first)
        openSheet { box ->
            sheetHeading(box, getString(R.string.account_choice_title), getString(R.string.account_choice_sub, who), wrapSub = true)
            choiceSheetHeading = box.getChildAt(box.childCount - 1)
            sheetAction(box, R.drawable.ic_add, getString(R.string.account_choice_merge)) {
                closeSheet()
                if (app.sync.pendingChoice.value == c) app.sync.choose(merge = true)
            }
            sheetAction(box, R.drawable.ic_repeat, getString(R.string.account_choice_fresh)) {
                closeSheet()
                if (app.sync.pendingChoice.value == c) app.sync.choose(merge = false)
            }
            sheetAction(box, R.drawable.ic_close, getString(R.string.account_choice_cancel), outlined = true) {
                closeSheet()
                cancelAccountChoice()
            }
        }
    }

    /** Cancel: nothing on the phone changes, nothing syncs, and Minim signs out of that account. */
    private fun cancelAccountChoice() {
        if (accountBusy) return
        accountBusy = true
        lifecycleScope.launch {
            try {
                app.sync.cancelChoice()
                app.account.signOut()
                showAccountMessage(null)
                showNote(getString(R.string.account_signed_out_note))
            } catch (_: Throwable) {
            } finally {
                accountBusy = false
            }
        }
    }

    /**
     * The library changed under the screens (a pull from another phone, or Start fresh). Liked,
     * Recents, playlists, the open list, Stats and the greetings reload in place (the lists diff, so
     * scroll positions stay). Discover drops songs marked "not interested" elsewhere right away; its
     * shelves are rebuilt only when it has none yet, or after a whole-library merge, and then not
     * while it's on screen (the next time the tab opens).
     */
    private fun onSyncedChanges(version: Long) {
        if (seenDataVersion < 0L || version <= seenDataVersion) {
            if (seenDataVersion < 0L) seenDataVersion = version
            return
        }
        seenDataVersion = version
        val full = app.sync.lastChangeWasFull
        greetingFactsAt = 0L
        scheduleRefresh(300L)
        lifecycleScope.launch {
            try {
                val engine = app.discoverEngine
                engine.dropHidden(app.database.notInterestedIds.value)?.let { if (discoverShelves.isNotEmpty()) showDiscover(it, afterBuild = false) }
                if (full) engine.markStale()
                if (isSearchOpen && discoverShelves.isEmpty()) loadDiscover(force = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** The last FermataBridge.dataVersion the screens were reloaded for (starts at the current one). */
    private var seenFermataVersion = -1L

    /** 5.10.0: a merge from the backup folder, a restore, or a rollback's old copy changed the library. */
    private fun onFermataChanges(version: Long) {
        if (seenFermataVersion < 0L || version <= seenFermataVersion) {
            if (seenFermataVersion < 0L) seenFermataVersion = version
            return
        }
        seenFermataVersion = version
        greetingFactsAt = 0L
        scheduleRefresh(300L)
        if (isSettingsOpen) renderBackupSettings()
        lifecycleScope.launch {
            try {
                val engine = app.discoverEngine
                engine.dropHidden(app.database.notInterestedIds.value)?.let { if (discoverShelves.isNotEmpty()) showDiscover(it, afterBuild = false) }
                engine.markStale()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // ---- 5.10.0: Settings → Storage backups, restore, and the fresh-install offer -------------

    private var backupBusy = false
    private var backupPickForOffer = false
    private var restoreOfferShown = false
    private val restoreOfferGate by lazy { com.minim.music.data.fermata.RestoreOfferGate(this) }

    private fun setupBackupSettings() {
        findViewById<View>(R.id.set_backup_folder)?.setOnClickListener { v ->
            if (app.backups == null) return@setOnClickListener
            haptic(v)
            backupPickForOffer = false
            launchBackupFolderPicker()
        }
        findViewById<View>(R.id.set_backup_now)?.setOnClickListener { v ->
            haptic(v)
            backUpNowFromSettings()
        }
        findViewById<View>(R.id.set_backup_restore)?.setOnClickListener { v ->
            haptic(v)
            openRestoreSheet()
        }
    }

    private fun launchBackupFolderPicker() {
        try {
            backupFolderPicker.launch(app.backups?.folder())
        } catch (_: Exception) {
            showNote(getString(R.string.backup_err_unreadable))
        }
    }

    /** Folder hint, last backup line, and which actions show. Cheap: no file reads. */
    private fun renderBackupSettings() {
        if (!::screenSettings.isInitialized) return
        val hint = findViewById<TextView>(R.id.set_backup_folder_hint) ?: return
        val choose = findViewById<TextView>(R.id.set_backup_folder) ?: return
        val status = findViewById<TextView>(R.id.set_backup_status) ?: return
        val now = findViewById<View>(R.id.set_backup_now) ?: return
        val restore = findViewById<View>(R.id.set_backup_restore) ?: return
        val restoreHint = findViewById<TextView>(R.id.set_backup_restore_hint) ?: return
        val b = app.backups
        if (b == null) {
            hint.text = getString(R.string.backup_folder_none)
            choose.visibility = View.GONE
            status.text = getString(R.string.backup_never)
            now.visibility = View.GONE
            restore.visibility = View.GONE
            return
        }
        val folder = b.folder()
        val access = b.hasFolderAccess()
        hint.text = com.minim.music.data.fermata.BackupText.folderHint(this, folder, access)
        choose.visibility = View.VISIBLE
        choose.text = getString(if (folder == null || !access) R.string.backup_folder_choose else R.string.backup_folder_change)
        status.text = com.minim.music.data.fermata.BackupText.statusLine(this, b.status(), System.currentTimeMillis(), backupBusy)
        val usable = folder != null && access
        now.visibility = if (usable) View.VISIBLE else View.GONE
        restore.visibility = if (usable) View.VISIBLE else View.GONE
        restoreHint.text = getString(R.string.backup_restore_hint)
    }

    private fun onBackupFolderPicked(uri: Uri?) {
        val b = app.backups ?: return
        val forOffer = backupPickForOffer
        backupPickForOffer = false
        if (uri == null) {
            if (forOffer) restoreOfferGate.markAnswered()
            return
        }
        try {
            b.setFolder(uri)
        } catch (_: Exception) {
            showNote(getString(R.string.backup_folder_lost))
            return
        }
        renderBackupSettings()
        if (forOffer) {
            lifecycleScope.launch {
                val list = withContext(Dispatchers.IO) { b.listBackups().filter { it.restorable } }
                if (list.isEmpty()) {
                    restoreOfferGate.markAnswered()
                    showNote(getString(R.string.backup_restore_none))
                } else showRestoreOfferSheet(list)
            }
        } else {
            showNote(getString(R.string.backup_folder_set, com.minim.music.data.fermata.BackupText.folderName(uri)))
        }
    }

    private fun backUpNowFromSettings() {
        val b = app.backups ?: return
        if (backupBusy) return
        backupBusy = true
        renderBackupSettings()
        lifecycleScope.launch {
            val (result, errors) = withContext(Dispatchers.IO) {
                // Nothing here is dropped silently: the merge saves what it left out in the
                // status (shown in the status line), and a failure to save the plays in progress
                // is shown in the note.
                val prepareError = try { app.fermataBridge?.prepareBackup(); null } catch (t: Throwable) { t }
                val mergeError = try { b.merge(); null } catch (t: Throwable) { t }
                b.backUpNow() to (prepareError to mergeError)
            }
            backupBusy = false
            renderBackupSettings()
            showNote(com.minim.music.data.fermata.BackupText.backUpNowNote(this@MainActivity, result, b.status(), errors.first, errors.second))
        }
    }

    /** Every backup in the folder, newest first; unreadable ones show why and can't be picked. */
    private fun openRestoreSheet() {
        val b = app.backups ?: return
        if (backupBusy) return
        lifecycleScope.launch {
            val (list, hasLibrary) = withContext(Dispatchers.IO) { b.listBackups() to app.database.hasLibrary() }
            openSheet { box ->
                sheetHeading(
                    box, getString(R.string.backup_restore_title),
                    com.minim.music.data.fermata.BackupText.restoreSub(this@MainActivity, hasLibrary), wrapSub = true
                )
                if (list.isEmpty()) {
                    sheetHeading(box, getString(R.string.backup_restore_none))
                    return@openSheet
                }
                val now = System.currentTimeMillis()
                for (info in list) {
                    val (label, trailing) = com.minim.music.data.fermata.BackupText.restoreRow(this@MainActivity, info, now)
                    val row = sheetAction(box, R.drawable.ic_repeat, label, trailing = trailing) {
                        closeSheet()
                        restoreBackup(info, hasLibrary)
                    }
                    if (!info.restorable) {
                        row.isEnabled = false
                        row.alpha = 0.45f
                    }
                }
            }
        }
    }

    private fun restoreBackup(info: com.minim.fermata.sync.BackupInfo, hadLibrary: Boolean) {
        val b = app.backups ?: return
        if (backupBusy || !info.restorable) return
        backupBusy = true
        showNote(getString(R.string.backup_restoring))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { b.restore(info) }
            backupBusy = false
            restoreOfferGate.markAnswered()
            renderBackupSettings()
            showNote(com.minim.music.data.fermata.BackupText.restoreDone(this@MainActivity, result, hadLibrary))
        }
    }

    /**
     * Fresh install (no likes, playlists or plays yet): offer to bring the library back from a
     * backup folder, once per install. Either answer is remembered; it never shows on a phone
     * that already has a library.
     */
    private fun maybeOfferRestore() {
        if (restoreOfferShown || !isActivityResumed || isWelcomeOpen || !app.preferences.welcomeDone) return
        val b = app.backups ?: return
        if (restoreOfferGate.answered || isSheetOpen) return
        restoreOfferShown = true
        lifecycleScope.launch {
            val folderSet = b.folder() != null && b.hasFolderAccess()
            var restorable = emptyList<com.minim.fermata.sync.BackupInfo>()
            val offer = withContext(Dispatchers.IO) {
                restoreOfferGate.decide(app.database.hasLibrary(), folderSet) {
                    restorable = b.listBackups().filter { it.restorable }
                    restorable.size
                }
            }
            if (isSheetOpen || isWelcomeOpen) {
                restoreOfferShown = false
                return@launch
            }
            when (offer) {
                com.minim.music.data.fermata.RestoreOfferGate.Offer.NONE -> Unit
                com.minim.music.data.fermata.RestoreOfferGate.Offer.RESTORE -> showRestoreOfferSheet(restorable)
                com.minim.music.data.fermata.RestoreOfferGate.Offer.PICK_FOLDER -> openSheet { box ->
                    sheetHeading(box, getString(R.string.backup_offer_prompt_title), getString(R.string.backup_offer_prompt_sub), wrapSub = true)
                    sheetAction(box, R.drawable.ic_add, getString(R.string.backup_offer_prompt_ok)) {
                        closeSheet()
                        backupPickForOffer = true
                        launchBackupFolderPicker()
                    }
                    sheetAction(box, R.drawable.ic_close, getString(R.string.backup_offer_prompt_no), outlined = true) {
                        closeSheet()
                        declineRestoreOffer()
                    }
                }
            }
        }
    }

    private fun showRestoreOfferSheet(restorable: List<com.minim.fermata.sync.BackupInfo>) {
        val newest = restorable.firstOrNull() ?: return
        val (label, _) = com.minim.music.data.fermata.BackupText.restoreRow(this, newest, System.currentTimeMillis())
        openSheet { box ->
            sheetHeading(box, getString(R.string.backup_offer_title), getString(R.string.backup_offer_sub), wrapSub = true)
            sheetAction(box, R.drawable.ic_repeat, getString(R.string.backup_offer_ok), trailing = label) {
                closeSheet()
                restoreBackup(newest, hadLibrary = false)
            }
            sheetAction(box, R.drawable.ic_close, getString(R.string.backup_offer_no), outlined = true) {
                closeSheet()
                declineRestoreOffer()
            }
        }
    }

    private fun declineRestoreOffer() {
        restoreOfferGate.markAnswered()
        try { app.backups?.dismissRestoreOffer() } catch (_: Throwable) { }
    }

    private fun startSignIn() {
        val signIn = findViewById<TextView>(R.id.account_sign_in)
        accountBusy = true
        showAccountMessage(null)
        signIn.text = getString(R.string.account_signing_in)
        signIn.alpha = 0.6f
        lifecycleScope.launch {
            val outcome = app.account.signIn(this@MainActivity)
            accountBusy = false
            signIn.text = getString(R.string.account_sign_in)
            signIn.alpha = 1f
            when (outcome) {
                is AccountOutcome.Done -> {
                    val name = app.account.user.value?.name.orEmpty()
                    showNote(
                        if (name.isNotBlank()) getString(R.string.account_signed_in_as, name.substringBefore(' '))
                        else getString(R.string.account_signed_in_note)
                    )
                }
                is AccountOutcome.Problem -> showAccountMessage(accountProblemText(outcome.problem, deleting = false))
            }
        }
    }

    private fun confirmDeleteAccount() {
        openSheet { box ->
            sheetHeading(box, getString(R.string.account_delete_title), getString(R.string.account_delete_sub))
            val v = layoutInflater.inflate(R.layout.sheet_confirm, box, false)
            v.findViewById<TextView>(R.id.sheet_confirm_cancel).setOnClickListener { closeSheet() }
            v.findViewById<TextView>(R.id.sheet_confirm_ok).apply {
                text = getString(R.string.account_delete_ok)
                setOnClickListener {
                    if (sheetClosing || accountBusy) return@setOnClickListener
                    haptic(it)
                    closeSheet()
                    deleteAccountNow()
                }
            }
            box.addView(v)
        }
    }

    private fun deleteAccountNow() {
        val delete = findViewById<TextView>(R.id.account_delete)
        val signOut = findViewById<View>(R.id.account_sign_out)
        accountBusy = true
        showAccountMessage(null)
        delete.text = getString(R.string.account_deleting)
        delete.alpha = 0.6f
        signOut.alpha = 0.6f
        lifecycleScope.launch {
            // v5.0.0: pause sync so nothing is written back while the cloud data is deleted
            try { app.sync.prepareDelete() } catch (_: Throwable) { }
            val outcome = app.account.deleteAccount(this@MainActivity)
            try { app.sync.afterDelete(deleted = outcome is AccountOutcome.Done) } catch (_: Throwable) { }
            accountBusy = false
            delete.text = getString(R.string.account_delete)
            delete.alpha = 1f
            signOut.alpha = 1f
            when (outcome) {
                is AccountOutcome.Done -> showNote(getString(R.string.account_deleted_note))
                is AccountOutcome.Problem -> showAccountMessage(accountProblemText(outcome.problem, deleting = true))
            }
        }
    }

    private fun accountProblemText(problem: AccountProblem, deleting: Boolean): String = getString(
        when (problem) {
            AccountProblem.CANCELLED ->
                if (deleting) R.string.account_err_delete_cancelled else R.string.account_err_cancelled
            AccountProblem.OFFLINE -> R.string.account_err_offline
            AccountProblem.NO_ACCOUNT -> R.string.account_err_no_account
            AccountProblem.UNAVAILABLE -> R.string.account_err_unavailable
            AccountProblem.NOT_READY -> R.string.account_err_not_ready
            AccountProblem.WRONG_ACCOUNT -> R.string.account_err_wrong_account
            AccountProblem.FAILED ->
                if (deleting) R.string.account_err_delete_failed else R.string.account_err_failed
        }
    )

    /** One short line under the Account buttons; null hides it. */
    private fun showAccountMessage(text: String?) {
        val msg = findViewById<TextView>(R.id.account_message)
        msg.text = text.orEmpty()
        msg.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Signed in or out: the Settings section and every profile picture follow. */
    private fun renderAccount(user: MinimAccount.User?) {
        findViewById<View>(R.id.account_signed_out).visibility = if (user == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.account_signed_in).visibility = if (user == null) View.GONE else View.VISIBLE
        if (user != null) {
            findViewById<TextView>(R.id.account_name).text =
                user.name.ifBlank { getString(R.string.account_signed_in_note) }
        }
        val url = user?.photoUrl
        val loaded = avatars().all { it.hasPhoto }
        if (url == avatarUrl && (url == null || loaded || avatarJob?.isActive == true)) return
        avatarUrl = url
        avatarJob?.cancel()
        if (url == null) {
            setAvatarPhoto(null, animate = false)
            return
        }
        val px = dp(56f).toInt()
        val cached = app.artworkPipeline.cachedBitmap(url, px)
        if (cached != null) {
            setAvatarPhoto(cached, animate = false)
            return
        }
        setAvatarPhoto(null, animate = false)
        // Offline before the photo was ever saved: the placeholder stays, and the next time Minim comes
        // back to the front this runs again and retries.
        avatarJob = lifecycleScope.launch {
            val bitmap = app.artworkPipeline.loadBitmap(url, px)
            if (avatarUrl == url && bitmap != null) setAvatarPhoto(bitmap, animate = animationsOn())
        }
    }

    private fun avatars(): List<AvatarView> = allRows.map { it.gear } + findViewById<AvatarView>(R.id.account_avatar)

    private fun setAvatarPhoto(bitmap: android.graphics.Bitmap?, animate: Boolean) {
        for (a in avatars()) a.setPhoto(bitmap, animate)
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

    // ==========================================================================================
    // 5.2.0: Gemini chat (the sheet that grows out of "Ask Gemini" on Home and "Ask" on Now Playing)
    // ==========================================================================================

    private lateinit var geminiChat: GeminiChatPanel
    private val isGeminiOpen get() = ::geminiChat.isInitialized && geminiChat.isOpen
    /** 5.3.0: open, or closed but its closing animation still on screen. */
    private val isGeminiShowing get() = ::geminiChat.isInitialized && geminiChat.isShowing

    private fun setupGemini() {
        geminiChat = GeminiChatPanel(findViewById(R.id.gemini_root), geminiHost)
        findViewById<View>(R.id.home_gemini).setOnClickListener { v ->
            haptic(v)
            openGemini(v, null)
        }
        // From Now Playing the chat starts about the song that's playing (the chip can be removed)
        findViewById<View>(R.id.np_gemini).setOnClickListener { v ->
            haptic(v)
            openGemini(v, app.playbackController.uiState.value.currentTrack)
        }
        setupGeminiSettings()
        ViewCompat.requestApplyInsets(root)
    }

    private fun openGemini(from: View, song: Track?) {
        if (isWelcomeOpen || isSheetOpen || recapStory != null || isGeminiOpen) return
        if (currentFocus is EditText) hideKeyboard()
        geminiChat.open(from, song)
    }

    private fun geminiModelLabel(id: String = app.geminiSettings.model): String =
        if (GeminiModels.isPreset(id)) GeminiModels.labelFor(id)
        else getString(R.string.set_gemini_custom) + " · " + id

    /** What Gemini's tools can do in Minim. Every call runs on the main thread. */
    private val geminiActions = object : MinimActions {
        override suspend fun search(query: String): List<Track> =
            when (val r = app.sourceAdapter.searchTracks(query)) {
                is SourceResult.Success -> r.data
                is SourceResult.Error -> throw java.io.IOException(r.message)
            }

        override suspend fun similar(seed: Track): List<Track> =
            when (val r = app.sourceAdapter.fetchRadio(seed.id)) {
                is SourceResult.Success -> r.data.filter { it.id != seed.id }
                is SourceResult.Error -> throw java.io.IOException(r.message)
            }

        override fun currentTrack(): Track? = app.playbackController.uiState.value.currentTrack
        override fun positionMs(): Long = app.playbackController.uiState.value.positionMs
        override fun isPlaying(): Boolean = app.playbackController.uiState.value.isPlaying

        override suspend fun lyricsText(track: Track): String? {
            val data = app.lyricsProvider.getLyrics(track) ?: return null
            val text = data.lines.joinToString("\n") { it.text.trim() }.trim()
            return text.ifEmpty { null }
        }

        override fun upNext(): List<Track> {
            val s = app.playbackController.uiState.value
            return if (s.currentIndex < 0) s.queue else s.queue.drop(s.currentIndex + 1)
        }

        override fun play(tracks: List<Track>) {
            val first = tracks.firstOrNull() ?: return
            // One song: its radio carries on after it. Several: them in order, then the radio.
            if (tracks.size == 1) app.playbackController.playRadio(first)
            else app.playbackController.playListThenRadio(tracks)
        }

        override fun addToQueue(tracks: List<Track>, playNext: Boolean) {
            val c = app.playbackController
            if (c.uiState.value.currentTrack == null) {
                // Nothing to queue behind: start them instead
                if (tracks.isNotEmpty()) c.playTrack(tracks.first(), tracks)
                return
            }
            // "Play next" puts each song right after the current one, so go backwards to keep the order
            if (playNext) tracks.asReversed().forEach { c.playNext(it) } else tracks.forEach { c.addToQueue(it) }
        }

        override suspend fun isLiked(track: Track): Boolean = app.database.likedIds.value.contains(track.id)

        override suspend fun setLiked(track: Track, liked: Boolean) = geminiSetLiked(track, liked)

        override suspend fun recentTracks(limit: Int): List<Track> = app.database.getRecentTracks(limit)

        override suspend fun likedTracks(): List<Track> = app.database.getLikedSongs()

        override suspend fun playsSince(sinceMs: Long): List<Pair<Track, Long>> =
            app.database.getPlayEvents(sinceMs).map { it.track to it.playedAt }

        override fun openArtist(name: String) = geminiOpenArtist(name)

        override fun nowMs(): Long = System.currentTimeMillis()
    }

    private suspend fun geminiSetLiked(track: Track, liked: Boolean) {
        if (app.database.likedIds.value.contains(track.id) == liked) return
        app.database.toggleLike(track)
        refreshData()
    }

    private fun geminiOpenArtist(name: String) {
        if (MusicSourceAdapter.firstArtistName(name).isBlank()) return
        if (isSettingsOpen) closeSettings()
        if (isQueueOpen) closeQueue()
        if (isNowPlayingOpen) collapseNowPlaying()
        openArtistByName(name)
    }

    private val geminiHost = object : GeminiChatPanel.Host {
        override fun palette(): Pal = pal
        override fun session(): GeminiChatSession = app.geminiChat

        override fun send(text: String, focus: Track?): Boolean {
            val now = java.text.SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", java.util.Locale.ENGLISH)
                .format(java.util.Date())
            val prompt = ChatTools.systemPrompt(app.preferences.userName.takeIf { it.isNotBlank() }, now)
            val ctx = applicationContext
            val settings = app.geminiSettings
            return app.geminiChat.send(text, focus, geminiActions, prompt) {
                withContext(Dispatchers.IO) { GeminiRoute.endpoint(ctx, settings) }
            }
        }

        override fun loadArt(view: ImageView, url: String?, sizePx: Int) {
            app.artworkPipeline.loadInto(view, url, sizePx)
        }

        override fun playTrack(track: Track, list: List<Track>) {
            app.playbackController.playRadio(track, list)
        }

        override fun showTrackOptions(anchor: View, track: Track) {
            this@MainActivity.showTrackOptions(anchor, track)
        }

        override suspend fun setLiked(track: Track, liked: Boolean) = geminiSetLiked(track, liked)

        override suspend fun savePlaylist(name: String, tracks: List<Track>): Boolean {
            if (tracks.isEmpty()) return false
            val base = name.trim().ifEmpty { getString(R.string.gemini_title) }.take(40)
            // A playlist with that name already exists: "Name 2", "Name 3", ...
            var id = app.database.createPlaylist(base)
            var n = 2
            while (id == -1L && n <= 20) {
                id = app.database.createPlaylist("${base.take(36)} $n")
                n++
            }
            if (id == -1L) return false
            for (t in tracks) app.database.addTrackToPlaylist(id, t)
            refreshData()
            return true
        }

        override fun openArtist(name: String) = geminiOpenArtist(name)

        override fun addOwnKey() {
            openGeminiSettings()
            showGeminiKeySheet()
        }

        override fun openGeminiSettings() {
            openSettings()
            scrollSettingsTo(findViewById(R.id.set_gemini_header))
        }

        override fun modelLabel(): String = geminiModelLabel()

        override fun needsKeyUpFront(): Boolean =
            !app.geminiSettings.hasApiKey &&
                (!GeminiRoute.hasBuiltIn(this@MainActivity) || GeminiRoute.defaultKnownOff)

        override fun note(text: String) = showNote(text)

        override fun haptic(v: View, type: Int) = this@MainActivity.haptic(v, type)

        override fun launch(block: suspend () -> Unit) {
            lifecycleScope.launch { block() }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Settings -> Gemini: the model dropdown and your own Google AI Studio key
    // ------------------------------------------------------------------------------------------

    private fun setupGeminiSettings() {
        // 5.8.4 u56: the whole Model row opens the picker, not just the name
        findViewById<View>(R.id.set_gemini_model_row).setOnClickListener { v ->
            haptic(v)
            showGeminiModelSheet()
        }
        findViewById<View>(R.id.set_gemini_key_add).setOnClickListener { v ->
            haptic(v)
            showGeminiKeySheet()
        }
        findViewById<View>(R.id.set_gemini_key_remove).setOnClickListener { v ->
            haptic(v)
            app.geminiSettings.clearApiKey()
            renderGeminiSettings()
            showNote(getString(R.string.set_gemini_key_removed))
        }
        // 5.3.2: copy this phone's App Check debug token (never logged, never synced)
        findViewById<View>(R.id.set_gemini_token_copy).setOnClickListener { v ->
            haptic(v)
            val token = AppCheckDebugToken.get(this)
            try {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText(getString(R.string.set_gemini_token), token)
                // Android 13+: keep it out of the clipboard preview pop-up
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                cm?.setPrimaryClip(clip)
                showNote(getString(R.string.set_gemini_token_copied))
            } catch (_: Exception) {
            }
        }
    }

    private fun renderGeminiSettings() {
        val s = app.geminiSettings
        findViewById<TextView>(R.id.set_gemini_model_pick).text = geminiModelLabel()
        findViewById<View>(R.id.set_gemini_model_row).contentDescription =
            getString(R.string.set_gemini_model_desc, geminiModelLabel())
        val key = if (s.hasApiKey) s.apiKey() else null
        findViewById<TextView>(R.id.set_gemini_key_status).text =
            if (key != null) getString(R.string.set_gemini_key_set, GeminiSettings.masked(key))
            else getString(R.string.set_gemini_key_none)
        findViewById<TextView>(R.id.set_gemini_key_add).setText(
            if (key != null) R.string.set_gemini_key_change else R.string.set_gemini_key_add
        )
        findViewById<View>(R.id.set_gemini_key_remove).visibility = if (s.hasApiKey) View.VISIBLE else View.GONE
        // 5.3.2: the device token only matters for the built-in route (needs the Firebase file)
        val builtIn = GeminiRoute.hasBuiltIn(this)
        findViewById<View>(R.id.set_gemini_token_box).visibility = if (builtIn) View.VISIBLE else View.GONE
        if (builtIn) {
            val token = AppCheckDebugToken.get(this)
            findViewById<TextView>(R.id.set_gemini_token_value).apply {
                text = token
                contentDescription = getString(R.string.set_gemini_token_desc, token.takeLast(4))
            }
        }
    }

    /** The model dropdown: the two Flash-Lite models, or Custom (any AI Studio model ID). */
    private fun showGeminiModelSheet() {
        val s = app.geminiSettings
        val current = s.model
        openSheet { box ->
            sheetHeading(box, getString(R.string.set_gemini_model))
            for (preset in GeminiModels.PRESETS) {
                sheetAction(
                    box, R.drawable.ic_sparkle, preset.label,
                    trailingIcon = if (preset.id == current) R.drawable.ic_check else null
                ) {
                    s.model = preset.id
                    renderSettingsOptions()
                    closeSheet()
                }
            }
            val custom = !GeminiModels.isPreset(current)
            sheetAction(
                box, R.drawable.ic_sparkle, getString(R.string.set_gemini_custom),
                trailing = (if (custom) current else s.customModel).takeIf { it.isNotBlank() },
                trailingIcon = if (custom) R.drawable.ic_check else null
            ) { showGeminiCustomModelSheet() }
        }
    }

    private fun showGeminiCustomModelSheet() {
        val s = app.geminiSettings
        openSheet { box ->
            sheetHeading(box, getString(R.string.set_gemini_custom_title), getString(R.string.set_gemini_custom_sub), wrapSub = true)
            val v = layoutInflater.inflate(R.layout.sheet_create_playlist, box, false)
            val input = v.findViewById<EditText>(R.id.sheet_name_input)
            val error = v.findViewById<TextView>(R.id.sheet_name_error)
            val save = v.findViewById<TextView>(R.id.sheet_create)
            input.setHint(R.string.set_gemini_custom_hint)
            input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            input.filters = arrayOf(android.text.InputFilter.LengthFilter(100))
            save.setText(R.string.set_gemini_key_save)
            val start = if (GeminiModels.isPreset(s.model)) s.customModel else s.model
            input.setText(start)
            input.setSelection(input.text.length)
            fun setReady(on: Boolean) {
                save.isEnabled = on
                save.alpha = if (on) 1f else 0.35f
            }
            setReady(start.isNotBlank())
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    setReady(!e?.toString()?.trim().isNullOrEmpty())
                    if (error.visibility == View.VISIBLE) error.visibility = View.GONE
                }
            })
            fun submit() {
                if (sheetClosing) return
                val raw = input.text?.toString().orEmpty()
                if (!GeminiModels.isValidId(raw)) {
                    error.setText(R.string.set_gemini_custom_bad)
                    error.visibility = View.VISIBLE
                    return
                }
                haptic(save)
                val id = GeminiModels.normalize(raw)
                s.customModel = id
                s.model = id
                renderSettingsOptions()
                closeSheet()
            }
            save.setOnClickListener { if (save.isEnabled) submit() }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
            }
            box.addView(v)
            input.requestFocus()
            showKeyboard(input)
        }
    }

    /** Add or change your own key. It's encrypted on the phone and never synced or logged. */
    private fun showGeminiKeySheet() {
        openSheet { box ->
            sheetHeading(box, getString(R.string.set_gemini_key_title), getString(R.string.set_gemini_key_sub), wrapSub = true)
            sheetAction(box, R.drawable.ic_open_link, getString(R.string.set_gemini_key_get), outlined = true) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_KEYS_URL)))
                } catch (_: Exception) {
                }
            }
            val v = layoutInflater.inflate(R.layout.sheet_create_playlist, box, false)
            (v.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(14f).toInt()
            val input = v.findViewById<EditText>(R.id.sheet_name_input)
            val error = v.findViewById<TextView>(R.id.sheet_name_error)
            val save = v.findViewById<TextView>(R.id.sheet_create)
            input.setHint(R.string.set_gemini_key_hint)
            input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            input.filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            input.isSaveEnabled = false // the key never goes into the saved screen state
            input.typeface = android.graphics.Typeface.MONOSPACE
            save.setText(R.string.set_gemini_key_save)
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    val on = GeminiSettings.cleanKey(e?.toString().orEmpty()).isNotEmpty()
                    save.isEnabled = on
                    save.alpha = if (on) 1f else 0.35f
                    if (error.visibility == View.VISIBLE) error.visibility = View.GONE
                }
            })
            fun submit() {
                if (sheetClosing) return
                val raw = input.text?.toString().orEmpty()
                if (GeminiSettings.cleanKey(raw).isEmpty()) return
                haptic(save)
                save.isEnabled = false
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { app.geminiSettings.setApiKey(raw) }
                    if (!ok) {
                        save.isEnabled = true
                        error.setText(R.string.set_gemini_key_failed)
                        error.visibility = View.VISIBLE
                        return@launch
                    }
                    input.setText("")
                    renderGeminiSettings()
                    closeSheet()
                    showNote(
                        getString(
                            if (GeminiSettings.looksLikeKey(raw)) R.string.set_gemini_key_saved
                            else R.string.set_gemini_key_odd
                        )
                    )
                }
            }
            save.setOnClickListener { if (save.isEnabled) submit() }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
            }
            box.addView(v)
            input.requestFocus()
            showKeyboard(input)
        }
    }

    override fun onDestroy() {
        if (showingCrashReport) {
            super.onDestroy()
            return
        }
        // 5.2.0: leaving the app (Back from Home, or it being closed) forgets the Gemini chat;
        // a rotation or theme change keeps it
        if (isFinishing && !isChangingConfigurations) app.wipeGeminiChat()
        if (::geminiChat.isInitialized) geminiChat.detach()
        // 5.8.2: the recap's song never outlives the screen it belongs to
        recapMusic?.let { m ->
            recapMusic = null
            m.release()
            if (recapResumeMain && !app.playbackController.uiState.value.isPlaying) app.playbackController.togglePlayPause()
            recapResumeMain = false
        }
        super.onDestroy()
    }

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
        /** 5.5.2: taps on the version in Settings that toggle the festival preview. */
        private const val VERSION_TAPS = 7
        /** A pause longer than this between taps starts the count again. */
        private const val VERSION_TAP_GAP_MS = 1_500L
        /** The festival wallpaper's fade on tab switches. */
        private const val THEME_FADE_MS = 260L
        private const val GRID_COLUMNS = 2
        private const val LIKED_LIST = -1L
        /** v4.9: Recents opens in the song list screen from Library. */
        private const val RECENTS_LIST = -2L
        /** 5.4.0: the songs saved offline (the "Offline" tile in Library). */
        private const val OFFLINE_LIST = -3L
        /** v4.9.2: most rows in today's songs / artists sheets. */
        private const val TODAY_SHEET_MAX = 100

        /** v4.9: how many quiet past days the Stats tab looks through for a better recap. */
        private const val MAX_RECAP_LOOKBACK = 30
        /** v4.9.1: the popup waits this long (and re-checks) so it never lands mid-animation. */
        private const val RECAP_POPUP_DELAY_MS = 700L
        // 5.0.0 part 3: an announcement opens a moment after Home settles
        private const val ANNOUNCEMENT_DELAY_MS = 1_200L
        private const val UPDATE_POPUP_DELAY_MS = 900L
        private const val ARTIST_SHELF = "artist:"

        /**
         * 5.9.0: the Fermata source for a song played from a shelf: Discover's shelves are
         * "discover"; an Artist page's shelves don't fit any source yet, so "unknown".
         */
        private fun shelfSource(shelf: com.minim.music.data.discover.Shelf): String =
            if (shelf.key.startsWith(ARTIST_SHELF)) com.minim.music.data.fermata.FermataSource.UNKNOWN
            else com.minim.music.data.fermata.FermataSource.DISCOVER
        /** 5.7.0: the Gemini shelf on the Search page (its title is Gemini's own). */
        private const val GEMINI_SHELF = "gemini"
        private const val GREETING_INTERVAL_MS = 7_000L
        /** 5.1.0: how often to look for a new greetings file while the app is open. */
        private const val GREETING_FILE_CHECK_MS = 10L * 60 * 1000
        private const val GREETING_MESSAGE_MS = 5_000L
        private const val GREETING_FACTS_MAX_AGE_MS = 60_000L
        private const val TIP_INTERVAL_MS = 10_000L
        private const val GREETING_SP = 24f
        private const val GREETING_MIN_SP = 17f
        private const val PILL_MIN_DP = 170f
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val WELCOME_AUTO_MS = 3_000L
        private const val AI_STUDIO_KEYS_URL = "https://aistudio.google.com/apikey"
    }
}
