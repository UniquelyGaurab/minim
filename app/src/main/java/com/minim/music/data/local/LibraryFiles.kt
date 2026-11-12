package com.minim.music.data.local

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.storage.StorageManager
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException

/**
 * 5.10.0: which database file Minim opens.
 *
 * 5.9.1 kept everything in [LibraryDatabase.DB_NAME]. 5.10.0 copies that file once into
 * [FILE_NAME] and only ever writes the copy, so the 5.9.1 file stays exactly as 5.9.1 left it and
 * rolling back to 5.9.1 keeps working (with what the library looked like on the day of the
 * upgrade). The copy goes to a temporary name first and is renamed into place only when it is
 * complete, so a kill partway just starts the copy again next time.
 *
 * Rolled back and upgraded again: if the 5.9.1 file changed after the copy (5.9.1 was used in
 * between), the old copy is kept as [PREVIOUS_NAME] and a fresh copy is made. Fermata then merges
 * the old copy's log into the new one (see FermataBridge.recoverPrevious), so nothing done in
 * either version is lost.
 *
 * What can go wrong on a phone, and what happens:
 * - Not enough free space: checked before copying (twice the 5.9.1 file plus 8 MB, as opening the
 *   copy folds its WAL into it). A copy that fails anyway (the disk filled up meanwhile) leaves
 *   no partial files behind. With no copy, Minim opens the 5.9.1 file itself, as 5.9.1 did; the
 *   next start with space copies it, Fermata's log included, so nothing is lost (a rollback
 *   meanwhile would just show the newer state). After a rollback and back, a failed copy puts
 *   the old copy back and keeps using it, and tries again on the next start.
 * - A kill before the 5.9.1 file's size and time are saved: they are saved before the copy is
 *   renamed into place, and a copy found without them counts as changed, so it is redone with the
 *   old copy kept as [PREVIOUS_NAME] (nothing lost; the old way, a later rollback went unseen).
 * - A kill or a failure while the old copy is renamed to [PREVIOUS_NAME] (one file at a time):
 *   a marker is saved first, and the next start puts back the files already moved before
 *   anything else, so Minim never opens an empty library with the old one's WAL beside it.
 */
object LibraryFiles {
    const val FILE_NAME = "minim_library_510.db"
    const val PREVIOUS_NAME = "minim_library_510_previous.db"
    /** SharedPreferences with the 5.9.1 file's size and time at the copy. Not in Android's backups. */
    const val PREFS = "minim_db_copy"
    private const val MOVING = "moving_to_previous"
    private val SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    private const val SPARE_BYTES = 8L * 1024 * 1024

