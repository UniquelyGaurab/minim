package com.minim.music

import kotlinx.coroutines.launch
import android.app.Application
import android.content.ComponentCallbacks2
import com.minim.music.data.greetings.OkHttpGreetingsFetcher
import com.minim.music.data.greetings.RemoteGreetingsSync
import com.minim.music.data.local.AppPreferences
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.source.LyricsProvider
import com.minim.music.data.source.MusicSourceAdapter
import com.minim.music.data.source.LrcLibLyricsAdapter
import com.minim.music.playback.PlaybackController
import com.minim.music.playback.SleepTimerManager
import com.minim.music.ui.Greetings
import com.minim.music.util.ArtworkPipeline
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

open class MinimApp : Application(), com.minim.fermata.android.FermataHolder {

    lateinit var okHttpClient: OkHttpClient
        private set

    lateinit var preferences: AppPreferences
        private set

    lateinit var database: LibraryDatabase
        private set

    lateinit var sourceAdapter: MusicSourceAdapter
        private set

    lateinit var lyricsProvider: LyricsProvider
        private set

    lateinit var artworkPipeline: ArtworkPipeline
        private set

    lateinit var playbackController: PlaybackController
    lateinit var discoverEngine: com.minim.music.data.discover.DiscoverEngine
        private set

    lateinit var sleepTimerManager: SleepTimerManager
        private set

    /** 5.4.0: songs saved offline (app's private files folder, not the cache). */
    lateinit var offlineStore: com.minim.music.data.offline.OfflineStore
        private set

    /** 5.4.0: the "Save offline" queue (3 songs at a time, each in parallel ranged parts). */
    lateinit var offline: com.minim.music.data.offline.OfflineManager
        private set

    /** v4.9.6: update checker / downloader for GitHub Releases. */
    lateinit var updates: com.minim.music.data.update.AppUpdates
        private set

    /** v4.9.6: when the update pop-up may show; per process, so a rotation doesn't ask again. */
    val updateGate = com.minim.music.ui.UpdatePromptGate(clock = { android.os.SystemClock.elapsedRealtime() })

    /** v4.9.8: when the quiet update check runs (cold start, then every few hours on coming back). */
    val updateSchedule = com.minim.music.data.update.UpdateCheckSchedule()

