package com.minim.music.data.fermata

import androidx.work.WorkInfo
import com.minim.fermata.android.FermataBackups

/**
 * 5.10.1: which of Fermata's WorkManager jobs move the backup status line in Settings.
 * Every backup and merge Fermata runs is one of these unique jobs, so watching them (instead of
 * only "Back up now") keeps the line current after the automatic backup on close, the merge on
 * open, the daily backup and the backup after a history clear.
 */
object BackupWork {
    val NAMES: List<String> = listOf(
        FermataBackups.ON_CLOSE_WORK,
        FermataBackups.MERGE_WORK,
        FermataBackups.DAILY_WORK,
        FermataBackups.NOW_WORK,
        FermataBackups.ONCE_WORK,
    )

    /**
     * True when the status might read differently now: a job finished, or the daily one went
     * back to waiting after a run. A job that is only queued or running hasn't written anything.
     */
    fun changesStatus(states: List<WorkInfo.State>): Boolean =
        states.any { it.isFinished || it == WorkInfo.State.ENQUEUED }
}
