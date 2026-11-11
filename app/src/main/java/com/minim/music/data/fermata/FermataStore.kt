package com.minim.music.data.fermata

import android.content.Context
import android.content.SharedPreferences
import com.minim.music.data.local.LibraryDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 5.9.0 u59: the phone side of the Fermata groundwork.
 *
 * - [DEVICE_PREFS] holds only this phone's id. It is left out of cloud backup and device
 *   transfer (res/xml/backup_rules.xml and data_extraction_rules.xml), so a restored or new
 *   phone makes its own id instead of copying the old one.
 * - [STATE_PREFS] holds the listening session and the source tags of the saved queue. Also left
 *   out of backups: it only means something on the phone that wrote it.
 */
object FermataStore {
    const val DEVICE_PREFS = "minim_fermata_device"
    const val STATE_PREFS = "minim_fermata"
    private const val KEY_QUEUE_SOURCES = "queue_sources"

    class PrefsKv(private val prefs: SharedPreferences) : FermataKv {
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
        override fun put(values: Map<String, Any?>) {
            val e = prefs.edit()
            values.forEach { (k, v) ->
                when (v) {
                    null -> e.remove(k)
                    is String -> e.putString(k, v)
                    is Long -> e.putLong(k, v)
                    is Int -> e.putLong(k, v.toLong())
                    else -> e.putString(k, v.toString())
                }
            }
            e.apply()
        }
    }

    fun deviceKv(context: Context): FermataKv =
        PrefsKv(context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE))

    fun stateKv(context: Context): FermataKv =
        PrefsKv(context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE))

    /** The library database as the logger's sink. */
    fun sink(db: LibraryDatabase): FermataSink = object : FermataSink {
        override fun insert(e: FermataEvent): Long = db.insertFermataEvent(e)
        override fun updatePlay(rowId: Long, listenedMs: Long, durationMs: Long?, speedPct: Int?) =
            db.updateFermataPlay(rowId, listenedMs, durationMs, speedPct)
    }

    /** One background thread for every Fermata write, in order. */
    fun newWriter(): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "minim-fermata").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    // ---- source tags of the saved queue (so they survive an app restart) ----------------------

    /** Encodes the queue's (id, source) pairs, one per line. */
    fun encodeQueueSources(pairs: List<Pair<String, String?>>): String =
        pairs.joinToString("\n") { (id, src) -> id + "\t" + (src ?: "") }

    /** The source for each saved queue entry, by position, only where the id still matches. */
    fun decodeQueueSources(raw: String?, ids: List<String>): List<String?> {
        val rows = raw?.takeIf { it.isNotEmpty() }?.split("\n").orEmpty()
        return ids.mapIndexed { i, id ->
            val parts = rows.getOrNull(i)?.split("\t")
            if (parts != null && parts.size == 2 && parts[0] == id && parts[1].isNotEmpty()) parts[1] else null
        }
    }

    fun saveQueueSources(kv: FermataKv, encoded: String) = kv.put(mapOf(KEY_QUEUE_SOURCES to encoded))
    fun loadQueueSources(kv: FermataKv): String? = kv.getString(KEY_QUEUE_SOURCES)
}
