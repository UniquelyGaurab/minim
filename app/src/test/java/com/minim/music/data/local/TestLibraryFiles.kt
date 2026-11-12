package com.minim.music.data.local

import android.content.Context

/** 5.10.0: tests start from no library at all, neither the 5.9.1 file nor 5.10.0's copy. */
object TestLibraryFiles {
    fun wipe(context: Context) {
        context.deleteDatabase(LibraryDatabase.DB_NAME)
        context.deleteDatabase(LibraryFiles.FILE_NAME)
        context.deleteDatabase(LibraryFiles.PREVIOUS_NAME)
        context.getDatabasePath(LibraryFiles.FILE_NAME + ".tmp").delete()
        for (sfx in listOf("", "-wal", "-shm", "-journal")) context.getDatabasePath(LibraryFiles.FILE_NAME + ".tmp" + sfx).delete()
        context.getSharedPreferences(LibraryFiles.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        LibraryFiles.resetHooksForTest()
    }

    /** Opens (creating) the 5.9.1 file itself, the way an older version left it. */
    fun legacy(context: Context) = LibraryDatabase(context, LibraryDatabase.DB_NAME)
}
