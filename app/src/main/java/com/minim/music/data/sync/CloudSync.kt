package com.minim.music.data.sync

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.provider.Settings
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.minim.music.data.account.CloudPaths
import com.minim.music.data.account.MinimAccount
import com.minim.music.data.local.LibraryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** The one Firestore instance, with the offline cache set up before anything uses it. */
object CloudDb {
    @Volatile private var configured = false

    fun get(): FirebaseFirestore {
        val db = FirebaseFirestore.getInstance()
        if (!configured) {
            synchronized(this) {
                if (!configured) {
                    try {
                        // offline persistence: changes made offline are kept and go up later
                        db.firestoreSettings = FirebaseFirestoreSettings.Builder()
                            .setLocalCacheSettings(PersistentCacheSettings.newBuilder().setSizeBytes(40L * 1024 * 1024).build())
                            .build()
                    } catch (_: IllegalStateException) {
                        // already in use: the default (also persistent) stays
                    }
                    configured = true
                }
            }
        }
        return db
    }
}

/**
 * Minim 5.0.0 part 2: cloud sync of likes, playlists, "not interested" and listening history
 * (plays, listening time, Recents). Settings are never synced.
 *
 * - Signed out: nothing runs and nothing is tracked; the app works on the phone as before.
 * - Signing in: a full merge. Everything on the phone is queued, the whole cloud is read and merged
 *   in, and only what the cloud is missing or has older goes up. Safe to repeat.
 * - Signed in to a different account than the phone last synced with: sync waits until the user
 *   picks Merge or Start fresh ([pendingChoice]).
 * - After that: changes go up in batches (likes/playlists a few seconds after the change, listening
 *   every few minutes and when the app goes to the background), and the phone pulls only what
 *   changed since its last pull (by the documents' "u" server time) when the app opens or comes back
 *   to the foreground. No live listeners, so a phone sitting open costs nothing.
 */
