package com.minim.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.minim.music.data.local.AppPreferences
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.model.LyricsData
import com.minim.music.data.model.PlaybackUiState
import com.minim.music.data.model.Track
import com.minim.music.data.source.LyricsProvider
import com.minim.music.util.ArtworkPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import android.os.SystemClock
import com.minim.music.data.model.SourceResult

class PlaybackController(
    private val context: Context,
    private val database: LibraryDatabase,
    private val preferences: AppPreferences,
    private val artworkPipeline: ArtworkProviderWrapper,
    private val lyricsProvider: LyricsProvider
) {
    // Secondary constructor for backward compatibility with MinimApp initialization
    constructor(
        context: Context,
        database: LibraryDatabase,
        preferences: AppPreferences,
        artworkPipeline: ArtworkPipeline
    ) : this(
        context,
        database,
        preferences,
        object : ArtworkProviderWrapper {
            override suspend fun extractAccent(url: String?): Int = artworkPipeline.extractAccentForUrl(url)
        },
        com.minim.music.MinimApp.instance.lyricsProvider
    )

    interface ArtworkProviderWrapper {
        suspend fun extractAccent(url: String?): Int
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _lyricsState = MutableStateFlow<LyricsData?>(null)
    val lyricsState: StateFlow<LyricsData?> = _lyricsState.asStateFlow()

    private var mediaController: MediaController? = null
    private var isRestored = false
    private var lyricsTrackId: String? = null
    private var lyricsJob: kotlinx.coroutines.Job? = null

    init {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener({
            try {
                val controller = controllerFuture.get()
                mediaController = controller
                controller.addListener(PlayerListener())
                setupPlayerState(controller)
            } catch (_: Exception) {
            }
        }, MoreExecutors.directExecutor())
    }

    private inner class PlayerListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            updateStateFromController(clearError = playbackState == Player.STATE_READY)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateStateFromController(clearError = isPlaying)
            syncListenTicker()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updateStateFromController()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateStateFromController(clearError = true)
            resetListen(mediaItem?.mediaId)
            maybeTopUpRadio()
            mediaItem?.let { item ->
                val track = Track.fromMediaItem(item)
                fetchLyricsForTrack(track)
                scope.launch {
                    val accent = resolveActiveAccent(track.thumbnailUrl)
                    if (_uiState.value.currentTrack?.id == track.id) {
                        _uiState.value = _uiState.value.copy(accentColor = accent)
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // v4.9.5: PlaybackService is quietly restarting the song after a YouTube 403
            val mediaId = mediaController?.currentMediaItem?.mediaId
            if (StreamRecovery.isRecovering(mediaId, SystemClock.elapsedRealtime())) {
                _uiState.value = _uiState.value.copy(isBuffering = true, errorMessage = null)
                return
            }
            val readableError = StreamRecovery.takeGiveUpMessage(mediaId)
                ?: error.cause?.localizedMessage
                ?: error.localizedMessage
                ?: "Playback failed. Check connection or try another track."
            _uiState.value = _uiState.value.copy(
                isPlaying = false,
                isBuffering = false,
                errorMessage = readableError
            )
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateStateFromController()
        }
    }

    /** Seed colour taken from the cover art (0 = none, the UI then uses the default greens). */
    private suspend fun resolveActiveAccent(thumbnailUrl: String?): Int =
        artworkPipeline.extractAccent(thumbnailUrl)

    private fun fetchLyricsForTrack(track: Track) {
        // Fetch once per track: playTrack() and the resulting media item transition
        // both land here for the same song, so skip the duplicate request.
        if (lyricsTrackId == track.id && _lyricsState.value?.providerName != "none") return
        lyricsTrackId = track.id
        _lyricsState.value = null
        lyricsJob?.cancel()
        lyricsJob = scope.launch {
            val lyrics = try {
                lyricsProvider.getLyrics(track)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (lyricsTrackId == track.id) {
                // An empty result means "no lyrics found" (shown as unavailable), not "still loading"
                _lyricsState.value = lyrics ?: LyricsData(
                    trackId = track.id,
                    lines = emptyList(),
                    isSynced = false,
                    hasWordTimestamps = false,
                    providerName = "none"
                )
            }
        }
    }

    private fun setupPlayerState(controller: MediaController) {
        // Shuffle is no longer offered, so make sure an old "on" state can't linger.
        if (controller.shuffleModeEnabled) controller.shuffleModeEnabled = false
        if (!isRestored) {
            isRestored = true
            val (savedQueue, savedIndex, savedPos) = preferences.restoreQueueState()
            if (savedQueue.isNotEmpty() && savedIndex in savedQueue.indices && controller.mediaItemCount == 0) {
                val items = savedQueue.map { it.toMediaItem() }
                controller.setMediaItems(items, savedIndex, savedPos)
                controller.prepare()
                val initialTrack = savedQueue[savedIndex]
                fetchLyricsForTrack(initialTrack)
                scope.launch {
                    val accent = resolveActiveAccent(initialTrack.thumbnailUrl)
                    _uiState.value = _uiState.value.copy(accentColor = accent)
                }
            }
        }
        updateStateFromController()
        maybeTopUpRadio()
    }

    fun refreshProgress() {
        val c = mediaController ?: return
        if (c.isPlaying) {
            _uiState.value = _uiState.value.copy(
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = if (c.duration > 0) c.duration else _uiState.value.durationMs,
                bufferedPositionMs = c.bufferedPosition.coerceAtLeast(0L)
            )
        }
    }

    private fun updateStateFromController(clearError: Boolean = false) {
        val c = mediaController ?: return
        val currentItem = c.currentMediaItem
        val track = currentItem?.let { Track.fromMediaItem(it) }

        val queue = ArrayList<Track>(c.mediaItemCount)
        for (i in 0 until c.mediaItemCount) {
            c.getMediaItemAt(i)?.let { queue.add(Track.fromMediaItem(it)) }
        }

        val isBuffering = c.playbackState == Player.STATE_BUFFERING
        val isPlaying = c.isPlaying
        val fallbackDurationMs = (track?.durationSec ?: 0L) * 1000L
        val resolvedDurationMs = if (c.duration > 0) c.duration else fallbackDurationMs

        _uiState.value = _uiState.value.copy(
            currentTrack = track,
            queue = queue,
            currentIndex = c.currentMediaItemIndex,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = resolvedDurationMs,
            bufferedPositionMs = c.bufferedPosition.coerceAtLeast(0L),
            repeatMode = c.repeatMode,
            errorMessage = if (clearError) null else _uiState.value.errorMessage
        )

        if (track != null) {
            preferences.saveQueueState(queue, c.currentMediaItemIndex, c.currentPosition)
        }
    }

    /** Plays [track] with [queueList] queued in order (Liked Songs, a playlist, "Play all"). */
    fun playTrack(track: Track, queueList: List<Track> = listOf(track)) {
        setRadio(false)
        startQueue(track, queueList)
    }

    /**
     * v4.3 radio: plays [track] at once, then fills the queue with its YouTube mix (similar songs).
     * If no mix can be fetched, [fallback] (the list the song was tapped in) is queued instead,
     * like before 4.3.
     */
    fun playRadio(track: Track, fallback: List<Track> = emptyList()) {
        setRadio(true)
        radioSession++
        radioSeen.clear()
        radioSeen.add(track.id)
        lastTopUpSeed = track.id
        if (!startQueue(track, listOf(track))) return
        val rest = fallback.filter { it.id != track.id }
        fetchRadio(track.id, radioSession, rest)
    }

    /**
     * v4.6 artist Shuffle: queues [list] (already shuffled) in order, and when it runs low the
     * radio keeps going with similar songs, like any other radio play.
     */
    fun playListThenRadio(list: List<Track>) {
        val first = list.firstOrNull() ?: return
        setRadio(true)
        radioSession++
        radioSeen.clear()
        list.forEach { radioSeen.add(it.id) }
        lastTopUpSeed = null
        startQueue(first, list)
    }

    private fun startQueue(track: Track, queueList: List<Track>): Boolean {
        val c = mediaController ?: return false
        val effectiveQueue = if (queueList.isEmpty()) listOf(track) else queueList
        val index = effectiveQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val items = effectiveQueue.map { it.toMediaItem() }

        _uiState.value = _uiState.value.copy(errorMessage = null, isBuffering = true)
        c.setMediaItems(items, index, 0L)
        c.prepare()
        c.play()

        fetchLyricsForTrack(track)
        scope.launch {
            database.recordPlayback(track)
            val accent = resolveActiveAccent(track.thumbnailUrl)
            if (_uiState.value.currentTrack?.id == track.id) {
                _uiState.value = _uiState.value.copy(accentColor = accent)
            }
        }
        return true
    }

    // ---- v4.3: radio queue -----------------------------------------------------------------------

    private val sourceAdapter get() = com.minim.music.MinimApp.instance.sourceAdapter
    private var radioMode = preferences.radioQueue
    private var radioSession = 0
    private var radioJob: Job? = null
    private var lastTopUpSeed: String? = null
    private val radioSeen = HashSet<String>()

    private fun setRadio(on: Boolean) {
        if (radioMode != on) {
            radioMode = on
            preferences.radioQueue = on
        }
        if (!on) {
            radioJob?.cancel()
            radioJob = null
        }
    }

    /** Over ~10 minutes is usually a full album, a live set or a 1-hour loop, not a song. */
    private fun isSongLength(t: Track): Boolean = t.durationSec <= MAX_RADIO_SONG_SEC

    /** When the radio queue is down to ~3 songs, more come from the mix of the song playing now. */
    private fun maybeTopUpRadio() {
        if (!radioMode) return
        val c = mediaController ?: return
        val cur = c.currentMediaItem ?: return
        val left = c.mediaItemCount - c.currentMediaItemIndex - 1
        if (left > RADIO_LOW_WATER || radioJob?.isActive == true) return
        if (lastTopUpSeed == cur.mediaId) return // one try per seed, so a failure can't loop
        lastTopUpSeed = cur.mediaId
        fetchRadio(cur.mediaId, radioSession, emptyList())
    }

    private fun fetchRadio(seedId: String, session: Int, fallback: List<Track>) {
        radioJob?.cancel()
        radioJob = scope.launch {
            val result = try {
                sourceAdapter.fetchRadio(seedId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SourceResult.Error("radio", e)
            }
            if (session != radioSession || !radioMode) return@launch
            val c = mediaController ?: return@launch
            val queued = HashSet<String>()
            for (i in 0 until c.mediaItemCount) queued.add(c.getMediaItemAt(i).mediaId)
            val blocked = database.notInterestedIds.value
            val mix = (result as? SourceResult.Success)?.data.orEmpty()
            var add = mix.filter {
                it.id !in queued && it.id !in radioSeen && it.id !in blocked && isSongLength(it)
            }.distinctBy { it.id }.take(RADIO_BATCH)
            if (add.isEmpty() && fallback.isNotEmpty()) {
                // No mix (offline, blocked, removed video): queue the tapped list like before 4.3
                add = fallback.filter { it.id !in queued && it.id !in blocked }.distinctBy { it.id }
            }
            if (add.isEmpty()) return@launch
            add.forEach { radioSeen.add(it.id) }
            val ended = c.playbackState == Player.STATE_ENDED
            val nextIndex = c.currentMediaItemIndex + 1
            c.addMediaItems(add.map { it.toMediaItem() })
            if (ended && nextIndex < c.mediaItemCount) {
                c.seekToDefaultPosition(nextIndex)
                c.play()
            }
            trimPlayedRadioItems(c)
            updateStateFromController()
        }
    }

    /** Keeps a long radio session small: at most 20 already-played songs stay behind the current one. */
    private fun trimPlayedRadioItems(c: MediaController) {
        val idx = c.currentMediaItemIndex
        val extra = idx - RADIO_KEEP_BEHIND
        if (extra > 0) c.removeMediaItems(0, extra)
    }

    // ---- v4.3: play tracking (a play counts after 30 s heard) -------------------------------------

    private var listenId: String? = null
    private var listenTrack: Track? = null
    private var listenedMs = 0L
    private var listenCounted = false
    private var listenTicker: Job? = null
    /** Bumped on every new song so a late "play counted" result can't attach to the next song. */
    private var listenSession = 0L
    /** The plays row of the song that's playing, once it counted (-1 before). */
    private var listenPlayRowId = -1L

    // v4.9: real listening time, to the second. Credited every tick while playing (and on
    // pause / skip for the part of a second since the last tick), split by hour so listening
    // across midnight goes to the right day, and saved every few seconds.
    private var lastCreditAt = 0L
    private var lastFlushAt = 0L
    private val pendingListen = LinkedHashMap<String, LibraryDatabase.ListenAdd>()

    private val _playsVersion = MutableStateFlow(0)
    /** Goes up each time a play is counted, so Recents and Discover can refresh. */
    val playsVersion: StateFlow<Int> = _playsVersion.asStateFlow()

    private val _listenVersion = MutableStateFlow(0)
    /** v4.9: goes up each time listening time is saved, so the live Stats can refresh. */
    val listenVersion: StateFlow<Int> = _listenVersion.asStateFlow()

    private fun resetListen(mediaId: String?) {
        // What was heard of the previous song since the last tick still belongs to it
        creditListen(SystemClock.elapsedRealtime())
        flushListen()
        listenId = mediaId
        listenTrack = null
        listenedMs = 0L
        listenCounted = false
        listenPlayRowId = -1L
        listenSession++
    }

    /** Adds the time heard since the last credit to the song that's playing. */
    private fun creditListen(nowRt: Long) {
        val since = lastCreditAt
        if (since == 0L) return
        lastCreditAt = nowRt
        val step = (nowRt - since).coerceIn(0L, MAX_CREDIT_STEP_MS)
        if (step <= 0L) return
        val track = listenTrack ?: return
        listenedMs += step
        val endWall = System.currentTimeMillis()
        for (slice in com.minim.music.data.stats.ListenSplit.split(endWall - step, endWall)) {
            val key = "${slice.day}|${slice.hour}|${track.id}"
            val old = pendingListen[key]
            pendingListen[key] = if (old == null) LibraryDatabase.ListenAdd(slice, track)
            else old.copy(slice = old.slice.copy(ms = old.slice.ms + slice.ms))
        }
    }

    /** Saves the pending listening time (and the counted play's real time so far). */
    private fun flushListen() {
        lastFlushAt = SystemClock.elapsedRealtime()
        if (pendingListen.isEmpty()) return
        val adds = pendingListen.values.toList()
        pendingListen.clear()
        val rowId = listenPlayRowId
        val heard = listenedMs
        scope.launch {
            try {
                database.addListenTime(adds, rowId, heard)
                _listenVersion.value = _listenVersion.value + 1
            } catch (_: Exception) {
            }
        }
    }

    /** v4.9: saves what's been heard right now (the app going to the background). */
    fun flushListening() {
        if (mediaController?.isPlaying == true) creditListen(SystemClock.elapsedRealtime())
        flushListen()
    }

    private fun syncListenTicker() {
        val playing = mediaController?.isPlaying == true
        if (!playing) {
            listenTicker?.cancel()
            listenTicker = null
            creditListen(SystemClock.elapsedRealtime())
            lastCreditAt = 0L
            flushListen()
            return
        }
        if (listenTicker?.isActive == true) return
        lastCreditAt = SystemClock.elapsedRealtime()
        mediaController?.currentMediaItem?.let { item ->
            if (item.mediaId != listenId) resetListen(item.mediaId)
            if (listenTrack == null) listenTrack = Track.fromMediaItem(item)
        }
        listenTicker = scope.launch {
            while (isActive) {
                delay(1000L)
                val c = mediaController ?: break
                if (!c.isPlaying) break
                val item = c.currentMediaItem ?: continue
                if (item.mediaId != listenId) {
                    resetListen(item.mediaId)
                    lastCreditAt = SystemClock.elapsedRealtime()
                }
                listenTrack = Track.fromMediaItem(item)
                val now = SystemClock.elapsedRealtime()
                creditListen(now)
                if (now - lastFlushAt >= LISTEN_FLUSH_MS) flushListen()
                if (listenCounted) continue
                val track = listenTrack ?: Track.fromMediaItem(item)
                val durMs = if (c.duration > 0) c.duration else track.durationSec * 1000L
                // Very short songs count once most of them is heard
                val need = if (durMs in 1L until 37_500L) durMs * 8 / 10 else COUNT_AFTER_MS
                if (listenedMs >= need) {
                    listenCounted = true
                    val heard = listenedMs
                    val session = listenSession
                    launch {
                        try {
                            val rowId = database.recordCountedPlay(track, heard)
                            if (session == listenSession) listenPlayRowId = rowId
                            _playsVersion.value = _playsVersion.value + 1
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    fun playQueueIndex(index: Int) {
        val c = mediaController ?: return
        if (index in 0 until c.mediaItemCount) {
            _uiState.value = _uiState.value.copy(errorMessage = null)
            c.seekToDefaultPosition(index)
            c.play()
        }
    }

    fun addToQueue(track: Track) {
        val c = mediaController ?: return
        c.addMediaItem(track.toMediaItem())
        if (c.mediaItemCount == 1) {
            c.prepare()
        }
        updateStateFromController()
    }

    fun pause() {
        val c = mediaController ?: return
        if (c.isPlaying || c.playWhenReady) {
            c.pause()
        }
        updateStateFromController()
    }

    fun togglePlayPause() {
        val c = mediaController ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            _uiState.value = _uiState.value.copy(errorMessage = null)
            if (c.playbackState == Player.STATE_IDLE) {
                c.prepare()
            } else if (c.playbackState == Player.STATE_ENDED) {
                c.seekToDefaultPosition()
            }
            c.play()
        }
    }

    fun skipNext() {
        val c = mediaController ?: return
        if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
            c.play()
        }
    }

    fun skipPrevious() {
        val c = mediaController ?: return
        if (c.hasPreviousMediaItem() && c.currentPosition < 3000L) {
            c.seekToPreviousMediaItem()
            c.play()
        } else {
            c.seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        val c = mediaController ?: return
        c.seekTo(positionMs.coerceAtLeast(0L))
        updateStateFromController()
    }

    /**
     * 5.5.0: a seek from Now Playing's bar. While playing, the sound dips quickly, jumps and comes
     * back up at the new spot (through the track fade's volume) instead of a hard cut. Paused,
     * or when the fade isn't running, it's a plain seek.
     */
    fun seekToSmooth(positionMs: Long) {
        val c = mediaController ?: return
        val target = positionMs.coerceAtLeast(0L)
        val fade = TrackFadeController.active
        if (c.isPlaying && fade != null && fade.dipAndSeek(target)) return
        seekTo(target)
    }

    fun toggleRepeat() {
        val c = mediaController ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /**
     * v4.8 song menu "Play next": puts the song right after the one that's playing.
     * With nothing in the queue it's the same as Add to queue.
     */
    fun playNext(track: Track) {
        val c = mediaController ?: return
        if (c.mediaItemCount == 0 || c.currentMediaItemIndex < 0) {
            addToQueue(track)
            return
        }
        val at = (c.currentMediaItemIndex + 1).coerceIn(0, c.mediaItemCount)
        c.addMediaItem(at, track.toMediaItem())
        updateStateFromController()
    }

    fun removeQueueItem(index: Int) {
        val c = mediaController ?: return
        if (index in 0 until c.mediaItemCount && index != c.currentMediaItemIndex) {
            c.removeMediaItem(index)
            updateStateFromController()
            // 5.5.1: removing songs from Up Next can leave the radio short; it tops up as usual
            maybeTopUpRadio()
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val c = mediaController ?: return
        // 5.5.1: the playing song itself never moves (it's pinned at the top of Up Next)
        if (fromIndex in 0 until c.mediaItemCount && toIndex in 0 until c.mediaItemCount &&
            fromIndex != toIndex && fromIndex != c.currentMediaItemIndex) {
            c.moveMediaItem(fromIndex, toIndex)
            updateStateFromController()
        }
    }

    fun clearUpcomingQueue() {
        val c = mediaController ?: return
        setRadio(false) // clearing the queue on purpose stops the radio from refilling it
        val currentIdx = c.currentMediaItemIndex
        if (currentIdx < 0) return
        val count = c.mediaItemCount
        if (count > currentIdx + 1) {
            c.removeMediaItems(currentIdx + 1, count)
            updateStateFromController()
        }
    }

    private companion object {
        const val COUNT_AFTER_MS = 30_000L
        /** Listening time is saved at least this often while playing (app killed = ~5 s lost). */
        const val LISTEN_FLUSH_MS = 5_000L
        /** One tick never credits more than this (the phone sleeping, a stalled tick). */
        const val MAX_CREDIT_STEP_MS = 2_000L
        const val MAX_RADIO_SONG_SEC = 600L
        const val RADIO_LOW_WATER = 3
        const val RADIO_BATCH = 25
        const val RADIO_KEEP_BEHIND = 20
    }
}
