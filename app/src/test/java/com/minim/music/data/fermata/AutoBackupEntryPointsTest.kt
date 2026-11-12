package com.minim.music.data.fermata

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.minim.fermata.android.FermataBackups
import com.minim.music.MinimApp
import com.minim.music.ui.LaunchTestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Automatic backups: the two entry points the app calls when it comes to the foreground and goes
 * to the background queue Fermata's unique WorkManager jobs, and nothing without a folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LaunchTestApp::class)
class AutoBackupEntryPointsTest {

    private val app get() = ApplicationProvider.getApplicationContext<MinimApp>()
    private lateinit var wm: WorkManager

    @Before
    fun holdJobs() {
        // Jobs are queued but never run, so the test sees what was queued.
        val config = Configuration.Builder()
            .setExecutor { }
            .setTaskExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, config)
        wm = WorkManager.getInstance(app)
    }

    /** Fermata's start (the first-launch import) runs on its own thread; wait for it. */
    private fun waitForStart() {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (Thread.getAllStackTraces().keys.none { it.name == "fermata-start" && it.isAlive }) return
            Thread.sleep(20)
        }
        error("Fermata did not start")
    }

    private fun queued(name: String) = wm.getWorkInfosForUniqueWork(name).get().filter { it.state != WorkInfo.State.CANCELLED }

    @Test
    fun withoutAFolderNothingIsQueued() {
        waitForStart()
        assertNotNull(app.backups)
        app.mergeBackupsOnOpen()
        app.backUpOnBackground()
        assertTrue(queued(FermataBackups.MERGE_WORK).isEmpty())
        assertTrue(queued(FermataBackups.ON_CLOSE_WORK).isEmpty())
    }

    @Test
    fun withAFolderEachCallQueuesItsUniqueExpeditedJob() {
        waitForStart()
        // As if the user had picked a folder (the permission itself isn't needed to queue).
        app.getSharedPreferences("fermata_backup", Context.MODE_PRIVATE).edit()
            .putString("tree_uri", "content://com.android.externalstorage.documents/tree/primary%3AMinim").commit()

        app.mergeBackupsOnOpen()
        app.mergeBackupsOnOpen()
        val merge = queued(FermataBackups.MERGE_WORK)
        assertEquals("a second open while one is queued adds nothing", 1, merge.size)

        app.backUpOnBackground()
        app.backUpOnBackground()
        val close = queued(FermataBackups.ON_CLOSE_WORK)
        assertEquals("the newer close replaces the waiting one", 1, close.size)
        assertTrue(queued(FermataBackups.DAILY_WORK).size <= 1)
    }
}
