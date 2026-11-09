package com.minim.music.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.minim.music.MinimApp
import com.minim.music.data.model.Track
import com.minim.music.data.source.YoutubeStreamRequest
import java.util.concurrent.TimeUnit

/**
 * 5.8.2 (update 54): the day's top song, playing softly behind the recap story.
 *
 * Its own small player, apart from the main one, so the story never touches your queue, your
 * place in it, the notification, or your history and stats (a recap play is never counted). It
 * fetches the song the same way the main player does (a song saved offline plays from the phone),
 * starts a little way in, fades in, loops, and fades out when the story closes.
 */
@OptIn(UnstableApi::class)
class RecapMusic(context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val player: ExoPlayer
    private var released = false
    private var muted = false
    private var level = 0f
    private var fade: Runnable? = null
    private var started = false

    init {
        val app = MinimApp.instance
        val plain = OkHttpDataSource.Factory(app.okHttpClient).setUserAgent(YoutubeStreamRequest.DESKTOP_USER_AGENT)
        val client = app.okHttpClient.newBuilder().cache(null).readTimeout(20, TimeUnit.SECONDS).build()
        val source = YoutubeChunkedDataSource.Factory(
            client,
            { videoId, excluded, preferItag, fresh ->
                app.sourceAdapter.resolveStreamBlocking(videoId, excluded, preferItag, fresh)
            },
            plain,
            { videoId -> app.offlineStore.audioFor(videoId) }
        )
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 20_000, 1_500, 3_000)
            .setTargetBufferBytes(2 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        player = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context.applicationContext).setDataSourceFactory(source))
            .setLoadControl(load)
            // No audio focus: the main player is paused for the story, and nothing else should stop
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false
            )
            .setHandleAudioBecomingNoisy(false)
            .build()
        player.volume = 0f
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !started) {
                    started = true
                    val dur = player.duration
                    val at = startAt(if (dur > 0) dur else 0L)
                    if (at > 0L) player.seekTo(at)
                    fadeTo(if (muted) 0f else VOLUME, 1_600L)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // No song behind the story (offline, blocked): the story itself carries on silently
            }
        })
    }

    fun play(track: Track) {
        if (released) return
        started = false
        player.setMediaItem(track.toMediaItem())
        player.prepare()
        player.play()
    }

    fun setMuted(m: Boolean) {
        muted = m
        if (started) fadeTo(if (m) 0f else VOLUME, 350L)
    }

    /** Holds while the app is in the background, carries on when it's back. */
    fun pause() { if (!released) player.pause() }
    fun resume() { if (!released) player.play() }

    /** Fades out, then lets the player go. [then] runs on the main thread once it's quiet. */
    fun stop(then: () -> Unit = {}) {
        if (released) { then(); return }
        fadeTo(0f, if (player.isPlaying && level > 0.01f) 450L else 0L) {
            release()
            then()
        }
    }

    fun release() {
        if (released) return
        released = true
        fade?.let { main.removeCallbacks(it) }
        fade = null
        try { player.release() } catch (_: Exception) {}
    }

    private fun fadeTo(target: Float, ms: Long, done: (() -> Unit)? = null) {
        fade?.let { main.removeCallbacks(it) }
        val from = level
        if (ms <= 0L) {
            level = target
            if (!released) player.volume = target
            done?.invoke()
            return
        }
        val start = android.os.SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (released) { done?.invoke(); return }
                val f = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / ms).coerceIn(0f, 1f)
                level = from + (target - from) * f
                player.volume = level
                if (f < 1f) main.postDelayed(this, 16L) else { fade = null; done?.invoke() }
            }
        }
        fade = r
        main.post(r)
    }

    companion object {
        /** Softer than the song normally plays, so it sits behind the story. */
        const val VOLUME = 0.7f

        /**
         * Where the song starts: about a third in (usually past the intro, near the chorus),
         * never so late that it ends almost at once. Short or unknown lengths start at the top.
         */
        fun startAt(durationMs: Long): Long {
            if (durationMs < 60_000L) return 0L
            return (durationMs * 0.3).toLong().coerceAtMost(durationMs - 45_000L).coerceAtLeast(0L)
        }
    }
}
