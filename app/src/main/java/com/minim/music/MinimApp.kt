package com.minim.music

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

    /** v4.9.6: update checker / downloader for GitHub Releases. */
    lateinit var updates: com.minim.music.data.update.AppUpdates
        private set

    /** v4.9.6: when the update pop-up may show; per process, so a rotation doesn't ask again. */
    val updateGate = com.minim.music.ui.UpdatePromptGate(clock = { android.os.SystemClock.elapsedRealtime() })

    /** v4.9.8: when the quiet update check runs (cold start, then every few hours on coming back). */
    val updateSchedule = com.minim.music.data.update.UpdateCheckSchedule()

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
            override fun onActivityStarted(activity: android.app.Activity) = foreground.onActivityStarted()
            override fun onActivityStopped(activity: android.app.Activity) =
                foreground.onActivityStopped(activity.isChangingConfigurations)
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
        playbackController = PlaybackController(this, database, preferences, artworkPipeline)
        discoverEngine = com.minim.music.data.discover.DiscoverEngine(this, database, sourceAdapter)
        sleepTimerManager = SleepTimerManager(playbackController)
        syncRemoteGreetings()
        updates = com.minim.music.data.update.AppUpdates(this, okHttpClient)
        updates.cleanUp()
    }

    private val greetingsExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "minim-greetings").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }

    /**
     * v4.9.6: loads the greetings file from GitHub (offline copy first, a fresh download about once
     * a day). Safe to call on every app start: it returns at once and does nothing when not due.
     */
    fun syncRemoteGreetings() {
        try {
            greetingsExecutor.execute {
                RemoteGreetingsSync.run(
                    dir = File(filesDir, "greetings"),
                    fetcher = OkHttpGreetingsFetcher(okHttpClient)
                ) { file -> Greetings.remote = file }
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
        @Volatile
        lateinit var instance: MinimApp
            private set
    }
}
