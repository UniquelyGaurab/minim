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

class MinimApp : Application() {

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
    val account: com.minim.music.data.account.MinimAccount by lazy {
        com.minim.music.data.account.MinimAccount(this)
    }

    /** v5.0.0: cloud sync of likes, playlists and listening history (only while signed in). */
    val sync: com.minim.music.data.sync.CloudSync by lazy {
        com.minim.music.data.sync.CloudSync(this, database, account)
    }
    @Volatile private var syncStarted = false

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
        sourceAdapter = MusicSourceAdapter(okHttpClient)
        lyricsProvider = LrcLibLyricsAdapter(okHttpClient)
        artworkPipeline = ArtworkPipeline(this, okHttpClient)
        setUpOffline()
        playbackController = PlaybackController(this, database, preferences, artworkPipeline)
        discoverEngine = com.minim.music.data.discover.DiscoverEngine(this, database, sourceAdapter)
        sleepTimerManager = SleepTimerManager(playbackController)
        syncRemoteGreetings()
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
            lyrics = { track -> (lyricsProvider as? LrcLibLyricsAdapter)?.fetchRaw(track) },
            art = { url -> artworkPipeline.fileForSaving(url) }
        )
        offline = com.minim.music.data.offline.OfflineManager(
            store, saver,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        )
    }
}
