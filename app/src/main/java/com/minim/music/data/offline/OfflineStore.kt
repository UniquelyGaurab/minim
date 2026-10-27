package com.minim.music.data.offline

import com.minim.music.data.model.Track
import com.minim.music.data.source.RawLyrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 5.4.0: one song saved offline. Files are names inside the store's folders. */
data class OfflineSong(
    val track: Track,
    val audioFile: String,
    val bytes: Long,
    /** "webm" (Opus) or "m4a" (AAC), exactly as YouTube sent it. */
    val format: String,
    val bitrate: Int,
    val itag: Int,
    val savedAtMs: Long,
    val artFile: String? = null,
    /** The saved cover is a YouTube video frame (cropped to a square when shown). */
    val artIsVideoFrame: Boolean = false,
    val lyricsFile: String? = null
)

/**
 * 5.4.0: songs saved offline, kept in the app's private files folder (`files/offline`), never in
 * the cache, so Android's "Clear cache" leaves them alone. Audio in `audio/`, lyrics in
 * `lyrics/`, covers in `art/`, and the list in `index.json` (written to a temp file, then renamed).
 */
class OfflineStore(root: File) {

    val dir: File = root
    private val audioDir = File(root, "audio")
    private val lyricsDir = File(root, "lyrics")
    private val artDir = File(root, "art")
    private val indexFile = File(root, "index.json")
    private val lock = Any()

    /** Saved songs by id, newest first. */
    private val songs = LinkedHashMap<String, OfflineSong>()
    private val _saved = MutableStateFlow<List<OfflineSong>>(emptyList())
    val saved: StateFlow<List<OfflineSong>> = _saved

    /** Thumbnail URL -> saved cover, for the artwork loader. */
    @Volatile private var artByUrl: Map<String, OfflineSong> = emptyMap()

    init {
        load()
    }

    fun isSaved(id: String): Boolean = synchronized(lock) { songs.containsKey(id) }

    fun song(id: String): OfflineSong? = synchronized(lock) { songs[id] }

    /** The saved audio for [id], or null when it isn't saved (or the file went missing). */
    fun audioFor(id: String): File? {
        val s = song(id) ?: return null
        val f = File(audioDir, s.audioFile)
        return f.takeIf { it.isFile && it.length() == s.bytes }
    }

