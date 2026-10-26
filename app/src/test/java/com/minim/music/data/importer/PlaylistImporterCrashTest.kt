package com.minim.music.data.importer

import com.minim.music.data.model.Track
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * 5.3.1: on Android 9 an ExceptionInInitializerError (an Error, not an Exception) escaped the
 * background import and crashed the whole app. Whatever goes wrong now, the import ends as Failed
 * on the Library card and nothing reaches the app's crash handler.
 */
class PlaylistImporterCrashTest {

    private class Throwing(
        val onSpotify: (() -> Nothing)? = null,
        val onSearch: (suspend () -> Nothing)? = null
    ) : ImportBackend {
        override suspend fun readYouTube(url: String, max: Int, onProgress: (Int) -> Unit): ImportBackend.YouTubeList =
            throw NoClassDefFoundError("com/minim/music/data/importer/SongMatcher")
        override suspend fun readSpotify(kind: String, id: String, max: Int, onProgress: (Int) -> Unit): SpotifyReader.Result {
            onSpotify?.invoke()
            return SpotifyReader.Result("Chill Mix", listOf(SourceSong("Kesariya", listOf("Arijit Singh"), 268_000)), 1, false)
        }
        override suspend fun resolveSpotifyShort(url: String): String? = throw IllegalStateException("boom")
        override suspend fun search(query: String, videos: Boolean): List<Track>? {
            onSearch?.invoke()
            return emptyList()
        }
    }

    private fun runImport(b: ImportBackend, input: ImportInput): Pair<ImportState?, List<Throwable>> = runBlocking {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, t -> uncaught.add(t) })
        val imp = PlaylistImporter(b, scope)
        assertTrue(imp.start(input))
        imp.awaitIdle()
        imp.state.value to uncaught.toList()
    }

    private fun assertFailedQuietly(result: Pair<ImportState?, List<Throwable>>, name: String? = null) {
        val (state, uncaught) = result
        assertEquals("nothing may reach the crash handler", emptyList<Throwable>(), uncaught)
        assertTrue("expected Failed, got $state", state is ImportState.Failed)
        assertEquals(ImportMessages.UNEXPECTED, (state as ImportState.Failed).message)
        if (name != null) assertEquals(name, state.name)
    }

    @Test fun `a class failing to load while matching a spotify playlist is a failed import, not a crash`() {
        val b = Throwing(onSearch = {
            throw ExceptionInInitializerError(java.util.regex.PatternSyntaxException("Syntax error", "\\{[^}]*}", 27))
        })
        assertFailedQuietly(runImport(b, ImportInput.Spotify("playlist", "x")), "Chill Mix")
    }

    @Test fun `the same error on plain text lines is a failed import too`() {
        val lines = ImportDetect.detect("Kesariya – Arijit Singh\nBeliever – Imagine Dragons") as ImportInput.Lines
        val b = Throwing(onSearch = { throw NoClassDefFoundError("com/minim/music/data/importer/SongMatcher") })
        assertFailedQuietly(runImport(b, lines), "your list")
    }

    @Test fun `errors while reading are failed imports`() {
        assertFailedQuietly(runImport(Throwing(onSpotify = { throw OutOfMemoryError("big page") }), ImportInput.Spotify("playlist", "x")))
        assertFailedQuietly(runImport(Throwing(), ImportInput.YouTube("PLabc")))
        assertFailedQuietly(runImport(Throwing(), ImportInput.SpotifyShort("https://spotify.link/abc")))
    }

    @Test fun `a timeout inside the work is a failed import, not a card stuck on importing`() {
        val b = Throwing(onSearch = {
            withTimeout(1) { delay(1_000) }
            throw AssertionError("unreachable")
        })
        val (state, uncaught) = runImport(b, ImportInput.Spotify("playlist", "x"))
        assertEquals(emptyList<Throwable>(), uncaught)
        assertTrue("expected Failed, got $state", state is ImportState.Failed)
    }

    @Test fun `cancelling with the x still just clears the card`() = runBlocking {
        val b = Throwing(onSearch = { delay(10_000); throw IllegalStateException("too late") })
        val imp = PlaylistImporter(b, CoroutineScope(SupervisorJob()))
        imp.start(ImportInput.Spotify("playlist", "x"))
        delay(100)
        imp.cancel()
        imp.awaitIdle()
        assertEquals(null, imp.state.value)
    }

    @Test fun `song keys still drop brackets of every kind`() {
        val a = SourceSong("Kesariya (From \"Brahmastra\") [Live] {Remix} （Lofi） 【HD】", listOf("Arijit Singh"))
        val b = SourceSong("Kesariya", listOf("Arijit Singh"))
        assertEquals(b.key, a.key)
        assertEquals("kesariya", SongMatcher.core("Kesariya [Official Video] {x}"))
    }
}
