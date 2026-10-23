package com.minim.music.data.greetings

import java.io.File

/** What one download attempt gave back. */
sealed class FetchResult {
    data class Ok(val body: String, val etag: String?) : FetchResult()
    object NotModified : FetchResult()
    data class Failed(val reason: String) : FetchResult()
}

/** Downloads greetings.json; [etag] is the one from the last good download (null = none). */
fun interface GreetingsFetcher {
    fun fetch(etag: String?): FetchResult
}

/**
 * v4.9.6: the offline copy of the remote greetings file and the "about once a day" rule.
 *
 * - The last good file is kept in [dir] so greetings work offline and right after the app opens.
 * - A download is only tried when the last good check is [CHECK_EVERY_MS] old, and after a failed
 *   try not again for [RETRY_AFTER_MS], so a phone without internet doesn't retry on every open.
 * - A download that fails or isn't a usable file never replaces the copy that's already there.
 * - A broken copy on disk is deleted and ignored (the built-in greetings carry on as normal).
 *
 * Blocking file and network work: call it off the main thread.
 */
class RemoteGreetingsStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis
) {
    enum class Outcome { UPDATED, NOT_MODIFIED, NOT_DUE, FAILED, BAD_FILE }

    data class Result(val outcome: Outcome, val file: RemoteGreetingFile? = null)

    data class Meta(val lastAttempt: Long = 0L, val lastSuccess: Long = 0L, val etag: String? = null)

    private val cacheFile get() = File(dir, CACHE_NAME)
    private val metaFile get() = File(dir, META_NAME)

    fun readMeta(): Meta = try {
        val f = metaFile
        if (!f.isFile || f.length() > 4096) Meta() else {
            val map = f.readLines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()
            Meta(
                lastAttempt = map["lastAttempt"]?.toLongOrNull() ?: 0L,
                lastSuccess = map["lastSuccess"]?.toLongOrNull() ?: 0L,
                etag = map["etag"]?.takeIf { it.isNotEmpty() && it.length < 300 }
            )
        }
    } catch (e: Exception) {
        Meta()
    }

    private fun writeMeta(m: Meta) {
        try {
            dir.mkdirs()
            val etag = m.etag?.replace('\n', ' ')?.replace('\r', ' ').orEmpty()
            writeAtomically(metaFile, "lastAttempt=${m.lastAttempt}\nlastSuccess=${m.lastSuccess}\netag=$etag\n")
        } catch (e: Exception) {
            // Not fatal: at worst it checks again sooner
        }
    }

    /** The offline copy, or null when there is none or it is unusable (then it is deleted). */
    fun loadCached(): RemoteGreetingFile? {
        return try {
            val f = cacheFile
            if (!f.isFile) return null
            if (f.length() > MAX_BYTES) {
                dropCache()
                return null
            }
            val parsed = RemoteGreetingsParser.parse(f.readText(Charsets.UTF_8))
            if (parsed == null) dropCache()
            parsed
        } catch (e: Exception) {
            null
        }
    }

    /** Whether it's time to look for a new file. */
    fun isDue(now: Long = clock()): Boolean {
        val m = readMeta()
        // The clock went backwards (or the meta file is from the future): check again
        if (m.lastAttempt > now || m.lastSuccess > now) return true
        if (!cacheFile.isFile && m.lastSuccess > 0L && now - m.lastAttempt >= RETRY_AFTER_MS) return true
        if (now - m.lastSuccess < CHECK_EVERY_MS) return false
        return now - m.lastAttempt >= RETRY_AFTER_MS || m.lastAttempt <= m.lastSuccess
    }

    /** Downloads a new file when one is due (or [force]); the result says what happened. */
    fun refresh(fetcher: GreetingsFetcher, force: Boolean = false): Result {
        val now = clock()
        if (!force && !isDue(now)) return Result(Outcome.NOT_DUE)
        val meta = readMeta()
        val hasCache = cacheFile.isFile
        val result = try {
            fetcher.fetch(if (hasCache) meta.etag else null)
        } catch (e: Exception) {
            FetchResult.Failed(e.javaClass.simpleName)
        }
        return when (result) {
            is FetchResult.NotModified -> {
                if (hasCache) {
                    writeMeta(meta.copy(lastAttempt = now, lastSuccess = now))
                    Result(Outcome.NOT_MODIFIED)
                } else {
                    writeMeta(meta.copy(lastAttempt = now, etag = null))
                    Result(Outcome.FAILED)
                }
            }
            is FetchResult.Failed -> {
                writeMeta(meta.copy(lastAttempt = now))
                Result(Outcome.FAILED)
            }
            is FetchResult.Ok -> {
                val parsed = if (result.body.length > MAX_BYTES) null else RemoteGreetingsParser.parse(result.body)
                if (parsed == null) {
                    // Keep whatever good copy we had; try again later
                    writeMeta(meta.copy(lastAttempt = now))
                    Result(Outcome.BAD_FILE)
                } else {
                    val saved = try {
                        dir.mkdirs()
                        writeAtomically(cacheFile, result.body)
                        true
                    } catch (e: Exception) {
                        false
                    }
                    writeMeta(Meta(lastAttempt = now, lastSuccess = now, etag = if (saved) result.etag else null))
                    Result(Outcome.UPDATED, parsed)
                }
            }
        }
    }

    private fun dropCache() {
        try {
            cacheFile.delete()
            val m = readMeta()
            writeMeta(m.copy(etag = null))
        } catch (e: Exception) {
        }
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("rename failed")
            }
        }
    }

    companion object {
        const val CACHE_NAME = "remote_greetings.json"
        const val META_NAME = "remote_greetings.meta"
        const val CHECK_EVERY_MS = 20L * 60 * 60 * 1000   // about once a day
        const val RETRY_AFTER_MS = 3L * 60 * 60 * 1000    // after a failed try
        const val MAX_BYTES = 64 * 1024
    }
}
