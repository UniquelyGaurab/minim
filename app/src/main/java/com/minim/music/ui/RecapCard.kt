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
 * The recap summary card, drawn straight onto a 1080 x 1920 (9:16) bitmap so it fits a story
 * exactly, and what's shared or saved is the same image the story shows.
 *
 * 5.8.2 (update 54): redesigned. 5.8.3 (update 55): the blurred glow is gone; the top is a solid
 * band in a second shade of the cover's palette; a big cover with the day's listening personality as a pill on its corner;
 * the top song under it; then time listened in large type with songs / artists / tapes beside it,
 * the top artist, and a small "minim" mark. Left-aligned, Poppins, lots of empty space.
 */
object RecapCard {
    const val W = 1080
    const val H = 1920
    private const val M = 96f // side margin

    /** 5.8.3 u55: where the solid top band ends (behind the middle of the big cover). */
    const val BAND_BOTTOM = 666f

    /** The top band: a second, solid shade of the cover's palette (never a blurred cover). */
    fun bandColor(pal: Pal): Int = ColorUtils.blendARGB(pal.bg, pal.fg, 0.12f) or 0xFF000000.toInt()

    /** The story's and card's colours: the palette built from the day's top song cover. */
    suspend fun palette(artwork: ArtworkPipeline, s: DayStats, darkMode: Boolean): Pal {
        val url = coverUrl(s)
        val seed = if (url != null) artwork.extractAccentForUrl(url) else 0
        return Pal.build(seed, darkMode)
    }

    /** The top song's cover, or the top tape's picture on a day with only tapes. */
    fun coverUrl(s: DayStats): String? =
        s.topSong?.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: s.tapes.topTape?.thumbnailUrl?.takeIf { it.isNotBlank() }

    /** Loads the cover and artist photo (either may be missing) and draws the card off the main thread. */
    suspend fun build(
        context: Context,
        artwork: ArtworkPipeline,
        s: DayStats,
        pal: Pal,
        artistPhotoUrl: String?,
        persona: RecapPersonality.Result? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        val cover = coverUrl(s)
            ?.let { runCatching { artwork.loadBitmap(MusicArt.big(it), 720) }.getOrNull() }
        val photo = artistPhotoUrl?.takeIf { it.isNotBlank() }
            ?.let { runCatching { artwork.loadBitmap(it, 160) }.getOrNull() }
        render(context, s, pal, cover, photo, persona)
    }

    fun render(
        context: Context,
        s: DayStats,
        pal: Pal,
        cover: Bitmap?,
        artistPhoto: Bitmap?,
        persona: RecapPersonality.Result? = null
    ): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bold = font(context, R.font.poppins_bold, Typeface.DEFAULT_BOLD)
        val semi = font(context, R.font.poppins_semibold, Typeface.DEFAULT_BOLD)
        val medium = font(context, R.font.poppins_medium, Typeface.DEFAULT)
        val regular = font(context, R.font.poppins_regular, Typeface.DEFAULT)
        // Text made sure to read on both the band and the bg, in Light and Dark mode
        val band = bandColor(pal)
        val fg = RecapInk.textOn(listOf(band, pal.bg), pal.fg)
        val muted = RecapInk.readableOn(listOf(band, pal.bg), pal.muted, fg)
        val soft = ColorUtils.blendARGB(pal.bg, fg, 0.08f)
        val hair = ColorUtils.setAlphaComponent(fg, 36)
        val right = W - M
        val contentW = (W - 2 * M).toInt()

        // 5.8.3 u55: no blurred cover. Two solid colours from the cover: a band in a second shade
        // across the top, ending behind the middle of the big cover, over the palette's bg
        c.drawColor(pal.bg)
        c.drawRect(0f, 0f, W.toFloat(), BAND_BOTTOM, Paint().apply { color = band })

        // Header: the day on the left, "my day in music" on the right
        textStart(c, Days.label(s.day), M, 150f, 38f, medium, fg, 640)
        textEnd(c, "my day in music", right, 150f, 32f, regular, muted, 360)

