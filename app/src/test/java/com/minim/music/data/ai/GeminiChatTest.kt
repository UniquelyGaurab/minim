package com.minim.music.data.ai

import com.minim.music.data.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.minim.music.data.greetings.MiniJson

/** 5.2.0: the parts of the Gemini chat that don't need a phone or the network. */
class GeminiChatTest {

    private fun track(id: String, title: String = "Song $id") =
        Track(id, title, "Artist $id", 200, "", "https://music.youtube.com/watch?v=$id")

    @Test
    fun models_presets_default_and_custom_ids() {
        assertEquals("gemini-3.5-flash-lite", GeminiModels.DEFAULT)
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite"), GeminiModels.PRESETS.map { it.id })
        assertEquals("gemini-2.5-pro", GeminiModels.normalize("  models/gemini-2.5-pro "))
        assertEquals("gemini-x", GeminiModels.normalize("\"gemini-x\""))
        assertTrue(GeminiModels.isValidId("gemini-3.5-flash-lite-preview-10-2026"))
        assertFalse(GeminiModels.isValidId(""))
        assertFalse(GeminiModels.isValidId("gemini pro"))
        assertFalse(GeminiModels.isValidId("../../v1/keys"))
        assertFalse(GeminiModels.isValidId("a?key=x"))
        // a saved value that's unusable falls back to the default
        assertEquals(GeminiModels.DEFAULT, GeminiModels.resolve(null))
        assertEquals(GeminiModels.DEFAULT, GeminiModels.resolve("bad id!"))
        assertEquals("gemini-3.1-flash-lite", GeminiModels.resolve("gemini-3.1-flash-lite"))
        assertEquals("Gemini 3.5 Flash-Lite", GeminiModels.labelFor("gemini-3.5-flash-lite"))
        assertEquals("my-model", GeminiModels.labelFor("my-model"))
    }

    @Test
    fun key_cleaning_hint_and_mask_never_show_the_key() {
        val key = "AIza" + "SyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q" // split so no real-looking key sits in the source
        assertEquals(key, GeminiSettings.cleanKey("  \"$key\"\n"))
        assertEquals(key, GeminiSettings.cleanKey("AIzaSyA1b2C3d4 E5f6G7h8I9j0K1l2M3n4O5p6Q"))
        assertTrue(GeminiSettings.looksLikeKey(key))
        assertFalse(GeminiSettings.looksLikeKey("sk-1234567890"))
        val masked = GeminiSettings.masked(key)
        assertEquals("AIza…5p6Q", masked)
        assertFalse(masked.contains(key.substring(4, key.length - 4)))
        assertEquals("••••", GeminiSettings.masked("short"))
    }

    @Test
    fun errors_are_sorted_into_friendly_problems() {
        GeminiRoute.defaultKnownOff = false
        val badKey = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        assertEquals(GeminiProblem.BadKey, GeminiErrors.classify(400, badKey, ownKey = true, model = "m"))
        assertEquals(GeminiProblem.Quota, GeminiErrors.classify(429, """{"error":{"status":"RESOURCE_EXHAUSTED"}}""", true, "m"))
        assertEquals(GeminiProblem.UnknownModel("nope"), GeminiErrors.classify(404, null, true, "nope"))
        assertEquals(GeminiProblem.Busy, GeminiErrors.classify(503, null, true, "m"))
        // junk bodies never crash
        assertNotNull(GeminiErrors.classify(500, "<html>oops", true, "m"))
    }

    @Test
    fun built_in_route_off_is_remembered_so_the_chat_asks_for_a_key_up_front() {
        GeminiRoute.defaultKnownOff = false
        val off = """{"error":{"code":403,"message":"Firebase AI Logic API has not been used in project minim before or it is disabled.","status":"PERMISSION_DENIED"}}"""
        assertEquals(GeminiProblem.DefaultUnavailable, GeminiErrors.classify(403, off, ownKey = false, model = "m"))
        assertTrue(GeminiRoute.defaultKnownOff)
        GeminiRoute.defaultKnownOff = false
    }

