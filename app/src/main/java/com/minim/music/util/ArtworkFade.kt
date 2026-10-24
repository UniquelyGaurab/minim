package com.minim.music.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import com.minim.music.R

/**
 * v4.9.9 part 3: covers that arrive from the network (or the disk) fade in over [FADE_MS]
 * instead of popping in. Only the image's own alpha moves, so the view's background (the
 * placeholder fill) stays put underneath. Covers from memory never come through here, so they
 * appear at once and scrolling doesn't flicker.
 */
object ArtworkFade {

    const val FADE_MS = 180L

    private val ease = DecelerateInterpolator(1.5f)

    fun show(view: ImageView, bitmap: Bitmap) {
        cancel(view)
        if (!ValueAnimator.areAnimatorsEnabled() || !view.isShown) {
            view.setImageBitmap(bitmap)
            return
        }
        val fresh = BitmapDrawable(view.resources, bitmap)
        fresh.alpha = 0
        view.setImageDrawable(fresh)
        val a = ValueAnimator.ofInt(0, 255).apply {
            duration = FADE_MS
            interpolator = ease
            addUpdateListener { fresh.alpha = it.animatedValue as Int }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fresh.alpha = 255
                    if (view.getTag(R.id.tag_artwork_fade) === animation) view.setTag(R.id.tag_artwork_fade, null)
                }
            })
        }
        view.setTag(R.id.tag_artwork_fade, a)
        a.start()
    }

    /** Ends a fade at once, the image fully showing (before something else replaces it). */
    fun cancel(view: ImageView) {
        (view.getTag(R.id.tag_artwork_fade) as? ValueAnimator)?.end()
        view.setTag(R.id.tag_artwork_fade, null)
    }

    /** True when [bitmap] is what the view shows (or is fading in) right now. */
    fun isShowing(view: ImageView, bitmap: Bitmap): Boolean = (view.drawable as? BitmapDrawable)?.bitmap === bitmap
}
