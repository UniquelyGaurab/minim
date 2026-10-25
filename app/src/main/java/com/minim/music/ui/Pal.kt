package com.minim.music.ui

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.minim.music.R

/**
 * The three colours every screen is drawn with.
 *  - bg: screen background (light in Light mode, dark in Dark mode)
 *  - fg: text, filled shapes and pills (dark in Light mode, light in Dark mode)
 *  - muted: faded lyric lines and the unplayed part of the progress bar
 */
data class Pal(val bg: Int, val fg: Int, val muted: Int) {

    fun lerp(to: Pal, t: Float): Pal = Pal(
        ColorUtils.blendARGB(bg, to.bg, t),
        ColorUtils.blendARGB(fg, to.fg, t),
        ColorUtils.blendARGB(muted, to.muted, t)
    )

    companion object {
        // v4.7: the default (nothing playing) is black and white only. Until 4.6.1 these were
        // the Canva greens #EEFEE1 / #2E6801 / #8CB36F.
        const val DESIGN_LIGHT = 0xFFFFFFFF.toInt()
        const val DESIGN_DARK = 0xFF000000.toInt()
        const val DESIGN_MUTED = 0xFF8C8C8C.toInt()

        /**
         * Builds the palette from a cover-art seed colour (0 = no cover / nothing playing).
         * One light and one dark version of the same hue, plus a muted in-between.
         * Grey or extreme covers fall back to black and white (v4.7), and the dark tone is pushed
         * darker until text stays clearly readable on the light tone.
         */
        fun build(seed: Int, darkMode: Boolean): Pal {
            var light = DESIGN_LIGHT
            var dark = DESIGN_DARK
            var muted = DESIGN_MUTED
            if (seed != 0) {
                val hsl = FloatArray(3)
                ColorUtils.colorToHSL(seed, hsl)
                val (h, s, l) = Triple(hsl[0], hsl[1], hsl[2])
                if (s >= 0.15f && l in 0.07f..0.95f) {
                    val k = ((s - 0.15f) / 0.45f).coerceIn(0f, 1f)
                    val darkS = 0.55f + 0.43f * k
                    light = ColorUtils.HSLToColor(floatArrayOf(h, 0.45f + 0.48f * k, 0.94f))
                    var darkL = 0.21f
                    dark = ColorUtils.HSLToColor(floatArrayOf(h, darkS, darkL))
                    while (ColorUtils.calculateContrast(dark, light) < 6.0 && darkL > 0.08f) {
                        darkL -= 0.015f
                        dark = ColorUtils.HSLToColor(floatArrayOf(h, darkS, darkL))
                    }
                    muted = ColorUtils.HSLToColor(floatArrayOf(h, 0.18f + 0.14f * k, 0.57f))
                }
            }
            return if (darkMode) Pal(bg = dark, fg = light, muted = muted)
            else Pal(bg = light, fg = dark, muted = muted)
        }
    }
}

/**
 * Colours views from the `android:tag` roles written in the layouts:
 *   bg            -> background colour = bg
 *   fill:fg|bg    -> background drawable tinted (filled or outlined shapes)
 *   text:fg|bg|muted -> text colour
 *   tint:fg|bg    -> icon tint
 *   opt           -> settings option pill (filled when selected, outlined otherwise)
 *   unpainted     -> this view and everything inside keep their own colours (v4.9.8: the full
 *                    screen lyrics colour themselves from the cover, not from the palette)
 */
object Painter {

    const val UNPAINTED = "unpainted"

    fun paint(view: View, p: Pal) {
        val tag = view.tag as? String
        if (tag == UNPAINTED) return
        if (tag != null) paintOne(view, tag, p)
        if (view is ProgressLine) view.setColors(p.fg, p.muted)
        if (view is AvatarView) view.setColors(p.fg)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) paint(view.getChildAt(i), p)
        }
    }

    private fun pick(name: String, p: Pal): Int = when (name) {
        "bg" -> p.bg
        "muted" -> p.muted
        // v4.8: soft tint of the main colour, for the pill rows in sheets
        "soft" -> ColorUtils.setAlphaComponent(p.fg, 0x1C)
        else -> p.fg
    }

    private fun paintOne(view: View, tag: String, p: Pal) {
        for (token in tag.split(' ')) {
            when {
                token == "bg" -> view.setBackgroundColor(p.bg)
                token == "opt" -> {
                    val on = view.isSelected
                    view.setBackgroundResource(if (on) R.drawable.shape_pill else R.drawable.shape_pill_outline)
                    view.backgroundTintList = ColorStateList.valueOf(p.fg)
                    (view as? TextView)?.setTextColor(if (on) p.bg else p.fg)
                }
                token.startsWith("fill:") ->
                    view.backgroundTintList = ColorStateList.valueOf(pick(token.substring(5), p))
                token.startsWith("text:") -> (view as? TextView)?.let {
                    val c = pick(token.substring(5), p)
                    it.setTextColor(c)
                    if (it is EditText) it.setHintTextColor(ColorUtils.setAlphaComponent(c, 0x88))
                }
                token.startsWith("tint:") ->
                    (view as? ImageView)?.imageTintList = ColorStateList.valueOf(pick(token.substring(5), p))
            }
        }
    }

    /** True when the background is light, so the status bar needs dark icons. */
    fun isLight(color: Int): Boolean = ColorUtils.calculateLuminance(color) > 0.5
}
