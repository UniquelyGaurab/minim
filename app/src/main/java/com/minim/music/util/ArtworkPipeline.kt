package com.minim.music.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import androidx.palette.graphics.Palette
import com.minim.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class ArtworkPipeline(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diskCacheDir = File(context.cacheDir, "artwork_disk").apply { mkdirs() }

    // v4.9.8: HD covers are full-colour bitmaps (a 1080px cover is about 4.7 MB), so the memory cache is
    // an eighth of the app's heap (16-64 MB) instead of a fixed 5 MB that held barely one of them.
    private val bitmapCache = object : LruCache<String, Bitmap>(memoryCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val accentCache = LruCache<String, Int>(200)

    /** v4.9.8: image sizes that answered 404 (maxresdefault for many songs), skipped for a while. */
    private val missingUrls = LruCache<String, Long>(512)
    @Volatile private var writesSinceTrim = 0

    init {
        scope.launch { trimDisk() }
    }

    fun loadInto(
        imageView: ImageView,
        url: String?,
        targetPx: Int,
        extractAccent: Boolean = false,
        onAccentReady: ((Int) -> Unit)? = null
    ) {
        val previousJob = imageView.getTag(R.id.tag_artwork_job) as? Job
        previousJob?.cancel()

        val cleanUrl = url?.trim().orEmpty()
        imageView.setTag(R.id.tag_artwork_url, cleanUrl)

        if (cleanUrl.isEmpty()) {
            ArtworkFade.cancel(imageView)
            imageView.setImageDrawable(null)
            if (extractAccent) {
                onAccentReady?.invoke(getDefaultAccent())
            }
            return
        }

        val cacheKey = "$cleanUrl@$targetPx"
        bitmapCache.get(cacheKey)?.let { cachedBmp ->
            if (!cachedBmp.isRecycled) {
                // v4.9.9: from memory it shows at once, no fade, so scrolling never flickers. The
                // same image already showing (maybe still fading in) is left as it is.
                if (!ArtworkFade.isShowing(imageView, cachedBmp)) {
                    ArtworkFade.cancel(imageView)
                    imageView.setImageBitmap(cachedBmp)
                }
                if (extractAccent) {
                    deliverAccent(cleanUrl, cachedBmp, imageView, onAccentReady)
                }
                return
            }
        }

        ArtworkFade.cancel(imageView)
        imageView.setImageDrawable(null)

        val job = scope.launch {
            val bitmap = fetchAndDecodeDownsampled(cleanUrl, targetPx)
            val accent = if (extractAccent && bitmap != null) {
                getOrExtractPaletteAccent(cleanUrl, bitmap)
            } else {
                getDefaultAccent()
            }

            withContext(Dispatchers.Main) {
                val currentTagUrl = imageView.getTag(R.id.tag_artwork_url) as? String
                if (currentTagUrl == cleanUrl) {
                    if (bitmap != null && !bitmap.isRecycled) {
                        bitmapCache.put(cacheKey, bitmap)
                        // v4.9.9: a cover that had to be fetched or decoded fades in
                        ArtworkFade.show(imageView, bitmap)
                    } else {
                        ArtworkFade.cancel(imageView)
                        imageView.setImageDrawable(null)
                    }
                    if (extractAccent) {
                        onAccentReady?.invoke(accent)
                    }
                }
            }
        }
        imageView.setTag(R.id.tag_artwork_job, job)
    }

    /** v4.9.9: the image if it's already in memory (so it can show at once, without a fade). */
    fun cachedBitmap(url: String?, targetPx: Int): Bitmap? {
        val cleanUrl = url?.trim().orEmpty()
        if (cleanUrl.isEmpty()) return null
        return bitmapCache.get("$cleanUrl@$targetPx")?.takeIf { !it.isRecycled }
    }

    /** v4.6: the decoded image itself (Artist page header, which also reads its brightness). */
    suspend fun loadBitmap(url: String?, targetPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val cleanUrl = url?.trim().orEmpty()
        if (cleanUrl.isEmpty()) return@withContext null
        val cacheKey = "$cleanUrl@$targetPx"
        bitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return@withContext it }
        val bmp = fetchAndDecodeDownsampled(cleanUrl, targetPx) ?: return@withContext null
        bitmapCache.put(cacheKey, bmp)
        bmp
    }

    suspend fun extractAccentForUrl(url: String?): Int = withContext(Dispatchers.IO) {
        val cleanUrl = url?.trim().orEmpty()
        if (cleanUrl.isEmpty()) return@withContext getDefaultAccent()

        accentCache.get(cleanUrl)?.let { return@withContext it }

        val bmp = fetchAndDecodeDownsampled(cleanUrl, 96)
            ?: return@withContext getDefaultAccent()
        getOrExtractPaletteAccent(cleanUrl, bmp)
    }

    private fun deliverAccent(
        url: String,
        bitmap: Bitmap,
        imageView: ImageView,
        onAccentReady: ((Int) -> Unit)?
    ) {
        if (onAccentReady == null) return
        accentCache.get(url)?.let {
            onAccentReady(it)
            return
        }
        scope.launch {
            val color = getOrExtractPaletteAccent(url, bitmap)
            withContext(Dispatchers.Main) {
                if (imageView.getTag(R.id.tag_artwork_url) == url) {
                    onAccentReady(color)
                }
            }
        }
    }

    /**
     * v4.9.8: the image for a view [reqSizePx] across, in full quality. Tries [ArtworkUrls.candidates]
     * in order (an HD size first, the original URL last) and remembers sizes that don't exist for a while.
     * Decoded as ARGB_8888 (4.9.7 and older used RGB_565, which banded gradients and soft covers), sampled
     * down by powers of two only while it stays at least [reqSizePx], and YouTube video frames are cut to a
     * centred square without their black bars.
     */
    private fun fetchAndDecodeDownsampled(url: String, reqSizePx: Int): Bitmap? {
        // 5.4.0: a song saved offline has its cover saved too, so it shows with no signal
        try {
            offlineArt?.invoke(url)?.let { (file, frame) ->
                decodeFile(file, reqSizePx, frame)?.let { return it }
            }
        } catch (_: Exception) {
        }
        for (candidate in ArtworkUrls.candidates(url, reqSizePx)) {
            if (isKnownMissing(candidate)) continue
            val file = fetchToDisk(candidate) ?: continue
            val bmp = decodeFile(file, reqSizePx, ArtworkUrls.isVideoThumb(candidate))
            if (bmp != null) return bmp
            // A file that won't decode: drop it so the next load downloads it again
            file.delete()
        }
        return null
    }

    /**
     * 5.4.0: the saved cover for a thumbnail URL, (file, is a video frame), or null. Set by the
     * app to the offline store's lookup.
     */
    @Volatile var offlineArt: ((String) -> Pair<File, Boolean>?)? = null

    /**
     * 5.4.0: the best cover file for saving a song offline: (file, is a video frame), or null.
     * Blocking; call off the main thread.
     */
    fun fileForSaving(url: String, sizePx: Int = 720): Pair<File, Boolean>? {
        if (url.isBlank()) return null
        for (candidate in ArtworkUrls.candidates(url, sizePx)) {
            if (isKnownMissing(candidate)) continue
            val file = fetchToDisk(candidate) ?: continue
            return file to ArtworkUrls.isVideoThumb(candidate)
        }
        return null
    }

    /** The cached file for [url], downloading it first when needed. Null when it can't be had. */
    private fun fetchToDisk(url: String): File? {
        return try {
            val diskFile = File(diskCacheDir, sha256(url))
            if (diskFile.exists() && diskFile.length() > 0L) {
                touch(diskFile)
                return diskFile
            }
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 404: this size doesn't exist for this image (maxresdefault often doesn't)
                    if (response.code == 404 || response.code == 410) markMissing(url)
                    return null
                }
                val bytes = response.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null
                val part = File(diskCacheDir, diskFile.name + "." + Thread.currentThread().id + ".part")
                FileOutputStream(part).use { it.write(bytes) }
                if (!part.renameTo(diskFile)) {
                    part.delete()
                    return null
                }
            }
            if (++writesSinceTrim >= TRIM_EVERY_WRITES) {
                writesSinceTrim = 0
                trimDisk()
            }
            diskFile
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeFile(file: File, reqSizePx: Int, videoFrame: Boolean): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            val square = if (videoFrame) ArtworkUrls.squareCrop(w, h, videoFrame = true) else null
            val keepW = square?.side ?: w
            val keepH = square?.side ?: h
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(keepW, keepH, reqSizePx, reqSizePx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            if (square == null) return decoded
            // The same square on the sampled-down bitmap
            val crop = ArtworkUrls.squareCrop(decoded.width, decoded.height, videoFrame = true)
            if (crop.side <= 0 || (crop.side == decoded.width && crop.side == decoded.height)) return decoded
            val out = Bitmap.createBitmap(decoded, crop.left, crop.top, crop.side, crop.side)
            if (out !== decoded) decoded.recycle()
            out
        } catch (_: OutOfMemoryError) {
            bitmapCache.evictAll()
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun isKnownMissing(url: String): Boolean {
        val at = missingUrls.get(url) ?: return false
        if (System.currentTimeMillis() - at < MISSING_FOR_MS) return true
        missingUrls.remove(url)
        return false
    }

    private fun markMissing(url: String) {
        missingUrls.put(url, System.currentTimeMillis())
    }

    private fun touch(f: File) {
        val now = System.currentTimeMillis()
        if (now - f.lastModified() > TOUCH_AFTER_MS) f.setLastModified(now)
    }

    /** Keeps the disk cache under [DISK_CACHE_BYTES], dropping the least recently used files. */
    private fun trimDisk() {
        try {
            val files = diskCacheDir.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= DISK_CACHE_BYTES) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= DISK_CACHE_BYTES * 8 / 10) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        } catch (_: Exception) {
            // Only tidying
        }
    }

    /**
     * Picks one seed colour from the cover. The UI turns it into a light, a dark and a muted
     * version of the same hue (and falls back to the default greens for grey covers).
     */
    private fun getOrExtractPaletteAccent(url: String, bitmap: Bitmap): Int {
        accentCache.get(url)?.let { return it }
        val palette = Palette.from(bitmap).generate()
        val dominant = palette.dominantSwatch
        val seed = if (dominant != null && dominant.hsl[1] >= 0.25f && dominant.hsl[2] in 0.1f..0.9f) {
            dominant.rgb
        } else {
            (palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.lightVibrantSwatch ?: dominant)?.rgb ?: 0
        }
        accentCache.put(url, seed)
        return seed
    }

    fun clearAllCaches() {
        bitmapCache.evictAll()
        accentCache.evictAll()
        scope.launch {
            diskCacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    fun trimMemory() {
        bitmapCache.trimToSize(bitmapCache.maxSize() / 2)
    }

    private fun getDefaultAccent(): Int = 0

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    companion object {
        /** v4.9.8: the disk cache keeps the most recently used covers up to this size. */
        const val DISK_CACHE_BYTES = 150L * 1024L * 1024L
        private const val TRIM_EVERY_WRITES = 40
        private const val MISSING_FOR_MS = 6L * 60L * 60L * 1000L
        private const val TOUCH_AFTER_MS = 24L * 60L * 60L * 1000L

        fun memoryCacheBytes(maxHeap: Long = Runtime.getRuntime().maxMemory()): Int =
            (maxHeap / 8L).coerceIn(16L * 1024L * 1024L, 64L * 1024L * 1024L).toInt()

        fun calculateInSampleSize(
            rawWidth: Int,
            rawHeight: Int,
            reqWidth: Int,
            reqHeight: Int
        ): Int {
            var inSampleSize = 1
            if (rawHeight > reqHeight || rawWidth > reqWidth) {
                val halfHeight = rawHeight / 2
                val halfWidth = rawWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight &&
                    (halfWidth / inSampleSize) >= reqWidth
                ) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize.coerceAtLeast(1)
        }

        fun formatDurationSec(seconds: Long): String {
            if (seconds <= 0L) return "0:00"
            val mins = seconds / 60
            val secs = seconds % 60
            return String.format("%d:%02d", mins, secs)
        }
    }
}
