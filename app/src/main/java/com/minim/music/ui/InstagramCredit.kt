package com.minim.music.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.view.View
import androidx.core.content.ContextCompat
import com.minim.music.R
import kotlin.math.roundToInt

/**
 * 7.0.0: the credits line in Settings → About reads "Designed by [Instagram glyph] @uniquely.gaurab",
 * then "Built by Charlie (Sesame AI)". The glyph is drawn with the text's own paint colour, so it
 * always matches the credit text (Light, Dark, Haze ink, Dynamic/Default), and is sized from the
 * text size, so it scales with Android's font size. The glyph and handle open the Instagram profile
 * in the Instagram app when it's installed, else in the browser.
 */
object InstagramCredit {
    const val HANDLE = "@uniquely.gaurab"
    const val URL = "https://www.instagram.com/uniquely.gaurab/"
    const val INSTAGRAM_PACKAGE = "com.instagram.android"

    /** Glyph size as a share of the text size (the vector keeps a small inset, so it reads near cap height). */
    const val GLYPH_EM = 1.1f
    /** Space on each side of the glyph, as a share of the text size. */
    const val GAP_EM = 0.22f

    /** The whole credits text: designed-by line with the glyph and tappable handle, then built-by. */
    fun build(context: Context): CharSequence {
        val sb = SpannableStringBuilder()
        // No space before the glyph: the span carries its own gap on each side, so it sits evenly
        sb.append(context.getString(R.string.about_credits_designed))
        val start = sb.length
        // The glyph replaces one object-replacement character; the handle follows it
        sb.append('\uFFFC')
        ContextCompat.getDrawable(context, R.drawable.ic_instagram)?.let {
            sb.setSpan(GlyphSpan(it.mutate()), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append(HANDLE)
        sb.setSpan(LinkSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append('\n')
        sb.append(context.getString(R.string.about_credits_built))
        return sb
    }

    /** What TalkBack reads for the credits. */
    fun description(context: Context): String =
        context.getString(R.string.about_credits_designed) + " " +
            context.getString(R.string.about_credits_instagram) + ". " +
            context.getString(R.string.about_credits_built)

    /** The Instagram app's intent for the profile (the app handles instagram.com links). */
    fun appIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(URL)).setPackage(INSTAGRAM_PACKAGE)

    /** The browser fallback. */
    fun webIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(URL))

    /** Opens the profile in Instagram if installed, otherwise in the browser. */
    fun open(context: Context) {
        val newTask = context !is android.app.Activity
        for (intent in listOf(appIntent(), webIntent())) {
            if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    /** Keeps the text colour (no link blue) and no underline. */
    class LinkSpan : ClickableSpan() {
        override fun onClick(widget: View) {
            widget.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            open(widget.context)
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.isUnderlineText = false
        }
    }

    /**
     * Draws [drawable] tinted with the paint's colour (the text colour at draw time), about 1.1em,
     * centred on the capitals, with a small gap each side. Leaves the line height alone.
     */
    class GlyphSpan(val drawable: Drawable) : ReplacementSpan() {
        private val bounds = Rect()

        fun glyphSize(paint: Paint): Int = (paint.textSize * GLYPH_EM).roundToInt()
        fun gap(paint: Paint): Int = (paint.textSize * GAP_EM).roundToInt()

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) {
                val m = paint.fontMetricsInt
                fm.ascent = m.ascent; fm.descent = m.descent; fm.top = m.top; fm.bottom = m.bottom; fm.leading = m.leading
            }
            return glyphSize(paint) + 2 * gap(paint)
        }

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint
        ) {
            val size = glyphSize(paint)
            paint.getTextBounds("H", 0, 1, bounds)
            val centreY = y - bounds.height() / 2f
            drawable.setBounds(0, 0, size, size)
            drawable.setTint(paint.color)
            canvas.save()
            canvas.translate(x + gap(paint), centreY - size / 2f)
            drawable.draw(canvas)
            canvas.restore()
        }
    }
}
