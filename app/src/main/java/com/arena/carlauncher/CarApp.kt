package com.arena.carlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.loc.LocationHub
import com.arena.carlauncher.map.MapEngine
import com.arena.carlauncher.map.NavAppRepository
import com.arena.carlauncher.media.MediaHub
import com.arena.carlauncher.services.LauncherService
import com.arena.carlauncher.theme.DayNightController
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.util.CrashLog
import com.arena.carlauncher.util.ImageCache
import com.arena.carlauncher.vehicle.TripComputer
import com.arena.carlauncher.vehicle.VehicleHub
import com.arena.carlauncher.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Application-wide wiring.
 *
 * Everything here is a process singleton because a *launcher* must survive being backgrounded
 * for hours while other apps (navigation, music) are on top: views come and go, the state hubs
 * do not. Each hub owns its own thread/permission checks and never throws out to callers.
 */
class CarApp : android.app.Application() {

    lateinit var prefs: LauncherPrefs
        private set

    /** Application-scoped coroutine scope (never cancelled with an Activity). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        // Before anything that could throw: if a preference or a hub dies on this device, the reason has
        // to survive into the next start (and into Settings → Debug) rather than vanish into logcat.
        CrashLog.install(this)
        super.onCreate()
        instance = this

        prefs = LauncherPrefs.get(this)
        if (CrashLog.suggestedSafeMode && !prefs.safeMode) {
            // Two failed boots in a row: start the next one minimal and say why, instead of crash-looping.
            prefs.safeMode = true
            CrashLog.note("auto safe mode after ${CrashLog.pendingStarts} unfinished starts")
        }
        boot("theme") { DayNightController.install(this) }
        boot("palette") { Palette.install(this, prefs) }
        boot("images") { ImageCache.install(this) }

        // Boot every hub; each one is lazy about permissions and silently degrades.
        boot("media") { MediaHub.install(this) }
        boot("location") { LocationHub.install(this) }
        boot("vehicle") { VehicleHub.install(this) }
        boot("trip") { TripComputer.install(this) }
        boot("weather") { WeatherRepository.install(this) }
        boot("navapps") { NavAppRepository.install(this) }
        boot("map") { MapEngine.install(this) }

        boot("channels") { ensureChannels(this) }
        if (prefs.bootStart && !prefs.safeMode) {
            boot("service") { LauncherService.start(this, "boot") }
        }
    }

    /** One hub failing must not cost the whole launcher: record it, keep going, show the home screen. */
    private inline fun boot(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            CrashLog.failure("$what install failed", t)
        }
    }

    /**
     * The callback a launcher on a 2.72 GB head unit has to answer: the framework asks for memory back
     * *before* it starts killing processes, and our two bitmap caches are the cheapest thing to give up.
     * Half, not all — an emptied tile cache means every visible tile goes back to the network, which the
     * user feels far more than the few megabytes freed.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val critical = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        ImageCache.get(this).let { if (critical) it.evictAll() else it.trim() }
        MapEngine.trimMemory()
        // Icons are empty()d under real pressure because they re-decode from PackageManager without a
        // network round trip; tiles only halve for the same reason in reverse. Nothing is `System.gc()`-ed
        // — that costs a frame and buys kilobytes.
    }

    override fun onTerminate() {
        // Only reached on emulators, but keeps the scope honest during tests.
        appScope.cancel()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: CarApp
            private set

        const val CHANNEL_SERVICE = "launcher_service"
        const val CHANNEL_SILENT = "launcher_silent"
        const val NOTIF_SERVICE = 0x4341 // "CA"

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_SERVICE) == null) {
                val ch = NotificationChannel(
                    CHANNEL_SERVICE,
                    ctx.getString(R.string.notif_channel_service),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                nm.createNotificationChannel(ch)
            }
            if (nm.getNotificationChannel(CHANNEL_SILENT) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_SILENT,
                        ctx.getString(R.string.notif_channel_silent),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { setShowBadge(false) }
                )
            }
        }

        fun serviceNotification(ctx: Context, text: String): Notification =
            NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_car)
                .setContentTitle(ctx.getString(R.string.notif_service_title))
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pendingSelf(ctx))
                .build()

        fun pendingSelf(ctx: Context): android.app.PendingIntent {
            val i = Intent(ctx, com.arena.carlauncher.home.HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                android.app.PendingIntent.FLAG_IMMUTABLE else 0
            return android.app.PendingIntent.getActivity(ctx, 0, i, flags)
        }

        /**
         * `startForeground(id, notification, type)` only exists on API 29+, and the
         * `location` type is mandatory from there on when the manifest declares it.
         */
        fun startForegroundCompat(service: Service, id: Int, notification: Notification) {
            if (Build.VERSION.SDK_INT >= 29) {
                service.startForeground(id, notification, FGS_TYPE)
            } else {
                service.startForeground(id, notification)
            }
        }

        /** ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION (1 shl 3). */
        private const val FGS_TYPE = 8
    }
}
