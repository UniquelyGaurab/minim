package com.minim.music.ui

/**
 * v4.9.9 part 2: the pure maths behind the song change colour fade and the tab switch motion,
 * kept free of Android views so they can be unit tested.
 */
object PaletteFade {

    /** One fade from the old song's colours to the new one's. */
    const val DURATION_MS = 450L

    /**
     * The palette at [t] (0..1, already eased) of a fade from [from] to [to].
     *
     * Background and muted blend channel by channel. Text blends too, unless it flips between light
     * and dark (a Light / Dark theme change, or a seed that lands on the other side): then a blend
     * would pass through the background's own grey and vanish, so the text keeps the old colour
     * until the background reaches the middle and then takes the new one. It always uses whichever
     * of the two reads better on the background of that frame.
     */
    fun at(from: Pal, to: Pal, t: Float): Pal {
        if (t <= 0f) return from
        if (t >= 1f) return to
        val bg = FullscreenLyricsLook.lerpColor(from.bg, to.bg, t)
        val muted = FullscreenLyricsLook.lerpColor(from.muted, to.muted, t)
        val fg = if (textFlips(from, to)) flippingText(from.fg, to.fg, bg, t)
        else FullscreenLyricsLook.lerpColor(from.fg, to.fg, t)
        return Pal(bg, fg, muted)
    }

    /**
     * Text that flips between light and dark: it stays on its old side until the new side reads
     * better on this frame's background, then jumps across once. Near the middle, where the
     * background is a mid tone, each side leans towards pure white or black so it stays readable;
     * at both ends it is the palette's own text colour.
     */
    fun flippingText(old: Int, new: Int, bg: Int, t: Float): Int {
        val oldX = if (isLight(old)) WHITE else BLACK
        val newX = if (isLight(new)) WHITE else BLACK
        val lean = kotlin.math.sqrt((1f - kotlin.math.abs(2f * t - 1f)).coerceIn(0f, 1f))
        return if (crossoverText(oldX, newX, bg) == newX) FullscreenLyricsLook.lerpColor(new, newX, lean)
        else FullscreenLyricsLook.lerpColor(old, oldX, lean)
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** True when the text goes from light to dark or the other way. */
    fun textFlips(from: Pal, to: Pal): Boolean = isLight(from.fg) != isLight(to.fg)

    /** The one of [old] and [new] that reads better on [bg]; ties go to the new colour. */
    fun crossoverText(old: Int, new: Int, bg: Int): Int =
        if (FullscreenLyricsLook.contrast(new, bg) >= FullscreenLyricsLook.contrast(old, bg)) new else old

    /** Same rule as [Painter.isLight]: luminance above one half. */
    fun isLight(c: Int): Boolean = FullscreenLyricsLook.relativeLuminance(c) > 0.5
}

/** Tab switch motion: the content slides a little and fades, the underline glides and resizes. */
object TabMotion {

    /** How far the content slides, in dp. */
    const val SHIFT_DP = 20f
    const val IN_MS = 230L
    const val OUT_MS = 200L
    const val LINE_MS = 260L

    /**
     * +1 when moving to a tab further right (the new content comes in from the right and the old
     * goes out to the left), -1 when moving left.
     */
    fun direction(fromIndex: Int, toIndex: Int): Float = if (toIndex > fromIndex) 1f else -1f

    /** Where the incoming content starts: on the side you're moving towards. */
    fun incomingStart(fromIndex: Int, toIndex: Int, shiftPx: Float): Float = shiftPx * direction(fromIndex, toIndex)

    /** Where the outgoing content ends: the other way. */
    fun outgoingEnd(fromIndex: Int, toIndex: Int, shiftPx: Float): Float = -shiftPx * direction(fromIndex, toIndex)

    /**
     * The new underline starts where the old one is drawn right now (its centre and width, which
     * may be mid-glide after a quick second tap) and glides to its own place and size.
     * Returns translationX and scaleX for the new underline's first frame.
     */
    fun lineStart(fromCenter: Float, fromWidth: Float, toCenter: Float, toWidth: Float): Pair<Float, Float> {
        val scale = if (toWidth > 0f && fromWidth > 0f) fromWidth / toWidth else 1f
        return Pair(fromCenter - toCenter, scale)
    }

    /**
     * A slide that is already partway (a quick second tap) keeps going from where it is, and takes
     * only the share of [fullMs] that is left, so it doesn't slow down or run twice.
     */
    fun remainingMs(fullMs: Long, alphaNow: Float, alphaTarget: Float): Long {
        val left = kotlin.math.abs(alphaTarget - alphaNow).coerceIn(0f, 1f)
        return (fullMs * left).toLong().coerceAtLeast(if (left > 0f) 60L else 0L)
    }
}
