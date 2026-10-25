package com.minim.music.data.account

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * v5.0: the optional Google account. Minim works fully without it; signing in only enables sync (part 2).
 *
 * Sign-in: Android Credential Manager ("Sign in with Google" flow) returns a Google ID token for the
 * Web client ID, which goes to Firebase Auth. The ID token carries only the basic profile (name, photo,
 * email): no extra scopes, no Drive / Gmail / contacts. The email is never shown in the app.
 *
 * Everything here returns an [AccountOutcome] instead of throwing, so the UI shows one friendly line.
 */
class MinimAccount(private val context: Context) {

    /** What the UI needs about the signed-in user. The email is deliberately not here. */
    data class User(val uid: String, val name: String, val photoUrl: String?)

    /** The Web client ID generated from google-services.json (null in builds without Firebase). */
    private val webClientId: String? = readWebClientId(context)

    private val auth: FirebaseAuth? = try {
        if (FirebaseApp.getApps(context).isNotEmpty() && webClientId != null) FirebaseAuth.getInstance() else null
    } catch (t: Throwable) {
        Log.w(TAG, "Firebase not available", t)
        null
    }

    /** False when this build has no Firebase setup: the Account section stays hidden. */
    val available: Boolean get() = auth != null

    private val _user = MutableStateFlow(auth?.currentUser?.toUser())
    val user: StateFlow<User?> = _user.asStateFlow()

    init {
        auth?.addAuthStateListener { a -> _user.value = a.currentUser?.toUser() }
    }

    // ---------------------------------------------------------------- sign in / out

    suspend fun signIn(activity: Activity): AccountOutcome {
        val auth = auth ?: return problem(AccountProblem.UNAVAILABLE)
        if (!isOnline()) return problem(AccountProblem.OFFLINE)
        return guarded("signIn") {
            val token = googleIdToken(activity)
            withTimeout(NETWORK_TIMEOUT_MS) {
                auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
            }
            _user.value = auth.currentUser?.toUser()
            AccountOutcome.Done
        }
    }

    /** Signs out of Minim only. Nothing on the phone (library, likes, stats, settings) is touched. */
    suspend fun signOut(): AccountOutcome {
        val auth = auth ?: return AccountOutcome.Done
        auth.signOut()
        _user.value = null
        clearGoogleChoice()
        return AccountOutcome.Done
    }

    // ---------------------------------------------------------------- delete account

    /**
     * Deletes the user's cloud data (the whole users/{uid} tree, see [CloudPaths.USER_TREE]) and then the
     * Firebase Auth user. If Firebase wants a recent sign-in, it asks Google again (account picker) and
     * retries. Local data stays on the phone.
     */
    suspend fun deleteAccount(activity: Activity): AccountOutcome {
        val auth = auth ?: return problem(AccountProblem.UNAVAILABLE)
        val user = auth.currentUser ?: return AccountOutcome.Done
        if (!isOnline()) return problem(AccountProblem.OFFLINE)
        return guarded("deleteAccount") {
            deleteCloudData(user.uid)
            try {
                withTimeout(NETWORK_TIMEOUT_MS) { user.delete().await() }
            } catch (e: FirebaseAuthRecentLoginRequiredException) {
                val token = googleIdToken(activity)
                withTimeout(NETWORK_TIMEOUT_MS) {
                    user.reauthenticate(GoogleAuthProvider.getCredential(token, null)).await()
                    user.delete().await()
                }
            }
            _user.value = null
            clearGoogleChoice()
            AccountOutcome.Done
        }
    }

