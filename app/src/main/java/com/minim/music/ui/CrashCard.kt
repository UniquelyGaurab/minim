package com.minim.music.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.minim.music.R
import com.minim.music.util.CrashReporter

/**
 * Hotfix 4.9.10: the small "Minim crashed last time" card, shown once on the launch after a crash
 * ([CrashReporter] saved the report). Copy puts the whole report on the clipboard; Dismiss just
 * closes it. It sits on top of everything (added to the activity's content frame) and is built in
 * code so it can't break any layout. Every step is guarded: it must never crash the app itself.
 */
object CrashCard {

    /** Shows the saved report, if any, and forgets it right away so it shows only once. */
    fun showIfPending(activity: Activity, pal: Pal) {
        try {
            val dir = activity.filesDir
            val report = CrashReporter.pending(dir) ?: return
            // Cleared before building the card: if the card itself failed, it won't come back every launch
            CrashReporter.clear(dir)
            show(activity, report, pal)
        } catch (_: Throwable) {
        }
    }

    private fun show(activity: Activity, report: String, pal: Pal) {
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val semi: Typeface? = font(activity, R.font.poppins_semibold)
        val regular: Typeface? = font(activity, R.font.poppins_regular)
        // Inverted colours: stands out on any screen
        val cardBg = pal.fg
        val cardFg = pal.bg

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(cardBg)
            }
            elevation = dp(12).toFloat()
            isClickable = true // taps on the card don't fall through to the screen below
        }
        card.addView(TextView(activity).apply {
            text = activity.getString(R.string.crash_card_title)
            setTextColor(cardFg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            semi?.let { typeface = it }
        })
        val headline = CrashReporter.headline(report)
        if (headline.isNotEmpty()) {
            card.addView(TextView(activity).apply {
                text = headline
                setTextColor(cardFg)
                alpha = 0.75f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                regular?.let { typeface = it }
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
        }
        card.addView(TextView(activity).apply {
            text = activity.getString(R.string.crash_card_body)
            setTextColor(cardFg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            regular?.let { typeface = it }
            setPadding(0, dp(6), 0, 0)
        })

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        fun pill(label: Int, filled: Boolean, onTap: () -> Unit) = TextView(activity).apply {
            text = activity.getString(label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            semi?.let { typeface = it }
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(50).toFloat()
                if (filled) setColor(cardFg) else setStroke(dp(1), cardFg)
            }
            setTextColor(if (filled) cardBg else cardFg)
            setOnClickListener {
                try {
                    onTap()
                } catch (_: Throwable) {
                }
            }
        }
        fun close() {
            try {
                (card.parent as? ViewGroup)?.removeView(card)
            } catch (_: Throwable) {
            }
        }
        row.addView(pill(R.string.crash_card_dismiss, filled = false) { close() })
        row.addView(pill(R.string.crash_card_copy, filled = true) {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Minim crash report", report))
            Toast.makeText(activity, R.string.crash_card_copied, Toast.LENGTH_SHORT).show()
            close()
        })
        // A gap between the two pills
        (row.getChildAt(1).layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(10)
        card.addView(row)

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(16), 0, dp(16), dp(24) + dp(48))
        }
        content.addView(card, lp)
        // Above the gesture / navigation bar once the real insets are known
        card.post {
            try {
                val insets = ViewCompat.getRootWindowInsets(card)
                val nav = insets?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: return@post
                val p = card.layoutParams as FrameLayout.LayoutParams
                p.bottomMargin = dp(24) + nav
                card.layoutParams = p
            } catch (_: Throwable) {
            }
        }
        card.visibility = View.VISIBLE
    }

    private fun font(context: Context, id: Int): Typeface? = try {
        ResourcesCompat.getFont(context, id)
    } catch (_: Throwable) {
        null
    }
}
