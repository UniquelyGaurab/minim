package com.minim.music.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import androidx.appcompat.content.res.AppCompatResources
import com.minim.music.R
import kotlin.math.max
import kotlin.math.min

/**
 * v5.0: the round profile picture (top right of every tab, where the Settings gear was, and in the
 * Settings Account section). Signed in: the Google photo, cropped to a circle. Signed out (or while the
 * photo loads, or offline before it was ever loaded): a person silhouette on a soft circle in the
 * dynamic colours, so it also works in Default B&W. The circle sits inside the view's padding, so the
 * touch target stays the full view size. [Painter] calls [setColors] on every palette change.
 */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var fg = Color.BLACK
    private var photo: Bitmap? = null
    private var photoAlpha = 1f
    private var fade: ValueAnimator? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderMatrix = Matrix()
    private val person = AppCompatResources.getDrawable(context, R.drawable.ic_person)!!.mutate()

    /** Placeholder colour: the palette's text colour; the circle behind is the same colour, faint. */
    fun setColors(fg: Int) {
        if (fg == this.fg) return
        this.fg = fg
        invalidate()
    }

    val hasPhoto: Boolean get() = photo != null

    /** null = placeholder. [animate] fades the photo in (180 ms, like covers) when the view is on screen. */
    fun setPhoto(bitmap: Bitmap?, animate: Boolean) {
        if (bitmap === photo) return
        fade?.cancel()
        photo = bitmap
        photoPaint.shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        updateShaderMatrix()
        if (bitmap != null && animate && isShown && isLaidOut) {
            photoAlpha = 0f
            fade = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180L
                interpolator = DecelerateInterpolator()
                addUpdateListener { photoAlpha = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            photoAlpha = 1f
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShaderMatrix()
    }

    override fun onDetachedFromWindow() {
        fade?.cancel()
        photoAlpha = 1f
        super.onDetachedFromWindow()
    }

    private fun radius(): Float =
        min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).coerceAtLeast(0) / 2f

    private fun cx(): Float = paddingLeft + (width - paddingLeft - paddingRight) / 2f
    private fun cy(): Float = paddingTop + (height - paddingTop - paddingBottom) / 2f

    /** Centre-crop the photo into the circle's square. */
    private fun updateShaderMatrix() {
        val b = photo ?: return
        val r = radius()
        if (r <= 0f || b.width <= 0 || b.height <= 0) return
        val scale = max(2f * r / b.width, 2f * r / b.height)
        shaderMatrix.reset()
        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postTranslate(cx() - b.width * scale / 2f, cy() - b.height * scale / 2f)
        photoPaint.shader?.setLocalMatrix(shaderMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        val r = radius()
        if (r <= 0f) return
        val cx = cx()
        val cy = cy()
        if (photo == null || photoAlpha < 1f) {
            fillPaint.color = (fg and 0x00FFFFFF) or (0x2E shl 24)
            canvas.drawCircle(cx, cy, r, fillPaint)
            val inset = r * 0.26f
            person.setBounds((cx - r + inset).toInt(), (cy - r + inset).toInt(), (cx + r - inset).toInt(), (cy + r - inset).toInt())
            person.setTint(fg)
            person.draw(canvas)
        }
        if (photo != null && photoAlpha > 0f) {
            photoPaint.alpha = (photoAlpha * 255).toInt()
            canvas.drawCircle(cx, cy, r, photoPaint)
        }
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
}
