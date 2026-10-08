package com.arena.carlauncher.util

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Why the launcher did not come up — recorded where the user can actually read it.
 *
 * A home screen is not allowed to be a black hole: when it dies during `onCreate`, the system just
 * drops back to whatever was there before and shows nothing at all, and on a head unit with no Play
 * Store there is often no logcat either. So [install] claims the uncaught-exception handler *first*
 * (before any preference or hub can throw), appends the trace to `filesDir/crash.log`, and counts
 * starts. Two starts that never reached a finished home screen and [suggestedSafeMode] flips on for
 * the next one — a launcher with three cards is better than a launcher that will not open.
 *
 * Settings → Debug shows this file verbatim (`LogSheet`), and `HomeActivity` offers it on the *next*
 * successful boot — a user with no PC cannot be expected to go hunting for it.
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val CRASH_FILE = "crash.log"
    private const val BOOT_FILE = "boot.txt"
    private const val MAX_BYTES = 60 * 1024
    private const val SHOW_CHARS = 8000

    private var dir: File? = null
    private var startedAt = 0L

    /** True when the previous two launches died before the home screen finished building. */
    @Volatile
    var suggestedSafeMode = false
        private set

    /** Number of starts since the last time the home screen completed. */
    @Volatile
    var pendingStarts = 0
        private set

    fun install(app: Application) {
        // The app-specific *external* dir on purpose: `filesDir` needs root or `run-as`, and the people
        // who need this file most are sitting in a car with no PC — any file manager opens
        // Android/data/<pkg>/files/crash.log. Internal storage is the fallback, not the plan.
        dir = try {
            app.getExternalFilesDir(null) ?: app.filesDir
        } catch (_: Throwable) {
            app.filesDir
        }
        startedAt = SystemClock.elapsedRealtime()
        pendingStarts = readBoot() + 1
        suggestedSafeMode = readBoot() >= 2
        writeBoot(pendingStarts)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write("uncaught in ${thread.name}", error)
            } catch (_: Throwable) {
            }
            try {
                previous?.uncaughtException(thread, error)
            } catch (_: Throwable) {
            }
        }
    }

    /** The home screen is up: stop counting, so a crash months from now starts its own fresh streak. */
    fun bootOk() {
        pendingStarts = 0
        writeBoot(0)
    }

    /** A failure we caught and degraded around — same file, so there is one place to read. */
    fun note(text: String) = write(text, null)

    fun failure(what: String, t: Throwable) = write(what, t)

    /** The tail of the log, newest last, capped so the settings sheet stays readable on a car panel. */
    fun recent(): String {
        val d = dir
        if (d == null) return "crash log unavailable (no filesDir)"
        val f = File(d, CRASH_FILE)
        if (!f.exists()) return ""
        return try {
            val text = f.readText()
            if (text.length > SHOW_CHARS) text.substring(text.length - SHOW_CHARS) else text
        } catch (t: Throwable) {
            "crash log unreadable: ${t.message}"
        }
    }

    fun clear() {
        try {
            dir?.let { File(it, CRASH_FILE).delete() }
        } catch (_: Throwable) {
        }
    }

    private fun readBoot(): Int {
        // Block body on purpose: a `return` inside `= try { … }` is not a statement, and Kotlin refuses it.
        val d = dir ?: return 0
        return try {
            val f = File(d, BOOT_FILE)
            if (f.exists()) f.readText().trim().toIntOrNull() ?: 0 else 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun writeBoot(value: Int) {
        try {
            val d = dir ?: return
            File(d, BOOT_FILE).writeText(value.toString())
        } catch (_: Throwable) {
        }
    }

    private fun write(what: String, t: Throwable?) {
        val d = dir ?: return
        try {
            val f = File(d, CRASH_FILE)
            var head = "\n— ${System.currentTimeMillis()} up=${SystemClock.elapsedRealtime() - startedAt}ms" +
                " android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}" +
                " device=${Build.MANUFACTURER}/${Build.MODEL} —\n$what\n"
            if (t != null) head += Log.getStackTraceString(t) + "\n"
            if (f.exists() && f.length() > MAX_BYTES) {
                val keep = f.readText()
                val from = (keep.length - MAX_BYTES / 2).coerceAtLeast(0)
                f.writeText(keep.substring(from))
            }
            FileOutputStream(f, true).use { it.write(head.toByteArray()) }
            if (t != null) Log.e(TAG, what, t) else Log.w(TAG, what)
        } catch (nested: Throwable) {
            Log.e(TAG, "crash log write failed: ${nested.message}")
        }
    }
}
