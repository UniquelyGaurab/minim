package com.minim.music.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.minim.music.MinimApp
import com.minim.music.R
import com.minim.music.data.update.UpdateFlowState
import com.minim.music.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 5.4.0: keeps "Save offline" and the update download going while Minim is in the background.
 *
 * Until 5.3.2 the update download ran only while Minim was on screen. On mobile data, Android
 * (Data Saver, and phone makers' "background data" switches) cuts the network of apps in the
 * background, so the download paused as soon as the user left Minim; on Wi-Fi it didn't. A
 * foreground service with a notification counts as in use, so the network stays on. It stops
 * itself once nothing is downloading.
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MinimApp
        if (!started) {
            started = true
            val first = text(app.offline.session.value, app.offline.busy, app.updates.state.value)
            if (!goForeground(first)) {
                stopSelf()
                return START_NOT_STICKY
            }
            scope.launch {
                combine(app.offline.session, app.offline.progress, app.updates.state) { session, progress, update ->
                    Triple(session, progress.isNotEmpty(), update)
                }.distinctUntilChanged().collectLatest { (session, saving, update) ->
                    val t = text(session, saving, update)
                    if (t == null) {
                        // A moment's grace: the work may be just about to start (or the next song)
                        kotlinx.coroutines.delay(1_500)
                        ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(t))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(t: String?): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(t ?: getString(R.string.transfer_starting)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        true
    } catch (e: Exception) {
        // Android 12+ refuses a start from the background; the work still runs while Minim is open
        false
    }

    /** The notification line, or null when nothing is downloading. */
    private fun text(session: Pair<Int, Int>, saving: Boolean, update: UpdateFlowState): String? {
        val parts = mutableListOf<String>()
        if (update is UpdateFlowState.Downloading) {
            val pct = if (update.total > 0) (update.done * 100 / update.total).toInt() else -1
            parts += if (pct >= 0) getString(R.string.transfer_update_pct, pct) else getString(R.string.transfer_update)
        }
        if (saving) {
            val (done, total) = session
            parts += getString(R.string.transfer_saving, minOf(done + 1, total), total)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun build(t: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_offline)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(t)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "minim_transfers"
        private const val NOTIFICATION_ID = 5402

        /** Call right after starting a save or the update download, while Minim is on screen. */
        fun ensureRunning(context: Context) {
            try {
                context.startForegroundService(Intent(context, TransferService::class.java))
            } catch (e: Exception) {
                // Not allowed right now (in the background on Android 12+): the work still runs
            }
        }
    }
}
