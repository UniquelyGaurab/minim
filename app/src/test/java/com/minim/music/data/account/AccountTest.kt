package com.minim.music.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** v5.0 part 1: Google sign-in errors, Firestore paths and rules, privacy text, avatar layout. */
class AccountTest {

    private fun projectFile(path: String): File =
        listOf(File("../$path"), File(path)).first { it.exists() }

    private fun appFile(path: String): File =
        listOf(File(path), File("app/$path")).first { it.exists() }

    // ------------------------------------------------------------ errors

    @Test
    fun credentialErrorsMapToFriendlyProblems() {
        assertEquals(AccountProblem.CANCELLED, AccountProblems.classify("GetCredentialCancellationException"))
        assertEquals(AccountProblem.NO_ACCOUNT, AccountProblems.classify("NoCredentialException"))
        assertEquals(AccountProblem.UNAVAILABLE, AccountProblems.classify("GetCredentialProviderConfigurationException"))
        assertEquals(AccountProblem.UNAVAILABLE, AccountProblems.classify("GetCredentialUnsupportedException"))
        assertEquals(AccountProblem.FAILED, AccountProblems.classify("GetCredentialUnknownException"))
    }

    @Test
    fun firebaseErrorsMapToFriendlyProblems() {
        assertEquals(AccountProblem.OFFLINE, AccountProblems.classify("FirebaseNetworkException"))
        assertEquals(AccountProblem.OFFLINE, AccountProblems.classify("UnknownHostException"))
        assertEquals(
            AccountProblem.WRONG_ACCOUNT,
            AccountProblems.classify("FirebaseAuthInvalidUserException", authErrorCode = "ERROR_USER_MISMATCH")
        )
        assertEquals(
            AccountProblem.FAILED,
            AccountProblems.classify("FirebaseAuthInvalidUserException", authErrorCode = "ERROR_USER_DISABLED")
        )
        // Database not created yet / rules not published / offline
        assertEquals(AccountProblem.NOT_READY, AccountProblems.classify("FirebaseFirestoreException", "NOT_FOUND"))
        assertEquals(AccountProblem.NOT_READY, AccountProblems.classify("FirebaseFirestoreException", "PERMISSION_DENIED"))
        assertEquals(AccountProblem.OFFLINE, AccountProblems.classify("FirebaseFirestoreException", "UNAVAILABLE"))
        assertEquals(AccountProblem.FAILED, AccountProblems.classify("FirebaseFirestoreException", "INTERNAL"))
        assertEquals(AccountProblem.FAILED, AccountProblems.classify("IllegalStateException"))
    }

    @Test
    fun realExceptionClassNamesStillMatch() {
        // Guards against a library rename silently turning every error into "Couldn't sign in"
        assertEquals("GetCredentialCancellationException",
            androidx.credentials.exceptions.GetCredentialCancellationException::class.java.simpleName)
        assertEquals("NoCredentialException",
            androidx.credentials.exceptions.NoCredentialException::class.java.simpleName)
        assertEquals("GetCredentialProviderConfigurationException",
            androidx.credentials.exceptions.GetCredentialProviderConfigurationException::class.java.simpleName)
        assertEquals("FirebaseNetworkException", com.google.firebase.FirebaseNetworkException::class.java.simpleName)
        assertEquals("FirebaseFirestoreException",
            com.google.firebase.firestore.FirebaseFirestoreException::class.java.simpleName)
    }

    // ------------------------------------------------------------ paths + rules

    @Test
    fun userDataLivesUnderTheUsersOwnDocument() {
        assertEquals("users/abc123", CloudPaths.userDoc("abc123"))
        CloudPaths.userCollections("abc123").forEach { assertTrue(it, it.startsWith("users/abc123/")) }
        // v5.0.0 part 2: everything sync writes is flat (a playlist's songs are a map in its document),
        // and Delete account walks exactly these four collections
        assertEquals(listOf("likes", "hidden", "playlists", "history"), CloudPaths.allPatterns())
        assertEquals(CloudPaths.USER_TREE.map { it.name }, com.minim.music.data.sync.CloudSync.COLLECTIONS)
        assertEquals("config", CloudPaths.CONFIG)
    }

    @Test
    fun unsafeUidsNeverBecomePaths() {
        assertFalse(CloudPaths.isSafeId(""))
        assertFalse(CloudPaths.isSafeId("a/b"))
        assertFalse(CloudPaths.isSafeId(".."))
        assertFalse(CloudPaths.isSafeId("x".repeat(129)))
        assertTrue(CloudPaths.isSafeId("Xy12AbCdEfGhIjKlMnOpQrStUv34"))
        assertTrue(runCatching { CloudPaths.userDoc("a/b") }.isFailure)
    }

    @Test
    fun firestoreRulesFileIsExactlyThePublishedRules() {
        val expected = """
            rules_version = '2';
            service cloud.firestore {
              match /databases/{database}/documents {
                match /users/{uid}/{document=**} {
                  allow read, write: if request.auth != null && request.auth.uid == uid;
                }
                match /config/{document=**} {
                  allow read: if true;
                  allow write: if false;
                }
                match /{document=**} {
                  allow read, write: if false;
                }
              }
            }

        """.trimIndent()
        assertEquals(expected, projectFile("firestore.rules").readText())
    }

    @Test
    fun firebaseConfigNeverGoesPublic() {
        assertTrue(projectFile(".gitignore").readLines().any { it.trim() == "google-services.json" })
        assertTrue(appFile("google-services.json.example").readText().contains("YOUR_WEB_CLIENT_ID"))
    }

    @Test
    fun userModelHasNoEmail() {
        // Google always shares the email, but Minim never shows or keeps it in the UI model
        val fields = MinimAccount.User::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fields.any { "mail" in it })
    }

    // ------------------------------------------------------------ privacy text + layouts

    private fun strings(): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(appFile("src/main/res/values/strings.xml")).documentElement
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val e = nodes.item(it) as Element
            e.getAttribute("name") to e.textContent.replace("\\'", "'")
        }
    }

    @Test
    fun privacyLineSaysExactlyWhatMinimGets() {
        assertEquals(
            "Minim only gets your name, profile picture and email. It never sees your password and can't " +
                "access your Gmail, Drive, contacts or anything else in your Google account.",
            strings()["account_privacy"]
        )
    }

    @Test
    fun avatarKeepsTheGearsTouchTargetAndOpensSettings() {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = f.newDocumentBuilder().parse(appFile("src/main/res/layout/view_top_tabs.xml")).documentElement
        val all = root.getElementsByTagName("com.minim.music.ui.AvatarView")
        assertEquals(1, all.length)
        val e = all.item(0) as Element
        val ns = "http://schemas.android.com/apk/res/android"
        assertEquals("@+id/top_gear", e.getAttributeNS(ns, "id"))
        assertEquals("34dp", e.getAttributeNS(ns, "layout_width"))
        assertEquals("34dp", e.getAttributeNS(ns, "layout_height"))
        assertEquals("@string/settings", e.getAttributeNS(ns, "contentDescription"))
        assertEquals("true", e.getAttributeNS(ns, "clickable"))
    }

    @Test
    fun accountSectionComesFirstInSettings() {
        val text = appFile("src/main/res/layout/view_settings.xml").readText()
        val account = text.indexOf("@+id/settings_account")
        assertTrue(account > 0)
        assertTrue(account < text.indexOf("@+id/settings_name"))
        listOf("account_sign_in", "account_sign_out", "account_delete", "account_privacy", "account_message")
            .forEach { assertTrue(it, text.contains("@+id/$it")) }
    }
}
