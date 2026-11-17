package com.minim.music.ui

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.minim.music.util.ArtworkFade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * 6.9.0 b76: the images of a Reprise story (Daily, Weekly, Monthly).
 *
 * - Each image has a list of URLs, tried in order: the cover itself, then the song's own video frame
 *   (track ids are YouTube video ids), so a missing or broken cover URL still finds a picture.
 * - A slide can bind before its image has arrived: the view shows its soft colour (or the letter for
 *   a photo) and is updated in place when the image lands, even mid-animation or mid-transition.
 * - Upcoming slides are fetched ahead ([prefetch]); a bind for the same image joins that fetch.
 * - The fallback (letter) only shows when every URL failed or there was none.
 *
 * Main-thread only (binds, prefetches and the in-flight table); the loads themselves run off it.
 */
class StoryArt(
    private val scope: CoroutineScope,
    /** Decodes [url] for a view [px] across (the app's artwork pipeline: memory, disk, then network). */
    private val load: suspend (url: String, px: Int) -> Bitmap?,
    /** The image if it's already decoded in memory, so it shows at once. */
    private val cached: (url: String, px: Int) -> Bitmap?,
    private val show: (ImageView, Bitmap) -> Unit = { v, b -> ArtworkFade.show(v, b) }
) {
    private val inflight = HashMap<String, Deferred<Bitmap?>>()
    private val bound = WeakHashMap<ImageView, String>()
    private val jobs = WeakHashMap<ImageView, Job>()

    /** Shows the first of [candidates] that loads in [img]; [fallback] only when none does. */
    fun bind(img: ImageView, candidates: List<String>, px: Int, fallback: (() -> Drawable?)? = null, placeholder: (() -> Drawable?)? = null) {
        val key = keyOf(candidates, px)
        jobs.remove(img)?.cancel()
        bound[img] = key
        for (c in candidates) {
            val hit = cached(c, px)
            if (hit != null && !hit.isRecycled) {
                img.setImageBitmap(hit)
                return
            }
        }
        if (candidates.isEmpty()) {
            img.setImageDrawable(fallback?.invoke())
            return
        }
        img.setImageDrawable(placeholder?.invoke())
        val fetch = fetch(candidates, px)
        jobs[img] = scope.launch {
            val bmp = try { fetch.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            // A newer bind on the same view wins
            if (bound[img] != key) return@launch
            if (bmp != null && !bmp.isRecycled) show(img, bmp) else img.setImageDrawable(fallback?.invoke())
        }
    }

    /** Starts fetching an image ahead of its slide (no view yet). */
    fun prefetch(candidates: List<String>, px: Int) {
        if (candidates.isEmpty()) return
        if (candidates.any { cached(it, px) != null }) return
        fetch(candidates, px)
    }

    /** Whether [img]'s last bind is still waiting for its image. */
    fun pending(img: ImageView): Boolean = jobs[img]?.isActive == true

    private fun fetch(candidates: List<String>, px: Int): Deferred<Bitmap?> {
        val key = keyOf(candidates, px)
        inflight[key]?.let { if (!it.isCancelled) return it }
        val d = scope.async {
            var found: Bitmap? = null
            for (c in candidates) {
                found = try { load(c, px) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (found != null && !found.isRecycled) break
                found = null
            }
            found
        }
        inflight[key] = d
        // A failed fetch can be tried again by a later bind; a finished one is in the pipeline's cache
        d.invokeOnCompletion { scope.launch { if (inflight[key] === d) inflight.remove(key) } }
        return d
    }

    companion object {
        private fun keyOf(candidates: List<String>, px: Int) = candidates.joinToString("|") + "@" + px

        /** The song's own YouTube video frame (a track id is its video id), or null for other ids. */
        fun videoFrame(trackId: String?): String? =
            trackId?.trim()?.takeIf { isVideoId(it) }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

        /** An 11-character YouTube video id (letters, digits, - and _). */
        fun isVideoId(s: String): Boolean = s.length == 11 && s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

        /** The URLs to try for a song's cover: the big cover, the cover as saved, then its video frame. */
        fun coverCandidates(url: String?, trackId: String?, big: (String) -> String = { it }): List<String> {
            val out = LinkedHashSet<String>()
            url?.trim()?.takeIf { it.isNotEmpty() }?.let {
                val b = try { big(it) } catch (_: Exception) { it }
                if (b.isNotBlank()) out.add(b)
                out.add(it)
            }
            videoFrame(trackId)?.let { out.add(it) }
            return out.toList()
        }

        /** The URLs to try for a round photo (artist or channel). */
        fun photoCandidates(url: String?): List<String> = listOfNotNull(url?.trim()?.takeIf { it.isNotEmpty() })

        /**
         * The size to decode an image shown [viewPx] across: room for the slides' scale-ups (up to
         * 1.15x) so a grown cover stays sharp, in a few fixed steps so a prefetch and a bind share one
         * decoded copy.
         */
        fun decodePx(viewPx: Int): Int {
            val want = (viewPx * 1.15f).toInt()
            return when {
                want <= 160 -> 160
                want <= 320 -> 320
                want <= 480 -> 480
                else -> 720
            }
        }

        /** The Guess slide's grow when a cover is picked. */
        const val GUESS_GROW = 1.12f

        /**
         * The Guess covers on a screen [screenWidthDp] wide: up to 96dp, three across inside the slide's
         * 36dp side padding with 12dp gaps, so the picked one can grow [GUESS_GROW]x and stay whole.
         */
        fun guessTileDp(screenWidthDp: Float): Float {
            val avail = screenWidthDp - 72f
            return ((avail - 2 * 12f) / 3f).coerceIn(56f, 96f)
        }

        /**
         * Lets a slide's scale-ups, overshoots and shadows draw past their parents' bounds and padding,
         * so a cover that grows is never cut off. Rounded corners still come from each image's own outline.
         */
        fun noClip(v: View) {
            if (v is ViewGroup) {
                v.clipChildren = false
                v.clipToPadding = false
                for (i in 0 until v.childCount) noClip(v.getChildAt(i))
            }
        }
    }
}
