package com.arena.carlauncher.home

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.provider.Settings
import android.util.Log
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.map.NavAppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Which app is on screen right now?" — the signal behind the centre-card auto-switch.
 *
 * Two independent feeds, because no single one works on every ROM:
 *  1. an optional **AccessibilityService** (instant, exact; the user enables it once), and
 *  2. **UsageStatsManager** polled at 1 Hz (works without accessibility but needs the
 *     `android.permission.PACKAGE_USAGE_STATS` app-op, grantable from adb or the settings page).
 *
 * When neither is available the launcher simply stops auto-switching to the map card and keeps the
 * manual swipe — a degraded mode, never a crash.
 */
object ForegroundAppWatcher {

    private const val TAG = "ForegroundAppWatcher"
    private const val POLL_MS = 1000L

    private var app: Context? = null
    private var prefs: LauncherPrefs? = null
    private val main = Handler(android.os.Looper.getMainLooper())

    private val _state = MutableStateFlow<String?>(null)
    val state = _state.asStateFlow()

    var accessibilityActive = false
        private set

    private var running = false
    private var lastFromAccessibility = 0L

    fun install(ctx: Context) {
        if (app == null) {
            app = ctx.applicationContext
            prefs = LauncherPrefs.get(ctx)
        }
    }

    fun start() {
        if (running) return
        running = true
        if (hasUsageAccess(app ?: return)) main.postDelayed(poll, POLL_MS)
    }

    fun stop() {
        running = false
        main.removeCallbacks(poll)
    }

    /** Written by [ForegroundAppTracker] on every window state change. */
    internal fun report(pkg: String?) {
        lastFromAccessibility = System.currentTimeMillis()
        accessibilityActive = true
        if (pkg == _state.value) return
        _state.value = pkg
    }

    fun current(): String? = _state.value

    fun isNavigationForeground(): Boolean {
        val pkg = _state.value ?: return false
        return NavAppRepository.isNavigationPackage(pkg)
    }

    fun isMusicForeground(): Boolean {
        val pkg = _state.value ?: return false
        if (pkg.isEmpty()) return false
        val preferred = prefs?.mediaSessionPackage.orEmpty()
        if (preferred.isNotBlank() && preferred == pkg) return true
        val owner = runCatching { com.arena.carlauncher.media.MediaHub.state.value.ownerPackage }.getOrNull()
        return !owner.isNullOrBlank() && owner == pkg
    }

    // ------------------------------------------------------------------ usage-stats feed
    fun hasUsageAccess(ctx: Context): Boolean = try {
        val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val op = try {
            AppOpsManager::class.java.getField("OPSTR_GET_USAGE_STATS").get(null) as String
        } catch (_: Throwable) {
            "android:get_usage_stats"
        }
        @Suppress("DEPRECATION")
        val res = aom.checkOpNoThrow(op, android.os.Process.myUid(), ctx.packageName)
        res == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        // Some head-unit ROMs hide AppOpsManager entirely; assume "unknown" and let the poll fail soft.
        true
    }

    fun openUsageAccessSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            // The accessibility feed is strictly better; only poll when it is silent.
            if (accessibilityActive && System.currentTimeMillis() - lastFromAccessibility < 4000) {
                main.postDelayed(this, POLL_MS * 2)
                return
            }
            accessibilityActive = System.currentTimeMillis() - lastFromAccessibility < 4000
            val pkg = queryForeground()
            if (pkg != null && pkg != _state.value) _state.value = pkg
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun queryForeground(): String? {
        val ctx = app ?: return null
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        return try {
            @Suppress("DEPRECATION")
            val events = usm.queryEvents(now - 12_000, now + 1000)
            var top: String? = null
            var topTime = 0L
            val e = android.app.usage.UsageEvents.Event()
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == 1 /* ACTIVITY_RESUMED == MOVE_TO_FOREGROUND */ ||
                    e.eventType == 2 /* ACTIVITY_PAUSED, still tells us who was on top */ ||
                    e.eventType == 23 /* ACTIVITY_STOPPED */
                ) {
                    val p = e.packageName ?: continue
                    if (p == ctx.packageName) continue
                    if (e.timeStamp >= topTime) {
                        topTime = e.timeStamp
                        top = p
                    }
                }
            }
            top
        } catch (t: Throwable) {
            Log.d(TAG, "usage stats unavailable: ${t.message}")
            main.removeCallbacks(poll)
            null
        }
    }

    fun debugDump(): String =
        "fg=${_state.value} acc=$accessibilityActive usage=${app?.let { hasUsageAccess(it) }}"
}