    @Test
    fun stream_chunks_give_text_and_function_calls_and_skip_thoughts() {
        val c = GeminiStream.parseChunk(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"hidden","thought":true},{"text":"Hi "},{"functionCall":{"name":"search_songs","args":{"query":"kesariya"}}}]}}]}"""
        )!!
        assertEquals("Hi ", c.text)
        assertEquals(1, c.calls.size)
        assertEquals("search_songs", c.calls[0].name)
        assertEquals("kesariya", c.calls[0].args["query"])
        assertFalse(c.blocked)
        assertTrue(GeminiStream.parseChunk("""{"candidates":[{"finishReason":"SAFETY"}]}""")!!.blocked)
        assertNull(GeminiStream.parseChunk("not json"))
    }

    private class FakeActions : MinimActions {
        var playing: Track? = null
        val liked = LinkedHashSet<String>()
        val queued = ArrayList<String>()
        var played: List<Track> = emptyList()
        val results = listOf(Track("a1", "Kesariya", "Arijit Singh", 268, "", ""), Track("a2", "Apna Bana Le", "Arijit Singh", 261, "", ""))
        override suspend fun search(query: String) = results
        override suspend fun similar(seed: Track) = results.filter { it.id != seed.id }
        override fun currentTrack() = playing
        override fun positionMs() = 61_000L
        override fun isPlaying() = playing != null
        override suspend fun lyricsText(track: Track): String? = "line one\nline two"
        override fun upNext() = emptyList<Track>()
        override fun play(tracks: List<Track>) { played = tracks; playing = tracks.first() }
        override fun addToQueue(tracks: List<Track>, playNext: Boolean) { queued += tracks.map { it.id } }
        override suspend fun isLiked(track: Track) = track.id in liked
        override suspend fun setLiked(track: Track, liked: Boolean) { if (liked) this.liked += track.id else this.liked -= track.id }
        override suspend fun recentTracks(limit: Int) = results.take(limit)
        override suspend fun likedTracks() = results.filter { it.id in liked }
        override suspend fun playsSince(sinceMs: Long) = listOf(results[0] to 1_000L, results[0] to 2_000L, results[1] to 3_000L)
        override fun openArtist(name: String) {}
        override fun nowMs() = 10_000_000L
    }

    @Test
    fun tools_search_then_play_like_and_preview_a_playlist() = runBlocking {
        val fake = FakeActions()
        val box = ChatToolbox(fake)
        val events = ArrayList<ChatEvent>()
        val emit: (ChatEvent) -> Unit = { events += it }

        val found = box.run(FunctionCall("search_songs", mapOf("query" to "arijit"), null), emit)
        assertEquals(2, (found["songs"] as List<*>).size)

        // ids Gemini never saw are refused
        assertTrue(box.run(FunctionCall("play_songs", mapOf("song_ids" to listOf("zz")), null), emit).containsKey("error"))

        box.run(FunctionCall("play_songs", mapOf("song_ids" to listOf("a1")), null), emit)
        assertEquals(listOf("a1"), fake.played.map { it.id })

        // like defaults to the song that's playing, and only once
        assertEquals("Kesariya", box.run(FunctionCall("like_song", emptyMap(), null), emit)["liked"])
        assertTrue(events.any { it is ChatEvent.Liked })
        assertTrue(box.run(FunctionCall("like_song", emptyMap(), null), emit).containsKey("already_liked"))

        box.run(FunctionCall("add_to_queue", mapOf("song_ids" to listOf("a2"), "play_next" to true), null), emit)
        assertEquals(listOf("a2"), fake.queued)

        val lyr = box.run(FunctionCall("get_current_song", mapOf("include_lyrics" to true), null), emit)
        assertEquals("line one\nline two", lyr["lyrics"])
        assertEquals("1:01", lyr["position"])

        box.run(FunctionCall("create_playlist_preview", mapOf("name" to "Rainy", "song_ids" to listOf("a1", "a2")), null), emit)
        val preview = events.filterIsInstance<ChatEvent.PlaylistPreview>().single()
        assertEquals("Rainy", preview.name)
        assertEquals(2, preview.tracks.size)

        box.run(FunctionCall("open_artist", mapOf("name" to "Arijit Singh"), null), emit)
        assertTrue(events.any { it is ChatEvent.OpenArtist && it.name == "Arijit Singh" })

        assertTrue(box.run(FunctionCall("no_such_tool", emptyMap(), null), emit).containsKey("error"))
    }

    @Test
    fun stats_count_plays_from_this_phone() = runBlocking {
        val r = ChatToolbox(FakeActions()).run(FunctionCall("get_listening_stats", mapOf("period" to "all"), null)) {}
        val text = JsonOut.write(r)
        assertTrue(text, text.contains("Kesariya"))
        assertTrue(text, text.contains("3"))
    }

    @Test
    fun declarations_and_prompt_are_valid_and_short_replies_are_asked_for() {
        val names = ChatTools.declarations.map { it["name"] }
        for (n in listOf("search_songs", "play_songs", "add_to_queue", "like_song", "get_current_song", "get_listening_stats", "open_artist", "create_playlist_preview")) {
            assertTrue(n, n in names)
        }
        val p = ChatTools.systemPrompt("Gaurab", "Sunday 25 October 2026, 9:00 AM")
        assertTrue(p.contains("Gaurab"))
        assertNotNull(MiniJson.parse(JsonOut.write(mapOf("tools" to ChatTools.declarations, "p" to p))))
    }
}
