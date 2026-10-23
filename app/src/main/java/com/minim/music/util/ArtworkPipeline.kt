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

    private val bitmapCache = object : LruCache<String, Bitmap>(5 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val accentCache = LruCache<String, Int>(200)

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
            imageView.setImageDrawable(null)
            if (extractAccent) {
                onAccentReady?.invoke(getDefaultAccent())
            }
            return
        }

        val cacheKey = "$cleanUrl@$targetPx"
        bitmapCache.get(cacheKey)?.let { cachedBmp ->
            if (!cachedBmp.isRecycled) {
                imageView.setImageBitmap(cachedBmp)
                if (extractAccent) {
                    deliverAccent(cleanUrl, cachedBmp, imageView, onAccentReady)
                }
                return
            }
        }

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
                        imageView.setImageBitmap(bitmap)
                    } else {
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

    private fun fetchAndDecodeDownsampled(url: String, reqSizePx: Int): Bitmap? {
        return try {
            val diskFile = File(diskCacheDir, sha256(url))
            if (!diskFile.exists() || diskFile.length() == 0L) {
                val request = Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return null
                    val bytes = response.body?.bytes() ?: return null
                    if (bytes.isEmpty()) return null
                    FileOutputStream(diskFile).use { it.write(bytes) }
                }
            }

            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(diskFile.absolutePath, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                return null
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(
                    boundsOptions.outWidth,
                    boundsOptions.outHeight,
                    reqSizePx,
                    reqSizePx
                )
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(diskFile.absolutePath, decodeOptions)
        } catch (_: Exception) {
            null
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
