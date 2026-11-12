package com.minim.music.data.local

import android.content.Context
import com.minim.music.data.ai.GeminiSettings
import java.io.File

/**
 * 5.10.1 (V6): Minim has no Google sign-in and no cloud sync any more. A phone that was signed in
 * on 5.x simply stops syncing and keeps its whole library: this only forgets the sign-in, once,
 * quietly and locally.
 *
 * - Minim's own sync markers (which account, when it last synced, how far each list was pulled)
 *   and the queue of changes that were waiting to go up. The library tables are not touched, and
 *   nothing is dropped: sync_outbox stays (empty), and this phone's device seed stays, because
 *   Fermata names this phone with it.
 * - Files the Firebase libraries left behind (the stored sign-in, App Check, the Firestore cache
 *   of cloud documents, heartbeat and installation ids). They hold copies of cloud data, never
 *   the library itself.
 *
 * It never contacts Firebase and never deletes anything in the cloud: the owner clears the old
 * cloud copy in the Firebase console.
 */
object LegacyCloudCleanup {
    const val PREFS = "minim_v6"
    const val KEY_DONE = "legacy_cloud_cleaned"

    /** Minim's sync markers in sync_state ("mk.<list>" pull markers are matched by prefix). */
    val SYNC_STATE_KEYS = listOf("uid", "full", "last")
    const val MARKER_PREFIX = "mk."

    /** Stored by 5.3.2's App Check debug token, in the Gemini prefs. */
    const val APP_CHECK_TOKEN_KEY = "appcheck_debug_token"

    fun runOnce(context: Context, db: LibraryDatabase) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        run(context, db)
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }

    fun run(context: Context, db: LibraryDatabase) {
        try {
            val w = db.writableDatabase
            w.beginTransaction()
            try {
                for (k in SYNC_STATE_KEYS) w.delete("sync_state", "k = ?", arrayOf(k))
                w.delete("sync_state", "k LIKE ?", arrayOf("$MARKER_PREFIX%"))
                w.delete("sync_outbox", null, null)
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        } catch (t: Throwable) {
            android.util.Log.w("LegacyCloud", "sync markers", t)
        }
        try {
            context.getSharedPreferences(GeminiSettings.PREFS, Context.MODE_PRIVATE).edit().remove(APP_CHECK_TOKEN_KEY).apply()
        } catch (_: Throwable) { }
        forgetFirebaseFiles(context)
    }

    /** Shared prefs, databases and files the Firebase SDKs wrote (matched by their own names). */
    fun isFirebasePrefs(name: String): Boolean =
        name.startsWith("com.google.firebase") || name.startsWith("FirebaseHeartBeat") ||
            name.startsWith("FirebaseAppHeartBeat") || name.startsWith("com.google.android.gms.appid")

    fun isFirebaseDatabase(name: String): Boolean =
        name.startsWith("firestore.") || name.startsWith("com.google.firebase")

    fun isFirebaseFile(name: String): Boolean =
        name.startsWith("PersistedInstallation") || name == "generatefid.lock" || name.startsWith("com.google.firebase")

    private fun forgetFirebaseFiles(context: Context) {
        try {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            prefsDir.listFiles()?.forEach { f ->
                val name = f.name.removeSuffix(".xml")
                if (f.name.endsWith(".xml") && isFirebasePrefs(name)) {
                    context.deleteSharedPreferences(name)
                    if (f.exists()) f.delete()
                }
            }
        } catch (_: Throwable) { }
        try {
            for (name in context.databaseList()) {
                if (isFirebaseDatabase(name) && !name.endsWith("-journal") && !name.endsWith("-wal") && !name.endsWith("-shm")) {
                    context.deleteDatabase(name)
                }
            }
        } catch (_: Throwable) { }
        try {
            for (dir in listOf(context.filesDir, context.noBackupFilesDir)) {
                dir.listFiles()?.forEach { f -> if (isFirebaseFile(f.name)) f.deleteRecursively() }
            }
        } catch (_: Throwable) { }
    }
}
