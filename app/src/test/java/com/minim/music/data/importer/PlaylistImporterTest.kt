package com.minim.music.data.importer

import com.minim.music.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImporterTest {

    private fun t(id: String, title: String, artist: String, sec: Long = 200) =
        Track(id, title, artist, sec, "", "https://www.youtube.com/watch?v=$id")

    private class Fake(
        val yt: ImportBackend.YouTubeList? = null,
        val sp: SpotifyReader.Result? = null,
        val spError: SpotifyReader.Why? = null,
        val catalog: List<Track> = emptyList(),
        val searchFails: Boolean = false
    ) : ImportBackend {
        val queries = mutableListOf<String>()
        override suspend fun readYouTube(url: String, max: Int, onProgress: (Int) -> Unit): ImportBackend.YouTubeList {
            onProgress(yt!!.tracks.size)
            return yt
        }
        override suspend fun readSpotify(kind: String, id: String, max: Int, onProgress: (Int) -> Unit): SpotifyReader.Result {
            spError?.let { throw SpotifyReader.SpotifyException(it) }
            return sp!!
        }
        override suspend fun resolveSpotifyShort(url: String): String? = null
        override suspend fun search(query: String, videos: Boolean): List<Track>? {
            synchronized(queries) { queries.add(query) }
            if (searchFails) return null
            val words = SongMatcher.norm(query).split(' ')
            return catalog.filter { tr -> words.any { w -> SongMatcher.norm(tr.title).split(' ').contains(w) } }
        }
    }

    private fun importer(b: ImportBackend) = PlaylistImporter(b, CoroutineScope(SupervisorJob()))

    private fun runImport(b: ImportBackend, input: ImportInput): ImportState? = runBlocking {
        val imp = importer(b)
        assertTrue(imp.start(input))
        imp.awaitIdle()
        imp.state.value
    }

    @Test fun `youtube playlist is exact, ticked, and repeats inside it are removed`() {
        val b = Fake(yt = ImportBackend.YouTubeList("Chill Mix", listOf(t("a", "A", "x"), t("b", "B", "y"), t("a", "A", "x")), 3, false))
        val s = runImport(b, ImportInput.YouTube("PL1")) as ImportState.Ready
        assertEquals("Chill Mix", s.name)
        assertEquals(ImportOrigin.YOUTUBE, s.origin)
        assertEquals(2, s.items.size)
        assertTrue(s.items.all { it.included && it.confidence == Confidence.SURE })
        assertTrue(s.note!!.contains("1 repeated song"))
        assertTrue(b.queries.isEmpty()) // no matching for YouTube
    }

    @Test fun `spotify songs are matched, doubtful ones flagged, missing ones unticked`() {
        val b = Fake(
            sp = SpotifyReader.Result(
                "Road Trip",
                listOf(
                    SourceSong("Believer", listOf("Imagine Dragons"), 204_000),
                    SourceSong("Hello", listOf("Adele"), 295_000),
                    SourceSong("Zzzqqq", listOf("Nobody"), 100_000)
                ),
                3, false
            ),
            catalog = listOf(t("bel", "Believer", "Imagine Dragons", 204), t("hel", "Hello (Cover)", "Some Guy", 290))
        )
        val s = runImport(b, ImportInput.Spotify("playlist", "x")) as ImportState.Ready
        assertEquals("Road Trip", s.name)
        assertEquals(3, s.items.size)
        assertEquals(Confidence.SURE, s.items[0].confidence)
        assertEquals(Confidence.DOUBTFUL, s.items[1].confidence)
        assertTrue(s.items[1].included)
        assertEquals(Confidence.NONE, s.items[2].confidence)
        assertFalse(s.items[2].included)
        assertEquals(2, s.included)
    }

    @Test fun `two source rows finding the same video keep one`() {
        val b = Fake(
            sp = SpotifyReader.Result(
                "Dupes",
                listOf(
                    SourceSong("Believer", listOf("Imagine Dragons"), 204_000),
                    SourceSong("Believer - Remastered", listOf("Imagine Dragons"), 204_000)
                ),
                2, false
            ),
            catalog = listOf(t("bel", "Believer", "Imagine Dragons", 204))
        )
        val s = runImport(b, ImportInput.Spotify("playlist", "x")) as ImportState.Ready
        assertEquals(1, s.items.size)
        assertTrue(s.note!!.contains("repeated"))
    }

    @Test fun `songs already in other playlists are never skipped`() {
        // the importer has no idea of other playlists: every unique song comes back
        val b = Fake(
            sp = SpotifyReader.Result("P", listOf(SourceSong("Believer", listOf("Imagine Dragons"), 204_000)), 1, false),
            catalog = listOf(t("bel", "Believer", "Imagine Dragons", 204))
        )
        val s = runImport(b, ImportInput.Spotify("playlist", "x")) as ImportState.Ready
        assertEquals(1, s.items.size)
    }

    @Test fun `private spotify playlist gives the friendly message`() {
        val s = runImport(Fake(spError = SpotifyReader.Why.NOT_PUBLIC), ImportInput.Spotify("playlist", "x"))
        assertEquals(ImportMessages.SPOTIFY_PRIVATE, (s as ImportState.Failed).message)
    }

    @Test fun `spotify stopping after 100 is said clearly`() {
        val songs = (1..100).map { SourceSong("Song $it", listOf("Artist"), 0) }
        val b = Fake(sp = SpotifyReader.Result("Big", songs, 250, true), catalog = emptyList())
        val s = runImport(b, ImportInput.Spotify("playlist", "x")) as ImportState.Ready
        assertTrue(s.note!!.contains("first 100 of 250"))
    }

    @Test fun `search failing every time is an error, not an empty playlist`() {
        val b = Fake(sp = SpotifyReader.Result("P", listOf(SourceSong("A", listOf("B"), 0)), 1, false), searchFails = true)
        val s = runImport(b, ImportInput.Spotify("playlist", "x"))
        assertEquals(ImportMessages.SEARCH_FAILED, (s as ImportState.Failed).message)
    }

    @Test fun `plain text lines are matched and named your list`() {
        val lines = ImportDetect.detect("Believer – Imagine Dragons") as ImportInput.Lines
        val b = Fake(catalog = listOf(t("bel", "Believer", "Imagine Dragons", 204)))
        val s = runImport(b, lines) as ImportState.Ready
        assertEquals(ImportOrigin.TEXT, s.origin)
        assertEquals("bel", s.items[0].chosen!!.id)
    }

    @Test fun `unsupported links and empty input fail with a message`() {
        val s = runImport(Fake(), ImportInput.Unsupported(ImportInput.Why.YOUTUBE_NOT_PLAYLIST))
        assertEquals(ImportMessages.unsupported(ImportInput.Why.YOUTUBE_NOT_PLAYLIST), (s as ImportState.Failed).message)
        assertEquals(ImportMessages.EMPTY, (runImport(Fake(), ImportInput.Empty) as ImportState.Failed).message)
    }

    @Test fun `review edits - remove, choose another option, save keeps order and each song once`() = runBlocking {
        val b = Fake(yt = ImportBackend.YouTubeList("L", listOf(t("a", "A", "x"), t("b", "B", "y"), t("c", "C", "z")), 3, false))
        val imp = importer(b)
        imp.start(ImportInput.YouTube("PL"))
        imp.awaitIdle()
        imp.remove(1)
        assertEquals(listOf("a", "c"), imp.tracksToSave().map { it.id })
        imp.toggle(1)
        assertEquals(listOf("a", "b", "c"), imp.tracksToSave().map { it.id })
        imp.choose(2, t("a", "A", "x")) // picking a song already in the list keeps it once
        assertEquals(listOf("a", "b"), imp.tracksToSave().map { it.id })
    }

    @Test fun `only one import runs at a time and clear forgets a finished one`() = runBlocking {
        val b = Fake(yt = ImportBackend.YouTubeList("L", listOf(t("a", "A", "x")), 1, false))
        val imp = importer(b)
        imp.start(ImportInput.YouTube("PL"))
        imp.awaitIdle()
        imp.clear()
        assertEquals(null, imp.state.value)
    }

    @Test fun `youtube playlist over the limit says so`() {
        val b = Fake(yt = ImportBackend.YouTubeList("Huge", listOf(t("a", "A", "x")), 4000, true))
        val s = runImport(b, ImportInput.YouTube("PL")) as ImportState.Ready
        assertTrue(s.note!!.contains("4000 songs"))
    }
}
