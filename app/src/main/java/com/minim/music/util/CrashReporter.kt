package com.minim.music.util

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hotfix 4.9.10: a crash catcher. An uncaught exception (any thread) is written to
 * [FILE_NAME] in the app's private files folder before Android's own handler closes the app.
 * On the next launch MainActivity shows a small "Minim crashed last time" card with a Copy
 * button ([CrashCard]), so the stack trace can be pasted into a chat.
 *
 * Nothing here may throw: a crash catcher that crashes hides the real crash.
 */
object CrashReporter {

    const val FILE_NAME = "last_crash.txt"
    /** Reports are cut to this length (a deep StackOverflowError trace is huge). */
    const val MAX_CHARS = 16_000

    @Volatile private var installed = false

    /**
     * Chains onto the current default handler: the report is saved first, then the previous
     * handler (Android's, which shows "Minim keeps stopping" and kills the process) still runs.
     */
    fun install(dir: File, appVersion: String, device: String) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                save(dir, format(error, thread.name, appVersion, device, System.currentTimeMillis()))
            } catch (_: Throwable) {
                // Never let the catcher replace the real crash
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /** The report text: when, which version and device, which thread, then the full stack trace. */
    fun format(error: Throwable, threadName: String, appVersion: String, device: String, timeMs: Long): String {
        val trace = StringWriter().also { sw -> PrintWriter(sw).use { error.printStackTrace(it) } }.toString()
        val time = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(timeMs))
        } catch (_: Throwable) {
            timeMs.toString()
        }
        val text = buildString {
            append("Minim crash report\n")
            append("Version: ").append(appVersion).append('\n')
            append("Device: ").append(device).append('\n')
            append("Time: ").append(time).append('\n')
            append("Thread: ").append(threadName).append('\n')
            append('\n')
            append(trace)
        }
        return if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) + "\n… (cut)" else text
    }

    fun save(dir: File, report: String) {
        dir.mkdirs()
        File(dir, FILE_NAME).writeText(report)
    }

    /** The saved report from the last run, or null. Never throws. */
    fun pending(dir: File): String? = try {
        val f = File(dir, FILE_NAME)
        if (f.isFile) f.readText().takeIf { it.isNotBlank() } else null
    } catch (_: Throwable) {
        null
    }

    /** Forgets the saved report (after it was shown, so it shows once). Never throws. */
    fun clear(dir: File) {
        try {
            File(dir, FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }

    /** The line the card shows under its title: the exception and its message, short. */
    fun headline(report: String): String {
        val lines = report.lines()
        val traceStart = lines.indexOfFirst { it.startsWith("Thread: ") }
        val first = lines.drop(if (traceStart >= 0) traceStart + 1 else 0).firstOrNull { it.isNotBlank() }
            ?: return ""
        val short = first.trim().replace(Regex("^(?:[a-z0-9_]+\\.)+"), "")
        return if (short.length > 140) short.take(140) + "…" else short
    }
}
