package com.minim.music.data.offline

import com.minim.music.data.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 5.4.0: saves one song (audio, lyrics, cover) into the store. Throws when it can't. */
fun interface SongSaver {
    suspend fun save(track: Track, onProgress: (Float) -> Unit)
}

/** 5.4.0: a playlist (or Liked Songs) being saved: how many of its songs are done. */
data class GroupProgress(val name: String, val ids: List<String>, val done: Int, val failed: Int) {
    val total get() = ids.size
    val finished get() = done + failed >= total
}

sealed class OfflineEvent {
    /** [inGroup]: part of a playlist save, so the playlist's own message covers it. */
    data class SongFailed(val track: Track, val reason: String, val inGroup: Boolean = false) : OfflineEvent()
    data class GroupFinished(val key: String, val name: String, val saved: Int, val failed: Int) : OfflineEvent()
}

/**
 * 5.4.0: the "Save offline" queue. Up to [parallel] songs save at the same time (each one also
 * pulls its file in several ranged parts, see [ParallelFetcher]). Nothing is automatic: only
 * the songs the user picked are saved, and songs added to a playlist later are not.
 */
class OfflineManager(
    val store: OfflineStore,
    private val saver: SongSaver,
    private val scope: CoroutineScope,
    parallel: Int = 3
) {
    private val permits = Semaphore(parallel)
    private val lock = Any()
    private val jobs = HashMap<String, Job>()

    /** Songs waiting or saving, by id: 0 = waiting, up to 1 = saved. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    /** Playlists being saved, by key ("pl:<id>" or "liked"). Dropped once every song is done. */
    private val _groups = MutableStateFlow<Map<String, GroupProgress>>(emptyMap())
    val groups: StateFlow<Map<String, GroupProgress>> = _groups

    private val _events = MutableSharedFlow<OfflineEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<OfflineEvent> = _events

    /** Songs finished in the current run of saves (for the notification "3 of 12"). */
    private val _session = MutableStateFlow(0 to 0)
    val session: StateFlow<Pair<Int, Int>> = _session

    val busy: Boolean get() = _progress.value.isNotEmpty()

    fun isSaving(id: String) = _progress.value.containsKey(id)

    /**
     * Saves [tracks] that aren't saved or saving yet. With [groupKey], the playlist tile shows a
     * ring until all of them are done. Returns how many new songs were queued.
     */
    fun save(tracks: List<Track>, groupKey: String? = null, groupName: String = ""): Int {
        val unique = tracks.distinctBy { it.id }.filter { it.id.isNotBlank() }
        val fresh = synchronized(lock) {
            val f = unique.filter { !store.isSaved(it.id) && !jobs.containsKey(it.id) }
            if (groupKey != null) {
                val pending = unique.filter { !store.isSaved(it.id) }.map { it.id }
                if (pending.isNotEmpty()) {
                    _groups.value = _groups.value + (groupKey to GroupProgress(groupName, pending, 0, 0))
                }
            }
            if (f.isNotEmpty()) {
                if (_progress.value.isEmpty()) _session.value = 0 to 0
                _progress.value = _progress.value + f.associate { it.id to 0f }
                _session.value = _session.value.first to (_session.value.second + f.size)
            }
            f.forEach { t -> jobs[t.id] = scope.launch { run(t) } }
            f
        }
        return fresh.size
    }

    private suspend fun run(track: Track) {
        var ok = false
        var reason = ""
        try {
            permits.withPermit {
                var last = 0f
                saver.save(track) { f ->
                    // Small steps only, so the rings don't redraw on every 64 KB
                    if (f - last >= 0.02f || f >= 1f) {
                        last = f
                        synchronized(lock) {
                            if (_progress.value.containsKey(track.id)) {
                                _progress.value = _progress.value + (track.id to f.coerceIn(0f, 0.99f))
                            }
                        }
                    }
                }
            }
            ok = true
        } catch (e: CancellationException) {
            synchronized(lock) { if (_progress.value.containsKey(track.id)) finishLocked(track.id, ok = false, counted = false) }
            throw e
        } catch (e: Exception) {
            reason = e.message ?: e.javaClass.simpleName
        }
        val inGroup = synchronized(lock) { finishLocked(track.id, ok, counted = true) }
        if (!ok) _events.tryEmit(OfflineEvent.SongFailed(track, reason, inGroup))
    }

    /** Returns true when [id] belonged to a playlist being saved. */
    private fun finishLocked(id: String, ok: Boolean, counted: Boolean): Boolean {
        jobs.remove(id)
        var inGroup = false
        _progress.value = _progress.value - id
        if (counted) _session.value = (_session.value.first + 1) to _session.value.second
        val groups = _groups.value.toMutableMap()
        val finished = mutableListOf<Pair<String, GroupProgress>>()
        for ((key, g) in _groups.value) {
            if (id !in g.ids) continue
            inGroup = true
            val next = if (ok) g.copy(done = g.done + 1) else g.copy(failed = g.failed + 1)
            if (next.finished) {
                groups.remove(key)
                finished += key to next
            } else {
                groups[key] = next
            }
        }
        _groups.value = groups
        if (counted) {
            finished.forEach { (key, g) -> _events.tryEmit(OfflineEvent.GroupFinished(key, g.name, g.done, g.failed)) }
        }
        return inGroup
    }

    /** "Remove from offline": stops any save in progress and deletes the saved files. */
    fun remove(ids: Collection<String>) {
        val drop = ids.toSet()
        val cancel = synchronized(lock) {
            // Clear them here too: a save that hasn't started yet never runs its own clean-up
            val c = drop.mapNotNull { jobs.remove(it) }
            val stopped = drop.count { _progress.value.containsKey(it) }
            _progress.value = _progress.value - drop
            if (stopped > 0) {
                _session.value = _session.value.first to maxOf(_session.value.first, _session.value.second - stopped)
            }
            // Drop them from any playlist ring so it doesn't wait for them
            _groups.value = _groups.value.mapNotNull { (k, g) ->
                val left = g.ids.filter { it !in drop }
                val next = g.copy(ids = left, done = minOf(g.done, left.size), failed = minOf(g.failed, left.size - minOf(g.done, left.size)))
                if (left.isEmpty() || next.finished) null else k to next
            }.toMap()
            c
        }
        cancel.forEach { it.cancel() }
        store.remove(drop)
    }

    /** Settings → Remove all: stops every save and deletes everything saved. */
    fun removeAll() {
        val cancel = synchronized(lock) {
            _groups.value = emptyMap()
            _progress.value = emptyMap()
            _session.value = 0 to 0
            val c = jobs.values.toList()
            jobs.clear()
            c
        }
        cancel.forEach { it.cancel() }
        store.removeAll()
        store.cleanPartials()
    }
}

/** 5.4.0: how full the corner rings are (0..1), from the queue's live numbers. */
object OfflineRings {
    /** A playlist being saved: finished songs plus how far the ones saving now have got. */
    fun group(g: GroupProgress, progress: Map<String, Float>): Float {
        if (g.total == 0) return 1f
        val now = g.ids.sumOf { (progress[it] ?: 0f).toDouble() }
        return ((g.done + g.failed + now) / g.total).toFloat().coerceIn(0f, 1f)
    }

    /** Everything saving right now ("3 of 12" plus the songs part-way), for the Offline tile. */
    fun session(session: Pair<Int, Int>, progress: Map<String, Float>): Float? {
        if (progress.isEmpty() || session.second <= 0) return null
        val now = progress.values.sumOf { it.toDouble() }
        return ((session.first + now) / session.second).toFloat().coerceIn(0f, 1f)
    }
}
