package com.minim.music.data.ai

import com.minim.music.data.model.Track
import com.minim.music.data.stats.StatsEngine

/**
 * v5.2.0: what the Gemini chat may do inside Minim. MainActivity implements it with the
 * app's own search, player, library and stats, so Gemini can only do what you could do
 * yourself with a few taps.
 */
interface MinimActions {
    suspend fun search(query: String): List<Track>
    suspend fun similar(seed: Track): List<Track>
    fun currentTrack(): Track?
    fun positionMs(): Long
    fun isPlaying(): Boolean
    suspend fun lyricsText(track: Track): String?
    fun upNext(): List<Track>
    fun play(tracks: List<Track>)
    fun addToQueue(tracks: List<Track>, playNext: Boolean)
    suspend fun isLiked(track: Track): Boolean
    suspend fun setLiked(track: Track, liked: Boolean)
    suspend fun recentTracks(limit: Int): List<Track>
    suspend fun likedTracks(): List<Track>
    /** Counted plays since [sinceMs], newest first. */
    suspend fun playsSince(sinceMs: Long): List<Pair<Track, Long>>
    fun openArtist(name: String)
    fun nowMs(): Long
}

/** What the chat screen shows besides Gemini's own words. */
sealed class ChatEvent {
    data class Songs(val tracks: List<Track>) : ChatEvent()
    data class Liked(val track: Track) : ChatEvent()
    data class Action(val kind: ActionKind, val title: String, val count: Int = 1) : ChatEvent()
    data class PlaylistPreview(val name: String, val tracks: List<Track>) : ChatEvent()
    data class OpenArtist(val name: String) : ChatEvent()
}

enum class ActionKind { PLAYING, QUEUED, QUEUED_NEXT, ALREADY_LIKED }

object ChatTools {

    const val MAX_SONGS = 15

    private fun idsParam(desc: String) = mapOf(
        "type" to "array",
        "items" to mapOf("type" to "string"),
        "description" to desc,
    )

    private fun fn(name: String, desc: String, props: Map<String, Any?> = emptyMap(), required: List<String> = emptyList()): Map<String, Any?> {
        val params = linkedMapOf<String, Any?>("type" to "object", "properties" to props)
        if (required.isNotEmpty()) params["required"] = required
        return if (props.isEmpty()) mapOf("name" to name, "description" to desc)
        else mapOf("name" to name, "description" to desc, "parameters" to params)
    }