class CloudSync(
    private val context: Context,
    private val lib: LibraryDatabase,
    private val account: MinimAccount
) {
    enum class Phase { OFF, NEEDS_CHOICE, FIRST_SYNC, SYNCING, IDLE }

    data class Status(
        val phase: Phase = Phase.OFF,
        val lastSyncAt: Long = 0L,
        val problem: SyncProblem? = null
    )

    /** Signed in to [name]'s account, but the phone's music came from another account. */
    data class Choice(val uid: String, val name: String)

    private val store = LibrarySync(lib)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _pendingChoice = MutableStateFlow<Choice?>(null)
    val pendingChoice: StateFlow<Choice?> = _pendingChoice.asStateFlow()

    /** Goes up by one whenever a pull changed the library, so screens can reload. */
    private val _dataVersion = MutableStateFlow(0L)
    val dataVersion: StateFlow<Long> = _dataVersion.asStateFlow()

    /**
     * True when the latest [dataVersion] change came from a whole-library merge (the first sync
     * after signing in, or Start fresh) rather than a small pull. Discover rebuilds for those.
     * Written before [dataVersion] goes up.
     */
    @Volatile var lastChangeWasFull = false
        private set

    @Volatile private var uid: String? = null
    @Volatile private var foreground = false
    @Volatile private var lastPullAt = 0L
    @Volatile private var pushJob: Job? = null
    @Volatile private var pushDueAt = Long.MAX_VALUE
    @Volatile private var retryJob: Job? = null
    @Volatile private var retryStep = 0
    @Volatile private var paused = false
    /** 5.0.0 part 3: the cloud config's "sync" switch is off (nothing goes up or comes down). */
    @Volatile private var remoteOff = false

    fun start() {
        if (!account.available) return
        lib.onSyncChange = { urgent -> requestPush(urgent) }
        scope.launch {
            try { store.ensureDevice(deviceKey()) } catch (t: Throwable) { Log.w(TAG, "device", t) }
            account.user.distinctUntilChangedBy { it?.uid }.collect { user -> onUser(user?.uid, user?.name) }
        }
        registerNetworkCallback()
    }

    // ---- account changes -----------------------------------------------------------------------

    @androidx.annotation.VisibleForTesting
    internal fun onUser(newUid: String?, name: String?) {
        cancelTimers()
        _pendingChoice.value = null
        if (newUid == null) {
            uid = null
            lib.syncTracking = false
            _status.value = Status(Phase.OFF)
            return
        }
        val last = store.state("uid")
        val lastSync = store.state("last")?.toLongOrNull() ?: 0L
        if (last != null && last != newUid) {
            // a different account than this phone's music came from: ask first
            uid = null
            lib.syncTracking = false
            _pendingChoice.value = Choice(newUid, name?.takeIf { it.isNotBlank() } ?: "this")
            _status.value = Status(Phase.NEEDS_CHOICE, 0L)
            return
        }
        begin(newUid, lastSync)
    }

    private fun begin(newUid: String, lastSync: Long) {
        store.setState("uid", newUid)
        uid = newUid
        lib.syncTracking = true
        val full = store.state("full") != newUid
        _status.value = Status(if (full) Phase.FIRST_SYNC else Phase.IDLE, if (full) 0L else lastSync)
        scope.launch { cycle(pull = true) }
    }

    /**
     * The user's answer for a different account. [merge] true: this phone's music is added to that
     * account. False: start fresh, the phone's library is replaced by that account's (a copy of the
     * old database is kept on the phone).
     */
    fun choose(merge: Boolean) {
        val c = _pendingChoice.value ?: return
        _pendingChoice.value = null
        scope.launch {
            mutex.withLock {
                store.clearOutbox()
                for (col in COLLECTIONS) store.setState("mk.$col", null)
                store.setState("full", null)
                store.setState("last", null)
                if (!merge) {
                    store.wipeLibrary(File(context.filesDir, "library_before_account_switch.db"))
                    lib.refreshLikedCache()
                    lib.refreshNotInterestedCache()
                    lastChangeWasFull = true
                    _dataVersion.value = _dataVersion.value + 1
                }
            }
            if (account.user.value?.uid == c.uid) begin(c.uid, 0L)
        }
    }

    /**
     * The user backed out of the different-account question (Cancel). Nothing on the phone changes:
     * the library, the outbox and the sync state stay as they are, still tied to the account the
     * phone's music came from, and nothing is synced. The caller then signs out. Returns false when
     * there was no question waiting.
     */
    fun cancelChoice(): Boolean {
        if (_pendingChoice.value == null) return false
        _pendingChoice.value = null
        cancelTimers()
        uid = null
        lib.syncTracking = false
        _status.value = Status(Phase.OFF)
        return true
    }

    /** Before signing out: send what's waiting (a few seconds at most), then stop. */
    suspend fun prepareSignOut() {
        if (uid == null) return
        cancelTimers()
        withContext(Dispatchers.IO) {
            try {
                withTimeout(8_000L) { cycle(pull = false) }
            } catch (_: Throwable) {
            }
        }
        withContext(Dispatchers.IO) {
            lib.syncTracking = false
            uid = null
            // signing in again does a full merge, which catches anything that didn't make it
            store.setState("full", null)
            store.clearOutbox()
        }
    }

    /** Before Delete account: stop syncing so nothing is written back while the cloud is emptied. */
    suspend fun prepareDelete() {
        paused = true
        cancelTimers()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                lib.syncTracking = false
                try {
                    withTimeout(10_000L) { CloudDb.get().waitForPendingWrites().await() }
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** After Delete account: [deleted] true forgets the account, so the phone's music stays local. */
    fun afterDelete(deleted: Boolean) {
        paused = false
        scope.launch {
            if (deleted) {
                uid = null
                store.clearOutbox()
                for (k in listOf("uid", "full", "last") + COLLECTIONS.map { "mk.$it" }) store.setState(k, null)
                _status.value = Status(Phase.OFF)
            } else {
                val u = account.user.value
                if (u != null && uid == u.uid) {
                    lib.syncTracking = true
                    cycle(pull = true)
                }
            }
        }
    }

    // ---- triggers ------------------------------------------------------------------------------

    fun onForeground() {
        foreground = true
        if (uid == null) return
        if (System.currentTimeMillis() - lastPullAt >= PULL_THROTTLE_MS) {
            scope.launch { cycle(pull = true) }
        }
    }

    fun onBackground() {
        foreground = false
        if (uid == null) return
        // listening is saved as the app leaves; give that a moment to land, then send it all
        schedulePush(3_000L)
    }

    /**
     * 5.0.0 part 3: the "sync" switch in the cloud config (config/app). Off stops every push and
     * pull; changes stay queued on the phone and go up when it is turned back on.
     */
    fun setRemoteEnabled(on: Boolean) {
        val wasOff = remoteOff
        remoteOff = !on
        if (!on) cancelTimers()
        else if (wasOff && uid != null) scope.launch { cycle(pull = true) }
    }

    /** Sync now (pull and push), e.g. from Settings. */
    fun syncNow() {
        if (uid == null) return
        scope.launch { cycle(pull = true) }
    }

    private fun requestPush(urgent: Boolean) {
        if (uid == null || paused || remoteOff) return
        val delayMs = when {
            urgent -> URGENT_DELAY_MS
            foreground -> HISTORY_DELAY_MS
            else -> HISTORY_DELAY_BG_MS
        }
        schedulePush(delayMs)
    }

    /** Debounce: one push at the earliest due time asked for; changes in between ride along. */
    @Synchronized
    private fun schedulePush(delayMs: Long) {
        val due = System.currentTimeMillis() + delayMs
        if (pushJob?.isActive == true && pushDueAt <= due) return
        pushJob?.cancel() // only ever cancels the wait, never a running sync
        pushDueAt = due
        pushJob = scope.launch {
            delay(delayMs)
            synchronized(this@CloudSync) { pushDueAt = Long.MAX_VALUE; pushJob = null }
            scope.launch {
                // likes and playlists: look at the cloud first, so a newer change from another phone wins
                val pullFirst = System.currentTimeMillis() - lastPullAt >= 30_000L && hasSharedPending()
                cycle(pull = pullFirst)
            }
        }
    }

    private fun hasSharedPending(): Boolean = try {
        store.readOutbox(500).any { it.key[0] in "LHPT" }
    } catch (_: Throwable) { false }

    private fun cancelTimers() {
        pushJob?.cancel(); pushJob = null; pushDueAt = Long.MAX_VALUE
        retryJob?.cancel(); retryJob = null
    }

    // ---- the sync cycle ------------------------------------------------------------------------

    /**
     * One sync: pull (when asked, or always while the full merge after signing in hasn't finished),
     * then push. Only one runs at a time.
     */
    private suspend fun cycle(pull: Boolean) {
        val me = uid ?: return
        if (paused || remoteOff) return
        mutex.withLock {
            if (uid != me || paused || remoteOff) return
            val full = store.state("full") != me
            val db = try { CloudDb.get() } catch (t: Throwable) {
                fail(SyncProblem.NOT_READY); return
            }
            val userDoc = db.collection(CloudPaths.USERS).document(me)
            try {
                if (full) {
                    _status.value = _status.value.copy(phase = Phase.FIRST_SYNC)
                    store.enqueueAll()
                    for (col in COLLECTIONS) store.setState("mk.$col", null)
                } else if (_status.value.phase != Phase.FIRST_SYNC) {
                    _status.value = _status.value.copy(phase = Phase.SYNCING)
                }
                var pulled = false
                if (pull || full) {
                    var changed = false
                    for (col in COLLECTIONS) changed = pullCollection(userDoc.collection(col), col) || changed
                    lastPullAt = System.currentTimeMillis()
                    pulled = true
                    if (changed) {
                        lib.refreshLikedCache()
                        lib.refreshNotInterestedCache()
                        lastChangeWasFull = full
                        _dataVersion.value = _dataVersion.value + 1
                    }
                    if (full) store.setState("full", me)
                }
                val acked = push(db, userDoc)
                if (pulled || acked) {
                    val now = System.currentTimeMillis()
                    store.setState("last", now.toString())
                    _status.value = Status(Phase.IDLE, now, null)
                } else {
                    _status.value = _status.value.copy(phase = Phase.IDLE)
                }
                retryStep = 0
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException && t !is TimeoutCancellationException) throw t
                Log.w(TAG, "sync failed", t)
                fail(problemOf(t))
            }
        }
    }

    private fun fail(p: SyncProblem) {
        _status.value = Status(Phase.IDLE, _status.value.lastSyncAt, p)
        // offline waits for the network to come back; quota waits for tomorrow; the rest backs off
        if (p == SyncProblem.FAILED || p == SyncProblem.NOT_READY) {
            val wait = RETRY_STEPS_MS[retryStep.coerceAtMost(RETRY_STEPS_MS.size - 1)]
            retryStep++
            retryJob?.cancel()
            retryJob = scope.launch { delay(wait); cycle(pull = true) }
        }
    }

    private fun problemOf(t: Throwable): SyncProblem = when (t) {
        is TimeoutCancellationException -> SyncProblem.OFFLINE
        is FirebaseFirestoreException -> SyncText.classify(t.code.name)
        is java.io.IOException -> SyncProblem.OFFLINE
        else -> SyncProblem.FAILED
    }

    /** Reads the documents changed since the last pull, page by page, and merges them in. */
    private suspend fun pullCollection(col: com.google.firebase.firestore.CollectionReference, name: String): Boolean {
        val markerMs = store.state("mk.$name")?.toLongOrNull() ?: 0L
        // a few seconds of overlap: merging is idempotent, so a re-read doc changes nothing
        val sinceMs = (markerMs - MARKER_OVERLAP_MS).coerceAtLeast(0L)
        val since = Timestamp(sinceMs / 1000L, ((sinceMs % 1000L) * 1_000_000L).toInt())
        var changed = false
        var last: DocumentSnapshot? = null
        var maxMs = markerMs
        while (true) {
            var q: Query = col.whereGreaterThan("u", since).orderBy("u").limit(PAGE.toLong())
            if (last != null) q = q.startAfter(last)
            val snap = withTimeout(NET_TIMEOUT_MS) { q.get(Source.SERVER).await() }
            val docs = snap.documents.map { LibrarySync.RemoteDoc(it.id, it.data ?: emptyMap()) }
            if (docs.isNotEmpty()) {
                changed = when (name) {
                    "likes" -> store.applyLikes(docs)
                    "hidden" -> store.applyHidden(docs)
                    "playlists" -> store.applyPlaylists(docs)
                    else -> store.applyHistory(docs)
                } || changed
            }
            for (d in snap.documents) {
                val ts = d.getTimestamp("u") ?: continue
                val ms = ts.seconds * 1000L + ts.nanoseconds / 1_000_000L
                if (ms > maxMs) maxMs = ms
            }
            if (maxMs > markerMs) store.setState("mk.$name", maxMs.toString())
            if (snap.size() < PAGE) break
            last = snap.documents.last()
        }
        return changed
    }

    /**
     * Sends the outbox in batched writes (at most one write per document per batch). Writes go into
     * Firestore's offline queue right away, so the outbox entries are done once committed; the
     * server's answer is awaited only to show "Synced". Returns true when the server confirmed.
     */
    private suspend fun push(db: FirebaseFirestore, userDoc: com.google.firebase.firestore.DocumentReference): Boolean {
        val tasks = ArrayList<Task<Void>>()
        var rounds = 0
        while (rounds < MAX_ROUNDS) {
            rounds++
            val items = store.readOutbox(OUTBOX_CHUNK)
            if (items.isEmpty()) break
            val writes = store.buildWrites(items)
            for (chunk in writes.chunked(BATCH_MAX)) {
                val batch = db.batch()
                for (w in chunk) {
                    val data = HashMap<String, Any?>(w.data)
                    data["u"] = FieldValue.serverTimestamp()
                    val ref = userDoc.collection(w.path.substringBefore('/')).document(w.path.substringAfter('/'))
                    if (w.merge) batch.set(ref, data, SetOptions.merge()) else batch.set(ref, data)
                }
                try {
                    tasks.add(batch.commit())
                } catch (t: Throwable) {
                    // a write Firestore refuses outright would block the outbox forever: drop it
                    Log.w(TAG, "batch refused", t)
                }
            }
            store.ack(items)
            if (items.size < OUTBOX_CHUNK) break
        }
        if (tasks.isEmpty()) return false
        try {
            withTimeout(NET_TIMEOUT_MS) { for (t in tasks) t.await() }
        } catch (t: TimeoutCancellationException) {
            // still queued in Firestore's offline cache; it goes up when the network is back
            throw t
        } catch (t: FirebaseFirestoreException) {
            // refused by the server: the next sign-in (or retry) does a full merge to put it right
            store.setState("full", null)
            throw t
        }
        return true
    }

    // ---- network -------------------------------------------------------------------------------

    private fun registerNetworkCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (uid == null || paused) return
                    if (_status.value.problem == SyncProblem.OFFLINE) {
                        scope.launch { delay(2_000L); cycle(pull = true) }
                    }
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "network callback", t)
        }
    }

    @SuppressLint("HardwareIds")
    private fun deviceKey(): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Throwable) { null }
        val fallback = store.state("device_seed") ?: java.util.UUID.randomUUID().toString().also { store.setState("device_seed", it) }
        return SyncIds.deviceKey(androidId, fallback)
    }

    companion object {
        private const val TAG = "MinimSync"
        val COLLECTIONS = listOf("likes", "hidden", "playlists", "history")
        private const val PAGE = 300
        private const val OUTBOX_CHUNK = 2000
        private const val BATCH_MAX = 400
        private const val MAX_ROUNDS = 50
        private const val NET_TIMEOUT_MS = 25_000L
        private const val MARKER_OVERLAP_MS = 5_000L
        private const val PULL_THROTTLE_MS = 60_000L
        const val URGENT_DELAY_MS = 5_000L
        const val HISTORY_DELAY_MS = 3 * 60_000L
        const val HISTORY_DELAY_BG_MS = 5 * 60_000L
        private val RETRY_STEPS_MS = longArrayOf(60_000L, 5 * 60_000L, 15 * 60_000L)
    }
}
