package com.minim.music.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.ColorUtils
import java.io.File

/**
 * 5.5.2: the festival wallpaper behind Home. Decodes it at screen size and works out Home's
 * colours from it: dark or light ink for the greeting and pills, and a base colour (the root under
 * Home during fades, and the text inside the filled pills).
 */
object ThemeWallpaper {

    /** Home's look over a wallpaper. */
    data class Look(val light: Boolean, val ink: Int, val bg: Int)

    const val DARK_INK = 0xFF111111.toInt()
    const val LIGHT_INK = 0xFFFFFFFF.toInt()

    /** A wallpaper brighter than this (0..1 luminance where the greeting sits) gets dark text. */
    const val LIGHT_LUMINANCE = 0.55

    /**
     * The look from the wallpaper's average colour [avg] and the average luminance [textLuma]
     * (0..1) of the part behind the greeting and the clock.
     */
    fun look(avg: Int, textLuma: Double): Look {
        val light = textLuma >= LIGHT_LUMINANCE
        val ink = if (light) DARK_INK else LIGHT_INK
        // The base leans away from the ink so a pill filled with ink stays readable on it
        val toward = if (light) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val bg = blend(avg, toward, 0.45f)
        return Look(light, ink, bg)
    }

    /** [a] moved [t] of the way to [b], opaque (plain maths so it runs in unit tests). */
    fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Power-of-two sample size so the picture decodes at about [w] x [h] or a bit bigger. */
    fun sampleSize(srcW: Int, srcH: Int, w: Int, h: Int): Int {
        var s = 1
        while (srcW / (s * 2) >= w && srcH / (s * 2) >= h) s *= 2
        return s
    }

    /** The picture and its look, or null when it can't be read. Run off the main thread. */
    fun decode(file: File, w: Int, h: Int): Pair<Bitmap, Look>? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, w, h)
            }
            val bmp = BitmapFactory.decodeFile(file.path, opts)
            if (bmp == null) null else bmp to lookOf(bmp)
        }
    } catch (e: Throwable) {
        null
    }

    private fun lookOf(bmp: Bitmap): Look {
        val small = Bitmap.createScaledBitmap(bmp, 24, 48, true)
        var r = 0L
        var g = 0L
        var b = 0L
        var lumaSum = 0.0
        var lumaN = 0
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val c = small.getPixel(x, y)
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                // The clock (top strip) and the greeting (the middle) are where text sits
                val fy = y.toFloat() / small.height
                if (fy < 0.08f || (fy > 0.3f && fy < 0.7f)) {
                    lumaSum += ColorUtils.calculateLuminance(c or 0xFF000000.toInt())
                    lumaN++
                }
            }
        }
        val n = (small.width * small.height).toLong()
        val avg = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        if (small !== bmp) small.recycle()
        return look(avg, if (lumaN == 0) 0.0 else lumaSum / lumaN)
    }
}

/**
 * 5.5.2 (update 39): the wallpaper fills the whole screen, behind the status bar and the
 * navigation bar (gesture bar or 3-button nav). Home's content stays inside the safe area; only
 * the wallpaper layer pushes out past the root's padding. Plain maths so it runs in unit tests.
 */
object ThemeBars {

    /** Above this average luminance (0..1) behind a bar, its icons go dark (same as the Artist page). */
    const val LIGHT_LUMINANCE = 0.40

    /** Strip read when a bar has no height (e.g. no nav bar): this fraction of the screen. */
    const val FALLBACK_STRIP = 0.04f

    /** Margins (left, top, right, bottom) that take the wallpaper layer out past the bars. */
    fun edgeToEdgeMargins(left: Int, top: Int, right: Int, bottom: Int): IntArray =
        intArrayOf(-left, -top, -right, -bottom)

    /** The status bar strip as fractions (y0, y1) of a full screen [screenH] tall. */
    fun statusStrip(statusInset: Int, screenH: Int): Pair<Float, Float> {
        if (screenH <= 0) return 0f to FALLBACK_STRIP
        val f = if (statusInset > 0) statusInset.toFloat() / screenH else FALLBACK_STRIP
        return 0f to f.coerceIn(0.005f, 1f)
    }