    /** The function declarations sent with every request. */
    val declarations: List<Map<String, Any?>> = listOf(
        fn(
            "search_songs", "Search the YouTube Music catalogue. Returns songs with ids. Shows nothing to the user.",
            mapOf(
                "query" to mapOf("type" to "string", "description" to "Song, artist, movie, lyric words or mood keywords."),
                "limit" to mapOf("type" to "integer", "description" to "How many results, 1 to 15. Default 8."),
            ),
            listOf("query"),
        ),
        fn(
            "show_songs", "Show songs in the chat as tappable cards the user can play. Use for suggestions or when the user should pick.",
            mapOf("song_ids" to idsParam("Ids from earlier tool results.")),
            listOf("song_ids"),
        ),
        fn(
            "play_songs", "Play now. One song starts it with a radio of similar songs after it; several songs play in that order.",
            mapOf("song_ids" to idsParam("Ids from earlier tool results, in play order.")),
            listOf("song_ids"),
        ),
        fn(
            "add_to_queue", "Add songs to the queue.",
            mapOf(
                "song_ids" to idsParam("Ids from earlier tool results."),
                "play_next" to mapOf("type" to "boolean", "description" to "True to play right after the current song, false for the end of the queue."),
            ),
            listOf("song_ids"),
        ),
        fn("get_up_next", "The songs queued after the current one."),
        fn(
            "find_similar", "Songs similar to a song (YouTube radio). Default: the current song.",
            mapOf(
                "song_id" to mapOf("type" to "string", "description" to "Optional id of the seed song."),
                "limit" to mapOf("type" to "integer", "description" to "How many, 1 to 15. Default 5."),
            ),
        ),
        fn(
            "like_song", "Add a song to the user's Liked songs right away. The app shows an Undo. Default: the current song.",
            mapOf("song_id" to mapOf("type" to "string", "description" to "Optional id; leave out for the current song.")),
        ),
        fn(
            "get_current_song", "The song playing now, its position, and optionally its lyrics (for translating or explaining).",
            mapOf("include_lyrics" to mapOf("type" to "boolean", "description" to "True to include the lyrics text.")),
        ),
        fn(
            "get_listening_stats", "The user's own listening stats from this phone: plays, top songs and top artists for a period.",
            mapOf(
                "period" to mapOf(
                    "type" to "string",
                    "enum" to listOf("today", "week", "month", "year", "all"),
                    "description" to "today, week (last 7 days), month (last 30 days), year (last 365 days) or all.",
                ),
            ),
            listOf("period"),
        ),
        fn(
            "get_recent_songs", "Songs the user played most recently, newest first. Good for reading their mood.",
            mapOf("limit" to mapOf("type" to "integer", "description" to "1 to 25. Default 10.")),
        ),
        fn(
            "get_liked_songs", "The user's Liked songs, newest first.",
            mapOf("limit" to mapOf("type" to "integer", "description" to "1 to 50. Default 25.")),
        ),
        fn(
            "open_artist", "Close the chat and open an artist's page in Minim.",
            mapOf("name" to mapOf("type" to "string", "description" to "Artist name.")),
            listOf("name"),
        ),
        fn(
            "create_playlist_preview",
            "Show a playlist preview card the user can rename, trim and save. It is NOT saved until the user taps Save Playlist.",
            mapOf(
                "name" to mapOf("type" to "string", "description" to "A short playlist name."),
                "song_ids" to idsParam("Ids from earlier tool results, 3 to 30 songs."),
            ),
            listOf("name", "song_ids"),
        ),
    )

    fun systemPrompt(userName: String?, localTime: String): String = buildString {
        append("You are Gemini, chatting inside Minim, a minimal music player app (YouTube Music catalogue). ")
        if (!userName.isNullOrBlank()) append("The user's name is $userName. ")
        append("Local time: $localTime.\n")
        append(
            """
Rules:
- Reply in the same language and script the user writes in. If they write Hinglish (Hindi in Latin letters mixed with English), reply in Hinglish.
- Keep every reply very short: one or two simple sentences. Do the work with the tools instead of describing it. No rambling, no lists, no headings, no markdown, no emojis unless the user uses them.
- Songs are referenced by id. Only use ids that came from tool results; never invent one.
- To play something: search_songs, then play_songs. To suggest songs: search_songs, then show_songs.
- Play by vibe or mood: run one to three searches that fit, then play_songs with a good mix.
- "Add more like this": find_similar, then add_to_queue.
- Playlists: collect songs, then create_playlist_preview with a short name. Say it's ready to save; never say it is saved.
- like_song likes right away and the app shows an Undo, so just confirm briefly.
- Translate or explain lyrics: get_current_song with include_lyrics, then answer only what was asked, briefly.
- Stats, roasts and mood reads: use get_listening_stats and get_recent_songs. Roasts are playful and about the music, never mean.
- Song stories and trivia: one or two facts; if you're not sure, say so.
- Finding a song from half a lyric or a movie scene: guess from what you know, then search_songs to confirm and show_songs.
- If a tool reports an error, say so in a few words.
""".trim()
        )
    }
}

/**
 * Runs Gemini's function calls against [actions]. Keeps every song it has handed to Gemini
 * by id, so later calls can refer to them.
 */
