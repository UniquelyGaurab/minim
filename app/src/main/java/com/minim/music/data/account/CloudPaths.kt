package com.minim.music.data.account

/**
 * v5.0: where a signed-in user's data lives in Firestore. Everything is under `users/{uid}/...`, the only
 * place the security rules (firestore.rules) let a user read and write, and only their own. The public,
 * read-only app config (part 3) lives under `config/...`.
 *
 * [USER_TREE] is the list Delete account walks. Part 2 (sync) must add every collection it writes here,
 * nested ones too (as [CloudCollection.children]), or Delete account would leave that data behind:
 * Firestore can't list a document's subcollections from the app.
 */
object CloudPaths {

    const val USERS = "users"
    const val CONFIG = "config"

    /** One collection under a user's document, with the collections under each of its documents. */
    data class CloudCollection(val name: String, val children: List<CloudCollection> = emptyList())

    /**
     * Everything part 2 (sync) writes. All flat: a playlist's songs are a map inside its document,
     * so there are no nested collections. Deleting a collection that's empty is harmless.
     * - likes/{songId}     like state + when it changed
     * - hidden/{songId}    "not interested"
     * - playlists/{uid}    one playlist with its songs
     * - history/{yyyyMMdd} one day of plays, listening time and taps
     */
    val USER_TREE: List<CloudCollection> = listOf(
        CloudCollection("likes"),
        CloudCollection("hidden"),
        CloudCollection("playlists"),
        CloudCollection("history")
    )

    fun userDoc(uid: String): String {
        require(isSafeId(uid)) { "bad uid" }
        return "$USERS/$uid"
    }

    /** The collection paths under [uid] at the top level (nested ones are found per document). */
    fun userCollections(uid: String): List<String> = USER_TREE.map { "${userDoc(uid)}/${it.name}" }

    /** Every collection name pattern in the tree, nested ones as "playlists/{doc}/tracks" (for tests and docs). */
    fun allPatterns(tree: List<CloudCollection> = USER_TREE, prefix: String = ""): List<String> =
        tree.flatMap { c ->
            val path = prefix + c.name
            listOf(path) + allPatterns(c.children, "$path/{doc}/")
        }

    /** Firebase uids are 1-128 chars without '/'; anything else must never become a path. */
    fun isSafeId(id: String): Boolean = id.isNotEmpty() && id.length <= 128 && '/' !in id && id != "." && id != ".."
}
