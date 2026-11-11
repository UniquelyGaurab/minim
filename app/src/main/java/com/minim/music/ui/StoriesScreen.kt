package com.minim.music.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.minim.music.MinimApp
import com.minim.music.R
import com.minim.music.data.model.Track
import com.minim.music.data.stories.StoriesStore
import com.minim.music.data.stories.StoryChannel
import com.minim.music.data.stories.StoryFeed
import com.minim.music.data.stories.StoryUpload
import com.minim.music.ui.adapter.StoriesAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import com.minim.music.data.stories.StoryLength
import com.minim.music.data.stories.StoryProgress
import com.minim.music.data.stories.StoryShelf
import com.minim.music.data.stories.StoryShelves
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 5.7.0 u44: the stories tab. Channels you add (by name or link) and their uploads, newest first
 * with every channel mixed. An upload plays as audio through the normal player (fades, queue,
 * volume boost), with the queue being the uploads below it; no radio is added after.
 */
class StoriesScreen(
    private val root: View,
    private val app: MinimApp,
    private val scope: CoroutineScope,
    private val host: Host
) {
    interface Host {
        fun openSheet(build: (LinearLayout) -> Unit)
        fun closeSheet()
        fun sheetHeading(parent: LinearLayout, title: String, sub: String?)
        fun sheetClosing(): Boolean
        fun confirmSheet(parent: LinearLayout, okText: String, onOk: () -> Unit)
        fun showNote(text: String)
        fun haptic(v: View)
        fun animationsOn(): Boolean
        fun showKeyboard(v: View)
        fun hideKeyboard()
        fun showUploadOptions(anchor: View, track: Track)
        fun palette(): Pal
        /** 5.7.0 u47: opens a channel's page (from its photo, or a New to You row). */
        fun openChannel(c: StoryChannel)
    }

    private val store = app.storiesStore
    private val res = root.resources
    private val list: RecyclerView = root.findViewById(R.id.stories_list)
    private val refresh: SwipeRefreshLayout = root.findViewById(R.id.stories_refresh)
    private val empty: View = root.findViewById(R.id.stories_empty)
    private var selectedId: String? = null
    /** 5.7.0 u46: the length chip that's on (none by default; not saved). */
    private var length: StoryLength = StoryLength.ALL
    private var loading = false
    private var failed = false
    private var fetchJob: Job? = null

    private val adapter = StoriesAdapter(
        artwork = app.artworkPipeline,
        onUpload = { track, all -> play(track, all) },
        onUploadLong = { anchor, track -> host.showUploadOptions(anchor, track) },
        onChannel = { c -> host.openChannel(c) },
        onChannelLong = { c -> confirmRemove(c) },
        onAdd = { v ->
            host.haptic(v)
            showAddSheet()
        },
        meta = { u -> metaLine(u) },
        onChip = { v, chip ->
            host.haptic(v)
            length = length.tap(chip)
            render()
        },
        onShelfTitle = { sh ->
            selectedId = sh.channelId
            newToYou = false
            render()
            list.scrollToPosition(0)
        },
        cardMeta = { sh, u -> cardLine(sh, u) },
        onNewChip = { v ->
            host.haptic(v)
            newToYou = !newToYou
            if (newToYou) {
                selectedId = null
                fetchNewToYou(force = false)
            }
            render()
            list.scrollToPosition(0)
        }
    )

    /** 5.7.0 u47: the New to You chip is on: rows from channels like yours instead of the shelves. */
    private var newToYou = false
    private var newLoading = false
    private var newFailed = false
    private var newJob: Job? = null

    init {
        list.layoutManager = LinearLayoutManager(root.context)
        list.adapter = adapter
        list.itemAnimator = null
        refresh.setOnRefreshListener { if (newToYou) fetchNewToYou(force = true) else fetch(force = true) }
        root.findViewById<View>(R.id.stories_empty_add).setOnClickListener {
            host.haptic(it)
            showAddSheet()
        }
        render()
        // 5.7.0 u46: an upload became half-finished or heard: Continue listening and dimming follow
        scope.launch {
            app.playbackController.storiesVersion.drop(1).collect { render() }
        }
    }

    /** 5.7.0 u46: Back on a channel's list returns to the shelves. False when already there. */
    fun backToShelves(): Boolean {
        if (selectedId == null && !newToYou) return false
        selectedId = null
        newToYou = false
        render()
        list.scrollToPosition(0)
        return true
    }

    /** The tab came on screen: refresh when the saved uploads are older than 20 minutes. */
    fun onShown() {
        if (store.channels.isEmpty()) return
        if (System.currentTimeMillis() - store.fetchedAt > STALE_MS || store.uploads.isEmpty()) fetch(force = false)
    }

    fun setPalette(p: Pal) {
        adapter.setPalette(p)
        refresh.setColorSchemeColors(p.fg)
        refresh.setProgressBackgroundColorSchemeColor(p.bg)
    }

    fun setActiveTrack(id: String?) = adapter.setActiveTrack(id)

    /** 5.7.0 u47: a Channel page's row line: how long ago and the length. */
    fun pageMeta(u: StoryUpload): String =
        listOfNotNull(ageText(u.publishedAt), length(u.durationSec)).filter { it.isNotBlank() }.joinToString(" \u00B7 ")

    fun play(track: Track, all: List<Track>) {
        // The uploads from this one down, so the next upload follows (no radio after stories)
        val start = all.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        app.playbackController.playTrack(track, all.drop(start).take(MAX_QUEUE), com.minim.music.data.fermata.FermataSource.TAPE)
    }

    private fun render() {
        val channels = store.channels
        if (selectedId != null && channels.none { it.id == selectedId }) selectedId = null
        empty.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
        refresh.visibility = if (channels.isEmpty()) View.GONE else View.VISIBLE
        refresh.isEnabled = channels.isNotEmpty()
        val all = StoryFeed.merge(store.uploads, channels)
        val progress = app.storyProgress.all()
        adapter.setNewChannels(com.minim.music.data.stories.NewUploadRing.channelsWithNew(all, progress, System.currentTimeMillis()))
        adapter.newToYouOn = newToYou
        if (newToYou && channels.isNotEmpty()) {
            // 5.7.0 u47: New to You, with played ones and followed channels left out
            val items = com.minim.music.data.stories.NewToYou.visible(store.newToYou, channels, progress)
            val shown = items.filter { length.matches(it.durationSec) }
            val note = when {
                shown.isNotEmpty() -> null
                newLoading -> res.getString(R.string.tapes_new_loading)
                newFailed && items.isEmpty() -> res.getString(R.string.tapes_new_error)
                items.isNotEmpty() -> res.getString(R.string.stories_none_length)
                else -> res.getString(R.string.tapes_new_none)
            }
            adapter.submitList(channels, null, res.getString(R.string.tapes_new_label), note, shown, length, progress, forceMeta = true)
            adapter.setPalette(host.palette())
            return
        }
        val sel = selectedId
        val status = when {
            loading -> res.getString(R.string.stories_loading)
            failed -> res.getString(R.string.stories_error)
            else -> res.getString(R.string.stories_none)
        }
        if (sel != null) {
            // One channel's uploads as rows (its photo, or its shelf title, was tapped)
            val shown = StoryShelves.channelList(all, sel, length)
            val label = channels.firstOrNull { it.id == sel }?.name ?: ""
            val note = when {
                shown.isNotEmpty() -> null
                all.any { it.channelId == sel } -> res.getString(R.string.stories_none_length)
                else -> status
            }
            adapter.submitList(channels, sel, label, note, shown, length, progress)
        } else {
            // 5.7.0 u46: shelves (Continue listening, New this week, one per channel), empty ones hidden
            val shelves = StoryShelves.build(all, channels, progress, length, System.currentTimeMillis())
            val note = when {
                shelves.isNotEmpty() -> null
                all.isNotEmpty() -> res.getString(R.string.stories_none_length)
                else -> status
            }
            // While there are only a channel or two, a quiet line at the end says how to remove one
            val foot = if (shelves.isNotEmpty() && channels.size <= 2) res.getString(R.string.stories_hint) else null
            adapter.submitShelves(channels, shelves, note, foot, length, progress)
        }
        adapter.setPalette(host.palette())
    }

    /** Fetches every channel (3 at a time) and keeps whatever loaded. */
    fun fetch(force: Boolean) {
        val channels = store.channels
        if (channels.isEmpty()) {
            refresh.isRefreshing = false
            return
        }
        if (fetchJob?.isActive == true && !force) return
        fetchJob?.cancel()
        loading = true
        failed = false
        if (store.uploads.isEmpty()) render()
        fetchJob = scope.launch {
            val gate = Semaphore(3)
            val results = channels.map { c ->
                async { gate.withPermit { c to app.sourceAdapter.fetchChannelUploads(c) } }
            }.awaitAll()
            val old = store.uploads
            val merged = ArrayList<StoryUpload>()
            var anyOk = false
            for ((c, r) in results) {
                if (r != null) {
                    anyOk = true
                    merged.addAll(r.uploads)
                    store.updateAvatar(c.id, r.avatarUrl)
                } else {
                    // Couldn't reach this one: keep what it had before
                    merged.addAll(old.filter { it.channelId == c.id })
                }
            }
            if (anyOk) store.saveUploads(StoryFeed.merge(merged, store.channels), System.currentTimeMillis())
            loading = false
            failed = !anyOk
            refresh.isRefreshing = false
            render()
        }
    }

    /** 5.7.0 u47: asks YouTube for related videos of your recent tapes, then keeps other channels' uploads. */
    fun fetchNewToYou(force: Boolean) {
        val channels = store.channels
        if (channels.isEmpty()) {
            refresh.isRefreshing = false
            return
        }
        if (!force && store.newToYou.isNotEmpty() && System.currentTimeMillis() - store.newToYouAt < NEW_STALE_MS) return
        if (newJob?.isActive == true && !force) return
        newJob?.cancel()
        newLoading = true
        newFailed = false
        render()
        newJob = scope.launch {
            val feed = StoryFeed.merge(store.uploads, channels)
            val seeds = com.minim.music.data.stories.NewToYou.seeds(feed, app.storyProgress.all())
            val gate = Semaphore(3)
            val lists = seeds.map { id -> async { gate.withPermit { app.sourceAdapter.relatedUploads(id) } } }.awaitAll()
            val ok = lists.filterNotNull()
            if (ok.isNotEmpty()) {
                val built = com.minim.music.data.stories.NewToYou.build(ok, store.channels, app.storyProgress.all(), System.currentTimeMillis())
                store.saveNewToYou(built, System.currentTimeMillis())
            }
            newLoading = false
            newFailed = ok.isEmpty()
            refresh.isRefreshing = false
            render()
        }
    }

    fun setOffline(marks: com.minim.music.ui.adapter.OfflineMarks) {
        adapter.offline = marks
    }

    /** 5.7.0 u47: the channel row changed elsewhere (Follow on a Channel page). */
    fun onChannelsChanged() {
        render()
        fetch(force = true)
    }

    private fun metaLine(u: StoryUpload): String {
        val parts = ArrayList<String>(3)
        if (selectedId == null) parts.add(u.channelName)
        val (bucket, n) = StoryFeed.age(u.publishedAt, System.currentTimeMillis())
        when (bucket) {
            StoryFeed.Age.TODAY -> parts.add(res.getString(R.string.story_age_today))
            StoryFeed.Age.YESTERDAY -> parts.add(res.getString(R.string.story_age_yesterday))
            StoryFeed.Age.DAYS -> parts.add(res.getQuantityString(R.plurals.story_age_days, n, n))
            StoryFeed.Age.WEEKS -> parts.add(res.getQuantityString(R.plurals.story_age_weeks, n, n))
            StoryFeed.Age.MONTHS -> parts.add(res.getQuantityString(R.plurals.story_age_months, n, n))
            StoryFeed.Age.YEARS -> parts.add(res.getQuantityString(R.plurals.story_age_years, n, n))
            StoryFeed.Age.UNKNOWN -> Unit
        }
        parts.add(length(u.durationSec))
        return parts.joinToString(" · ")
    }

    /** 5.7.0 u46: the line under a card's title: what's left, or the channel and the length. */
    private fun cardLine(sh: StoryShelf, u: StoryUpload): String {
        val p = app.storyProgress.get(u.videoId)
        return when (sh.kind) {
            StoryShelf.Kind.CONTINUE -> listOf(u.channelName, res.getString(R.string.stories_min_left, StoryProgress.minutesLeft(p, u.durationSec)))
            StoryShelf.Kind.NEW -> listOf(u.channelName, length(u.durationSec))
            StoryShelf.Kind.CHANNEL -> listOfNotNull(ageText(u.publishedAt), length(u.durationSec))
        }.filter { it.isNotBlank() }.joinToString(" \u00B7 ")
    }

    private fun ageText(publishedAt: Long): String? {
        val (bucket, n) = StoryFeed.age(publishedAt, System.currentTimeMillis())
        return when (bucket) {
            StoryFeed.Age.TODAY -> res.getString(R.string.story_age_today)
            StoryFeed.Age.YESTERDAY -> res.getString(R.string.story_age_yesterday)
            StoryFeed.Age.DAYS -> res.getQuantityString(R.plurals.story_age_days, n, n)
            StoryFeed.Age.WEEKS -> res.getQuantityString(R.plurals.story_age_weeks, n, n)
            StoryFeed.Age.MONTHS -> res.getQuantityString(R.plurals.story_age_months, n, n)
            StoryFeed.Age.YEARS -> res.getQuantityString(R.plurals.story_age_years, n, n)
            StoryFeed.Age.UNKNOWN -> null
        }
    }

    private fun length(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ---- Add / remove -------------------------------------------------------------------------

    fun showAddSheet() {
        host.openSheet { box ->
            host.sheetHeading(box, res.getString(R.string.sheet_add_channel), res.getString(R.string.sheet_add_channel_sub))
            val v = LayoutInflater.from(box.context).inflate(R.layout.sheet_add_channel, box, false)
            val input = v.findViewById<EditText>(R.id.channel_input)
            val search = v.findViewById<TextView>(R.id.channel_search)
            val status = v.findViewById<TextView>(R.id.channel_status)
            val results = v.findViewById<LinearLayout>(R.id.channel_results)
            var ready = false
            var job: Job? = null
            fun setReady(on: Boolean) {
                if (on == ready) return
                ready = on
                search.isEnabled = on
                val a = if (on) 1f else 0.35f
                if (host.animationsOn()) search.animate().alpha(a).setDuration(180L).start() else search.alpha = a
            }
            fun say(text: String?) {
                status.text = text ?: ""
                status.visibility = if (text == null) View.GONE else View.VISIBLE
            }
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = setReady(!s?.toString()?.trim().isNullOrEmpty())
            })
            fun submit() {
                val q = input.text?.toString()?.trim().orEmpty()
                if (q.isEmpty() || host.sheetClosing()) return
                host.haptic(search)
                host.hideKeyboard()
                job?.cancel()
                results.removeAllViews()
                say(res.getString(R.string.channel_searching))
                job = scope.launch {
                    val found = app.sourceAdapter.findChannels(q)
                    when {
                        found == null -> say(res.getString(R.string.channel_search_error))
                        found.isEmpty() -> say(res.getString(R.string.channel_none_found))
                        else -> {
                            say(null)
                            for (c in found) results.addView(channelResult(results, c))
                            Painter.paint(results, host.palette())
                        }
                    }
                }
            }
            search.setOnClickListener { if (ready) submit() }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    if (ready) submit()
                    true
                } else false
            }
            box.addView(v)
            input.requestFocus()
            host.showKeyboard(input)
        }
    }

    /** A found channel, as the same pill as Search's artists, with + (or "Already added"). */
    private fun channelResult(parent: LinearLayout, c: StoryChannel): View {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_artist_result, parent, false)
        v.setPadding(0, 0, 0, v.paddingBottom)
        val pill = v.findViewById<LinearLayout>(R.id.artist_pill).apply { clipToOutline = true }
        val photo = v.findViewById<ImageView>(R.id.artist_photo_small).apply { clipToOutline = true }
        v.findViewById<TextView>(R.id.artist_result_name).text = c.name
        val added = store.contains(c)
        val sub = if (added) res.getString(R.string.channel_added_already) else c.subtitle
        v.findViewById<TextView>(R.id.artist_result_subs).apply {
            text = sub
            visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
        }
        (pill.getChildAt(pill.childCount - 1) as? ImageView)?.apply {
            rotation = 0f
            setImageResource(if (added) R.drawable.ic_check else R.drawable.ic_add)
        }
        val px = (48 * res.displayMetrics.density).toInt()
        app.artworkPipeline.loadInto(photo, c.avatarUrl, px)
        pill.contentDescription = c.name
        pill.setOnClickListener {
            if (host.sheetClosing()) return@setOnClickListener
            host.haptic(it)
            host.closeSheet()
            if (store.add(c)) {
                host.showNote(res.getString(R.string.note_channel_added, c.name))
                selectedId = null
                render()
                list.scrollToPosition(0)
                fetch(force = true)
            }
        }
        return v
    }

    private fun confirmRemove(c: StoryChannel) {
        host.openSheet { box ->
            host.sheetHeading(box, res.getString(R.string.remove_channel_title, c.name), res.getString(R.string.remove_channel_sub))
            host.confirmSheet(box, res.getString(R.string.remove_channel_ok)) {
                store.remove(c.id)
                host.showNote(res.getString(R.string.note_channel_removed, c.name))
                render()
            }
        }
    }

    companion object {
        private const val STALE_MS = 20 * 60_000L
        private const val NEW_STALE_MS = 3 * 60 * 60_000L
        private const val MAX_QUEUE = 100
    }
}
