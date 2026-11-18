package com.minim.music.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/**
 * 6.9.0 b78: the hidden Performance readout (debug builds only). Counts the Stats tab's frames with
 * FrameMetrics (Android 7+, so it works on Android 9), and the main-thread disk and network work
 * StrictMode catches, so real numbers can come from the phone.
 */
object PerfMeter {
    /** A frame slower than this missed the 60 Hz deadline (janky). */
    const val FRAME_BUDGET_NS = 16_666_667L

    @Volatile var frames = 0L; private set
    @Volatile var janky = 0L; private set
    /** Frames the screen had to skip because of the slow ones (each 16.7 ms over counts one). */
    @Volatile var dropped = 0L; private set
    @Volatile var worstMs = 0L; private set

    /** Whether frames are counted now: while the Stats tab is the tab (Now Playing opened from it included). */
    @Volatile var countWhile: () -> Boolean = { false }

    private val violations = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * 7.0.0: off in releases. GitHub releases ship the debuggable build type, so FLAG_DEBUGGABLE alone
     * would turn StrictMode, the frame listener and the Performance entry on for everyone. Set this to
     * true only for a test build that needs the readout.
     */
    @Volatile var enabled = false

    fun isDebug(context: Context): Boolean =
        enabled && (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** One frame's total time: counted only while [counting]. */
    fun onFrame(totalNs: Long) {
        if (!countWhile()) return
        frames++
        if (totalNs > FRAME_BUDGET_NS) {
            janky++
            dropped += totalNs / FRAME_BUDGET_NS
        }
        val ms = totalNs / 1_000_000
        if (ms > worstMs) worstMs = ms
    }

    fun resetFrames() {
        frames = 0; janky = 0; dropped = 0; worstMs = 0
    }

    fun violations(): Map<String, Int> = HashMap(violations)

    fun clearViolations() = violations.clear()

    private var frameThread: HandlerThread? = null

    /** Starts listening to the window's frames (debug builds only). */
    fun attach(activity: Activity) {
        if (!isDebug(activity)) return
        val t = frameThread ?: HandlerThread("minim-frames").also { it.start(); frameThread = it }
        try {
            activity.window.addOnFrameMetricsAvailableListener(
                Window.OnFrameMetricsAvailableListener { _, m, _ -> onFrame(m.getMetric(FrameMetrics.TOTAL_DURATION)) },
                Handler(t.looper)
            )
        } catch (_: Exception) {
        }
    }

    /**
     * StrictMode for debug builds: main-thread disk reads, writes and network are logged and counted
     * by where they come from in Minim (never a crash).
     */
    fun strictMode(context: Context) {
        if (!isDebug(context)) return
        val b = android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog()
        b.penaltyListener(java.util.concurrent.Executors.newSingleThreadExecutor()) { v ->
            val where = v.stackTrace.firstOrNull { it.className.startsWith("com.minim.") }
            val key = "${v.javaClass.simpleName.removeSuffix("Violation")} at " +
                (where?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "system")
            violations.merge(key, 1, Int::plus)
        }
        android.os.StrictMode.setThreadPolicy(b.build())
    }

    /** The readout's lines. */
    fun report(times: Map<String, Long>): String {
        val sb = StringBuilder()
        sb.append("Last load times\n")
        if (times.isEmpty()) sb.append("  (open Stats and a Reprise first)\n")
        for ((k, v) in times.toSortedMap()) sb.append("  $k: $v ms\n")
        sb.append("\nStats tab frames (since last reset)\n")
        val pct = if (frames > 0) janky * 100 / frames else 0
        sb.append("  frames: $frames\n  janky: $janky ($pct%)\n  dropped: $dropped\n  worst: $worstMs ms\n")
        val v = violations()
        sb.append("\nMain-thread disk/network (StrictMode)\n")
        if (v.isEmpty()) sb.append("  none caught\n")
        for ((k, n) in v.entries.sortedByDescending { it.value }.take(8)) sb.append("  $n× $k\n")
        return sb.toString()
    }
}
