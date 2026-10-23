package com.minim.music.data.source

import com.minim.music.data.model.LyricsData
import com.minim.music.data.model.Track

interface LyricsProvider {
    suspend fun getLyrics(track: Track): LyricsData?
    fun clearCache()
}
