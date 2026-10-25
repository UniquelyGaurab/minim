package com.minim.music.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Outline
import android.graphics.RectF
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import com.minim.music.R
import com.minim.music.data.ai.ActionKind
import com.minim.music.data.ai.ChatItem
import com.minim.music.data.ai.ChatListener
import com.minim.music.data.ai.GeminiChatSession
import com.minim.music.data.ai.GeminiProblem
import com.minim.music.data.model.Track
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v5.2.0: the Gemini chat sheet.
 *
 * It grows out of the pill you tapped (the pill's rounded shape stretches into the sheet, the
 * way Now Playing opens from the mini player), stops at about 70% of the screen so the music
 * stays visible, and goes full screen when you drag the top up. Swipe it down or press Back
 * and it shrinks back into the pill. Colours come from the palette tags like the rest of
 * Minim, so they follow the playing song's cover and crossfade with it.
 */
class GeminiChatPanel(
    private val root: FrameLayout,
    private val host: Host,
) : ChatListener {

    interface Host {
        fun palette(): Pal
        fun session(): GeminiChatSession
        /** Sends a message with the right route, tools and instructions. */
        fun send(text: String, focus: Track?): Boolean
        fun loadArt(view: ImageView, url: String?, sizePx: Int)
        fun playTrack(track: Track, list: List<Track>)
        fun showTrackOptions(anchor: View, track: Track)
        suspend fun setLiked(track: Track, liked: Boolean)
        suspend fun savePlaylist(name: String, tracks: List<Track>): Boolean
        fun openArtist(name: String)
        fun openGeminiSettings()
        fun modelLabel(): String
        /** True when the chat can't work until you add a key (shown before the first message). */
        fun needsKeyUpFront(): Boolean
        fun note(text: String)
        fun haptic(v: View, type: Int)
        fun launch(block: suspend () -> Unit)
    }

    private val res: Resources = root.resources
    private fun dp(v: Float): Int = (v * res.displayMetrics.density).roundToInt()
    private fun dp(v: Int): Int = dp(v.toFloat())

    private val scrim: View = root.findViewById(R.id.gemini_scrim)
    private val panel: LinearLayout = root.findViewById(R.id.gemini_panel)
    private val top: View = root.findViewById(R.id.gemini_top)
    private val modelLabel: TextView = root.findViewById(R.id.gemini_model_label)
    private val newChat: TextView = root.findViewById(R.id.gemini_new)
    private val contextChip: View = root.findViewById(R.id.gemini_context)
    private val contextText: TextView = root.findViewById(R.id.gemini_context_text)
    private val scroll: NestedScrollView = root.findViewById(R.id.gemini_scroll)
    private val list: LinearLayout = root.findViewById(R.id.gemini_list)
    private val starters: View = root.findViewById(R.id.gemini_starters)
    private val input: EditText = root.findViewById(R.id.gemini_input)
    private val send: View = root.findViewById(R.id.gemini_send)
    private val sendFill: View = root.findViewById(R.id.gemini_send_fill)
    private val sendIcon: ImageView = root.findViewById(R.id.gemini_send_icon)

    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val inflater = LayoutInflater.from(root.context)

    var isOpen = false
        private set
    private var anchor: View? = null
    private var focus: Track? = null
    private var full = false
    private var statusTop = 0
    private var navBottom = 0
    private var imeBottom = 0
    private var morph: ValueAnimator? = null
    private var settle: ValueAnimator? = null
    private var sendFilled = false

    /** item key -> its view, so streamed text finds its paragraph. */
    private val views = HashMap<Long, View>()
    private val fades = HashMap<Long, WordFade>()
    private var thinkingRow: View? = null
    private var thinkingAnim: Animator? = null
    private var placeholder: View? = null
    private var lastSent: Pair<String, Track?>? = null

    // the shape the sheet is clipped to while it morphs out of / into the pill
    private val clip = RectF()
    private var clipRadius = 0f
    private var clipping = false

    init {
        panel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (clipping) {
                    outline.setRoundRect(
                        clip.left.roundToInt(), clip.top.roundToInt(),
                        clip.right.roundToInt(), clip.bottom.roundToInt(), clipRadius
                    )
                } else {
                    // rounded top, square bottom (the bottom corners sit below the screen edge)
                    val r = dp(28).toFloat()
                    outline.setRoundRect(0, 0, view.width, view.height + r.toInt(), r)
                }
            }
        }
        panel.clipToOutline = true
        scrim.setOnClickListener { close() }
        setupDrag()
        setupInput()
        root.findViewById<View>(R.id.gemini_context_close).setOnClickListener { clearFocus(true) }
        newChat.setOnClickListener { host.session().clear() }
        starter(R.id.gemini_starter_roast, R.string.gemini_starter_roast_msg)
        starter(R.id.gemini_starter_playlist, R.string.gemini_starter_playlist_msg)
        starter(R.id.gemini_starter_translate, R.string.gemini_starter_translate_msg)

        // the input rides up with the keyboard, frame by frame
        ViewCompat.setWindowInsetsAnimationCallback(
            panel,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    running: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    if (running.any { it.typeMask and WindowInsetsCompat.Type.ime() != 0 }) {
                        imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        applyBottom()
                    }
                    return insets
                }
            }
        )
    }

    // ------------------------------------------------------------------------------------------
    // Insets and size
    // ------------------------------------------------------------------------------------------

    /** Called from MainActivity's insets listener (the chat draws behind the system bars). */
    fun applyInsets(statusBar: Int, navBar: Int, ime: Int, rootPadLeft: Int, rootPadRight: Int) {
        statusTop = statusBar
        navBottom = navBar
        imeBottom = ime
        (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.setMargins(-rootPadLeft, -statusBar, -rootPadRight, -navBar)
            root.layoutParams = lp
        }
        applyBottom()
    }

    private fun applyBottom() {
        val bottom = max(navBottom, imeBottom)
        if (panel.paddingBottom != bottom) panel.setPadding(0, 0, 0, bottom)
        if (isOpen && settle?.isRunning != true && morph?.isRunning != true) {
            setPanelHeight(targetHeight(full))
        }
    }

    private fun screenH(): Int = if (root.height > 0) root.height else res.displayMetrics.heightPixels

    private fun fullHeight(): Int = screenH() - statusTop

    /** About 70% of the screen; with the keyboard up it grows so a few lines stay visible. */
    private fun targetHeight(full: Boolean): Int {
        val fullH = fullHeight()
        if (full) return fullH
        val half = (screenH() * 0.70f).roundToInt()
        val withIme = if (imeBottom > navBottom) imeBottom + dp(320) else 0
        return min(fullH, max(half, withIme))
    }

    private fun setPanelHeight(h: Int) {
        val lp = panel.layoutParams
        if (lp.height != h) {
            lp.height = h
            panel.layoutParams = lp
        }
    }

    // ------------------------------------------------------------------------------------------
    // Open / close
    // ------------------------------------------------------------------------------------------

    /** Opens the chat out of [from]. [song] is set when opened from Now Playing. */
    fun open(from: View?, song: Track?) {
        if (isOpen) return
        isOpen = true
        anchor = from
        full = false
        host.session().listener = this
        modelLabel.text = host.modelLabel()
        setFocus(song)
        rebuild()
        Painter.paint(root, host.palette())
        onPalette(host.palette())
        setPanelHeight(targetHeight(false))
        root.visibility = View.VISIBLE
        scrim.alpha = 0f
        contentAlpha(0f)
        panel.visibility = View.INVISIBLE
        root.post {
            panel.visibility = View.VISIBLE
            runMorph(opening = true)
            scrollToEnd(false)
        }
    }

    fun close(then: (() -> Unit)? = null) {
        if (!isOpen) {
            then?.invoke()
            return
        }
        isOpen = false
        hideKeyboard()
        settle?.cancel()
        runMorph(opening = false) {
            root.visibility = View.GONE
            stopThinking()
            fades.values.forEach { it.finish() }
            then?.invoke()
        }
    }

    /** Rect of the pill inside the panel, or a small pill at the bottom when it isn't on screen. */
    private fun pillRect(): RectF {
        val a = anchor
        val pLoc = IntArray(2).also { panel.getLocationOnScreen(it) }
        if (a != null && a.isShown && a.width > 0) {
            val aLoc = IntArray(2).also { a.getLocationOnScreen(it) }
            val l = (aLoc[0] - pLoc[0]).toFloat()
            val t = (aLoc[1] - pLoc[1]).toFloat()
            val r = RectF(l, t, l + a.width, t + a.height)
            if (r.top < 0) r.offset(0f, -r.top)
            if (r.bottom > panel.height) r.offset(0f, panel.height - r.bottom)
            return r
        }
        val w = dp(140).toFloat()
        val h = dp(34).toFloat()
        val cx = panel.width / 2f
        val b = panel.height - panel.paddingBottom - dp(16).toFloat()
        return RectF(cx - w / 2, b - h, cx + w / 2, b)
    }

    private fun startColor(): Int {
        val p = host.palette()
        val a = anchor
        // the Now Playing pill is filled; the Home one is an outline over the background
        return if (a != null && a.id == R.id.np_gemini) p.fg else ColorUtils.blendARGB(p.bg, p.fg, 0.10f)
    }

    private fun runMorph(opening: Boolean, end: (() -> Unit)? = null) {
        morph?.cancel()
        val from = pillRect()
        val r28 = dp(28).toFloat()
        val to = RectF(0f, 0f, panel.width.toFloat(), panel.height + r28)
        val pillR = from.height() / 2f
        val c0 = startColor()
        val bg = host.palette().bg
        val startT = if (opening) 0f else 1f
        val endT = if (opening) 1f else 0f
        clipping = true
        val anim = ValueAnimator.ofFloat(startT, endT).apply {
            duration = if (opening) 420L else 320L
            interpolator = emphasized
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                clip.set(
                    lerp(from.left, to.left, t), lerp(from.top, to.top, t),
                    lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t)
                )
                clipRadius = lerp(pillR, r28, t)
                panel.invalidateOutline()
                panel.backgroundTintList = ColorStateList.valueOf(ColorUtils.blendARGB(c0, bg, (t * 1.6f).coerceAtMost(1f)))
                scrim.alpha = t
                // the contents arrive once the shape has mostly grown
                val c = ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)
                contentAlpha(c)
                top.translationY = (1f - c) * dp(10)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    clipping = false
                    panel.invalidateOutline()
                    panel.backgroundTintList = null
                    Painter.paint(panel, host.palette())
                    if (!cancelled) end?.invoke()
                }
            })
        }
        morph = anim
        anim.start()
    }

    private fun contentAlpha(a: Float) {
        for (i in 0 until panel.childCount) panel.getChildAt(i).alpha = a
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ------------------------------------------------------------------------------------------
    // Drag the top: up for full screen, down to close
    // ------------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        val slop = ViewConfiguration.get(root.context).scaledTouchSlop
        var downY = 0f
        var startH = 0
        var dragging = false
        var vt: VelocityTracker? = null
        top.setOnTouchListener { _, e ->
            if (!isOpen || morph?.isRunning == true) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    settle?.cancel()
                    downY = e.rawY
                    startH = panel.height
                    dragging = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain().also { it.addMovement(e) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dy = e.rawY - downY
                    if (!dragging && abs(dy) > slop) {
                        dragging = true
                        hideKeyboard()
                    }
                    if (dragging) {
                        val h = (startH - dy).roundToInt().coerceIn(dp(140), fullHeight())
                        setPanelHeight(h)
                        val half = targetHeight(false)
                        scrim.alpha = (h.toFloat() / half).coerceIn(0f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.addMovement(e)
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    if (dragging) {
                        val h = panel.height
                        val half = targetHeight(false)
                        val fullH = fullHeight()
                        val fling = dp(900)
                        when {
                            vy > fling && h <= half + dp(40) -> close()
                            vy > fling -> settleTo(false)
                            vy < -fling -> settleTo(true)
                            h < half * 0.72f -> close()
                            h > (half + fullH) / 2 -> settleTo(true)
                            else -> settleTo(false)
                        }
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun settleTo(toFull: Boolean) {
        full = toFull
        settle?.cancel()
        val from = panel.height
        val to = targetHeight(toFull)
        settle = ValueAnimator.ofInt(from, to).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                setPanelHeight(it.animatedValue as Int)
                scrim.alpha = 1f
            }
            start()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------

    private fun setupInput() {
        // wraps over several lines, but the keyboard shows Send instead of a new-line key
        input.setHorizontallyScrolling(false)
        input.maxLines = 4
        input.setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else false
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateSend()
        })
        send.setOnClickListener { submit() }
        updateSend(animate = false)
    }

    private fun canSend() = input.text.isNotBlank() && !host.session().busy

    private fun updateSend(animate: Boolean = true) {
        val on = canSend()
        send.isEnabled = on
        if (on == sendFilled && animate) return
        sendFilled = on
        val p = host.palette()
        if (animate) {
            sendFill.animate().alpha(if (on) 1f else 0f).scaleX(if (on) 1f else 0.4f).scaleY(if (on) 1f else 0.4f)
                .setDuration(if (on) 260 else 180)
                .setInterpolator(if (on) OvershootInterpolator(2f) else DecelerateInterpolator()).start()
        } else {
            sendFill.alpha = if (on) 1f else 0f
            sendFill.scaleX = if (on) 1f else 0.4f
            sendFill.scaleY = if (on) 1f else 0.4f
        }
        sendIcon.imageTintList = ColorStateList.valueOf(if (on) p.bg else p.fg)
        sendIcon.alpha = if (on || input.text.isNotBlank()) 1f else 0.55f
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || host.session().busy) return
        if (host.send(text, focus)) {
            lastSent = text to focus
            input.setText("")
            host.haptic(send, HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun starter(id: Int, msg: Int) {
        root.findViewById<View>(id).setOnClickListener { v ->
            if (host.session().busy) return@setOnClickListener
            val text = res.getString(msg)
            host.haptic(v, HapticFeedbackConstants.CLOCK_TICK)
            if (host.send(text, focus)) lastSent = text to focus
        }
    }

    private fun hideKeyboard() {
        val imm = root.context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    // ------------------------------------------------------------------------------------------
    // Context chip (opened from Now Playing)
    // ------------------------------------------------------------------------------------------

    private fun setFocus(song: Track?) {
        focus = song
        contextChip.animate().cancel()
        if (song == null) {
            contextChip.visibility = View.GONE
        } else {
            contextText.text = "♪  " + song.title
            contextChip.visibility = View.VISIBLE
            contextChip.alpha = 1f
            contextChip.scaleX = 1f
            contextChip.scaleY = 1f
        }
    }

    private fun clearFocus(animate: Boolean) {
        focus = null
        if (!animate) {
            contextChip.visibility = View.GONE
            return
        }
        host.haptic(contextChip, HapticFeedbackConstants.CLOCK_TICK)
        contextChip.pivotX = 0f
        contextChip.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(160).withEndAction {
            contextChip.visibility = View.GONE
        }.start()
    }

    // ------------------------------------------------------------------------------------------
    // Palette
    // ------------------------------------------------------------------------------------------

    /** After Painter has painted the tree: the parts whose colour depends on state. */
    fun onPalette(p: Pal) {
        sendIcon.imageTintList = ColorStateList.valueOf(if (sendFilled) p.bg else p.fg)
        input.setHintTextColor(ColorUtils.setAlphaComponent(p.fg, 0x80))
        input.highlightColor = ColorUtils.setAlphaComponent(p.fg, 0x40)
    }

    // ------------------------------------------------------------------------------------------
    // The message list
    // ------------------------------------------------------------------------------------------

    private fun rebuild() {
        val s = host.session()
        list.removeAllViews()
        views.clear()
        fades.clear()
        thinkingRow = null
        placeholder = null
        for (item in s.items) addItemView(item, animate = false)
        updatePlaceholder()
        starters.animate().cancel()
        starters.visibility = if (s.isEmpty) View.VISIBLE else View.GONE
        starters.alpha = 1f
        starters.layoutParams = starters.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        newChat.visibility = if (s.isEmpty) View.GONE else View.VISIBLE
        if (s.thinking) startThinking(false)
        updateSend(animate = false)
    }

    private fun updatePlaceholder() {
        val empty = host.session().isEmpty
        placeholder?.let { list.removeView(it) }
        placeholder = null
        if (!empty) return
        val v: View = if (host.needsKeyUpFront()) {
            errorView(GeminiProblem.DefaultUnavailable, retry = false)
        } else {
            TextView(root.context).apply {
                setTextAppearance(R.style.MinimText)
                text = res.getString(R.string.gemini_empty)
                textSize = 14f
                tag = "text:muted"
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(28), dp(12), dp(12))
            }
        }
        placeholder = v
        list.addView(v)
        Painter.paint(v, host.palette())
    }

    private fun nearBottom(): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        return child.bottom - (scroll.height + scroll.scrollY) < dp(140)
    }

    private fun scrollToEnd(smooth: Boolean) {
        scroll.post {
            val child = scroll.getChildAt(0) ?: return@post
            val y = max(0, child.bottom + scroll.paddingBottom - scroll.height)
            if (smooth) scroll.smoothScrollTo(0, y) else scroll.scrollTo(0, y)
        }
    }

    private fun addItemView(item: ChatItem, animate: Boolean) {
        val v: View = when (item) {
            is ChatItem.User -> userView(item)
            is ChatItem.Assistant -> assistantView(item, animate)
            is ChatItem.Songs -> songsView(item, animate)
            is ChatItem.Liked -> likedView(item)
            is ChatItem.Action -> actionView(item)
            is ChatItem.Playlist -> playlistView(item)
            is ChatItem.Error -> errorView(item.problem, retry = true)
        }
        views[item.key] = v
        val insertAt = thinkingRow?.let { list.indexOfChild(it) }?.takeIf { it >= 0 } ?: list.childCount
        list.addView(v, insertAt)
        Painter.paint(v, host.palette())
        if (animate && item !is ChatItem.Assistant && item !is ChatItem.Songs) {
            v.alpha = 0f
            v.translationY = dp(10).toFloat()
            if (item is ChatItem.User) {
                v.pivotX = v.width.toFloat()
                v.scaleX = 0.96f
                v.scaleY = 0.96f
            }
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(240).setInterpolator(emphasized).start()
        }
    }

    private fun userView(item: ChatItem.User): View {
        val wrap = FrameLayout(root.context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) }
        }
        val tv = TextView(root.context).apply {
            setTextAppearance(R.style.MinimText)
            text = item.text
            textSize = 14.5f
            setBackgroundResource(R.drawable.shape_bubble)
            tag = "fill:fg text:bg"
            setPadding(dp(14), dp(9), dp(14), dp(10))
            maxWidth = (res.displayMetrics.widthPixels * 0.76f).roundToInt()
            setTextIsSelectable(true)
        }
        wrap.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END))
        return wrap
    }

    private fun sparkle(sizeDp: Int): ImageView = ImageView(root.context).apply {
        setImageResource(R.drawable.ic_sparkle)
        tag = "tint:fg"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun assistantView(item: ChatItem.Assistant, animate: Boolean): View {
        val row = LinearLayout(root.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) }
        }
        val icon = sparkle(14).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(3); marginEnd = dp(10) }
        }
        val tv = TextView(root.context).apply {
            setTextAppearance(R.style.MinimText)
            textSize = 14.5f
            tag = "text:fg"
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(icon)
        row.addView(tv)
        val fade = WordFade(tv)
        fades[item.key] = fade
        val clean = cleanText(item.text.toString())
        if (animate) fade.append(clean) else fade.setInstant(clean)
        return row
    }

    /** The model is told not to use markdown; this catches the odd ** anyway. */
    private fun cleanText(s: String) = s.replace("**", "").replace("__", "")

    private fun actionLine(text: String): TextView = TextView(root.context).apply {
        setTextAppearance(R.style.MinimText_Semi)
        this.text = text
        textSize = 12.5f
        tag = "text:muted"
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun actionView(item: ChatItem.Action): View {
        val text = when (item.kind) {
            ActionKind.PLAYING -> if (item.count > 1)
                res.getQuantityString(R.plurals.gemini_playing_more, item.count - 1, item.title, item.count - 1)
            else res.getString(R.string.gemini_playing, item.title)
            ActionKind.QUEUED -> if (item.count > 1) res.getQuantityString(R.plurals.gemini_queued, item.count, item.count)
            else res.getString(R.string.gemini_queued_one, item.title)
            ActionKind.QUEUED_NEXT -> if (item.count > 1) res.getQuantityString(R.plurals.gemini_queued_next, item.count, item.count)
            else res.getString(R.string.gemini_queued_next_one, item.title)
            ActionKind.ALREADY_LIKED -> res.getString(R.string.gemini_already_liked, item.title)
        }
        return actionLine(text).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10); marginStart = dp(24) }
        }
    }

    private fun smallPill(text: String): TextView = TextView(root.context).apply {
        setTextAppearance(R.style.MinimText_Semi)
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.shape_pill_outline)
        tag = "fill:fg text:fg"
        setPadding(dp(12), 0, dp(12), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26))
    }

    private fun likedView(item: ChatItem.Liked): View {
        val row = LinearLayout(root.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10); marginStart = dp(24) }
        }
        val line = actionLine("").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val undo = smallPill(res.getString(R.string.gemini_undo))
        fun render() {
            line.text = if (item.undone) res.getString(R.string.gemini_like_undone)
            else res.getString(R.string.gemini_liked, item.track.title)
            undo.visibility = if (item.undone) View.GONE else View.VISIBLE
        }
        undo.setOnClickListener { v ->
            if (item.undone) return@setOnClickListener
            item.undone = true
            host.haptic(v, HapticFeedbackConstants.CLOCK_TICK)
            host.launch { host.setLiked(item.track, false) }
            undo.animate().alpha(0f).setDuration(150).withEndAction { render() }.start()
            line.animate().alpha(0f).setDuration(120).withEndAction {
                render()
                line.animate().alpha(1f).setDuration(160).start()
            }.start()
        }
        row.addView(line)
        row.addView(undo)
        render()
        return row
    }

    private fun songCard(track: Track, all: List<Track>): View {
        val v = inflater.inflate(R.layout.item_track, list, false)
        v.setPadding(0, 0, 0, dp(8))
        val card = v.findViewById<View>(R.id.track_card).apply { clipToOutline = true }
        val art = v.findViewById<ImageView>(R.id.track_art).apply { clipToOutline = true }
        v.findViewById<TextView>(R.id.track_title).text = track.title
        v.findViewById<TextView>(R.id.track_artist).text = track.artist
        host.loadArt(art, track.thumbnailUrl, dp(44))
        card.setOnClickListener { cv ->
            host.haptic(cv, HapticFeedbackConstants.CONTEXT_CLICK)
            host.playTrack(track, all)
        }
        card.setOnLongClickListener { lv -> host.showTrackOptions(lv, track); true }
        return v
    }

    private fun songsView(item: ChatItem.Songs, animate: Boolean): View {
        val col = LinearLayout(root.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10); marginStart = dp(24) }
        }
        item.tracks.forEachIndexed { i, t ->
            val card = songCard(t, item.tracks)
            col.addView(card)
            if (animate) {
                // each card pops in with a small bounce, one after another
                card.alpha = 0f
                card.scaleX = 0.88f
                card.scaleY = 0.88f
                card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(i * 55L).setDuration(300)
                    .setInterpolator(OvershootInterpolator(1.8f)).start()
            }
        }
        return col
    }

    // ------------------------------------------------------------------------------------------
    // Playlist preview card
    // ------------------------------------------------------------------------------------------

    private fun playlistView(item: ChatItem.Playlist): View {
        val ctx = root.context
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.shape_card)
            tag = "fill:soft"
            setPadding(dp(14), dp(12), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12); marginStart = dp(24) }
        }
        val name = EditText(ctx).apply {
            setTextAppearance(R.style.MinimText_Bold)
            textSize = 17f
            tag = "text:fg"
            background = null
            setPadding(0, dp(2), 0, dp(2))
            setText(item.name)
            hint = res.getString(R.string.gemini_playlist_name_hint)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(android.text.InputFilter.LengthFilter(60))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isEnabled = !item.saved
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    item.name = s?.toString().orEmpty()
                }
            })
        }
        val count = actionLine("").apply { setPadding(0, dp(2), 0, dp(8)) }
        fun renderCount() {
            count.text = res.getQuantityString(R.plurals.gemini_playlist_count, item.tracks.size, item.tracks.size)
        }
        renderCount()
        val rows = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // rows below a removed one slide up to close the gap
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
                setDuration(220)
                setAnimator(LayoutTransition.DISAPPEARING, null)
                setAnimator(LayoutTransition.APPEARING, null)
            }
        }
        val save = savePill(item)
        fun removeRow(rowView: View, track: Track) {
            if (item.saved) return
            val idx = item.tracks.indexOfFirst { it.id == track.id }
            if (idx < 0) return
            item.tracks.removeAt(idx)
            rows.removeView(rowView)
            renderCount()
            host.haptic(rows, HapticFeedbackConstants.CLOCK_TICK)
        }
        for (t in item.tracks.toList()) rows.addView(playlistRow(t, item, ::removeRow))
        card.addView(name)
        card.addView(count)
        card.addView(rows)
        card.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
        })
        return card
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun playlistRow(t: Track, item: ChatItem.Playlist, remove: (View, Track) -> Unit): View {
        val ctx = root.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val art = ImageView(ctx).apply {
            setBackgroundResource(R.drawable.shape_art_small)
            tag = "fill:fg"
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        host.loadArt(art, t.thumbnailUrl, dp(36))
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        val title = TextView(ctx).apply {
            setTextAppearance(R.style.MinimText_Semi)
            text = t.title
            textSize = 13.5f
            tag = "text:fg"
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val artist = TextView(ctx).apply {
            setTextAppearance(R.style.MinimText)
            text = t.artist
            textSize = 12f
            tag = "text:fg"
            alpha = 0.7f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        texts.addView(title)
        texts.addView(artist)
        val x = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            tag = "tint:fg"
            alpha = 0.7f
            setPadding(dp(7), dp(7), dp(7), dp(7))
            contentDescription = res.getString(R.string.gemini_playlist_remove, t.title)
            visibility = if (item.saved) View.GONE else View.VISIBLE
            setBackgroundResource(android.R.color.transparent)
        }
        row.addView(art, LinearLayout.LayoutParams(dp(36), dp(36)))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(x, LinearLayout.LayoutParams(dp(30), dp(30)))
        x.setOnClickListener { animateOut(row, 1) { remove(row, t) } }

        // swipe a row sideways to remove it
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var swiping = false
        row.setOnTouchListener { v, e ->
            if (item.saved) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    swiping = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!swiping && abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) {
                        swiping = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (!swiping && abs(dy) > slop) return@setOnTouchListener false
                    if (swiping) {
                        v.translationX = dx
                        v.alpha = 1f - (abs(dx) / v.width).coerceIn(0f, 0.8f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (swiping) {
                        val dx = v.translationX
                        if (abs(dx) > v.width * 0.35f) {
                            animateOut(v, if (dx > 0) 1 else -1) { remove(v, t) }
                        } else {
                            v.animate().translationX(0f).alpha(1f).setDuration(180).start()
                        }
                    }
                    swiping = false
                    true
                }
                else -> false
            }
        }
        return row
    }

    private fun animateOut(v: View, dir: Int, end: () -> Unit) {
        v.animate().translationX(dir * v.width.toFloat()).alpha(0f).setDuration(180)
            .setInterpolator(DecelerateInterpolator()).withEndAction(end).start()
    }

    private fun savePill(item: ChatItem.Playlist): View {
        val ctx = root.context
        val pill = FrameLayout(ctx).apply {
            setBackgroundResource(R.drawable.shape_pill)
            tag = "fill:fg"
            minimumWidth = dp(40)
            isClickable = true
            isFocusable = true
        }
        val label = TextView(ctx).apply {
            setTextAppearance(R.style.MinimText_Semi)
            text = res.getString(R.string.gemini_playlist_save)
            textSize = 14f
            tag = "text:bg"
            gravity = Gravity.CENTER
            setPadding(dp(22), 0, dp(22), 0)
        }
        val check = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_check)
            tag = "tint:bg"
            contentDescription = res.getString(R.string.gemini_playlist_saved)
        }
        pill.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        pill.addView(check, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        if (item.saved) {
            label.visibility = View.GONE
            pill.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        } else {
            check.alpha = 0f
            check.scaleX = 0.4f
            check.scaleY = 0.4f
        }
        var saving = false
        pill.setOnClickListener { v ->
            if (item.saved || saving) return@setOnClickListener
            if (item.tracks.isEmpty()) {
                host.note(res.getString(R.string.gemini_playlist_empty))
                return@setOnClickListener
            }
            saving = true
            val name = item.name.trim().ifEmpty { res.getString(R.string.gemini_playlist_name_hint) }
            host.launch {
                val ok = host.savePlaylist(name, item.tracks.toList())
                saving = false
                if (!ok) {
                    host.note(res.getString(R.string.gemini_playlist_failed))
                    return@launch
                }
                item.saved = true
                item.name = name
                host.haptic(v, if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
                morphToCheck(pill, label, check)
                lockPlaylist(v)
            }
        }
        return pill
    }

    /** The Save pill shrinks into a round ✓. */
    private fun morphToCheck(pill: View, label: View, check: View) {
        val w0 = pill.width
        val w1 = dp(40)
        label.animate().alpha(0f).setDuration(120).withEndAction { label.visibility = View.GONE }.start()
        ValueAnimator.ofInt(w0, w1).apply {
            duration = 280
            interpolator = emphasized
            addUpdateListener {
                val lp = pill.layoutParams
                lp.width = it.animatedValue as Int
                pill.layoutParams = lp
            }
            start()
        }
        check.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(140).setDuration(260)
            .setInterpolator(OvershootInterpolator(2.4f)).start()
    }

    /** After saving: the name can't be edited and the ✕ buttons go. */
    private fun lockPlaylist(anyChild: View) {
        val card = anyChild.parent as? LinearLayout ?: return
        (card.getChildAt(0) as? EditText)?.apply {
            isEnabled = false
            clearFocus()
        }
        (card.getChildAt(2) as? LinearLayout)?.let { rows ->
            for (i in 0 until rows.childCount) {
                val row = rows.getChildAt(i) as? LinearLayout ?: continue
                row.getChildAt(row.childCount - 1)?.animate()?.alpha(0f)?.setDuration(160)?.withEndAction {
                    row.getChildAt(row.childCount - 1)?.visibility = View.GONE
                }?.start()
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Errors
    // ------------------------------------------------------------------------------------------

    private fun problemText(p: GeminiProblem): String = when (p) {
        GeminiProblem.BadKey -> res.getString(R.string.gemini_err_bad_key)
        GeminiProblem.Quota -> res.getString(R.string.gemini_err_quota)
        is GeminiProblem.UnknownModel -> res.getString(R.string.gemini_err_model, p.model)
        GeminiProblem.DefaultUnavailable -> res.getString(R.string.gemini_err_default_off)
        GeminiProblem.Offline -> res.getString(R.string.gemini_err_offline)
        GeminiProblem.Blocked -> res.getString(R.string.gemini_err_blocked)
        GeminiProblem.Busy -> res.getString(R.string.gemini_err_busy)
        GeminiProblem.Region -> res.getString(R.string.gemini_err_region)
        is GeminiProblem.Other -> res.getString(R.string.gemini_err_other, p.message)
    }

    private fun errorView(p: GeminiProblem, retry: Boolean): View {
        val col = LinearLayout(root.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) }
        }
        val row = LinearLayout(root.context).apply { orientation = LinearLayout.HORIZONTAL }
        val icon = sparkle(14).apply {
            alpha = 0.6f
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(3); marginEnd = dp(10) }
        }
        val tv = TextView(root.context).apply {
            setTextAppearance(R.style.MinimText)
            text = problemText(p)
            textSize = 14f
            tag = "text:fg"
            alpha = 0.85f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(icon)
        row.addView(tv)
        col.addView(row)
        val action: TextView? = when (p) {
            GeminiProblem.BadKey, GeminiProblem.DefaultUnavailable, is GeminiProblem.UnknownModel ->
                smallPill(res.getString(R.string.gemini_err_action_settings)).apply {
                    setOnClickListener { close { host.openGeminiSettings() } }
                }
            GeminiProblem.Busy, GeminiProblem.Offline, GeminiProblem.Quota, is GeminiProblem.Other ->
                if (retry) smallPill(res.getString(R.string.gemini_err_action_retry)).apply {
                    setOnClickListener { v ->
                        val last = lastSent ?: return@setOnClickListener
                        if (host.session().busy) return@setOnClickListener
                        host.haptic(v, HapticFeedbackConstants.CLOCK_TICK)
                        host.send(last.first, last.second)
                    }
                } else null
            else -> null
        }
        action?.let {
            col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)).apply {
                topMargin = dp(8)
                marginStart = dp(24)
            })
        }
        return col
    }

    // ------------------------------------------------------------------------------------------
    // Thinking: the sparkle slowly turns and pulses in the app colour
    // ------------------------------------------------------------------------------------------

    private fun startThinking(animateIn: Boolean) {
        if (thinkingRow != null) return
        val row = LinearLayout(root.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) }
        }
        val icon = sparkle(18)
        row.addView(icon)
        thinkingRow = row
        list.addView(row)
        Painter.paint(row, host.palette())
        if (animateIn) {
            row.alpha = 0f
            row.animate().alpha(1f).setDuration(200).start()
        }
        val spin = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, 360f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        val pulse = ObjectAnimator.ofPropertyValuesHolder(
            icon,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.8f, 1.1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.8f, 1.1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 1f),
        ).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val set = android.animation.AnimatorSet().apply { playTogether(spin, pulse) }
        thinkingAnim = set
        set.start()
        if (nearBottom()) scrollToEnd(true)
    }

    private fun stopThinking() {
        thinkingAnim?.cancel()
        thinkingAnim = null
        thinkingRow?.let { list.removeView(it) }
        thinkingRow = null
    }

    // ------------------------------------------------------------------------------------------
    // Session events
    // ------------------------------------------------------------------------------------------

    override fun onItemAdded(item: ChatItem) {
        if (!isOpen) return
        val stick = nearBottom() || item is ChatItem.User
        if (placeholder != null) {
            list.removeView(placeholder)
            placeholder = null
        }
        if (item is ChatItem.User) hideStarters()
        newChat.visibility = View.VISIBLE
        addItemView(item, animate = true)
        if (stick) scrollToEnd(true)
    }

    override fun onAssistantText(item: ChatItem.Assistant, delta: String) {
        if (!isOpen) return
        val stick = nearBottom()
        val fade = fades[item.key]
        if (fade == null) {
            if (views[item.key] == null) addItemView(item, animate = true)
        } else {
            fade.append(cleanText(delta))
        }
        if (stick) scrollToEnd(true)
    }

    override fun onThinking(thinking: Boolean) {
        if (!isOpen) return
        if (thinking) startThinking(true) else stopThinking()
    }

    override fun onBusy(busy: Boolean) {
        if (!isOpen) return
        updateSend()
    }

    override fun onOpenArtist(name: String) {
        if (isOpen) close { host.openArtist(name) } else host.openArtist(name)
    }

    override fun onCleared() {
        if (!isOpen) return
        list.animate().alpha(0f).setDuration(140).withEndAction {
            rebuild()
            list.alpha = 0f
            list.animate().alpha(1f).setDuration(200).start()
        }.start()
        starters.alpha = 0f
        starters.animate().alpha(1f).setDuration(240).start()
        lastSent = null
    }

    private fun hideStarters() {
        if (starters.visibility != View.VISIBLE) return
        val h = starters.height
        starters.animate().alpha(0f).setDuration(180).withEndAction {
            if (h <= 0) {
                starters.visibility = View.GONE
                return@withEndAction
            }
            ValueAnimator.ofInt(h, 0).apply {
                duration = 200
                interpolator = emphasized
                addUpdateListener {
                    starters.layoutParams = starters.layoutParams.apply { height = it.animatedValue as Int }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        starters.visibility = View.GONE
                        starters.layoutParams = starters.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                    }
                })
                start()
            }
        }.start()
    }

    /** For MainActivity.onDestroy: stop listening (the session lives on in MinimApp). */
    fun detach() {
        if (host.session().listener === this) host.session().listener = null
        stopThinking()
        morph?.cancel()
        settle?.cancel()
    }
}
