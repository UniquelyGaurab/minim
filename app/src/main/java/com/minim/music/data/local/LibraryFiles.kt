package com.minim.music.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

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
 */
object LibraryFiles {
    const val FILE_NAME = "minim_library_510.db"
    const val PREVIOUS_NAME = "minim_library_510_previous.db"
    /** SharedPreferences with the 5.9.1 file's size and time at the copy. Not in Android's backups. */
    const val PREFS = "minim_db_copy"
    private val SUFFIXES = listOf("", "-wal", "-shm", "-journal")

    fun prepare(context: Context): String {
        val target = context.getDatabasePath(FILE_NAME)
        val legacy = context.getDatabasePath(LibraryDatabase.DB_NAME)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (target.exists()) {
            if (!legacy.exists() || !changedSinceCopy(prefs, legacy)) return FILE_NAME
            // 5.9.1 was used after the copy: keep the old copy for its log, copy again
            val prev = context.getDatabasePath(PREVIOUS_NAME)
            for (sfx in SUFFIXES) File(prev.path + sfx).delete()
            for (sfx in SUFFIXES) {
                val f = File(target.path + sfx)
                if (f.exists() && !f.renameTo(File(prev.path + sfx))) return FILE_NAME
            }
        }
        if (!legacy.exists()) return FILE_NAME
        try {
            copy(legacy, target)
        } catch (e: Exception) {
            // never block the app: open the 5.9.1 file itself (rollback then keeps today's state)
            android.util.Log.w("LibraryFiles", "copy failed, using the 5.9.1 file", e)
            return LibraryDatabase.DB_NAME
        }
        prefs.edit()
            .putLong("size", legacyLength(legacy))
            .putLong("time", legacyTime(legacy))
            .commit()
        return FILE_NAME
    }

    private fun changedSinceCopy(prefs: android.content.SharedPreferences, legacy: File): Boolean {
        if (!prefs.contains("time")) return false
        return prefs.getLong("time", 0L) != legacyTime(legacy) || prefs.getLong("size", 0L) != legacyLength(legacy)
    }

    // the main file and its WAL together: SQLite may leave the newest pages in the WAL
    private fun legacyTime(legacy: File): Long =
        SUFFIXES.take(2).maxOf { File(legacy.path + it).let { f -> if (f.exists()) f.lastModified() else 0L } }

    private fun legacyLength(legacy: File): Long =
        SUFFIXES.take(2).sumOf { File(legacy.path + it).let { f -> if (f.exists()) f.length() else 0L } }

    private fun copy(legacy: File, target: File) {
        val tmp = File(target.path + ".tmp")
        for (sfx in SUFFIXES) File(tmp.path + sfx).delete()
        for (sfx in SUFFIXES) {
            val src = File(legacy.path + sfx)
            if (src.exists()) src.copyTo(File(tmp.path + sfx), overwrite = true)
        }
        // opening the copy folds its WAL or hot journal into the copy itself
        SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (!c.moveToFirst() || c.getString(0) != "ok") error("copy of the library is damaged")
            }
        }
        for (sfx in SUFFIXES.drop(1)) File(tmp.path + sfx).delete()
        for (sfx in SUFFIXES) File(target.path + sfx).delete()
        if (!tmp.renameTo(target)) error("could not move the library copy into place")
    }
}
