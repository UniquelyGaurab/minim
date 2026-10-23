package com.minim.music.playback

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SleepTimerManager(private val playbackController: PlaybackController) {

    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null

    private val _remainingTimeMs = MutableStateFlow(0L)
    val remainingTimeMs: StateFlow<Long> = _remainingTimeMs.asStateFlow()

    fun startTimer(durationMinutes: Int) {
        cancelTimer()
        if (durationMinutes <= 0) return

        _remainingTimeMs.value = durationMinutes * 60L * 1000L
        runnable = object : Runnable {
            override fun run() {
                val current = _remainingTimeMs.value
                if (current > 1000L) {
                    _remainingTimeMs.value = current - 1000L
                    handler.postDelayed(this, 1000L)
                } else {
                    _remainingTimeMs.value = 0L
                    runnable = null
                    playbackController.pause()
                }
            }
        }
        handler.postDelayed(runnable!!, 1000L)
    }

    fun cancelTimer() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        _remainingTimeMs.value = 0L
    }

    val isRunning: Boolean
        get() = runnable != null
}
