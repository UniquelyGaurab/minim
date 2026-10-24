package com.minim.music.ui

import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import java.util.WeakHashMap

/**
 * v4.9.9 part 2: song title and artist change with a short fade: the old text fades out, the new
 * text fades in (about 230 ms in all). The text views keep their size and place the whole time,
 * so the marquee and the Home pill's resize work as before.
 *
 * The player state arrives several times a second with the same text; only a real change fades.
 * A change that arrives mid-fade (a quick skip) just swaps the text waiting to come in, and a
 * change during the fade-in turns around from the current opacity, so nothing flashes.
 */
class TextCrossfade(private val animationsOn: () -> Boolean) {

    private val pending = WeakHashMap<TextView, CharSequence>()
    private val tokens = WeakHashMap<TextView, Any>()
    private val ease = DecelerateInterpolator()

    /** The text [view] is showing or about to show. */
    fun target(view: TextView): CharSequence = pending[view] ?: view.text ?: ""

    /**
     * Shows [text] in [view], fading when [animate], animations are on and the view is on screen.
     * [onSet] runs once the new text is in the view (for example to restart the marquee).
     */
    fun set(view: TextView, text: CharSequence, animate: Boolean, onSet: (() -> Unit)? = null) {
        if (target(view).toString() == text.toString()) return
        val fade = animate && animationsOn() && view.isShown && view.width > 0 && !view.text.isNullOrEmpty()
        if (!fade) {
            pending.remove(view)
            view.animate().cancel()
            view.alpha = 1f
            view.text = text
            onSet?.invoke()
            return
        }
        val alreadyFadingOut = pending.containsKey(view)
        pending[view] = text
        if (alreadyFadingOut) return
        // Safety net: if something else cancels the fade, the new text still lands
        val token = Any()
        tokens[view] = token
        view.postDelayed({
            if (tokens[view] !== token) return@postDelayed
            val next = pending.remove(view) ?: return@postDelayed
            view.animate().cancel()
            view.text = next
            view.alpha = 1f
            onSet?.invoke()
        }, OUT_MS + SAFETY_MS)
        view.animate().cancel()
        val outMs = (OUT_MS * view.alpha.coerceIn(0f, 1f)).toLong()
        view.animate().alpha(0f).setStartDelay(0L).setDuration(outMs).setInterpolator(ease)
            .withEndAction {
                val next = pending.remove(view) ?: return@withEndAction
                view.text = next
                onSet?.invoke()
                view.animate().alpha(1f).setStartDelay(0L).setDuration(IN_MS).setInterpolator(ease).start()
            }
            .start()
    }

    companion object {
        const val OUT_MS = 100L
        const val IN_MS = 130L
        private const val SAFETY_MS = 250L
    }
}
