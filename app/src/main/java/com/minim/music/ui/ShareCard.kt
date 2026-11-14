package com.minim.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.minim.music.R
import com.minim.music.util.ArtworkPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 6.6.0 (update 68): the share card, Spotify story style. 1080 x 1920 (9:16), like the daily recap
 * card, and drawn the same way (Poppins, the cover's palette, [RecapInk] for readable text).
 *
 * A full-bleed gradient in the cover's colours (never a blurred cover, as since the recap's 5.8.3),
 * with one light card in the middle: the big cover (a 2x2 collage for a playlist), the title, the
 * artist or the song count, "Shared by <name>" and the minim mark.
 */
object ShareCard {
    const val W = RecapCard.W
    const val H = RecapCard.H

    /** The card in the middle. */
    const val CARD_W = 800f
    private const val PAD = 56f
    private const val COVER = CARD_W - 2 * PAD
    private const val CARD_H = PAD + COVER + 344f
    const val CARD_LEFT = (W - CARD_W) / 2f
    const val CARD_TOP = (H - CARD_H) / 2f - 20f

    /** The card's palette: the dark tone of the cover's colour behind, the light tone on the card. */
    fun palette(seed: Int): Pal = Pal.build(seed, darkMode = true)

    /** The gradient's top colour: a brighter shade of the cover's hue, or a soft grey for grey covers. */
    fun glowColor(pal: Pal): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(pal.fg, hsl)
        return if (hsl[1] < 0.06f) 0xFF3C3C3C.toInt()
        else ColorUtils.HSLToColor(floatArrayOf(hsl[0], minOf(0.72f, hsl[1]), 0.36f))
    }

    /** Loads the cover(s) and draws the card off the main thread. [coverUrls]: one for a song, up to four for a playlist. */
    suspend fun build(
        context: Context,
        artwork: ArtworkPipeline,
        title: String,
        subtitle: String,
        sharer: String?,
        coverUrls: List<String>,
        playlist: Boolean
    ): Pair<Bitmap, Pal> = withContext(Dispatchers.Default) {
        val urls = coverUrls.filter { it.isNotBlank() }.take(if (playlist) 4 else 1)
        val seed = urls.firstOrNull()?.let { runCatching { artwork.extractAccentForUrl(it) }.getOrNull() } ?: 0
        val size = if (playlist && urls.size > 1) 360 else 720
        val covers = urls.mapNotNull { u -> runCatching { artwork.loadBitmap(MusicArt.big(u), size) }.getOrNull() }
        val pal = palette(seed)
        render(context, pal, title, subtitle, sharer, covers, playlist) to pal
    }

    fun render(
        context: Context,
        pal: Pal,
        title: String,
        subtitle: String,
        sharer: String?,
        covers: List<Bitmap>,
        playlist: Boolean
    ): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bold = font(context, R.font.poppins_bold, Typeface.DEFAULT_BOLD)
        val semi = font(context, R.font.poppins_semibold, Typeface.DEFAULT_BOLD)
        val medium = font(context, R.font.poppins_medium, Typeface.DEFAULT)
        val regular = font(context, R.font.poppins_regular, Typeface.DEFAULT)

        // Background: the cover's colours, brighter at the top, down to the dark tone
        val glow = glowColor(pal)
        val bg = pal.bg or 0xFF000000.toInt()
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, H.toFloat(),
                intArrayOf(glow, ColorUtils.blendARGB(glow, bg, 0.55f), bg),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        })
        // a soft light behind the card, so it doesn't sit on a flat wall
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = RadialGradient(
                W / 2f, H * 0.42f, W * 0.75f,
                intArrayOf(ColorUtils.setAlphaComponent(pal.fg, 46), ColorUtils.setAlphaComponent(pal.fg, 0)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        })

        // The card: the light tone, with a soft shadow
        val cardColor = pal.fg or 0xFF000000.toInt()
        val ink = RecapInk.text(cardColor, bg)
        val muted = RecapInk.readable(cardColor, pal.muted, ink)
        val card = RectF(CARD_LEFT, CARD_TOP, CARD_LEFT + CARD_W, CARD_TOP + CARD_H)
        c.drawRoundRect(card, 56f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardColor
            setShadowLayer(80f, 0f, 36f, ColorUtils.setAlphaComponent(0xFF000000.toInt(), 110))
        })

        // The cover, or a 2x2 collage for a playlist
        val art = RectF(card.left + PAD, card.top + PAD, card.right - PAD, card.top + PAD + COVER)
        val soft = ColorUtils.blendARGB(cardColor, ink, 0.08f)
        if (covers.isEmpty()) {
            c.drawRoundRect(art, 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = soft })
            centered(c, "♪", art.centerX(), art.centerY() + 80f, 240f, bold, muted, 400)
        } else if (!playlist || covers.size == 1) {
            roundImage(c, covers[0], art, 32f)
        } else {
            collage(c, covers, art, 32f)
        }

        // Title, then the artist (or "12 songs"), left-aligned like the recap card
        val textW = COVER.toInt()
        var y = art.bottom + 92f
        start(c, title, art.left, y, 56f, bold, ink, textW)
        y += 58f
        start(c, subtitle, art.left, y, 36f, regular, muted, textW)

        // A hairline, then "Shared by <name>" on the left and the minim mark on the right
        y += 46f
        c.drawRect(art.left, y, art.right, y + 2f, Paint().apply { color = ColorUtils.setAlphaComponent(ink, 34) })
        val footer = y + 76f
        val logo = runCatching { BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_round) }.getOrNull()
        val mark = paint(38f, medium, ink)
        val markW = mark.measureText("minim")
        val logoSize = 60f
        val markLeft = art.right - markW
        c.drawText("minim", markLeft, footer, mark)
        val logoRect = RectF(markLeft - 16f - logoSize, footer - 44f, markLeft - 16f, footer - 44f + logoSize)
        if (logo != null) roundImage(c, logo, logoRect, logoSize / 2f)
        val name = com.minim.music.data.share.ShareLinks.cleanName(sharer)
        if (name != null) {
            start(c, "Shared by $name", art.left, footer, 32f, semi, muted, (logoRect.left - 28f - art.left).toInt())
        }
        return bmp
    }

    /** Two, three or four covers in a 2x2 grid (fewer than four repeat), with rounded outer corners. */
    private fun collage(c: Canvas, covers: List<Bitmap>, rect: RectF, radius: Float) {
        val four = List(4) { covers[it % covers.size] }.let {
            // three covers: the fourth tile is the first again, but not next to itself
            if (covers.size == 3) listOf(covers[0], covers[1], covers[2], covers[0]) else it
        }
        val half = rect.width() / 2f
        val save = c.save()
        val clip = android.graphics.Path().apply { addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW) }
        c.clipPath(clip)
        for (i in 0 until 4) {
            val col = i % 2
            val row = i / 2
            val r = RectF(rect.left + col * half, rect.top + row * half, rect.left + (col + 1) * half, rect.top + (row + 1) * half)
            roundImage(c, four[i], r, 0f)
        }
        c.restoreToCount(save)
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

    private fun centered(c: Canvas, s: String, cx: Float, baseline: Float, size: Float, face: Typeface, color: Int, maxW: Int) {
        val p = paint(size, face, color).apply { textAlign = Paint.Align.CENTER }
        c.drawText(TextUtils.ellipsize(s, p, maxW.toFloat(), TextUtils.TruncateAt.END).toString(), cx, baseline, p)
    }

    private fun start(c: Canvas, s: String, x: Float, baseline: Float, size: Float, face: Typeface, color: Int, maxW: Int) {
        if (maxW <= 0 || s.isBlank()) return
        val p = paint(size, face, color)
        c.drawText(TextUtils.ellipsize(s, p, maxW.toFloat(), TextUtils.TruncateAt.END).toString(), x, baseline, p)
    }

    /** Center-cropped into [rect] with rounded corners. */
    private fun roundImage(c: Canvas, src: Bitmap, rect: RectF, radius: Float) {
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = maxOf(rect.width() / src.width, rect.height() / src.height)
        val dx = rect.left + (rect.width() - src.width * scale) / 2f
        val dy = rect.top + (rect.height() - src.height * scale) / 2f
        shader.setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        if (radius <= 0f) c.drawRect(rect, p) else c.drawRoundRect(rect, radius, radius, p)
    }
}