    /** The navigation bar strip as fractions (y0, y1) of a full screen [screenH] tall. */
    fun navStrip(navInset: Int, screenH: Int): Pair<Float, Float> {
        if (screenH <= 0) return (1f - FALLBACK_STRIP) to 1f
        val f = if (navInset > 0) navInset.toFloat() / screenH else FALLBACK_STRIP
        return (1f - f.coerceIn(0.005f, 1f)) to 1f
    }

    /**
     * The pixels of a [srcW] x [srcH] picture that a centre-cropped [viewW] x [viewH] view shows
     * in rows [y0]..[y1] (fractions of the view), as left, top, right, bottom (right/bottom
     * exclusive). Every column of the view is included.
     */
    fun cropBox(srcW: Int, srcH: Int, viewW: Int, viewH: Int, y0: Float, y1: Float): IntArray {
        if (srcW <= 0 || srcH <= 0 || viewW <= 0 || viewH <= 0) return intArrayOf(0, 0, 0, 0)
        val scale = maxOf(viewW.toFloat() / srcW, viewH.toFloat() / srcH)
        val shownW = viewW / scale
        val shownH = viewH / scale
        val left = (srcW - shownW) / 2f
        val top = (srcH - shownH) / 2f
        val ax = left.toInt().coerceIn(0, srcW - 1)
        val bx = (left + shownW).toInt().coerceIn(ax + 1, srcW)
        val ay = (top + shownH * y0).toInt().coerceIn(0, srcH - 1)
        val by = (top + shownH * y1 + 0.999f).toInt().coerceIn(ay + 1, srcH)
        return intArrayOf(ax, ay, bx, by)
    }

    /** True = light picture behind the bar, so dark icons on it. */
    fun isLight(luminance: Double): Boolean = luminance > LIGHT_LUMINANCE

    /** What the system bars do. */
    data class Plan(
        val lightStatus: Boolean,
        val lightNav: Boolean,
        val navTransparent: Boolean,
        val navContrastEnforced: Boolean,
    )

    /**
     * Over the wallpaper both bars are see-through (no grey scrim on 3-button nav) and their
     * icons follow the picture behind each one; anywhere else exactly the app's usual bars.
     */
    fun plan(
        wallOnTop: Boolean,
        statusOnLight: Boolean,
        navOnLight: Boolean,
        baseLightStatus: Boolean,
        baseLightNav: Boolean,
        baseNavContrast: Boolean,
    ): Plan = if (wallOnTop) {
        Plan(statusOnLight, navOnLight, navTransparent = true, navContrastEnforced = false)
    } else {
        Plan(baseLightStatus, baseLightNav, navTransparent = false, navContrastEnforced = baseNavContrast)
    }
}

/** 5.5.2: the toasts for the festival preview (7 taps on the version, long press for the big day). */
object ThemePreviewText {
    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    fun message(r: com.minim.music.data.themes.PreviewResult): String = when (r) {
        is com.minim.music.data.themes.PreviewResult.On -> "${r.name} preview on"
        is com.minim.music.data.themes.PreviewResult.Off -> "${r.name} preview off"
        is com.minim.music.data.themes.PreviewResult.BigDayOn ->
            "${r.name}: ${MONTHS[(r.date.month - 1).coerceIn(0, 11)]} ${r.date.day} lines on"
        is com.minim.music.data.themes.PreviewResult.BigDayOff -> "${r.name}: back to the usual lines"
        com.minim.music.data.themes.PreviewResult.NoBigDay -> "This theme has no special day"
        com.minim.music.data.themes.PreviewResult.NothingAhead -> "No festival coming up"
        com.minim.music.data.themes.PreviewResult.Offline -> "Couldn't reach GitHub. Try again online"
        com.minim.music.data.themes.PreviewResult.NotPreviewing -> "Tap the version 7 times first"
    }
}
