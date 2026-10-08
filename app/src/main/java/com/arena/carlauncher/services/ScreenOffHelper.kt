package com.arena.carlauncher.services

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.arena.carlauncher.R
import com.arena.carlauncher.data.LauncherPrefs

/**
 * "Screen off" for a head unit, without root and without pretending otherwise.
 *
 * There is no public API to switch the display off. In order of preference:
 *  1. `PowerManager.goToSleep()` — works when the APK holds `android.permission.DEVICE_POWER`, i.e.
 *     installed as a privileged app (the path most car-launcher users end up on).
 *  2. `su -c "input keyevent 26"` — works on rooted units, the other common case.
 *  3. quick-dim: brightness → minimum + a 5 s screen timeout. The panel actually sleeps through its
 *     normal timeout, so the effect is the same and it needs no privileges at all.
 */
object ScreenOffHelper {

    private const val TAG = "ScreenOff"
    private var savedBrightness = -1
    private var savedTimeout = -1
    private var dimmed = false

    fun turnOff(ctx: Context) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            try {
                pm.javaClass.getMethod("goToSleep", Long::class.javaPrimitiveType)
                    .invoke(pm, System.uptimeMillis())
                undim(ctx)
                return
            } catch (t: Throwable) {
                Log.d(TAG, "goToSleep unavailable: ${t.message}")
            }
        }
        if (rootInput(ctx, 26)) {
            undim(ctx)
            return
        }
        quickDim(ctx)
        try {
            Toast.makeText(ctx, R.string.toast_screen_dim_instead, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    /** Dims to the floor and shortens the timeout so the panel sleeps within seconds. */
    fun quickDim(ctx: Context) {
        val p = LauncherPrefs.get(ctx)
        if (!dimmed) {
            savedBrightness = try {
                Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Throwable) {
                -1
            }
            savedTimeout = try {
                Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
            } catch (_: Throwable) {
                -1
            }
        }
        dimmed = true
        if (Settings.System.canWrite(ctx)) {
            try {
                Settings.System.putInt(
                    ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 2)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 3000)
            } catch (_: Throwable) {
            }
        }
        // Keep the "wake by music" behaviour useful while dimmed.
        if (p.wakeOnPlay) Log.d(TAG, "wake-on-play armed")
    }

    fun undim(ctx: Context) {
        if (!dimmed) return
        dimmed = false
        if (!Settings.System.canWrite(ctx)) return
        try {
            if (savedBrightness > 0) {
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, savedBrightness)
            }
            if (savedTimeout > 0) {
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, savedTimeout)
            }
        } catch (_: Throwable) {
        }
    }

    fun isDimmed(): Boolean = dimmed

    private fun rootInput(ctx: Context, keyCode: Int): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent $keyCode"))
        val code = p.waitFor()
        code == 0
    } catch (t: Throwable) {
        Log.d(TAG, "no su: ${t.message}")
        false
    }
}