class ChatToolbox(
    private val actions: MinimActions,
    private val known: LinkedHashMap<String, Track> = LinkedHashMap(),
) {

    fun remember(tracks: List<Track>) {
        for (t in tracks) known[t.id] = t
        // keep it bounded
        while (known.size > 600) known.remove(known.keys.first())
    }

    fun lookup(id: String): Track? = known[id] ?: actions.currentTrack()?.takeIf { it.id == id }

    private fun songJson(t: Track): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("id" to t.id, "title" to t.title, "artist" to t.artist)
        if (t.durationSec > 0) m["duration"] = "%d:%02d".format(t.durationSec / 60, t.durationSec % 60)
        return m
    }

    private fun songsJson(list: List<Track>): List<Map<String, Any?>> {
        remember(list)
        return list.map { songJson(it) }
    }

    private fun intArg(args: Map<String, Any?>, key: String, def: Int, min: Int, max: Int): Int {
        val v = when (val raw = args[key]) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        } ?: def
        return v.coerceIn(min, max)
    }

    private fun idsArg(args: Map<String, Any?>): List<String> =
        args["song_ids"].asList().orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }.distinct()

    private fun resolve(ids: List<String>): Pair<List<Track>, List<String>> {
        val found = ArrayList<Track>()
        val missing = ArrayList<String>()
        for (id in ids) lookup(id)?.let { found += it } ?: missing.add(id)
        return found to missing
    }

    private fun err(msg: String) = mapOf("error" to msg)

    /** Runs one call. Returns the JSON-able result for Gemini; [emit] shows things in the chat. */
    suspend fun run(call: FunctionCall, emit: (ChatEvent) -> Unit): Map<String, Any?> = try {
        runInner(call, emit)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (t: Throwable) {
        err("That didn't work: ${t.javaClass.simpleName}")
    }

    private suspend fun runInner(call: FunctionCall, emit: (ChatEvent) -> Unit): Map<String, Any?> {
        val a = call.args
        when (call.name) {
            "search_songs" -> {
                val q = (a["query"] as? String)?.trim().orEmpty()
                if (q.isEmpty()) return err("Empty query.")
                val limit = intArg(a, "limit", 8, 1, ChatTools.MAX_SONGS)
                val res = actions.search(q).take(limit)
                if (res.isEmpty()) return mapOf("songs" to emptyList<Any>(), "note" to "No results.")
                return mapOf("songs" to songsJson(res))
            }
            "show_songs" -> {
                val (found, missing) = resolve(idsArg(a))
                if (found.isEmpty()) return err("Unknown song ids. Search first.")
                emit(ChatEvent.Songs(found.take(ChatTools.MAX_SONGS)))
                return withMissing(mapOf("shown" to found.size.coerceAtMost(ChatTools.MAX_SONGS)), missing)
            }
            "play_songs" -> {
                val (found, missing) = resolve(idsArg(a))
                if (found.isEmpty()) return err("Unknown song ids. Search first.")
                val list = found.take(50)
                actions.play(list)
                emit(ChatEvent.Action(ActionKind.PLAYING, list.first().title, list.size))
                return withMissing(mapOf("playing" to list.first().title, "songs" to list.size), missing)
            }
            "add_to_queue" -> {
                val (found, missing) = resolve(idsArg(a))
                if (found.isEmpty()) return err("Unknown song ids. Search first.")
                val next = a["play_next"] == true
                val list = found.take(50)
                actions.addToQueue(list, next)
                emit(ChatEvent.Action(if (next) ActionKind.QUEUED_NEXT else ActionKind.QUEUED, list.first().title, list.size))
                return withMissing(mapOf("added" to list.size, "play_next" to next), missing)
            }
            "get_up_next" -> {
                val q = actions.upNext().take(25)
                return mapOf("up_next" to songsJson(q))
            }
            "find_similar" -> {
                val seedId = (a["song_id"] as? String)?.trim().orEmpty()
                val seed = if (seedId.isNotEmpty()) lookup(seedId) else actions.currentTrack()
                    ?: return err("Nothing is playing. Give a song_id.")
                if (seed == null) return err("Unknown song id. Search first.")
                val limit = intArg(a, "limit", 5, 1, ChatTools.MAX_SONGS)
                val queued = actions.upNext().map { it.id }.toSet()
                val res = actions.similar(seed).filter { it.id != seed.id && it.id !in queued }.take(limit)
                return mapOf("seed" to seed.title, "songs" to songsJson(res))
            }
            "like_song" -> {
                val id = (a["song_id"] as? String)?.trim().orEmpty()
                val t = if (id.isNotEmpty()) lookup(id) else actions.currentTrack()
                if (t == null) return err(if (id.isEmpty()) "Nothing is playing." else "Unknown song id. Search first.")
                if (actions.isLiked(t)) {
                    emit(ChatEvent.Action(ActionKind.ALREADY_LIKED, t.title))
                    return mapOf("already_liked" to t.title)
                }
                actions.setLiked(t, true)
                emit(ChatEvent.Liked(t))
                return mapOf("liked" to t.title)
            }
            "get_current_song" -> {
                val t = actions.currentTrack() ?: return mapOf("playing" to false, "note" to "Nothing is playing.")
                remember(listOf(t))
                val m = linkedMapOf<String, Any?>(
                    "song" to songJson(t),
                    "is_playing" to actions.isPlaying(),
                    "position" to "%d:%02d".format(actions.positionMs() / 60000, (actions.positionMs() / 1000) % 60),
                )
                if (a["include_lyrics"] == true) {
                    val ly = actions.lyricsText(t)
                    if (ly.isNullOrBlank()) m["lyrics"] = "Not available for this song."
                    else m["lyrics"] = ly.take(6000)
                }
                return m
            }
            "get_listening_stats" -> return stats((a["period"] as? String)?.lowercase() ?: "week")
            "get_recent_songs" -> {
                val limit = intArg(a, "limit", 10, 1, 25)
                return mapOf("recent" to songsJson(actions.recentTracks(limit).take(limit)))
            }
            "get_liked_songs" -> {
                val limit = intArg(a, "limit", 25, 1, 50)
                val all = actions.likedTracks()
                return mapOf("total_liked" to all.size, "liked" to songsJson(all.take(limit)))
            }
            "open_artist" -> {
                val name = (a["name"] as? String)?.trim().orEmpty()
                if (name.isEmpty()) return err("No artist name.")
                emit(ChatEvent.OpenArtist(name))
                return mapOf("opened" to name)
            }
            "create_playlist_preview" -> {
                val name = (a["name"] as? String)?.trim().orEmpty().take(60).ifEmpty { "New playlist" }
                val (found, missing) = resolve(idsArg(a))
                if (found.isEmpty()) return err("Unknown song ids. Search first.")
                val list = found.take(50)
                emit(ChatEvent.PlaylistPreview(name, list))
                return withMissing(mapOf("preview_shown" to true, "name" to name, "songs" to list.size, "saved" to false), missing)
            }
        }
        return err("Unknown function ${call.name}.")
    }

    private fun withMissing(m: Map<String, Any?>, missing: List<String>): Map<String, Any?> =
        if (missing.isEmpty()) m else m + ("unknown_ids" to missing)

    private suspend fun stats(period: String): Map<String, Any?> {
        val day = 24L * 60 * 60 * 1000
        val now = actions.nowMs()
        val since = when (period) {
            "today" -> startOfToday(now)
            "week" -> now - 7 * day
            "month" -> now - 30 * day
            "year" -> now - 365 * day
            else -> 0L
        }
        val plays = actions.playsSince(since)
        val bySong = LinkedHashMap<String, Pair<Track, Int>>()
        val byArtist = HashMap<String, Int>()
        for ((t, _) in plays) {
            val cur = bySong[t.id]
            bySong[t.id] = t to ((cur?.second ?: 0) + 1)
            val artist = StatsEngine.primaryArtist(t.artist)
            if (artist.isNotEmpty()) byArtist[artist] = (byArtist[artist] ?: 0) + 1
        }
        val topSongs = bySong.values.sortedByDescending { it.second }.take(10)
        remember(topSongs.map { it.first })
        val minutes = plays.sumOf { it.first.durationSec.coerceIn(0, 900) } / 60
        return linkedMapOf(
            "period" to period,
            "plays" to plays.size,
            "different_songs" to bySong.size,
            "approx_minutes" to minutes,
            "top_songs" to topSongs.map { songJson(it.first) + ("plays" to it.second) },
            "top_artists" to byArtist.entries.sortedByDescending { it.value }.take(8)
                .map { mapOf("artist" to it.key, "plays" to it.value) },
            "note" to "A play counts after 30 seconds of listening.",
        )
    }

    private fun startOfToday(now: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = now
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