    /** 5.3.0: background playlist import (YouTube, Spotify, plain lists); one at a time. */
    val importer: com.minim.music.data.importer.PlaylistImporter by lazy {
        com.minim.music.data.importer.PlaylistImporter(
            com.minim.music.data.importer.AppImportBackend(sourceAdapter, okHttpClient),
            // 5.3.1: the importer turns every error into a failed import itself; this handler is the
            // last line of defence so nothing thrown on the import's threads can ever crash the app.
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() +
                    kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                        try {
                            android.util.Log.e("Minim", "Import error", t)
                        } catch (_: Throwable) {
                        }
                    }
            )
        )
    }

    /** v5.0: the optional Google account (Firebase Auth). Created on first use, never throws. */
    /** 5.7.0 u46: how far you got into each stories upload (resume, Continue listening, dimming). */
    val storyProgress: com.minim.music.data.stories.StoryProgressStore by lazy {
        com.minim.music.data.stories.StoryProgressStore(this)
    }

    /** 5.7.0 u47: followed channels and saved uploads, shared by tapes and Channel pages. */
    val storiesStore: com.minim.music.data.stories.StoriesStore by lazy {
        com.minim.music.data.stories.StoriesStore(this)
    }

    /** 5.7.0 u48: liked artists (the Artist page heart, Liked > Artists, a future Artist tab). */
    val likedArtists: com.minim.music.data.artists.LikedArtistsStore by lazy {
        com.minim.music.data.artists.LikedArtistsStore(this)
    }

    val account: com.minim.music.data.account.MinimAccount by lazy {
        com.minim.music.data.account.MinimAccount(this)
    }

    /** v5.0.0: cloud sync of likes, playlists and listening history (only while signed in). */
    val sync: com.minim.music.data.sync.CloudSync by lazy {
        com.minim.music.data.sync.CloudSync(this, database, account)
    }
    @Volatile private var syncStarted = false

    // ---- 5.10.0: Fermata (likes, playlists and history go through its log; folder backups) -----

    /** Null only if Fermata could not start; Minim then works as 5.9.1 did, without backups. */
    var fermataBridge: com.minim.music.data.fermata.FermataBridge? = null
        private set
    var backups: com.minim.fermata.android.FermataBackups? = null
        private set

    override val fermata: com.minim.fermata.Fermata
        get() = fermataBridge?.fermata ?: error("Fermata did not start")

    override val appVersion: String? by lazy {
        try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Throwable) {
            null
        }
    }

    override fun beforeBackup() {
        fermataBridge?.prepareBackup()
    }

    private fun startFermata() {
        try {
            val bridge = com.minim.music.data.fermata.FermataBridge(
                this, database, appVersion,
                signedIn = { try { account.user.value != null } catch (_: Throwable) { false } },
            )
            bridge.attach()
            fermataBridge = bridge
            val b = com.minim.fermata.android.FermataBackups(this, bridge.fermata, appVersion)
            backups = b
            // Clear listening history: back up right away, replacing this phone's previous file
            // too, so neither of its backups still holds the cleared history
            database.afterClearHistory = {
                if (b.folder() != null) Thread { try { b.backUpNow(replacePrevious = true) } catch (_: Throwable) { } }.start()
            }
            Thread({
                bridge.finishStart()
                try { if (b.folder() != null) b.scheduleDaily() } catch (_: Throwable) { }
            }, "fermata-start").start()
        } catch (t: Throwable) {
            android.util.Log.w("MinimApp", "Fermata unavailable", t)
        }
    }

    /**
     * v4.9.8: app opens for the whole process (replaces 4.9.6's flags on MainActivity, which missed
     * every open after the activity was closed while playback kept the process alive).
     */
    val foreground = com.minim.music.util.AppForegroundTracker(clock = { android.os.SystemClock.elapsedRealtime() })

    private fun installCrashReporter() {
        try {
            val version = try {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).let { "${it.versionName} (${it.longVersionCode})" }
            } catch (_: Throwable) {
                "?"
            }
            val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
            com.minim.music.util.CrashReporter.install(filesDir, version, device)
        } catch (_: Throwable) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Hotfix 4.9.10: save any crash's stack trace; MainActivity shows it once on the next launch
        installCrashReporter()
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                val wasForeground = foreground.isInForeground
                foreground.onActivityStarted()
                if (!wasForeground && syncStarted) try { sync.onForeground() } catch (_: Throwable) { }
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                foreground.onActivityStopped(activity.isChangingConfigurations)
                if (!foreground.isInForeground && syncStarted) try { sync.onBackground() } catch (_: Throwable) { }
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })

        preferences = AppPreferences(this)
        // v4.7: first launch or not is decided before anything saves a setting
        preferences.resolveWelcome(this)
        preferences.applySavedTheme()

        val httpCacheDir = File(cacheDir, "http_cache")
        okHttpClient = OkHttpClient.Builder()
            .cache(Cache(httpCacheDir, 15L * 1024L * 1024L)) // 15 MB bounded HTTP cache
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        database = LibraryDatabase(this)
        startFermata()
        sourceAdapter = MusicSourceAdapter(okHttpClient)
        lyricsProvider = LrcLibLyricsAdapter(okHttpClient)
        artworkPipeline = ArtworkPipeline(this, okHttpClient)
        setUpOffline()
        playbackController = PlaybackController(this, database, preferences, artworkPipeline)
        discoverEngine = com.minim.music.data.discover.DiscoverEngine(this, database, sourceAdapter)
        sleepTimerManager = SleepTimerManager(playbackController)
        syncRemoteGreetings()
        // 5.5.2: the festival theme from what's on the phone first (tiny files), so the first
        // greeting is already the festival's, then a check for updates in the background
        try {
            publishTheme(themeSync.state())
        } catch (t: Throwable) {
            android.util.Log.w("MinimApp", "themes", t)
        }
        syncThemes()
        updates = com.minim.music.data.update.AppUpdates(this, okHttpClient)
        updates.cleanUp()
        try {
            sync.start()
            syncStarted = true
        } catch (t: Throwable) {
            android.util.Log.w("MinimApp", "sync unavailable", t)
        }
        startVibe()
    }

    // ---- 5.0.0 part 3: the cloud config (Firestore config/app) ------------------------------

    /** Festivals, announcements, switches, extra greetings and the update lock. */
    val vibe: com.minim.music.data.config.VibeConfigSource by lazy {
        com.minim.music.data.config.VibeConfigSource(account)
    }

    // ------------------------------------------------------------------------------------------
    // 5.2.0: Gemini chat. The conversation lives here (not in the screen), so it survives closing
    // the chat sheet, Now Playing, Settings and rotating the phone. It is only ever in memory:
    // wiped when the app is closed (MainActivity finishing, or swiped away from Recents) and gone
    // with the process. Nothing is written to disk or synced.
    // ------------------------------------------------------------------------------------------

    val geminiSettings: com.minim.music.data.ai.GeminiSettings by lazy {
        com.minim.music.data.ai.GeminiSettings(this)
    }

    /**
     * 5.6.0: Full Screen Lyrics translation. Same Gemini route and model as the chat; saved
     * translations live in the no-backup folder, so they stay on this phone only.
     */
    val lyricsTranslator: com.minim.music.data.ai.LyricsTranslator by lazy {
        com.minim.music.data.ai.LyricsTranslator(
            client = com.minim.music.data.ai.GeminiClient(okHttpClient),
            cache = com.minim.music.data.ai.TranslationCache(File(noBackupFilesDir, "lyric_translations")),
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
            ),
        )
    }

    /**
     * 5.8.1: Gemini lyrics for songs that have none. Only ever with your own key, only when you
     * tap. Saved lyrics live in the no-backup folder (this phone only, never synced); the audio
     * is put together in the cache and deleted right after the request.
     */
    val aiLyrics: com.minim.music.data.ai.AiLyricsMaker by lazy {
        com.minim.music.data.ai.AiLyricsMaker(
            store = com.minim.music.data.ai.AiLyricsStore(File(noBackupFilesDir, "ai_lyrics")),
            audio = com.minim.music.data.ai.PhoneSongAudio(
                workDir = File(cacheDir, "ai_lyrics_audio"),
                offline = { if (this::offlineStore.isInitialized) offlineStore else null },
                options = { id, fresh -> sourceAdapter.audioOptionsBlocking(id, fresh) },
                fetcher = com.minim.music.data.offline.ParallelFetcher(
                    okHttpClient.newBuilder().cache(null)
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                ),
            ),
            requester = com.minim.music.data.ai.GeminiAudioRequester(okHttpClient),
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
            ),
        )
    }

    /** 5.7.0: the Gemini shelf on the Search page, made at most once a day. */
    val geminiShelfMaker: com.minim.music.data.ai.GeminiShelfMaker by lazy {
        com.minim.music.data.ai.GeminiShelfMaker(
            client = com.minim.music.data.ai.GeminiClient(okHttpClient),
            file = File(noBackupFilesDir, "gemini_shelf.json"),
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
            ),
        )
    }

    private val geminiMain by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Volatile private var geminiChatMade = false

    val geminiChat: com.minim.music.data.ai.GeminiChatSession by lazy {
        geminiChatMade = true
        com.minim.music.data.ai.GeminiChatSession(
            client = com.minim.music.data.ai.GeminiClient(okHttpClient),
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
            ),
            post = { block -> geminiMain.post(block) },
        )
    }

    /** Forgets the whole chat (the app was closed). Safe from any thread. */
    fun wipeGeminiChat() {
        if (!geminiChatMade) return
        geminiMain.post { try { geminiChat.clear() } catch (_: Throwable) { } }
    }

    @Volatile private var githubGreetings = com.minim.music.data.greetings.RemoteGreetingFile.EMPTY
    @Volatile private var configGreetings = com.minim.music.data.greetings.RemoteGreetingFile.EMPTY

    /** Home's extra greetings: the GitHub file, then the cloud config's (and today's festival's). */
    @Synchronized
    private fun publishGreetings() {
        val merged = com.minim.music.data.greetings.RemoteGreetingFile.merge(githubGreetings, configGreetings)
        if (merged != Greetings.remote) Greetings.remote = merged
    }

    private fun startVibe() {
        try {
            val main = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
            main.launch {
                vibe.config.collect { cfg ->
                    configGreetings = cfg.greetingFile()
                    publishGreetings()
                    if (syncStarted) try {
                        sync.setRemoteEnabled(cfg.isOn(com.minim.music.data.config.VibeConfig.SWITCH_SYNC))
                    } catch (_: Throwable) { }
                }
            }
            vibe.refresh()
        } catch (t: Throwable) {
            android.util.Log.w("MinimApp", "cloud config unavailable", t)
        }
    }

    private val greetingsExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "minim-greetings").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }

    /**
     * v4.9.6: loads the greetings file from GitHub (offline copy first, then a fresh download when
     * due: 5.1.0 checks every 10 minutes while the app is open, see MainActivity). Safe to call
     * often: it returns at once and does nothing when not due.
     */
    fun syncRemoteGreetings() {
        try {
            greetingsExecutor.execute {
                RemoteGreetingsSync.run(
                    dir = File(filesDir, "greetings"),
                    fetcher = OkHttpGreetingsFetcher(okHttpClient),
                    seed = { assets.open(GREETINGS_SEED_ASSET).bufferedReader().use { it.readText() } }
                ) { file ->
                    githubGreetings = file
                    publishGreetings()
                }
            }
        } catch (e: Exception) {
            // Greetings are optional
        }
    }

    // ---- 5.5.2: festival themes -----------------------------------------------------------------

    /** What Home should look like now (festival, preview, its greetings and wallpaper). */
    val themeState = kotlinx.coroutines.flow.MutableStateFlow(com.minim.music.data.themes.ThemeState.NONE)

    private val themesExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "minim-themes").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }

    private val themeSync by lazy {
        com.minim.music.data.themes.ThemeSync(
            com.minim.music.data.themes.ThemeStore(File(filesDir, "themes")),
            com.minim.music.data.themes.OkHttpThemeFetcher(okHttpClient)
        )
    }

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private fun publishTheme(state: com.minim.music.data.themes.ThemeState) {
        val set = state.greetings
        Greetings.themeSet = set
        // Only once the festival's greetings are on the phone do the regular ones pause
        Greetings.theme = set?.file?.takeIf { it.size > 0 }
        themeState.value = state
    }

    private fun onThemes(block: () -> Unit) {
        try {
            themesExecutor.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    android.util.Log.w("MinimApp", "themes", t)
                }
            }
        } catch (e: Exception) {
            // Themes are optional
        }
    }

    /**
     * Checks themes.json (every 10 minutes while the app is open, like the greetings) and the files
     * of festivals starting within 2 days, then works out today's theme. Also re-checks the date, so
     * a festival starts at midnight without a restart. Returns at once.
     */
    fun syncThemes(force: Boolean = false) = onThemes { publishTheme(themeSync.run(force)) }

    /** 7 taps on the version in Settings: the festival preview on or off. [done] runs on the main thread. */
    fun toggleThemePreview(done: (com.minim.music.data.themes.PreviewResult) -> Unit) = onThemes {
        val (result, state) = themeSync.togglePreview()
        publishTheme(state)
        mainHandler.post { done(result) }
    }

    /** Long press on the version during a preview: the festival's big day on or off. */
    fun toggleThemeBigDay(done: (com.minim.music.data.themes.PreviewResult) -> Unit) = onThemes {
        val (result, state) = themeSync.toggleBigDay()
        publishTheme(state)
        mainHandler.post { done(result) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            artworkPipeline.trimMemory()
            lyricsProvider.clearCache()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        artworkPipeline.trimMemory()
        lyricsProvider.clearCache()
    }

    companion object {
        /** 5.1.0: a copy of greetings/greetings.json, used until the first download. */
        const val GREETINGS_SEED_ASSET = "greetings_seed.json"

        @Volatile
        lateinit var instance: MinimApp
            private set
    }

    /**
     * 5.4.0: Save offline. The store lives in files/offline (so clearing the cache keeps it);
     * playback, lyrics and covers look there first. Saving runs in a foreground service so it
     * keeps going on mobile data with the screen off.
     */
    private fun setUpOffline() {
        val store = com.minim.music.data.offline.OfflineStore(com.minim.music.data.offline.OfflineStore.folderIn(filesDir))
        store.cleanPartials()
        offlineStore = store
        (lyricsProvider as? LrcLibLyricsAdapter)?.offlineLyrics = { id -> store.lyricsFor(id) }
        artworkPipeline.offlineArt = { url -> store.artFor(url) }
        val saveClient = okHttpClient.newBuilder()
            .cache(null)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val saver = com.minim.music.data.offline.YoutubeSongSaver(
            store = store,
            options = { id, fresh -> sourceAdapter.audioOptionsBlocking(id, fresh) },
            fetcher = com.minim.music.data.offline.ParallelFetcher(saveClient),
            // 5.7.0 u47: a saved tape keeps no lyrics (its captions load from YouTube when online)
            lyrics = { track -> if (com.minim.music.data.stories.StoryTracks.isStory(track)) null else (lyricsProvider as? LrcLibLyricsAdapter)?.fetchRaw(track) },
            art = { url -> artworkPipeline.fileForSaving(url) }
        )
        offline = com.minim.music.data.offline.OfflineManager(
            store, saver,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        )
    }
}
