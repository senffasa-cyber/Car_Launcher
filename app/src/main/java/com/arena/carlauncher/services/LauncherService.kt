package com.arena.carlauncher.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.arena.carlauncher.CarApp
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.home.ForegroundAppWatcher
import com.arena.carlauncher.loc.LocationHub
import com.arena.carlauncher.media.MediaHub
import com.arena.carlauncher.theme.DayNightController
import com.arena.carlauncher.vehicle.VehicleHub
import com.arena.carlauncher.weather.WeatherRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Always-on companion of the launcher UI.
 *
 * It owns the background half of every live feature so the cards keep working when a navigation app
 * is fullscreen on top (media session, GPS, vehicle bus, day/night, weather): the Home Activity is
 * paused in that situation, and a launcher that stops updating while the driver looks at the map is
 * useless. A foreground service is the only construct that survives on aggressive OEM killers — plus
 * the notification lets the user see *why* something is running.
 */
class LauncherService : LifecycleService() {

    private val main = Handler(Looper.getMainLooper())
    private var mediaWasPlaying = false
    private var wasReversing = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    LocationHub.resumeFromIdle()
                    WeatherRepository.maybeRefresh()
                    MediaHub.fetchSessions(this@LauncherService)
                    DayNightController.apply()
                    ScreenOffHelper.undim(this@LauncherService)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // No screen, nothing to refresh: hand the GPS back for the duration. The hub keeps
                    // its last fix, so the speedometer/trip cards resume instantly.
                    LocationHub.pauseForIdle()
                    Log.d(TAG, "screen off")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        CarApp.ensureChannels(this)
        try {
            CarApp.startForegroundCompat(this, CarApp.NOTIF_SERVICE, CarApp.serviceNotification(this, ""))
        } catch (t: Throwable) {
            // Android 12+ can refuse a foreground start from a receiver; the launcher UI still works.
            Log.w(TAG, "startForeground refused: ${t.message}")
        }
        MediaHub.install(this)
        LocationHub.install(this)
        VehicleHub.install(this)
        ForegroundAppWatcher.install(this)

        MediaHub.startTracking()
        LocationHub.start()
        VehicleHub.start()
        ForegroundAppWatcher.start()

        try {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        } catch (_: Throwable) {
        }

        lifecycleScope.launch {
            MediaHub.state
                .map { it.isPlaying to it.ownerPackage }
                .distinctUntilChanged()
                .collect { (playing, _) -> onPlayingChanged(playing) }
        }
        lifecycleScope.launch {
            VehicleHub.state
                .map { it.reverse }
                .distinctUntilChanged()
                .collect { onReverse(it) }
        }
        lifecycleScope.launch {
            while (true) {
                delay(60_000)
                minuteTick()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_RESTART -> {
                MediaHub.fetchSessions(this, force = true)
                VehicleHub.refresh()
            }
            ACTION_REFRESH -> minuteTick()
        }
        updateNotification()
        // START_STICKY: these units kill background apps; the system relaunches us.
        return START_STICKY
    }

    private fun minuteTick() {
        DayNightController.tick()
        WeatherRepository.maybeRefresh()
        MediaHub.fetchSessions(this)
        updateNotification()
    }

    private fun onPlayingChanged(playing: Boolean) {
        updateNotification()
        val p = LauncherPrefs.get(this)
        if (playing && !mediaWasPlaying && p.wakeOnPlay) wakeBriefly()
        mediaWasPlaying = playing
    }

    private fun onReverse(reversing: Boolean) {
        val p = LauncherPrefs.get(this)
        if (reversing && !wasReversing) {
            if (p.pauseOnReverse) MediaHub.act(MediaHub.Action.PAUSE)
        }
        wasReversing = reversing
    }

    /**
     * Short FULL_WAKEUP hold. `ACQUIRE_CAUSES_WAKEUP` is what makes turning the screen on for
     * "radio started while parked" legal without any special permission.
     */
    private fun wakeBriefly() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (pm.isInteractive) return
            val lock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "CarLauncher:wake"
            )
            lock.acquire(3000L)
            wakeLock = lock
            main.postDelayed({ releaseWake() }, 3200L)
        } catch (t: Throwable) {
            Log.d(TAG, "wake failed: ${t.message}")
        }
    }

    private fun releaseWake() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    private fun updateNotification() {
        val s = MediaHub.state.value
        val text = if (s.hasText) "${s.title} — ${s.artist}".trim().trimEnd('—', ' ')
        else getString(com.arena.carlauncher.R.string.notif_service_idle)
        val n = CarApp.serviceNotification(this, text)
        try {
            startForeground(CarApp.NOTIF_SERVICE, n)
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        releaseWake()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {
        }
        MediaHub.stopTracking()
        LocationHub.stop()
        VehicleHub.stop()
        ForegroundAppWatcher.stop()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LauncherService"
        const val ACTION_RESTART = "com.arena.carlauncher.action.RESTART"
        const val ACTION_REFRESH = "com.arena.carlauncher.action.REFRESH"

        fun start(ctx: Context, why: String = "manual") {
            val i = Intent(ctx, LauncherService::class.java).setAction(ACTION_RESTART)
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                else ctx.startService(i)
                Log.i(TAG, "service started ($why)")
            } catch (t: Throwable) {
                Log.w(TAG, "service start refused ($why): ${t.message}")
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, LauncherService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