    /**
     * The hook part 2 builds on: removes every document under users/{uid}. Reads come from the server
     * only (an offline cache read could miss documents). A missing database (NOT_FOUND) means there's
     * nothing stored, so it counts as done.
     */
    private suspend fun deleteCloudData(uid: String) {
        val db = com.minim.music.data.sync.CloudDb.get()
        val userDoc = db.document(CloudPaths.userDoc(uid))
        try {
            withTimeout(DELETE_TIMEOUT_MS) {
                for (c in CloudPaths.USER_TREE) deleteCollection(db, userDoc.collection(c.name), c.children)
                userDoc.delete().await()
            }
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.NOT_FOUND) {
                Log.i(TAG, "No Firestore database yet, so no cloud data to delete")
                return
            }
            throw e
        }
    }

    private suspend fun deleteCollection(
        db: FirebaseFirestore,
        col: CollectionReference,
        children: List<CloudPaths.CloudCollection>
    ) {
        var rounds = 0
        while (rounds++ < MAX_DELETE_ROUNDS) {
            val page = col.limit(DELETE_PAGE.toLong()).get(Source.SERVER).await()
            if (page.isEmpty) return
            for (doc in page.documents) {
                for (child in children) deleteCollection(db, doc.reference.collection(child.name), child.children)
            }
            val batch = db.batch()
            page.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Shows the Google account picker and returns the ID token for [webClientId]. */
    private suspend fun googleIdToken(activity: Activity): String {
        val option = GetSignInWithGoogleOption.Builder(webClientId ?: error("no web client id")).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        throw IllegalStateException("Unexpected credential type ${credential.type}")
    }

    /** So the next sign-in shows the account picker again instead of reusing the old choice. */
    private suspend fun clearGoogleChoice() {
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "clearCredentialState failed", t)
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> AccountOutcome): AccountOutcome =
        try {
            block()
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "$what timed out")
            problem(AccountProblem.OFFLINE)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val p = AccountProblems.classify(
                className = kindOf(t),
                firestoreCode = (t as? FirebaseFirestoreException)?.code?.name,
                authErrorCode = (t as? FirebaseAuthException)?.errorCode
            )
            if (p != AccountProblem.CANCELLED) Log.w(TAG, "$what failed: $p", t)
            problem(p)
        }

    private fun problem(p: AccountProblem) = AccountOutcome.Problem(p)

    /** Canonical names via instanceof (R8 may rename classes, and subclasses count too). */
    private fun kindOf(t: Throwable): String = when (t) {
        is GetCredentialCancellationException -> "GetCredentialCancellationException"
        is NoCredentialException -> "NoCredentialException"
        is GetCredentialProviderConfigurationException -> "GetCredentialProviderConfigurationException"
        is GetCredentialUnsupportedException -> "GetCredentialUnsupportedException"
        is FirebaseNetworkException -> "FirebaseNetworkException"
        is FirebaseFirestoreException -> "FirebaseFirestoreException"
        is UnknownHostException -> "UnknownHostException"
        is SocketTimeoutException -> "SocketTimeoutException"
        is ConnectException -> "ConnectException"
        else -> t.javaClass.simpleName
    }

    private fun isOnline(): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (t: Throwable) {
        true // can't tell: let the real request decide
    }

    private fun FirebaseUser.toUser(): User {
        val profile = providerData.firstOrNull { it.providerId == GoogleAuthProvider.PROVIDER_ID }
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: profile?.displayName?.takeIf { it.isNotBlank() }
            ?: ""
        val photo = (photoUrl ?: profile?.photoUrl)?.toString()?.takeIf { it.startsWith("https://") }
        return User(uid = uid, name = name, photoUrl = photo)
    }

    companion object {
        private const val TAG = "MinimAccount"
        private const val NETWORK_TIMEOUT_MS = 30_000L
        private const val DELETE_TIMEOUT_MS = 60_000L
        private const val DELETE_PAGE = 300
        private const val MAX_DELETE_ROUNDS = 400

        /** The google-services plugin turns the Web OAuth client in google-services.json into this string. */
        @SuppressLint("DiscouragedApi")
        private fun readWebClientId(context: Context): String? = try {
            val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (id != 0) context.getString(id).takeIf { it.endsWith(".apps.googleusercontent.com") } else null
        } catch (t: Throwable) {
            null
        }
    }
}
