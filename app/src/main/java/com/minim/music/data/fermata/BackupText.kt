package com.minim.music.data.fermata

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateUtils
import com.minim.fermata.sync.BackupError
import com.minim.fermata.sync.BackupInfo
import com.minim.fermata.sync.BackupStatus
import com.minim.fermata.sync.RestoreResult
import com.minim.music.R

/**
 * 5.10.0: the words Settings → Storage and the restore sheets show for Fermata's backups. Kept
 * apart from MainActivity so tests can check them.
 */
object BackupText {

    /** "Music/Minim backups" for a tree picked with the system folder picker. */
    fun folderName(tree: Uri): String {
        val id = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (_: Exception) {
            return tree.lastPathSegment ?: tree.toString()
        }
        val path = id.substringAfter(':', id)
        return path.ifBlank { id.substringBefore(':') }
    }

    /** The hint under "Backup folder". */
    fun folderHint(ctx: Context, folder: Uri?, hasAccess: Boolean): String = when {
        folder == null -> ctx.getString(R.string.backup_folder_none)
        !hasAccess -> ctx.getString(R.string.backup_folder_lost)
        else -> folderName(folder)
    }

    /** The line under "Backup": the last backup, or why the last attempt failed. */
    fun statusLine(ctx: Context, st: BackupStatus, now: Long, running: Boolean): String {
        if (running) return ctx.getString(R.string.backup_running)
        st.lastError?.let { return ctx.getString(R.string.backup_failed, errorText(ctx, it)) }
        val at = st.lastSuccessAt ?: return ctx.getString(R.string.backup_never)
        val ago = DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
        return ctx.getString(R.string.backup_last, ago, ctx.getString(R.string.backup_events, st.lastEventCount.toInt()))
    }

    fun errorText(ctx: Context, e: BackupError): String = ctx.getString(
        when (e) {
            BackupError.NO_FOLDER -> R.string.backup_err_no_folder
            BackupError.FOLDER_ACCESS_LOST -> R.string.backup_err_access
            BackupError.WRITE_FAILED -> R.string.backup_err_write
            BackupError.VERIFY_FAILED -> R.string.backup_err_verify
            BackupError.RENAME_FAILED -> R.string.backup_err_rename
            BackupError.FOLDER_UNREADABLE -> R.string.backup_err_unreadable
            BackupError.NOT_FOUND -> R.string.backup_err_not_found
            BackupError.DAMAGED -> R.string.backup_err_damaged
            BackupError.TOO_NEW -> R.string.backup_err_too_new
            BackupError.RESTORE_FAILED -> R.string.backup_err_restore
        }
    )

    /**
     * The restore sheet's explanation. Fermata's restore adds the backup's events to what the
     * phone has; on a phone that already has a library that is a merge, not a replacement.
     */
    fun restoreSub(ctx: Context, phoneHasLibrary: Boolean): String =
        ctx.getString(if (phoneHasLibrary) R.string.backup_restore_sub_merge else R.string.backup_restore_sub)

    /** One row of the restore list: who made it and when, and the trailing event count. */
    fun restoreRow(ctx: Context, info: BackupInfo, now: Long): Pair<String, String?> {
        val who = if (info.fromThisPhone) ctx.getString(R.string.backup_this_phone)
        else ctx.getString(R.string.backup_other_phone, info.deviceId?.take(6) ?: "?")
        val whenText = info.createdAt?.let {
            DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString()
        } ?: info.fileName
        val label = ctx.getString(R.string.backup_restore_item, who, whenText)
        val trailing = info.problem?.let { ctx.getString(R.string.backup_unreadable, it) }
            ?: info.eventCount?.let { ctx.getString(R.string.backup_events, it.toInt()) }
        return label to trailing
    }

    /** The note after a restore. */
    fun restoreDone(ctx: Context, r: RestoreResult, phoneHadLibrary: Boolean): String = when (r) {
        is RestoreResult.Failure -> ctx.getString(R.string.backup_restore_failed, errorText(ctx, r.error))
        is RestoreResult.Success -> when {
            !phoneHadLibrary -> ctx.getString(R.string.backup_restored, r.added)
            r.added == 0 -> ctx.getString(R.string.backup_merged_nothing)
            else -> ctx.getString(R.string.backup_merged, r.added)
        }
    }
}

/**
 * 5.10.0: whether to offer "Restore your library?" on a fresh install. Asked once per install:
 * the answer is kept in minim_backup_offer.xml, which Android's own backups leave out, so a new
 * phone (or a reinstall) asks again.
 */
class RestoreOfferGate(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val answered: Boolean get() = prefs.getBoolean(KEY, false)

    fun markAnswered() {
        prefs.edit().putBoolean(KEY, true).apply()
    }

    enum class Offer { NONE, PICK_FOLDER, RESTORE }

    /**
     * [phoneHasLibrary]: the phone already has likes, playlists or plays (then never offer, and
     * remember that, so it is not offered after the user clears things later).
     */
    fun decide(phoneHasLibrary: Boolean, folderSet: Boolean, restorable: () -> Int): Offer {
        if (answered) return Offer.NONE
        if (phoneHasLibrary) {
            markAnswered()
            return Offer.NONE
        }
        if (!folderSet) return Offer.PICK_FOLDER
        return if (restorable() > 0) Offer.RESTORE else Offer.PICK_FOLDER
    }

    companion object {
        const val PREFS = "minim_backup_offer"
        private const val KEY = "answered"
    }
}