    // Test hooks: free space, the rename, and a call after each file copied (throw for a kill or
    // a full disk).
    @Volatile internal var freeSpace: (File) -> Long = ::allocatableBytes
    @Volatile internal var rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }
    @Volatile internal var afterCopyingFile: ((File) -> Unit)? = null
    @Volatile internal var beforePlacingCopy: (() -> Unit)? = null

    internal fun resetHooksForTest() {
        freeSpace = ::allocatableBytes
        rename = { from, to -> from.renameTo(to) }
        afterCopyingFile = null
        beforePlacingCopy = null
    }

    @Volatile private var storage: StorageManager? = null

    /**
     * Space the copy can use: StorageManager counts cached files the system would clear for it,
     * so a phone that is only full of caches still gets the copy. The plain free space if that fails.
     */
    @SuppressLint("UsableSpace") // the fallback only, when StorageManager can't answer for this folder
    private fun allocatableBytes(dir: File): Long {
        val sm = storage ?: return dir.usableSpace
        return try { sm.getAllocatableBytes(sm.getUuidForPath(dir)) } catch (_: Exception) { dir.usableSpace }
    }

    // commit(), not apply(): each of these must be on disk before the file step after it (a kill
    // between the two is what the marker and the saved size and time are for)
    @SuppressLint("ApplySharedPref")
    fun prepare(context: Context): String {
        storage = context.getSystemService(StorageManager::class.java)
        val target = context.getDatabasePath(FILE_NAME)
        val legacy = context.getDatabasePath(LibraryDatabase.DB_NAME)
        val prev = context.getDatabasePath(PREVIOUS_NAME)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        undoHalfMove(prefs, target, prev)
        var moved = false
        if (target.exists()) {
            if (!legacy.exists() || !changedSinceCopy(prefs, legacy)) return FILE_NAME
            // 5.9.1 was used after the copy: keep the old copy for its log, copy again
            if (!enoughSpace(legacy)) return FILE_NAME // keep the old copy; try next start
            if (!moveToPrevious(prefs, target, prev)) return FILE_NAME
            moved = true
        }
        if (!legacy.exists()) return FILE_NAME
        try {
            if (!enoughSpace(legacy)) throw IOException("not enough free space to copy the library")
            copy(legacy, target, prefs)
        } catch (e: Exception) {
            android.util.Log.w("LibraryFiles", "copy failed", e)
            if (moved) {
                // put the old copy back and keep using it; the change is still seen next start
                for (sfx in SUFFIXES) File(target.path + sfx).delete()
                for (sfx in SUFFIXES) {
                    val f = File(prev.path + sfx)
                    if (f.exists()) rename(f, File(target.path + sfx))
                }
                if (File(target.path).exists()) return FILE_NAME
            }
            // never block the app: open the 5.9.1 file itself, as 5.9.1 did
            return LibraryDatabase.DB_NAME
        }
        return FILE_NAME
    }

    private fun enoughSpace(legacy: File): Boolean {
        val dir = legacy.parentFile ?: return true
        return freeSpace(dir) >= 2 * legacyLength(legacy) + SPARE_BYTES
    }

    private fun changedSinceCopy(prefs: SharedPreferences, legacy: File): Boolean {
        // no saved size and time (a kill right after an older build's copy): treat it as changed
        if (!prefs.contains("time") || !prefs.contains("size")) return true
        return prefs.getLong("time", 0L) != legacyTime(legacy) || prefs.getLong("size", 0L) != legacyLength(legacy)
    }

    /** Renames the copy's files to [PREVIOUS_NAME], all or none (see the class comment). */
    @SuppressLint("ApplySharedPref") // see prepare()
    private fun moveToPrevious(prefs: SharedPreferences, target: File, prev: File): Boolean {
        // stale files first, before the marker: while it is set, every previous file is a moved one
        for (sfx in SUFFIXES) File(prev.path + sfx).delete()
        prefs.edit().putBoolean(MOVING, true).commit()
        for (sfx in SUFFIXES) {
            val f = File(target.path + sfx)
            if (f.exists() && !rename(f, File(prev.path + sfx))) {
                undoHalfMove(prefs, target, prev)
                return false
            }
        }
        prefs.edit().remove(MOVING).commit()
        return true
    }

    @SuppressLint("ApplySharedPref") // see prepare()
    private fun undoHalfMove(prefs: SharedPreferences, target: File, prev: File) {
        if (!prefs.getBoolean(MOVING, false)) return
        for (sfx in SUFFIXES) {
            val p = File(prev.path + sfx)
            val t = File(target.path + sfx)
            if (p.exists() && !t.exists()) rename(p, t)
        }
        prefs.edit().remove(MOVING).commit()
    }

    // the main file and its WAL together: SQLite may leave the newest pages in the WAL
    private fun legacyTime(legacy: File): Long =
        SUFFIXES.take(2).maxOf { File(legacy.path + it).let { f -> if (f.exists()) f.lastModified() else 0L } }

    private fun legacyLength(legacy: File): Long =
        SUFFIXES.take(2).sumOf { File(legacy.path + it).let { f -> if (f.exists()) f.length() else 0L } }

    @SuppressLint("ApplySharedPref") // see prepare()
    private fun copy(legacy: File, target: File, prefs: SharedPreferences) {
        val tmp = File(target.path + ".tmp")
        val size = legacyLength(legacy)
        val time = legacyTime(legacy)
        try {
            for (sfx in SUFFIXES) File(tmp.path + sfx).delete()
            for (sfx in SUFFIXES) {
                val src = File(legacy.path + sfx)
                if (src.exists()) {
                    val dst = File(tmp.path + sfx)
                    src.copyTo(dst, overwrite = true)
                    afterCopyingFile?.invoke(dst)
                }
            }
            // opening the copy folds its WAL or hot journal into the copy itself
            SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use { c ->
                    if (!c.moveToFirst() || c.getString(0) != "ok") error("copy of the library is damaged")
                }
            }
            for (sfx in SUFFIXES.drop(1)) File(tmp.path + sfx).delete()
            // saved before the rename: a kill between the two just copies again next start
            if (!prefs.edit().putLong("size", size).putLong("time", time).commit()) error("could not save the copy's details")
            beforePlacingCopy?.invoke()
            for (sfx in SUFFIXES) File(target.path + sfx).delete()
            if (!rename(tmp, target)) error("could not move the library copy into place")
        } catch (e: Exception) {
            for (sfx in SUFFIXES) File(tmp.path + sfx).delete()
            throw e
        }
    }
}