        // The big cover, with a soft shadow
        val rect = RectF(M, 222f, right, 222f + (W - 2 * M))
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.bg
            setShadowLayer(64f, 0f, 28f, ColorUtils.setAlphaComponent(0xFF000000.toInt(), 80))
        }
        c.drawRoundRect(rect, 40f, 40f, shadow)
        if (cover != null) {
            roundImage(c, cover, rect, 40f)
        } else {
            c.drawRoundRect(rect, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = soft })
            text(c, "♪", rect.centerX(), rect.centerY() + 90f, 260f, bold, muted, 400)
        }

        // The listening personality, as a pill sitting on the cover's bottom-left corner
        if (persona != null) {
            val tp = paint(42f, semi, pal.bg)
            val label = persona.label
            val tw = tp.measureText(label)
            val h = 96f
            val dot = 16f
            val left = M + 40f
            val pill = RectF(left, rect.bottom - h / 2f, left + 44f + dot + 22f + tw + 44f, rect.bottom + h / 2f)
            c.drawRoundRect(pill, h / 2f, h / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fg
                setShadowLayer(30f, 0f, 10f, ColorUtils.setAlphaComponent(0xFF000000.toInt(), 60))
            })
            c.drawCircle(pill.left + 44f + dot / 2f, pill.centerY(), dot / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.bg })
            c.drawText(label, pill.left + 44f + dot + 22f, pill.centerY() + 15f, tp)
        }

        // Top song (or the top tape on a day with only tapes)
        val song = s.topSong
        val tape = s.tapes.topTape
        var y = rect.bottom + 132f
        if (song != null) {
            textStart(c, "top song", M, y, 30f, regular, muted, contentW)
            textStart(c, song.title, M, y + 74f, 64f, bold, fg, contentW)
            val plays = if (song.plays == 1) "1 play" else "${song.plays} plays"
            textStart(c, "${song.artist}  ·  $plays", M, y + 130f, 36f, regular, muted, contentW)
        } else if (tape != null) {
            textStart(c, "top tape", M, y, 30f, regular, muted, contentW)
            textStart(c, tape.title, M, y + 74f, 64f, bold, fg, contentW)
            textStart(c, tape.channel, M, y + 130f, 36f, regular, muted, contentW)
        }

        // Time listened, large, with the counts stacked on the right
        y = 1440f
        c.drawRect(M, y, right, y + 2f, Paint().apply { color = hair })
        textStart(c, "listened", M, y + 70f, 30f, regular, muted, 560)
        textStart(c, StatsFormat.duration(s.totalMs), M, y + 178f, 112f, bold, fg, 600)
        val counts = tiles(s).drop(1)
        var cy = y + 78f + (3 - counts.size).coerceAtLeast(0) * 28f
        for ((i, v) in counts.withIndex()) {
            val face = if (i == 0) semi else regular
            val col = if (i == 0) fg else muted
            textEnd(c, "${v.first} ${v.second}", right, cy, 38f, face, col, 340)
            cy += 56f
        }

        // Top artist (5.7.0 u51: the top channel on a day with tapes and no songs)
        y = 1660f
        c.drawRect(M, y, right, y + 2f, Paint().apply { color = hair })
        val artist = pillPick(s)
        if (artist != null) {
            val r = 52f
            val photoRect = RectF(M, y + 44f, M + 2 * r, y + 44f + 2 * r)
            if (artistPhoto != null) {
                roundImage(c, artistPhoto, photoRect, r)
            } else {
                c.drawOval(photoRect, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg })
                text(c, artist.name.trim().take(1).uppercase(), photoRect.centerX(), photoRect.centerY() + 18f, 52f, bold, pal.bg, 90)
            }
            val tx = photoRect.right + 32f
            val maxW = (right - tx).toInt()
            textStart(c, artist.label, tx, photoRect.centerY() - 10f, 28f, regular, muted, maxW)
            textStart(c, artist.name, tx, photoRect.centerY() + 40f, 46f, semi, fg, maxW)
        }

        // Footer: the personality line, and the small minim mark
        val line = persona?.line ?: ""
        textStart(c, line, M, 1860f, 28f, regular, muted, 700)
        textEnd(c, "minim", right, 1862f, 40f, medium, fg, 200)
        return bmp
    }

    /** 5.7.0 u51: the numbers row: listened (songs and tapes), songs, artists, and tapes when there were any. */
    fun tiles(s: DayStats): List<Pair<String, String>> = buildList {
        add(StatsFormat.duration(s.totalMs) to "listened")
        add(s.songs.toString() to if (s.songs == 1) "song" else "songs")
        add(s.artists.toString() to if (s.artists == 1) "artist" else "artists")
        if (s.tapes.tapes > 0) add(s.tapes.tapes.toString() to if (s.tapes.tapes == 1) "tape" else "tapes")
    }

    class PillPick(val name: String, val label: String, val isChannel: Boolean)

    /** 5.7.0 u51: the pill at the bottom: the top artist, or the top channel when there's no artist. */
    fun pillPick(s: DayStats): PillPick? {
        s.topArtist?.let { return PillPick(it.name, "top artist", false) }
        s.tapes.topChannel?.let { return PillPick(it.name, "top channel", true) }
        return null
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
    private fun textEnd(c: Canvas, s: String, x: Float, baseline: Float, size: Float, face: Typeface, color: Int, maxW: Int) {
        val p = paint(size, face, color).apply { textAlign = Paint.Align.RIGHT }
        val t = TextUtils.ellipsize(s, p, maxW.toFloat(), TextUtils.TruncateAt.END).toString()
        c.drawText(t, x, baseline, p)
    }

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
