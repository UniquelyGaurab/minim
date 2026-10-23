package com.minim.music

import android.app.Application
import android.content.ComponentCallbacks2
import com.minim.music.data.local.AppPreferences
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.source.LyricsProvider
import com.minim.music.data.source.MusicSourceAdapter
import com.minim.music.data.source.LrcLibLyricsAdapter
import com.minim.music.playback.PlaybackController
import com.minim.music.playback.SleepTimerManager
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

    override fun onCreate() {
        super.onCreate()
        instance = this

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
