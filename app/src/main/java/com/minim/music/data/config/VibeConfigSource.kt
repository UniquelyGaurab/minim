package com.minim.music.data.config

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.minim.music.data.account.CloudPaths
import com.minim.music.data.account.MinimAccount
import com.minim.music.data.sync.CloudDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Minim 5.0.0 part 3: keeps [config] filled from the Firestore document `config/app`.
 *
 * - Reads Firestore's offline copy first (so a festival shows at once on a cold start, even offline),
 *   then the server: on every app open, at most every 30 minutes (5 after a failure).
 * - No Firebase in this build, no network, the rules refusing the read, a missing or broken document:
 *   [config] stays at what it was (built-in defaults the first time). A document that is gone on the
 *   server turns everything back to the defaults. Nothing here throws or crashes.
 * - Reading needs no sign-in (the rules let anyone read config/), and nothing is ever written.
 */
class VibeConfigSource(private val account: MinimAccount) {

    private val _config = MutableStateFlow(VibeConfig.DEFAULT)
    val config: StateFlow<VibeConfig> = _config

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val schedule = VibeRefreshSchedule()
    @Volatile private var cacheRead = false

    fun refresh(force: Boolean = false) {
        val available = try { account.available } catch (_: Throwable) { false }
        if (!available) return
        scope.launch {
            mutex.withLock {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!schedule.shouldRefresh(now, force)) return@withLock
                val doc = try {
                    CloudDb.get().collection(CloudPaths.CONFIG).document(DOC)
                } catch (t: Throwable) {
                    Log.w(TAG, "no Firestore", t)
                    schedule.onFinished(now, false)
                    return@withLock
                }
                if (!cacheRead) {
                    cacheRead = true
                    try {
                        val snap = withTimeout(5_000L) { doc.get(Source.CACHE).await() }
                        if (snap.exists()) publish(snap.data)
                    } catch (_: Throwable) {
                        // nothing cached yet
                    }
                }
                val ok = try {
                    val snap = withTimeout(20_000L) { doc.get(Source.SERVER).await() }
                    if (snap.exists()) publish(snap.data) else _config.value = VibeConfig.DEFAULT
                    true
                } catch (e: FirebaseFirestoreException) {
                    if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        Log.w(TAG, "config/ can't be read: check the Firestore rules (FIREBASE_SETUP.md)")
                    }
                    false
                } catch (t: Throwable) {
                    false
                }
                schedule.onFinished(android.os.SystemClock.elapsedRealtime(), ok)
            }
        }
    }

    private fun publish(data: Map<String, Any?>?) {
        val plain = VibeConfigValues.normalize(data, { v -> (v as? Timestamp)?.toDate() ?: (v as? java.util.Date) }) as? Map<*, *>
        val parsed = VibeConfigParser.parse(plain)
        if (parsed.skipped > 0) Log.w(TAG, "config/$DOC: ${parsed.skipped} entries skipped")
        if (parsed != _config.value) _config.value = parsed
    }

    companion object {
        private const val TAG = "VibeConfig"
        /** The one document: config/app. */
        const val DOC = "app"
    }
}
