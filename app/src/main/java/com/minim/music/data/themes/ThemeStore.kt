package com.minim.music.data.themes

import java.io.File

/** What one theme download gave back (bytes, so it works for the wallpaper too). */
sealed class ThemeFetch {
    class Ok(val bytes: ByteArray, val etag: String?) : ThemeFetch()
    object NotModified : ThemeFetch()
    data class Failed(val reason: String) : ThemeFetch()
}

/** Downloads [url] (a conditional GET when [etag] is set); more than [maxBytes] counts as failed. */
fun interface ThemeFetcher {
    fun fetch(url: String, etag: String?, maxBytes: Int): ThemeFetch
}

/** The 7-tap preview: which festival, and (long press) whether its big day is being previewed. */
data class ThemePreview(val id: String, val bigDay: Boolean = false)

/**
 * 5.5.2: the festival themes kept on the phone, in [dir]:
 *
 * - `themes.json` (+ meta): the last good copy, so themes work offline
 * - `<id>/greetings.json` and `<id>/wallpaper.webp` (+ their ETags and source path)
 * - `preview.txt`: the 7-tap preview, on this phone only
 *
 * A download that fails or isn't usable never replaces a good copy. Blocking: call it off the main thread.
 */
class ThemeStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    private fun festivalDir(id: String) = File(dir, "f_$id")
    val themesFile get() = File(dir, "themes.json")
    private val metaFile get() = File(dir, "themes.meta")
    private val previewFile get() = File(dir, "preview.txt")

    fun greetingsFile(id: String) = File(festivalDir(id), "greetings.json")
    fun wallpaperFile(id: String) = File(festivalDir(id), "wallpaper.webp")

    // ---- themes.json ---------------------------------------------------------------------------

    fun loadThemes(): FestivalThemeFile? = try {
        val f = themesFile
        if (!f.isFile || f.length() > MAX_THEMES_BYTES) null
        else FestivalThemesParser.parse(f.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    /** When themes.json was last checked (ms), for the "every 10 minutes" rule. */
    fun lastCheck(): Long = readKv(metaFile)["lastCheck"]?.toLongOrNull() ?: 0L

    fun isDue(): Boolean {
        val last = lastCheck()
        val now = clock()
        return last <= 0L || now < last || now - last >= CHECK_EVERY_MS
    }

    /**
     * Downloads themes.json when due ([force] = now). Returns the file to use: the new one, else
     * the last good copy (also when the new one is broken), else null.
     */
    fun refreshThemes(fetcher: ThemeFetcher, force: Boolean = false): FestivalThemeFile? {
        val cached = loadThemes()
        if (!force && !isDue()) return cached
        val meta = readKv(metaFile)
        val etag = meta["etag"]?.takeIf { cached != null && it.isNotEmpty() }
        val result = try {
            fetcher.fetch(THEMES_URL, etag, MAX_THEMES_BYTES)
        } catch (e: Exception) {
            ThemeFetch.Failed(e.javaClass.simpleName)
        }
        return when (result) {
            is ThemeFetch.Ok -> {
                val text = String(result.bytes, Charsets.UTF_8)
                val parsed = FestivalThemesParser.parse(text)
                if (parsed != null) {
                    writeAtomically(themesFile, result.bytes)
                    writeKv(metaFile, mapOf("lastCheck" to clock().toString(), "etag" to result.etag.orEmpty()))
                    parsed
                } else {
                    writeKv(metaFile, meta + ("lastCheck" to clock().toString()))
                    cached
                }
            }
            ThemeFetch.NotModified -> {
                writeKv(metaFile, meta + ("lastCheck" to clock().toString()))
                cached
            }
            // A failed try waits a minute before the next, so a phone without internet doesn't hammer
            is ThemeFetch.Failed -> {
                writeKv(metaFile, meta + ("lastCheck" to (clock() - CHECK_EVERY_MS + RETRY_AFTER_MS).toString()))
                cached
            }
        }
    }

    // ---- A festival's files --------------------------------------------------------------------

    fun loadGreetingsText(id: String): String? = try {
        val f = greetingsFile(id)
        if (!f.isFile || f.length() > MAX_GREETINGS_BYTES) null else f.readText(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** The wallpaper on the phone for [f], or null (none, or it was for another path). */
    fun wallpaperFor(f: FestivalTheme): File? {
        val path = f.wallpaper ?: return null
        val file = wallpaperFile(f.id)
        val meta = readKv(File(festivalDir(f.id), "wallpaper.meta"))
        return file.takeIf { it.isFile && it.length() > 0 && meta["path"] == path }
    }

    /** Downloads (or re-checks) [f]'s greetings file and wallpaper. Returns whether anything changed. */
    fun refreshFestival(fetcher: ThemeFetcher, f: FestivalTheme): Boolean {
        var changed = false
        f.greetingsPath?.let { path ->
            changed = refreshOne(fetcher, f.id, "greetings", greetingsFile(f.id), path, MAX_GREETINGS_BYTES) { bytes ->
                ThemeGreetingsParser.parse(String(bytes, Charsets.UTF_8)) != null
            } || changed
        }
        f.wallpaper?.let { path ->
            changed = refreshOne(fetcher, f.id, "wallpaper", wallpaperFile(f.id), path, MAX_WALLPAPER_BYTES) { bytes ->
                isWebp(bytes)
            } || changed
        }
        return changed
    }

    private fun refreshOne(
        fetcher: ThemeFetcher, id: String, name: String, target: File, path: String, max: Int,
        valid: (ByteArray) -> Boolean
    ): Boolean {
        val metaFile = File(festivalDir(id), "$name.meta")
        val meta = readKv(metaFile)
        val have = target.isFile && meta["path"] == path
        val etag = meta["etag"]?.takeIf { have && it.isNotEmpty() }
        val result = try {
            fetcher.fetch(RAW_BASE + path, etag, max)
        } catch (e: Exception) {
            ThemeFetch.Failed(e.javaClass.simpleName)
        }
        if (result is ThemeFetch.Ok && valid(result.bytes)) {
            val same = have && target.length() == result.bytes.size.toLong() &&
                (try { target.readBytes().contentEquals(result.bytes) } catch (e: Exception) { false })
            if (!same) writeAtomically(target, result.bytes)
            writeKv(metaFile, mapOf("path" to path, "etag" to result.etag.orEmpty()))
            return !same
        }
        return false
    }

    /** Removes the files of festivals that are over (or gone from the list), keeping [keep]. */
    fun prune(keep: Set<String>) {
        try {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name.startsWith("f_") && f.name.removePrefix("f_") !in keep) f.deleteRecursively()
            }
        } catch (e: Exception) {
        }
    }

    // ---- Preview -------------------------------------------------------------------------------

    fun preview(): ThemePreview? = try {
        val f = previewFile
        if (!f.isFile || f.length() > 200) null else {
            val parts = f.readText().trim().split('\n').map { it.trim() }
            parts.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { ThemePreview(it, parts.getOrNull(1) == "bigday") }
        }
    } catch (e: Exception) {
        null
    }

    fun setPreview(p: ThemePreview?) {
        try {
            if (p == null) previewFile.delete()
            else writeAtomically(previewFile, (p.id + if (p.bigDay) "\nbigday\n" else "\n").toByteArray())
        } catch (e: Exception) {
        }
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private fun readKv(f: File): Map<String, String> = try {
        if (!f.isFile || f.length() > 4096) emptyMap() else f.readLines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private fun writeKv(f: File, map: Map<String, String>) {
        try {
            writeAtomically(f, map.entries.joinToString("") { (k, v) ->
                "$k=${v.replace('\n', ' ').replace('\r', ' ').take(300)}\n"
            }.toByteArray())
        } catch (e: Exception) {
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("rename failed")
            }
        }
    }

    companion object {
        const val RAW_BASE = "https://raw.githubusercontent.com/UniquelyGaurab/minim/main/"
        const val THEMES_URL = RAW_BASE + "themes/themes.json"
        const val CHECK_EVERY_MS = 10 * 60 * 1000L
        const val RETRY_AFTER_MS = 60 * 1000L
        const val MAX_THEMES_BYTES = 256 * 1024
        const val MAX_GREETINGS_BYTES = 128 * 1024
        const val MAX_WALLPAPER_BYTES = 1024 * 1024

        /** A real WebP starts with "RIFF" .... "WEBP" (a renamed PNG or JPG doesn't). */
        fun isWebp(b: ByteArray): Boolean =
            b.size > 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
                b[3] == 'F'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
                b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
    }
}

/** What Home should look like right now. */
data class ThemeState(
    val theme: FestivalTheme? = null,
    val preview: ThemePreview? = null,
    /** The festival's greetings (null = the regular greetings stay on). */
    val greetings: ThemeGreetingSet? = null,
    val wallpaper: File? = null
) {
    val isOn: Boolean get() = theme != null

    companion object {
        val NONE = ThemeState()

        /**
         * Resolves the state for [today]: the preview's festival when one is set (and still in the
         * list and not over), else the festival on today. [greetingsText] and [wallpaper] read what's
         * on the phone. The big-day preview moves the festival's "days" lines to today.
         */
        fun resolve(
            file: FestivalThemeFile?,
            today: ThemeDate,
            preview: ThemePreview?,
            greetingsText: (FestivalTheme) -> String?,
            wallpaper: (FestivalTheme) -> File?
        ): ThemeState {
            val list = file?.festivals.orEmpty()
            val previewed = preview?.let { p -> list.firstOrNull { it.id == p.id && today <= it.end } }
            val theme = previewed ?: ThemePicker.active(list, today) ?: return NONE
            val usedPreview = if (previewed != null) preview else null
            val override = if (usedPreview?.bigDay == true) today else null
            val greetings = when {
                theme.greetingsPath != null -> ThemeGreetingsParser.parse(greetingsText(theme), override)
                theme.inlineGreetings != null -> ThemeGreetingsParser.parseMap(theme.inlineGreetings, override)
                else -> null
            }
            return ThemeState(theme, usedPreview, greetings, wallpaper(theme))
        }
    }
}
