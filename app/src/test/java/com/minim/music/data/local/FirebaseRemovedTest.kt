package com.minim.music.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.ai.GeminiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 5.10.1 (V6): no Firebase or Google sign-in anywhere, and the old sign-in is forgotten quietly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirebaseRemovedTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun noFirebaseOrSignInClassesAreOnTheClasspath() {
        val gone = listOf(
            "com.google.firebase.FirebaseApp",
            "com.google.firebase.auth.FirebaseAuth",
            "com.google.firebase.firestore.FirebaseFirestore",
            "com.google.firebase.appcheck.FirebaseAppCheck",
            "com.google.android.gms.auth.api.signin.GoogleSignIn",
            "com.google.android.libraries.identity.googleid.GetGoogleIdOption",
            "androidx.credentials.CredentialManager",
        )
        for (name in gone) {
            try {
                Class.forName(name)
                fail(name + " is still there")
            } catch (_: ClassNotFoundException) { }
        }
    }

    @Test
    fun noAppSourceMentionsFirebaseOrSignIn() {
        val src = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        assertTrue(src.isDirectory)
        val bad = Regex("import\\s+com\\.google\\.(firebase|android\\.gms)|import\\s+androidx\\.credentials|google-services")
        val hits = src.walkTopDown().filter { it.extension == "kt" }.filter { bad.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(emptyList<String>(), hits)
        assertFalse(File(src, "com/minim/music/data/account").exists())
        val manifest = File(src.parentFile, "AndroidManifest.xml").readText()
        assertFalse(manifest.contains("firebase", ignoreCase = true))
        assertFalse(File(src.parentFile.parentFile.parentFile, "google-services.json").exists())
    }

    @Test
    fun theOldSignInIsForgottenWithoutTouchingTheLibrary() {
        // leftovers of a 5.x phone that was signed in
        context.getSharedPreferences("com.google.firebase.auth.api.Store.W0RFRkFVTFRd", Context.MODE_PRIVATE).edit().putString("u", "x").commit()
        context.getSharedPreferences("FirebaseHeartBeatW0RFRkFVTFRd", Context.MODE_PRIVATE).edit().putLong("t", 1).commit()
        context.getSharedPreferences(GeminiSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putString(LegacyCloudCleanup.APP_CHECK_TOKEN_KEY, "token").putString("keep", "me").commit()
        File(context.filesDir, "PersistedInstallation.W0RFRkFVTFRd.json").writeText("{}")
        File(context.filesDir, "generatefid.lock").writeText("")
        File(context.filesDir, "profile").mkdirs()
        File(context.filesDir, "profile/photo.png").writeText("p")
        context.getSharedPreferences("minim_prefs_keep", Context.MODE_PRIVATE).edit().putString("a", "b").commit()

        val db = LibraryDatabase(context)
        val w = db.writableDatabase
        w.execSQL("INSERT OR REPLACE INTO sync_state (k, v) VALUES ('uid', 'abc')")
        w.execSQL("INSERT OR REPLACE INTO sync_state (k, v) VALUES ('mk.likes', '5')")

        // the app already ran its once-only clean-up at start (nothing to forget then), so run it again
        assertTrue(context.getSharedPreferences(LegacyCloudCleanup.PREFS, Context.MODE_PRIVATE).getBoolean(LegacyCloudCleanup.KEY_DONE, false))
        LegacyCloudCleanup.run(context, db)

        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val left = prefsDir.list().orEmpty().toList()
        assertTrue(left.toString(), left.none { it.startsWith("com.google.firebase") || it.startsWith("FirebaseHeartBeat") })
        assertTrue(left.contains("minim_prefs_keep.xml"))
        val ai = context.getSharedPreferences(GeminiSettings.PREFS, Context.MODE_PRIVATE)
        assertFalse(ai.contains(LegacyCloudCleanup.APP_CHECK_TOKEN_KEY))
        assertEquals("me", ai.getString("keep", null))
        assertFalse(File(context.filesDir, "PersistedInstallation.W0RFRkFVTFRd.json").exists())
        assertFalse(File(context.filesDir, "generatefid.lock").exists())
        assertTrue("the profile photo is not Firebase's", File(context.filesDir, "profile/photo.png").exists())
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_state WHERE k = 'uid' OR k LIKE 'mk.%'", null).use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        db.close()
    }

    @Test
    fun firebaseNamesAreMatchedNarrowly() {
        assertTrue(LegacyCloudCleanup.isFirebasePrefs("com.google.firebase.auth.api.Store.X"))
        assertTrue(LegacyCloudCleanup.isFirebaseDatabase("firestore.%5BDEFAULT%5D.minim.%28default%29"))
        assertFalse(LegacyCloudCleanup.isFirebasePrefs("minim_prefs"))
        assertFalse(LegacyCloudCleanup.isFirebaseDatabase("minim_library.db"))
        assertFalse(LegacyCloudCleanup.isFirebaseFile("profile"))
        assertFalse(LegacyCloudCleanup.isFirebaseFile("fermata"))
    }
}
