package com.minim.music.data.themes

import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class OkHttpThemeFetcher(client: OkHttpClient) : ThemeFetcher {
    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun fetch(url: String, etag: String?, maxBytes: Int): ThemeFetch {
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> ThemeFetch.NotModified
                    !response.isSuccessful -> ThemeFetch.Failed("HTTP ${response.code}")
                    else -> {
                        val body = response.body ?: return ThemeFetch.Failed("no body")
                        if (body.contentLength() > maxBytes) return ThemeFetch.Failed("too big")
                        val source = body.source()
                        source.request(maxBytes + 1L)
                        if (source.buffer.size > maxBytes) return ThemeFetch.Failed("too big")
                        ThemeFetch.Ok(source.buffer.readByteArray(), response.header("ETag"))
                    }
                }
            }
        } catch (e: Exception) {
            ThemeFetch.Failed(e.javaClass.simpleName)
        }
    }
}

/** What a 7-tap on the version did, for its toast. */
sealed class PreviewResult {
    data class On(val name: String) : PreviewResult()
    data class Off(val name: String) : PreviewResult()
    data class BigDayOn(val name: String, val date: ThemeDate) : PreviewResult()
    data class BigDayOff(val name: String) : PreviewResult()
    object NoBigDay : PreviewResult()
    object NothingAhead : PreviewResult()
    object Offline : PreviewResult()
    object NotPreviewing : PreviewResult()
}

/**
 * 5.5.2: keeps the festival themes up to date and works out what Home should show. Blocking: run
 * on a background thread (MinimApp has one).
 */
class ThemeSync(
    private val store: ThemeStore,
    private val fetcher: ThemeFetcher,
    private val today: () -> ThemeDate = { ThemeDate.today() }
) {
    /** Checks for updates when due (every 10 minutes, [force] = now) and returns the state for today. */
    fun run(force: Boolean = false): ThemeState {
        val due = force || store.isDue()
        val file = store.refreshThemes(fetcher, force)
        if (file != null) {
            val day = today()
            val preview = store.preview()
            val wanted = file.festivals.filter { ThemePicker.shouldPrefetch(it, day) || it.id == preview?.id }
            if (due) wanted.forEach { store.refreshFestival(fetcher, it) }
            else wanted.filter { needsFiles(it) }.forEach { store.refreshFestival(fetcher, it) }
            // Festivals that are over or gone lose their files (the preview's stay)
            store.prune(file.festivals.filter { day <= it.end }.map { it.id }.toSet() + listOfNotNull(preview?.id))
        }
        return state(file)
    }

    /** The state from what's on the phone, without downloading. */
    fun state(file: FestivalThemeFile? = store.loadThemes()): ThemeState {
        val day = today()
        var preview = store.preview()
        // A preview of a festival that's over (or gone) switches itself off
        if (preview != null && file != null && file.festivals.none { it.id == preview!!.id && day <= it.end }) {
            store.setPreview(null)
            preview = null
        }
        return ThemeState.resolve(file, day, preview, { store.loadGreetingsText(it.id) }, { store.wallpaperFor(it) })
    }

    private fun needsFiles(f: FestivalTheme): Boolean =
        (f.greetingsPath != null && store.loadGreetingsText(f.id) == null) ||
            (f.wallpaper != null && store.wallpaperFor(f) == null)

    /** 7 taps on the version: the preview on (the festival on now, else the next one) or off. */
    fun togglePreview(): Pair<PreviewResult, ThemeState> {
        val current = store.preview()
        var file = store.loadThemes()
        if (current != null) {
            store.setPreview(null)
            val name = file?.festivals?.firstOrNull { it.id == current.id }?.name ?: "Theme"
            return PreviewResult.Off(name) to state(file)
        }
        // Always look for the newest list first, so a festival added a minute ago can be previewed
        file = store.refreshThemes(fetcher, force = true) ?: return PreviewResult.Offline to state(null)
        val target = ThemePicker.upcoming(file.festivals, today()) ?: return PreviewResult.NothingAhead to state(file)
        store.setPreview(ThemePreview(target.id))
        store.refreshFestival(fetcher, target)
        return PreviewResult.On(target.name) to state(file)
    }

    /** Long press on the version during a preview: the festival's big day (its "days" lines today) on or off. */
    fun toggleBigDay(): Pair<PreviewResult, ThemeState> {
        val current = store.preview() ?: return PreviewResult.NotPreviewing to state()
        val file = store.loadThemes()
        val theme = file?.festivals?.firstOrNull { it.id == current.id } ?: return PreviewResult.NotPreviewing to state(file)
        if (current.bigDay) {
            store.setPreview(current.copy(bigDay = false))
            return PreviewResult.BigDayOff(theme.name) to state(file)
        }
        val greetings = state(file).greetings
        val day = greetings?.specialDays?.firstOrNull() ?: return PreviewResult.NoBigDay to state(file)
        store.setPreview(current.copy(bigDay = true))
        return PreviewResult.BigDayOn(theme.name, day) to state(file)
    }
}