    fun lyricsFor(id: String): RawLyrics? {
        val name = song(id)?.lyricsFile ?: return null
        return try {
            val o = JSONObject(File(lyricsDir, name).readText())
            RawLyrics(o.optBoolean("synced"), o.optString("text")).takeIf { it.text.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** The saved cover for a thumbnail URL: (file, is a video frame), or null. */
    fun artFor(url: String): Pair<File, Boolean>? {
        val s = artByUrl[url] ?: return null
        val f = File(artDir, s.artFile ?: return null)
        return if (f.isFile && f.length() > 0) f to s.artIsVideoFrame else null
    }

    /** Where a new download goes before it is added (inside the store, so a rename finishes it). */
    fun tempAudioFile(id: String): File {
        audioDir.mkdirs()
        return File(audioDir, "${safe(id)}.part")
    }

    fun audioName(id: String, format: String) = "${safe(id)}.$format"

    /** Moves a finished download into place, saves its lyrics and cover, and lists the song. */
    fun add(
        track: Track,
        downloaded: File,
        format: String,
        bitrate: Int,
        itag: Int,
        lyrics: RawLyrics?,
        art: File?,
        artIsVideoFrame: Boolean,
        now: Long = System.currentTimeMillis()
    ): OfflineSong {
        audioDir.mkdirs()
        val audio = File(audioDir, audioName(track.id, format))
        audio.delete()
        if (!downloaded.renameTo(audio)) {
            downloaded.copyTo(audio, overwrite = true)
            downloaded.delete()
        }
        var lyricsName: String? = null
        if (lyrics != null && lyrics.text.isNotBlank()) {
            lyricsDir.mkdirs()
            lyricsName = "${safe(track.id)}.json"
            val o = JSONObject().put("synced", lyrics.synced).put("text", lyrics.text)
            writeAtomic(File(lyricsDir, lyricsName), o.toString())
        }
        var artName: String? = null
        if (art != null && art.isFile && art.length() > 0) {
            artDir.mkdirs()
            artName = "${safe(track.id)}.img"
            try {
                art.copyTo(File(artDir, artName), overwrite = true)
            } catch (_: Exception) {
                artName = null
            }
        }
        val song = OfflineSong(
            track = track, audioFile = audio.name, bytes = audio.length(), format = format,
            bitrate = bitrate, itag = itag, savedAtMs = now, artFile = artName,
            artIsVideoFrame = artIsVideoFrame, lyricsFile = lyricsName
        )
        synchronized(lock) {
            songs.remove(track.id)
            val rest = songs.values.toList()
            songs.clear()
            songs[track.id] = song
            rest.forEach { songs[it.track.id] = it }
            persistLocked()
        }
        return song
    }

    /** Removes these songs and their files. */
    fun remove(ids: Collection<String>) {
        val gone = synchronized(lock) {
            val g = ids.mapNotNull { songs.remove(it) }
            if (g.isNotEmpty()) persistLocked()
            g
        }
        gone.forEach { deleteFiles(it) }
    }

    /** "Remove all" in Settings: every saved song and every leftover file. */
    fun removeAll() {
        synchronized(lock) {
            songs.clear()
            persistLocked()
        }
        listOf(audioDir, lyricsDir, artDir).forEach { d -> d.listFiles()?.forEach { it.delete() } }
    }

    /** Bytes used by saved songs, their lyrics and covers. */
    fun bytesUsed(): Long = listOf(audioDir, lyricsDir, artDir).sumOf { d ->
        d.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L
    }

    /** Leftover half-downloads from a save that was interrupted (the app closed mid-save). */
    fun cleanPartials() {
        audioDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun deleteFiles(s: OfflineSong) {
        File(audioDir, s.audioFile).delete()
        s.lyricsFile?.let { File(lyricsDir, it).delete() }
        s.artFile?.let { File(artDir, it).delete() }
    }

    private fun load() {
        synchronized(lock) {
            songs.clear()
            try {
                if (indexFile.isFile) {
                    val arr = JSONObject(indexFile.readText()).optJSONArray("songs") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val s = fromJson(arr.getJSONObject(i)) ?: continue
                        // A song whose audio went missing isn't offline any more
                        if (File(audioDir, s.audioFile).isFile) songs[s.track.id] = s
                    }
                }
            } catch (_: Exception) {
                songs.clear()
            }
            publishLocked()
        }
    }

    private fun persistLocked() {
        val arr = JSONArray()
        songs.values.forEach { arr.put(toJson(it)) }
        val o = JSONObject().put("version", 1).put("songs", arr)
        try {
            dir.mkdirs()
            writeAtomic(indexFile, o.toString())
        } catch (_: Exception) {
        }
        publishLocked()
    }

    private fun publishLocked() {
        val list = songs.values.toList()
        artByUrl = list.filter { it.artFile != null && it.track.thumbnailUrl.isNotBlank() }
            .associateBy { it.track.thumbnailUrl }
        _saved.value = list
    }

    private fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    companion object {
        /** File-name-safe song id (YouTube ids are already safe; anything else is cleaned). */
        private val UNSAFE_CHARS = com.minim.music.util.SafeRegex.of("[^A-Za-z0-9_-]")
        fun safe(id: String): String = id.replace(UNSAFE_CHARS, "_").take(80).ifBlank { "song" }

        /** The store's folder: the app's private files (kept when the cache is cleared), never the cache. */
        fun folderIn(filesDir: File): File = File(filesDir, "offline")

        fun toJson(s: OfflineSong): JSONObject = JSONObject()
            .put("id", s.track.id).put("title", s.track.title).put("artist", s.track.artist)
            .put("durationSec", s.track.durationSec).put("thumb", s.track.thumbnailUrl)
            .put("sourceUrl", s.track.sourceUrl)
            .put("audio", s.audioFile).put("bytes", s.bytes).put("format", s.format)
            .put("bitrate", s.bitrate).put("itag", s.itag).put("savedAt", s.savedAtMs)
            .put("art", s.artFile ?: "").put("artFrame", s.artIsVideoFrame)
            .put("lyrics", s.lyricsFile ?: "")

        fun fromJson(o: JSONObject): OfflineSong? {
            val id = o.optString("id")
            val audio = o.optString("audio")
            if (id.isBlank() || audio.isBlank() || audio.contains('/') || audio.contains("..")) return null
            fun name(key: String) = o.optString(key).takeIf { it.isNotBlank() && !it.contains('/') && !it.contains("..") }
            return OfflineSong(
                track = Track(
                    id = id, title = o.optString("title"), artist = o.optString("artist"),
                    durationSec = o.optLong("durationSec"), thumbnailUrl = o.optString("thumb"),
                    sourceUrl = o.optString("sourceUrl")
                ),
                audioFile = audio, bytes = o.optLong("bytes"), format = o.optString("format"),
                bitrate = o.optInt("bitrate"), itag = o.optInt("itag"), savedAtMs = o.optLong("savedAt"),
                artFile = name("art"), artIsVideoFrame = o.optBoolean("artFrame"), lyricsFile = name("lyrics")
            )
        }
    }
}
