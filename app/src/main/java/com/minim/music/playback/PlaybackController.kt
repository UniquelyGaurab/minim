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
import com.minim.music.data.fermata.FermataKv
import com.minim.music.data.fermata.FermataLogger
import com.minim.music.data.fermata.FermataMoves
import com.minim.music.data.fermata.FermataSource
import com.minim.music.data.fermata.FermataStore
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
import kotlinx.coroutines.withContext
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
            // 5.7.0 u46: the last upload of the queue played to the end
            if (playbackState == Player.STATE_ENDED) saveStoryProgress(ended = true)
            // 5.9.0 u59: the queue ran out: the last song's play row is done (not a skip)
            if (playbackState == Player.STATE_ENDED) fermataSafe {
                creditListen(SystemClock.elapsedRealtime())
                fermataPosition()
                fermata?.leave(userMovedOn = false, positionMs = null)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateStateFromController(clearError = isPlaying)
            syncListenTicker()
            syncStoryTicker()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updateStateFromController()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 5.7.0 u46: the upload you leave keeps its place (finished when it moved on by itself)
            onStoryTransition(mediaItem, reason)
            applyTapeSpeed(mediaItem)
            updateStateFromController(clearError = true)
            fermataSafe { fermataOnTransition(mediaItem, reason) }
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

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // 5.8.3 u55: a repeat-one loop keeps the same item, so the controller sends no media
            // item transition for it, only this. Each loop is a new play.
            // Same place in the queue before and after: the same song again (repeat one, or a
            // queue of one on repeat all)
            // 5.9.0 u59: where the song was when the player jumped (a notification or headset next
            // comes in here, before the transition), for the skip row's position
            fermataSafe {
                mediaController?.takeIf { oldPosition.mediaItemIndex in 0 until it.mediaItemCount }
                    ?.getMediaItemAt(oldPosition.mediaItemIndex)?.mediaId
                    ?.let { fermataLastJump = LeaveHint(it, oldPosition.positionMs, 0L) }
            }
            val id = mediaController?.currentMediaItem?.mediaId
            val sameItem = oldPosition.mediaItemIndex == newPosition.mediaItemIndex
            if (RepeatLoop.isLoopDiscontinuity(reason, if (sameItem) id else null, id)) {
                startNewLoop(id)
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
        _captionChoice.value = null
        if (com.minim.music.data.stories.StoryTracks.isStory(track)) {
            // 5.7.0 u44: stories uploads aren't songs, so no lyrics lookup (it would only find wrong ones).
            // 5.7.0 u46: their YouTube captions show instead.
            lyricsJob = scope.launch { loadCaptions(track.id, null) }
            return
        }
        lyricsJob = scope.launch {
            val lyrics = try {
                lyricsProvider.getLyrics(track)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // 5.8.1: no lyrics found, but Gemini wrote some for this song before: those show
            // (saved on the phone, so no request). Real lyrics always win when there are any.
            val aiSaved = if (lyrics == null || lyrics.lines.none { it.text.isNotBlank() }) {
                try {
                    com.minim.music.MinimApp.instance.aiLyrics.saved(track.id)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
            } else null
            if (lyricsTrackId == track.id) {
                // An empty result means "no lyrics found" (shown as unavailable), not "still loading"
                _lyricsState.value = aiSaved ?: lyrics ?: LyricsData(
                    trackId = track.id,
                    lines = emptyList(),
                    isSynced = false,
                    hasWordTimestamps = false,
                    providerName = "none"
                )
            }
        }
    }

    /** 5.8.1: Gemini finished [data] for [trackId]; it shows if that song is still the one playing. */
    fun showAiLyrics(trackId: String, data: LyricsData) {
        if (lyricsTrackId != trackId) return
        val now = _lyricsState.value
        // only over "none found" or older AI lyrics, never over real ones
        if (now != null && now.lines.any { it.text.isNotBlank() } && now.providerName != com.minim.music.data.ai.AI_LYRICS_PROVIDER) return
        _lyricsState.value = data
    }

    // ---- 5.7.0 u46: captions for stories uploads -------------------------------------------------

    /** The caption tracks an upload has, and which one is showing (the switch pill). */
    data class CaptionChoice(
        val trackId: String,
        val options: List<com.minim.music.data.captions.CaptionTrack>,
        val selected: Int
    )

    private val _captionChoice = MutableStateFlow<CaptionChoice?>(null)
    /** Set while an upload with more than one usable caption track is playing. */
    val captionChoice: StateFlow<CaptionChoice?> = _captionChoice.asStateFlow()

    /** Joined caption lines by video + language + kind, so switching back is instant. */
    private val captionLines = object : LinkedHashMap<String, List<com.minim.music.data.captions.Cue>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<com.minim.music.data.captions.Cue>>?) = size > 24
    }

    /**
     * Loads [videoId]'s captions into the lyrics slot. [pick] is a track the user chose with the
     * pill; null takes the best one (the channel's own first, the original language first), and
     * moves down the list when one can't be read.
     */
    private suspend fun loadCaptions(videoId: String, pick: Int?) {
        val C = com.minim.music.data.captions.CaptionLyrics
        val list = sourceAdapter.fetchCaptionTracks(videoId)
        if (lyricsTrackId != videoId) return
        val options = com.minim.music.data.captions.CaptionPicker.choices(list?.tracks.orEmpty(), list?.videoLanguage)
        if (options.isEmpty()) {
            _captionChoice.value = null
            _lyricsState.value = C.none(videoId)
            return
        }
        val order = if (pick != null) listOf(pick) else options.indices.toList()
        for (i in order) {
            val t = options.getOrNull(i) ?: continue
            val lines = captionLinesFor(videoId, t)
            if (lyricsTrackId != videoId) return
            if (lines.isNotEmpty() || pick != null) {
                _captionChoice.value = CaptionChoice(videoId, options, i)
                _lyricsState.value = C.of(videoId, lines)
                return
            }
        }
        _captionChoice.value = null
        _lyricsState.value = C.none(videoId)
    }

    private suspend fun captionLinesFor(videoId: String, t: com.minim.music.data.captions.CaptionTrack): List<com.minim.music.data.captions.Cue> {
        val key = "$videoId|${t.languageTag}|${t.auto}"
        captionLines[key]?.let { return it }
        for (url in com.minim.music.data.captions.CaptionPicker.urlsInOrder(t)) {
            val body = sourceAdapter.fetchCaptionText(url) ?: continue
            val cues = withContext(Dispatchers.Default) {
                com.minim.music.data.captions.CaptionJoiner.join(com.minim.music.data.captions.CaptionParser.parse(body), t.auto)
            }
            if (cues.isNotEmpty()) {
                captionLines[key] = cues
                return cues
            }
        }
        return emptyList()
    }

    /** The caption pill: shows the next of the upload's caption tracks. */
    fun nextCaptionTrack() {
        val ch = _captionChoice.value ?: return
        if (ch.options.size < 2 || lyricsTrackId != ch.trackId) return
        val next = (ch.selected + 1) % ch.options.size
        _captionChoice.value = ch.copy(selected = next)
        lyricsJob?.cancel()
        lyricsJob = scope.launch { loadCaptions(ch.trackId, next) }
    }

    // ---- 5.7.0 u46: where you got to in each stories upload ---------------------------------------

    private val storyProgress get() = com.minim.music.MinimApp.instance.storyProgress
    private val _storiesVersion = MutableStateFlow(0)
    /** Goes up when an upload becomes half-finished or heard, so the stories tab redraws. */
    val storiesVersion: StateFlow<Int> = _storiesVersion.asStateFlow()
    private var storyTicker: Job? = null
    /** The upload being followed, and where it was at the last tick (read after it changed). */
    private var storyTrackedId: String? = null
    private var storyLastPos = 0L
    private var storyLastDur = 0L
    private var storyLastSaveRt = 0L

    // ---- 5.7.0 u47: tapes keep a speed per channel; songs always play at 1x ----------------------

    private val tapeSpeeds by lazy { com.minim.music.data.stories.TapeSpeedStore(context) }

    /** The speed the player is at now (1x while nothing is connected). */
    val playbackSpeed: Float get() = mediaController?.playbackParameters?.speed ?: 1f

    private fun applyTapeSpeed(item: MediaItem?) {
        val c = mediaController ?: return
        val want = tapeSpeeds.speedFor(item?.let { Track.fromMediaItem(it) })
        if (c.playbackParameters.speed != want) c.setPlaybackSpeed(want)
    }

    /** Sets the playing tape's speed and remembers it for its channel. Songs stay at 1x. */
    fun setTapeSpeed(speed: Float) {
        val c = mediaController ?: return
        val item = c.currentMediaItem ?: return
        val track = Track.fromMediaItem(item)
        if (!com.minim.music.data.stories.StoryTracks.isStory(track)) return
        tapeSpeeds.set(track.artist, speed)
        applyTapeSpeed(item)
    }

    private fun isStoryItem(item: MediaItem?): Boolean =
        item != null && com.minim.music.data.stories.StoryTracks.isStory(Track.fromMediaItem(item))

    /** Saves the playing upload's position (no-op for songs). */
    private fun saveStoryProgress(ended: Boolean) {
        val c = mediaController ?: return
        val item = c.currentMediaItem
        if (!isStoryItem(item)) return
        val id = item!!.mediaId
        val dur = c.duration.takeIf { it > 0 } ?: 0L
        val pos = if (ended && dur > 0) dur else c.currentPosition
        storyTrackedId = id
        storyLastPos = pos
        storyLastDur = dur
        recordStory(id, pos, dur, ended)
    }

    private fun recordStory(id: String, pos: Long, dur: Long, ended: Boolean) {
        storyLastSaveRt = SystemClock.elapsedRealtime()
        if (storyProgress.record(id, pos, dur, ended, System.currentTimeMillis())) _storiesVersion.value++
    }

    private fun onStoryTransition(mediaItem: MediaItem?, reason: Int) {
        val prev = storyTrackedId
        if (prev != null && storyLastPos > 0L) {
            val moved = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            recordStory(prev, if (moved && storyLastDur > 0) storyLastDur else storyLastPos, storyLastDur, moved)
        }
        storyTrackedId = null
        storyLastPos = 0L
        storyLastDur = 0L
        if (!isStoryItem(mediaItem)) return
        val c = mediaController ?: return
        val resume = com.minim.music.data.stories.StoryProgress.resumeMs(storyProgress.get(mediaItem!!.mediaId))
        // Skipping to it (or the queue moving on) starts at 0: carry on where you stopped instead
        if (resume > 0L && c.currentPosition < 1_500L && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            c.seekTo(resume)
        }
        storyTrackedId = mediaItem.mediaId
    }

    /** While an upload plays, its position is noted every second and saved every 5 seconds. */
    private fun syncStoryTicker() {
        val c = mediaController
        if (c == null || !c.isPlaying) {
            storyTicker?.cancel()
            storyTicker = null
            saveStoryProgress(ended = false)
            return
        }
        if (storyTicker?.isActive == true) return
        storyTicker = scope.launch {
            while (isActive) {
                val mc = mediaController ?: break
                if (!mc.isPlaying) break
                val item = mc.currentMediaItem
                if (isStoryItem(item)) {
                    val id = item!!.mediaId
                    if (storyTrackedId != id) storyTrackedId = id
                    storyLastPos = mc.currentPosition
                    storyLastDur = mc.duration.takeIf { it > 0 } ?: storyLastDur
                    if (SystemClock.elapsedRealtime() - storyLastSaveRt >= 5_000L) {
                        recordStory(id, storyLastPos, storyLastDur, false)
                    }
                }
                delay(1000L)
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
                // 5.9.0 u59: each entry keeps the source tag it had (none = unknown)
                val sources = fermataSafeGet(emptyList()) {
                    FermataStore.decodeQueueSources(fermataState?.let { FermataStore.loadQueueSources(it) }, savedQueue.map { it.id })
                }
                val items = savedQueue.mapIndexed { i, t -> t.toMediaItem(sources.getOrNull(i)) }
                controller.setMediaItems(items, savedIndex, savedPos)
                controller.prepare()
                applyTapeSpeed(controller.currentMediaItem)
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
            fermataSaveQueueSources(c)
        }
    }

    /** 5.9.0 u59: saves the queue's source tags beside the saved queue, when they changed. */
    private fun fermataSaveQueueSources(c: MediaController) = fermataSafe {
        val kv = fermataState ?: return@fermataSafe
        val n = minOf(c.mediaItemCount, 100)
        val pairs = ArrayList<Pair<String, String?>>(n)
        for (i in 0 until n) {
            val item = c.getMediaItemAt(i)
            pairs.add(item.mediaId to Track.playSourceOf(item))
        }
        val encoded = FermataStore.encodeQueueSources(pairs)
        if (encoded != fermataQueueSourcesSaved) {
            fermataQueueSourcesSaved = encoded
            FermataStore.saveQueueSources(kv, encoded)
        }
    }

    /** Plays [track] with [queueList] queued in order (Liked Songs, a playlist, "Play all"). */
    fun playTrack(track: Track, queueList: List<Track> = listOf(track), source: String? = null) {
        setRadio(false)
        startQueue(track, queueList, source)
    }

    /**
     * v4.3 radio: plays [track] at once, then fills the queue with its YouTube mix (similar songs).
     * If no mix can be fetched, [fallback] (the list the song was tapped in) is queued instead,
     * like before 4.3.
     */
    fun playRadio(track: Track, fallback: List<Track> = emptyList(), source: String? = null) {
        setRadio(true)
        radioSession++
        radioSeen.clear()
        radioSeen.add(track.id)
        lastTopUpSeed = track.id
        if (!startQueue(track, listOf(track), source)) return
        val rest = fallback.filter { it.id != track.id }
        fetchRadio(track.id, radioSession, rest, source)
    }

    /**
     * v4.6 artist Shuffle: queues [list] (already shuffled) in order, and when it runs low the
     * radio keeps going with similar songs, like any other radio play.
     */
    fun playListThenRadio(list: List<Track>, source: String? = null) {
        val first = list.firstOrNull() ?: return
        setRadio(true)
        radioSession++
        radioSeen.clear()
        list.forEach { radioSeen.add(it.id) }
        lastTopUpSeed = null
        startQueue(first, list, source)
    }

    private fun startQueue(track: Track, queueList: List<Track>, source: String? = null): Boolean {
        val c = mediaController ?: return false
        val effectiveQueue = if (queueList.isEmpty()) listOf(track) else queueList
        val index = effectiveQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val items = effectiveQueue.map { it.toMediaItem(source) }
        fermataNoteLeave(c)
        // 5.7.0 u46: an upload you'd started carries on where you stopped
        val startMs = if (com.minim.music.data.stories.StoryTracks.isStory(effectiveQueue[index]))
            com.minim.music.data.stories.StoryProgress.resumeMs(storyProgress.get(effectiveQueue[index].id)) else 0L
        saveStoryProgress(ended = false)
        storyTrackedId = null

        _uiState.value = _uiState.value.copy(errorMessage = null, isBuffering = true)
        c.setMediaItems(items, index, startMs)
        c.prepare()
        c.play()

        fetchLyricsForTrack(track)
        scope.launch {
            // 5.7.0 u44: stories uploads stay out of your song history, stats and Discover
            if (!com.minim.music.data.stories.StoryTracks.isStory(track)) database.recordPlayback(track)
            val accent = resolveActiveAccent(track.thumbnailUrl)
            if (_uiState.value.currentTrack?.id == track.id) {
                _uiState.value = _uiState.value.copy(accentColor = accent)
            }
        }
        return true
    }

    // ---- v4.3: radio queue -----------------------------------------------------------------------

    private val sourceAdapter get() = com.minim.music.MinimApp.instance.sourceAdapter
    private val recommendations get() = com.minim.music.MinimApp.instance.recommendations
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
        fetchRadio(cur.mediaId, radioSession, emptyList(), null)
    }

    private fun fetchRadio(seedId: String, session: Int, fallback: List<Track>, fallbackSource: String?) {
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
            var addSource: String? = FermataSource.RADIO
            val pool = mix.filter {
                it.id !in queued && it.id !in radioSeen && it.id !in blocked && isSongLength(it)
            }.distinctBy { it.id }
            // 6.1: Fermata reorders YouTube's mix (liked songs and artists up, early skips down,
            // nothing heard in the last few hours). Same list back when it isn't ready or there's
            // too little history yet. Off the main thread; it never waits for the model.
            val ordered = if (pool.size > 1) withContext(Dispatchers.Default) { recommendations.reorderRadio(pool) } else pool
            if (session != radioSession || !radioMode) return@launch
            // 6.1.1: the reorder ran off the main thread, so the player (and its queue, and the
            // "not interested" list) are read again: the controller can have been released or
            // reconnected, and the user can have queued or hidden a song meanwhile.
            val player = mediaController ?: return@launch
            val queuedNow = HashSet<String>()
            for (i in 0 until player.mediaItemCount) queuedNow.add(player.getMediaItemAt(i).mediaId)
            val blockedNow = database.notInterestedIds.value
            var add = ordered.filter { it.id !in queuedNow && it.id !in blockedNow }.take(RADIO_BATCH)
            if (add.isEmpty() && fallback.isNotEmpty()) {
                // No mix (offline, blocked, removed video): queue the tapped list like before 4.3
                add = fallback.filter { it.id !in queuedNow && it.id !in blockedNow }.distinctBy { it.id }
                addSource = fallbackSource
            }
            if (add.isEmpty()) return@launch
            add.forEach { radioSeen.add(it.id) }
            val ended = player.playbackState == Player.STATE_ENDED
            val nextIndex = player.currentMediaItemIndex + 1
            player.addMediaItems(add.map { it.toMediaItem(addSource) })
            if (ended && nextIndex < player.mediaItemCount) {
                player.seekToDefaultPosition(nextIndex)
                player.play()
            }
            trimPlayedRadioItems(player)
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
    // across midnight goes to the right day. 6.2.0: saved on pause, stop, song change and the app
    // going to the background, plus a checkpoint every minute while playing (ListenSaves).
    private var lastCreditAt = 0L
    private var lastFlushAt = 0L
    private val pendingListen = com.minim.music.data.stats.ListenBuffer<LibraryDatabase.ListenAdd>({ it.slice }, { a, s -> a.copy(slice = s) })
    /** 5.7.0 u51: tape listening waiting to be saved. */
    private val pendingTapeListen = com.minim.music.data.stats.ListenBuffer<LibraryDatabase.TapeListenAdd>({ it.slice }, { a, s -> a.copy(slice = s) })

    /** 1.5f -> 150. */
    private fun speedPctOf(speed: Float): Int = (speed * 100f + 0.5f).toInt().coerceIn(25, 400)

    private val _playsVersion = MutableStateFlow(0)
    /** Goes up each time a play is counted, so Recents and Discover can refresh. */
    val playsVersion: StateFlow<Int> = _playsVersion.asStateFlow()

    private val _listenVersion = MutableStateFlow(0)
    /** v4.9: goes up each time listening time is saved, so the live Stats can refresh. */
    val listenVersion: StateFlow<Int> = _listenVersion.asStateFlow()

    /** 5.8.3 u55: where the tick last saw the song (for spotting a repeat-one loop), -1 before. */
    private var lastTickPos = -1L

    // ---- 5.9.0 u59: Fermata event log (local only, nothing you see) ------------------------------

    private val fermataState: FermataKv? = try { FermataStore.stateKv(context) } catch (_: Throwable) { null }
    private val fermata: FermataLogger? = try {
        fermataState?.let {
            FermataLogger(
                // 5.10.0: plays, skips, queue adds and tape plays go into the Fermata log
                // (FermataBridge); 5.9.0's fermata_events table is no longer written
                sink = (context.applicationContext as? com.minim.music.MinimApp)?.fermataBridge?.journal
                    ?: FermataStore.noSink,
                kv = it,
                executor = FermataStore.newWriter(),
                deviceIdKv = FermataStore.deviceKv(context)
            )
        }
    } catch (_: Throwable) { null }
    private var fermataQueueSourcesSaved: String? = null

    private data class LeaveHint(val mediaId: String, val positionMs: Long, val durationMs: Long)
    /** Set by the app just before it moves to another song: what was playing, and where. */
    private var fermataLeaveHint: LeaveHint? = null
    /** The last jump the player reported (where a notification or headset next left the song). */
    private var fermataLastJump: LeaveHint? = null

    /** Logging must never break playback: anything thrown in here is dropped. */
    private inline fun fermataSafe(block: () -> Unit) {
        try { block() } catch (_: Throwable) { }
    }

    private inline fun <T> fermataSafeGet(fallback: T, block: () -> T): T =
        try { block() } catch (_: Throwable) { fallback }

    /** Opens [item]'s play row (or, with [loop], a fresh row for the next repeat-one loop). */
    private fun fermataStart(item: MediaItem, loop: Boolean = false) {
        val f = fermata ?: return
        val c = mediaController
        val track = Track.fromMediaItem(item)
        val isTape = com.minim.music.data.stories.StoryTracks.isStory(track)
        val dur = c?.takeIf { it.currentMediaItem?.mediaId == item.mediaId }?.duration?.takeIf { it > 0 }
            ?: (track.durationSec * 1000L)
        val speed = if (isTape) speedPctOf(c?.playbackParameters?.speed ?: 1f) else null
        val source = Track.playSourceOf(item)
        if (loop) f.startLoop(track.id, track.title, track.artist, dur, isTape, source, speed)
        else f.startPlay(track.id, track.title, track.artist, dur, isTape, source, speed)
    }

    /** Where the player is in the song with the open row, for its skip position and length. */
    private fun fermataPosition() {
        val f = fermata ?: return
        val c = mediaController ?: return
        val item = c.currentMediaItem ?: return
        if (item.mediaId != f.openTrackId()) return
        val isTape = com.minim.music.data.stories.StoryTracks.isStory(Track.fromMediaItem(item))
        f.position(item.mediaId, c.currentPosition, c.duration,
            if (isTape) speedPctOf(c.playbackParameters.speed) else null)
    }

    /** The app is about to move to another song: remember where the current one was. */
    private fun fermataNoteLeave(c: MediaController) = fermataSafe {
        val item = c.currentMediaItem ?: return@fermataSafe
        fermataLeaveHint = LeaveHint(item.mediaId, c.currentPosition, c.duration.takeIf { it > 0 } ?: 0L)
    }

    /**
     * A new song in the player. The one that was playing leaves: a play row always, plus a skip
     * row when you moved on yourself (next anywhere, or picking another song) with more than 10
     * seconds left. Then the new song's row opens if it's going to play.
     */
    private fun fermataOnTransition(mediaItem: MediaItem?, reason: Int) {
        val f = fermata ?: return
        val leaving = f.openTrackId()
        val hint = fermataLeaveHint
        val jump = fermataLastJump
        fermataLeaveHint = null
        fermataLastJump = null
        // A repeat-one loop is handled by startNewLoop (one row per loop, like play counts)
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && leaving != null && leaving == mediaItem?.mediaId) return
        if (leaving != null) {
            // What was heard of the leaving song up to now goes on its row
            creditListen(SystemClock.elapsedRealtime())
            val userMovedOn = FermataMoves.isUserMove(reason)
            val from = hint?.takeIf { it.mediaId == leaving } ?: jump?.takeIf { it.mediaId == leaving }
            if (from != null) f.position(leaving, from.positionMs, from.durationMs)
            f.leave(userMovedOn, from?.positionMs)
        }
        val c = mediaController
        if (mediaItem != null && c?.playWhenReady == true) fermataStart(mediaItem)
    }

    /** You queued [track] yourself (Play next / Add to queue in a song menu). */
    fun noteQueuedByHand(track: Track) = fermataSafe {
        fermata?.queued(track.id, track.title, track.artist, track.durationSec * 1000L, FermataSource.QUEUE)
    }

    /**
     * The playback service is going away (app closed, music stopped): saves what's been heard
     * and waits briefly so the open play row's last update lands. It stays the same single row.
     */
    fun flushForShutdown() = fermataSafe {
        flushListening()
        fermata?.flushAndWait(FERMATA_SHUTDOWN_WAIT_MS)
    }

    /**
     * 5.8.3 u55: the same song starting over (repeat one). What was heard so far stays with the
     * loop that just ended; the new loop starts from zero and counts again after 30 seconds.
     */
    private fun startNewLoop(mediaId: String?) {
        val c = mediaController
        val durMs = c?.duration?.takeIf { it > 0 } ?: ((listenTrack?.durationSec ?: 0L) * 1000L)
        if (!RepeatLoop.shouldStartNewLoop(mediaId != null && mediaId == listenId, listenedMs, durMs)) return
        val track = listenTrack
        resetListen(mediaId)
        // Same song: keep crediting it without a gap
        listenTrack = track
        lastTickPos = c?.currentPosition ?: -1L
        // 5.9.0 u59: one Fermata play row per loop too, the song itself as the one before it
        fermataLastJump = null
        fermataSafe { c?.currentMediaItem?.takeIf { it.mediaId == mediaId }?.let { fermataStart(it, loop = true) } }
    }

    private fun resetListen(mediaId: String?) {
        // What was heard of the previous song since the last tick still belongs to it
        creditListen(SystemClock.elapsedRealtime())
        flushListen()
        listenId = mediaId
        listenTrack = null
        listenedMs = 0L
        listenCounted = false
        listenPlayRowId = -1L
        lastTickPos = -1L
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
        fermataSafe { fermata?.heard(track.id, step) }
        // 5.7.0 u44: listening to a tape isn't song listening. 5.7.0 u51: it's tape listening,
        // saved in its own tables (real time heard, with the speed it played at).
        if (com.minim.music.data.stories.StoryTracks.isStory(track)) {
            listenedMs += step
            val speedPct = speedPctOf(mediaController?.playbackParameters?.speed ?: 1f)
            val channelId = com.minim.music.data.stories.StoryTracks.channelIdOf(track).orEmpty()
            val endWall = System.currentTimeMillis()
            for (slice in com.minim.music.data.stats.ListenSplit.split(endWall - step, endWall)) {
                pendingTapeListen.add("${slice.day}|${slice.hour}|${track.id}|$speedPct", LibraryDatabase.TapeListenAdd(slice, track, channelId, speedPct))
            }
            return
        }
        listenedMs += step
        val endWall = System.currentTimeMillis()
        for (slice in com.minim.music.data.stats.ListenSplit.split(endWall - step, endWall)) {
            pendingListen.add("${slice.day}|${slice.hour}|${track.id}", LibraryDatabase.ListenAdd(slice, track))
        }
    }

    /** Saves the pending listening time (and the counted play's real time so far). */
    private fun flushListen() {
        lastFlushAt = SystemClock.elapsedRealtime()
        // 5.9.0 u59: the open Fermata play row gets the time heard so far (an update, never a new row)
        fermataSafe {
            fermataPosition()
            fermata?.flush()
        }
        if (pendingTapeListen.isNotEmpty()) {
            val tapeAdds = pendingTapeListen.drain()
            scope.launch {
                try {
                    database.addTapeListenTime(tapeAdds)
                    _listenVersion.value = _listenVersion.value + 1
                } catch (_: Exception) {
                }
            }
        }
        if (pendingListen.isEmpty()) return
        val adds = pendingListen.drain()
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

    /**
     * 6.2.0: true while the Stats tab is on screen. Listening time is then saved every 5 s, as
     * before 6.2, so the numbers there move live; otherwise once a minute.
     */
    @Volatile
    var liveStats: Boolean = false

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
            // 5.9.0 u59: playback starts: the song's play row opens (if it isn't already)
            fermataSafe { fermataStart(item) }
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
                // 5.8.3 u55: backup for a missed loop: back at the start after being at the end
                val pos = c.currentPosition
                val loopDur = if (c.duration > 0) c.duration else (listenTrack?.durationSec ?: 0L) * 1000L
                if (RepeatLoop.isLoopByPosition(c.repeatMode == Player.REPEAT_MODE_ONE, lastTickPos, pos, loopDur)) {
                    startNewLoop(item.mediaId)
                }
                lastTickPos = pos
                fermataSafe { fermataStart(item) }
                val now = SystemClock.elapsedRealtime()
                creditListen(now)
                if (com.minim.music.data.stats.ListenSaves.due(now, lastFlushAt, if (liveStats) com.minim.music.data.stats.ListenSaves.LIVE_STATS_MS else LISTEN_FLUSH_MS)) flushListen()
                if (listenCounted) continue
                val track = listenTrack ?: Track.fromMediaItem(item)
                val durMs = if (c.duration > 0) c.duration else track.durationSec * 1000L
                // Very short songs count once most of them is heard
                val need = if (durMs in 1L until 37_500L) durMs * 8 / 10 else COUNT_AFTER_MS
                if (listenedMs >= need) {
                    listenCounted = true
                    val heard = listenedMs
                    val session = listenSession
                    if (com.minim.music.data.stories.StoryTracks.isStory(track)) {
                        // 5.7.0 u51: a tape counts after 30 s heard too, in its own table
                        val channelId = com.minim.music.data.stories.StoryTracks.channelIdOf(track).orEmpty()
                        launch {
                            try {
                                database.recordTapePlay(track, channelId)
                                _listenVersion.value = _listenVersion.value + 1
                            } catch (_: Exception) {
                            }
                        }
                        continue
                    }
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
            fermataNoteLeave(c)
            c.seekToDefaultPosition(index)
            c.play()
        }
    }

    /**
     * [byHand] (5.9.0): you chose Add to queue / Play next in a song menu. That writes a Fermata
     * "queued" row and tags the entry "queue"; anything else (Gemini adding songs) is untagged.
     */
    fun addToQueue(track: Track, byHand: Boolean = false) {
        val c = mediaController ?: return
        c.addMediaItem(track.toMediaItem(if (byHand) FermataSource.QUEUE else null))
        if (byHand) noteQueuedByHand(track)
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
            fermataNoteLeave(c)
            c.seekToNextMediaItem()
            c.play()
        }
    }

    fun skipPrevious() {
        val c = mediaController ?: return
        if (c.hasPreviousMediaItem() && c.currentPosition < 3000L) {
            fermataNoteLeave(c)
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
    fun playNext(track: Track, byHand: Boolean = false) {
        val c = mediaController ?: return
        if (c.mediaItemCount == 0 || c.currentMediaItemIndex < 0) {
            addToQueue(track, byHand)
            return
        }
        val at = (c.currentMediaItemIndex + 1).coerceIn(0, c.mediaItemCount)
        c.addMediaItem(at, track.toMediaItem(if (byHand) FermataSource.QUEUE else null))
        if (byHand) noteQueuedByHand(track)
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
        /** 5.9.0: how long the service's shutdown waits for the last Fermata save. */
        const val FERMATA_SHUTDOWN_WAIT_MS = 300L
        const val COUNT_AFTER_MS = 30_000L
        /** Listening time is saved at least this often while playing (app killed = up to a minute lost). 6.2.0: was 5 s. */
        const val LISTEN_FLUSH_MS = com.minim.music.data.stats.ListenSaves.CHECKPOINT_MS
        /** One tick never credits more than this (the phone sleeping, a stalled tick). */
        const val MAX_CREDIT_STEP_MS = 2_000L
        const val MAX_RADIO_SONG_SEC = 600L
        const val RADIO_LOW_WATER = 3
        const val RADIO_BATCH = 25
        const val RADIO_KEEP_BEHIND = 20
    }
}
