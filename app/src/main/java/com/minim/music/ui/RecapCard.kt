package com.minim.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.minim.music.R
import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.Days
import com.minim.music.data.stats.StatsFormat
import com.minim.music.util.ArtworkPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v4.9.1: the recap summary card, drawn straight onto a 1080 x 1920 (9:16) bitmap so it fits an
 * Instagram story exactly, and what's shared or saved is the same image the story shows.
 * "minim" on top with the date and day under it, the top song's cover, then the key numbers.
 * Colours: the palette built from the top song's cover.
 */
object RecapCard {
    const val W = 1080
    const val H = 1920

    /** The story's and card's colours: the palette built from the day's top song cover. */
    suspend fun palette(artwork: ArtworkPipeline, s: DayStats, darkMode: Boolean): Pal {
        val url = s.topSong?.thumbnailUrl?.takeIf { it.isNotBlank() }
        val seed = if (url != null) artwork.extractAccentForUrl(url) else 0
        return Pal.build(seed, darkMode)
    }

    /** Loads the cover and artist photo (either may be missing) and draws the card off the main thread. */
    suspend fun build(
        context: Context,
        artwork: ArtworkPipeline,
        s: DayStats,
        pal: Pal,
        artistPhotoUrl: String?
    ): Bitmap = withContext(Dispatchers.Default) {
        val cover = s.topSong?.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?.let { runCatching { artwork.loadBitmap(MusicArt.big(it), 560) }.getOrNull() }
        val photo = artistPhotoUrl?.takeIf { it.isNotBlank() }
            ?.let { runCatching { artwork.loadBitmap(it, 144) }.getOrNull() }
        render(context, s, pal, cover, photo)
    }

    fun render(
        context: Context,
        s: DayStats,
        pal: Pal,
        cover: Bitmap?,
        artistPhoto: Bitmap?
    ): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bold = font(context, R.font.poppins_bold, Typeface.DEFAULT_BOLD)
        val semi = font(context, R.font.poppins_semibold, Typeface.DEFAULT_BOLD)
        val regular = font(context, R.font.poppins_regular, Typeface.DEFAULT)
        val soft = ColorUtils.blendARGB(pal.bg, pal.fg, 0.08f)

        c.drawColor(pal.bg)
        val cx = W / 2f

        // minim, then the date and day under the logo
        // v4.9.2: small, regular weight wordmark (like the app header) so the cover stands out
        text(c, "minim", cx, 200f, 52f, regular, pal.fg, W - 160)
        text(c, Days.label(s.day), cx, 272f, 40f, regular, pal.muted, W - 160)

        // Top song cover
        val size = 560f
        val top = 350f
        val rect = RectF(cx - size / 2f, top, cx + size / 2f, top + size)
        val song = s.topSong
        if (cover != null) {
            roundImage(c, cover, rect, 56f)
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = soft }
            c.drawRoundRect(rect, 56f, 56f, p)
            text(c, "♪", cx, rect.centerY() + 70f, 200f, bold, pal.muted, 400)
        }
        var y = top + size + 96f
        if (song != null) {
            text(c, "top song", cx, y - 26f - 30f, 32f, regular, pal.muted, W - 200)
            text(c, song.title, cx, y + 20f, 56f, bold, pal.fg, W - 180)
            val plays = if (song.plays == 1) "1 play" else "${song.plays} plays"
            text(c, "${song.artist}  ·  $plays", cx, y + 82f, 38f, regular, pal.muted, W - 200)
        }

        // time / songs / artists
        y = 1390f
        val colW = (W - 160) / 3f
        val values = listOf(
            StatsFormat.duration(s.timeMs) to "listened",
            s.songs.toString() to if (s.songs == 1) "song" else "songs",
            s.artists.toString() to if (s.artists == 1) "artist" else "artists"
        )
        val box = RectF(80f, y - 130f, W - 80f, y + 90f)
        c.drawRoundRect(box, 56f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = soft })
        for ((i, v) in values.withIndex()) {
            val x = 80f + colW * i + colW / 2f
            text(c, v.first, x, y, 70f, bold, pal.fg, colW.toInt() - 20)
            text(c, v.second, x, y + 56f, 34f, regular, pal.muted, colW.toInt() - 20)
        }

        // Top artist
        val artist = s.topArtist
        if (artist != null) {
            val ay = 1610f
            val r = 72f
            val pillRect = RectF(80f, ay - r - 20f, W - 80f, ay + r + 20f)
            c.drawRoundRect(pillRect, pillRect.height() / 2f, pillRect.height() / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = soft })
            val photoRect = RectF(100f, ay - r, 100f + 2 * r, ay + r)
            if (artistPhoto != null) {
                roundImage(c, artistPhoto, photoRect, r)
            } else {
                c.drawOval(photoRect, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.fg })
                val initial = artist.name.trim().take(1).uppercase()
                text(c, initial, photoRect.centerX(), ay + 24f, 70f, bold, pal.bg, 120)
            }
            val tx = photoRect.right + 36f
            val maxW = (W - 120 - tx).toInt()
            textStart(c, "top artist", tx, ay - 18f, 32f, regular, pal.muted, maxW)
            textStart(c, artist.name, tx, ay + 44f, 52f, semi, pal.fg, maxW)
        }

        text(c, "my day on minim", cx, 1840f, 30f, regular, pal.muted, W - 200)
        return bmp
    }

    private fun font(context: Context, id: Int, fallback: Typeface): Typeface =
        try {
            ResourcesCompat.getFont(context, id) ?: fallback
        } catch (_: Exception) {
            fallback
        }

    private fun paint(size: Float, face: Typeface, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = face
        this.color = color
    }

    /** Centered, cut with … when wider than [maxW]. */
    private fun text(c: Canvas, s: String, cx: Float, baseline: Float, size: Float, face: Typeface, color: Int, maxW: Int) {
        val p = paint(size, face, color).apply { textAlign = Paint.Align.CENTER }
        val t = TextUtils.ellipsize(s, p, maxW.toFloat(), TextUtils.TruncateAt.END).toString()
        c.drawText(t, cx, baseline, p)
    }

    private fun textStart(c: Canvas, s: String, x: Float, baseline: Float, size: Float, face: Typeface, color: Int, maxW: Int) {
        val p = paint(size, face, color)
        val t = TextUtils.ellipsize(s, p, maxW.toFloat(), TextUtils.TruncateAt.END).toString()
        c.drawText(t, x, baseline, p)
    }

    /** Center-cropped into [rect] with rounded corners ([radius] = half the size for a circle). */
    private fun roundImage(c: Canvas, src: Bitmap, rect: RectF, radius: Float) {
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = maxOf(rect.width() / src.width, rect.height() / src.height)
        val dx = rect.left + (rect.width() - src.width * scale) / 2f
        val dy = rect.top + (rect.height() - src.height * scale) / 2f
        shader.setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        c.drawRoundRect(rect, radius, radius, p)
    }
}
