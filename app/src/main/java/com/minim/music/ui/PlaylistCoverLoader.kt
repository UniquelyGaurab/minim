package com.minim.music.ui

import android.util.LruCache
import android.view.View
import com.minim.music.data.local.PlaylistCoverStore
import com.minim.music.util.ArtworkPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 6.8.0: turns a playlist's cover choice into what [PlaylistCoverView] draws, for the playlist
 * header and the Library tiles, so the tile and the page always match. Finished looks are kept
 * in a small memory cache so a tile scrolling back in doesn't flash.
 */
object PlaylistCoverLoader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = LruCache<String, PlaylistCoverView.Look>(48)

    /** Everything a cover depends on, so a change (new song first, new word) draws again. */
    fun key(cover: PlaylistLook.Cover, urls: List<String?>, name: String, px: Int): String =
        when (cover.mode) {
            PlaylistLook.Mode.AUTO -> "a|${urls.firstOrNull { !it.isNullOrBlank() }}"
            PlaylistLook.Mode.MOSAIC -> "m|${PlaylistLook.mosaicUrls(urls)}"
            PlaylistLook.Mode.TYPE -> "t|${cover.text}|${cover.color}"
            PlaylistLook.Mode.BLUR -> "b|${cover.url}|$name"
            PlaylistLook.Mode.PHOTO -> "p|${cover.file}"
        } + "|$px"

    fun cached(key: String): PlaylistCoverView.Look? = cache.get(key)

    /** What to draw. Anything missing (no covers yet, a lost photo) falls back to the first song's cover. */
    suspend fun look(
        cover: PlaylistLook.Cover,
        urls: List<String?>,
        name: String,
        px: Int,
        artwork: ArtworkPipeline,
        store: PlaylistCoverStore
    ): PlaylistCoverView.Look {
        val k = key(cover, urls, name, px)
        cache.get(k)?.let { return it }
        val look = build(cover, urls, name, px, artwork, store)
        cache.put(k, look)
        return look
    }

    private suspend fun build(
        cover: PlaylistLook.Cover,
        urls: List<String?>,
        name: String,
        px: Int,
        artwork: ArtworkPipeline,
        store: PlaylistCoverStore
    ): PlaylistCoverView.Look {
        suspend fun auto(): PlaylistCoverView.Look {
            val first = urls.firstOrNull { !it.isNullOrBlank() } ?: return PlaylistCoverView.Look.Filled()
            return PlaylistCoverView.Look.Single(artwork.loadBitmap(first, px))
        }
        return when (cover.mode) {
            PlaylistLook.Mode.AUTO -> auto()
            PlaylistLook.Mode.MOSAIC -> {
                val four = PlaylistLook.mosaicUrls(urls) ?: return auto()
                val bitmaps = kotlinx.coroutines.coroutineScope {
                    four.map { u -> async { artwork.loadBitmap(u, (px / 2).coerceAtLeast(64)) } }.awaitAll()
                }
                PlaylistCoverView.Look.Mosaic(bitmaps)
            }
            PlaylistLook.Mode.TYPE -> PlaylistCoverView.Look.Type(cover.text, cover.color)
            PlaylistLook.Mode.BLUR -> PlaylistCoverView.Look.Blur(artwork.loadBitmap(cover.url, 160), name)
            PlaylistLook.Mode.PHOTO -> {
                val b = withContext(Dispatchers.IO) { store.loadPhoto(cover) }
                if (b != null) PlaylistCoverView.Look.Single(b) else auto()
            }
        }
    }

    /** Draws a cover into [view]; a recycled view that moved on to another playlist is left alone. */
    fun bind(
        view: PlaylistCoverView,
        cover: PlaylistLook.Cover,
        urls: List<String?>,
        name: String,
        artwork: ArtworkPipeline,
        store: PlaylistCoverStore
    ) {
        val px = view.width.takeIf { it > 0 } ?: view.layoutParams?.width?.takeIf { it > 0 } ?: 300
        val k = key(cover, urls, name, px)
        (view.getTag(TAG_JOB) as? Job)?.cancel()
        view.setTag(TAG_KEY, k)
        cache.get(k)?.let { view.show(it); return }
        if (cover.mode == PlaylistLook.Mode.TYPE) {
            view.show(PlaylistCoverView.Look.Type(cover.text, cover.color)); return
        }
        view.show(PlaylistCoverView.Look.Single(null))
        val job = scope.launch {
            val look = try { look(cover, urls, name, px, artwork, store) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { return@launch }
            if (view.getTag(TAG_KEY) == k) view.show(look)
        }
        view.setTag(TAG_JOB, job)
    }

    /** Forgets a view (a tile that now shows something else). */
    fun unbind(view: View) {
        (view.getTag(TAG_JOB) as? Job)?.cancel()
        view.setTag(TAG_KEY, null)
    }

    /** A photo was replaced: its old look must not come back from the cache. */
    fun clear() = cache.evictAll()

    private val TAG_JOB = com.minim.music.R.id.cover_job_tag
    private val TAG_KEY = com.minim.music.R.id.cover_key_tag
}
