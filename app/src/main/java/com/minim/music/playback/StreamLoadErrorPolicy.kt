package com.minim.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * v4.9.5: a YouTube refusal that the data source could not fix with a fresh link is not retried
 * blindly by ExoPlayer (that would only repeat the same 403). It goes straight to the player
 * error, where PlaybackService restarts the song with another format. Every other error keeps
 * ExoPlayer's normal retries.
 */
@OptIn(UnstableApi::class)
class StreamLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        var cause: Throwable? = loadErrorInfo.exception
        var depth = 0
        while (cause != null && depth < 10) {
            if (cause is YoutubeStreamException) return C.TIME_UNSET
            cause = cause.cause
            depth++
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
