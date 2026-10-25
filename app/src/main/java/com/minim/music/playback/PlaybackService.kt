package com.minim.music.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.minim.music.MinimApp
import com.minim.music.data.source.YoutubeStreamRequest
import java.util.concurrent.TimeUnit
import com.minim.music.ui.MainActivity

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var trackFadeController: TrackFadeController? = null
    private var volumeBoostController: VolumeBoostController? = null
    private val audioSettingsListener: () -> Unit = {
        trackFadeController?.refresh()
        volumeBoostController?.apply()
    }
    lateinit var player: ExoPlayer
        private set

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val app = MinimApp.instance

        // Plain OkHttp source for anything that is not a YouTube stream (HLS fallback etc.)
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(app.okHttpClient)
            .setUserAgent(YoutubeStreamRequest.DESKTOP_USER_AGENT)

        // v4.9.5: YouTube audio goes through a client without the HTTP cache (POST pieces are
        // never cached anyway) and with a slightly longer read timeout for slow networks
        val streamClient = app.okHttpClient.newBuilder()
            .cache(null)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        // v4.9.5: minim://track/{videoId} is resolved and fetched in NewPipe-style pieces
        // (range + rn in the URL, matching User-Agent), with a fresh link on a mid-song 403
        val streamDataSourceFactory = YoutubeChunkedDataSource.Factory(
            streamClient,
            { videoId, excluded, preferItag, fresh ->
                app.sourceAdapter.resolveStreamBlocking(videoId, excluded, preferItag, fresh)
            },
            okHttpDataSourceFactory
        )

        // Bounded audio buffer sizes tailored for 2 GB RAM devices
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs
                45_000, // maxBufferMs
                1_500,  // bufferForPlaybackMs
                3_000   // bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(4 * 1024 * 1024) // Cap audio buffer at 4 MB
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(streamDataSourceFactory)
                    .setLoadErrorHandlingPolicy(StreamLoadErrorPolicy())
            )
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // v4.9.5: added before the MediaSession so it runs first and the UI already knows a
        // quiet restart is under way when the error reaches it
        player.addListener(streamRecoveryListener)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()

        // Track fade in/out based on the saved fade setting (Off / 2 s / 4 s), read live on every tick
        trackFadeController = TrackFadeController(player) { app.preferences.crossfadeSeconds }.also { it.start() }

        // v4.4: volume boost (Off / Normal / Loud) via LoudnessEnhancer on the player's audio session
        volumeBoostController = VolumeBoostController(player) { app.preferences.volumeBoost }.also { it.start() }
        app.preferences.addAudioSettingsListener(audioSettingsListener)
    }

    /** v4.9.5: restarts a song from the same spot with another format after a YouTube 403. */
    private val streamRecoveryListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val failure = findStreamFailure(error) ?: return
            if (failure.responseCode != 403 && failure.responseCode != 410 && failure.responseCode != 401) return
            val item = player.currentMediaItem ?: return
            if (item.mediaId != failure.videoId) return
            val retry = StreamRecovery.onStreamFailure(
                failure.videoId, failure.itag, failure.otherOptions, SystemClock.elapsedRealtime()
            )
            if (!retry) return
            val index = player.currentMediaItemIndex
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            recoveryHandler.post {
                if (player.currentMediaItem?.mediaId != failure.videoId) return@post
                player.seekTo(index, positionMs)
                player.prepare()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            StreamRecovery.reset(mediaItem?.mediaId)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) StreamRecovery.onPlaying(player.currentMediaItem?.mediaId)
        }
    }

    private val recoveryHandler = Handler(Looper.getMainLooper())

    private fun findStreamFailure(error: Throwable): YoutubeStreamException? {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 10) {
            if (cause is YoutubeStreamException) return cause
            cause = cause.cause
            depth++
        }
        return null
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 5.2.0: swiping Minim away from Recents closes the app, so the Gemini chat is forgotten
        // even while the music keeps playing
        MinimApp.instance.wipeGeminiChat()
        val playerInstance = mediaSession?.player
        if (playerInstance == null || !playerInstance.playWhenReady || playerInstance.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        recoveryHandler.removeCallbacksAndMessages(null)
        MinimApp.instance.preferences.removeAudioSettingsListener(audioSettingsListener)
        trackFadeController?.release()
        trackFadeController = null
        volumeBoostController?.release()
        volumeBoostController = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
