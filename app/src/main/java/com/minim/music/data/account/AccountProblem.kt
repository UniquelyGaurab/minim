package com.minim.music.data.account

/**
 * v5.0: what went wrong with sign-in, sign-out or Delete account, in words the UI can show. Each one
 * maps to one short, friendly line in Settings (never a crash, never a raw error).
 */
enum class AccountProblem {
    /** The user closed the Google account picker. */
    CANCELLED,
    /** No internet (checked before starting, or a network error / timeout on the way). */
    OFFLINE,
    /** No Google account on the phone (Credential Manager found nothing to offer). */
    NO_ACCOUNT,
    /** Google sign-in can't run here (no Google Play services, or this build has no Firebase setup). */
    UNAVAILABLE,
    /** The cloud database isn't created yet or its rules aren't published (Firestore NOT_FOUND / PERMISSION_DENIED). */
    NOT_READY,
    /** Re-confirming for Delete account picked a different Google account than the signed-in one. */
    WRONG_ACCOUNT,
    /** Anything else. */
    FAILED
}

/** The result of an account action: done, or the one problem to show. */
sealed class AccountOutcome {
    object Done : AccountOutcome()
    data class Problem(val problem: AccountProblem) : AccountOutcome()
}

/**
 * v5.0: sorts an exception from Credential Manager, Firebase Auth or Firestore into an [AccountProblem].
 * Works on canonical class names (MinimAccount maps exceptions to them with instanceof, so R8 renaming
 * can't break it). That keeps it a pure function, unit-tested without the Android runtime.
 */
object AccountProblems {

    /** Firestore status codes, as [com.google.firebase.firestore.FirebaseFirestoreException.Code] names. */
    fun fromFirestoreCode(code: String?): AccountProblem = when (code) {
        "UNAVAILABLE", "DEADLINE_EXCEEDED" -> AccountProblem.OFFLINE
        // NOT_FOUND on a query = the (default) database doesn't exist; PERMISSION_DENIED = rules not published
        "NOT_FOUND", "PERMISSION_DENIED", "FAILED_PRECONDITION" -> AccountProblem.NOT_READY
        else -> AccountProblem.FAILED
    }

    /**
     * [className] is the exception's simple class name; [firestoreCode] the Firestore code name when it's a
     * FirebaseFirestoreException; [authErrorCode] the Firebase Auth error code ("ERROR_USER_MISMATCH", ...).
     */
    fun classify(className: String, firestoreCode: String? = null, authErrorCode: String? = null): AccountProblem =
        when {
            className == "GetCredentialCancellationException" -> AccountProblem.CANCELLED
            className == "NoCredentialException" -> AccountProblem.NO_ACCOUNT
            className == "GetCredentialProviderConfigurationException" ||
                className == "GetCredentialUnsupportedException" -> AccountProblem.UNAVAILABLE
            className == "FirebaseNetworkException" -> AccountProblem.OFFLINE
            authErrorCode == "ERROR_USER_MISMATCH" -> AccountProblem.WRONG_ACCOUNT
            className == "FirebaseFirestoreException" -> fromFirestoreCode(firestoreCode)
            className == "UnknownHostException" || className == "SocketTimeoutException" ||
                className == "ConnectException" -> AccountProblem.OFFLINE
            else -> AccountProblem.FAILED
        }
}
