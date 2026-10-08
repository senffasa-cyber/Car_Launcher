package com.arena.carlauncher.theme

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.data.WeatherSnapshot
import com.arena.carlauncher.weather.WeatherRepository
import java.util.Calendar

/**
 * Day/night decision maker.
 *
 * Priority: manual override in the quick tiles → head-unit `uiMode` (the firmware switches this
 * with the parking lights on almost every SPRD box) → astronomical sunrise/sunset from the weather
 * feed → wall-clock fallback. Switching the palette must be instant while driving, therefore the
 * controller notifies [Palette] listeners instead of relying on an Activity recreate.
 */
object DayNightController {

    const val OVERRIDE_AUTO = 0
    const val OVERRIDE_DAY = 1
    const val OVERRIDE_NIGHT = 2

    private var app: Context? = null
    private var prefs: LauncherPrefs? = null
    private var lastNight: Boolean? = null

    fun install(application: Application) {
        app = application
        prefs = LauncherPrefs.get(application)
        apply(notifyOnly = true)
    }

    fun context(): Context? = app
    fun prefs(): LauncherPrefs? = prefs

    /** @return true when it should currently be dark. */
    fun shouldBeNight(ctx: Context): Boolean {
        val p = prefs ?: LauncherPrefs.get(ctx)
        return when (p.themeMode) {
            LauncherPrefs.THEME_LIGHT -> false
            LauncherPrefs.THEME_DARK, LauncherPrefs.THEME_AMOLED -> true
            else -> Palette.systemNight(ctx) || astronomicalNight(ctx)
        }
    }

    /**
     * Astronomical night: prefer real sunrise/sunset (with dusk/dawn offsets so the screen dims a
     * little before it actually gets dark), otherwise a fixed 19:30 → 06:30 window.
     */
    fun astronomicalNight(ctx: Context): Boolean {
        val w: WeatherSnapshot? = WeatherRepository.peek()
        val c = Calendar.getInstance()
        val minuteOfDay = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        if (w != null && w.valid && w.sunsetMin > w.sunriseMin) {
            return minuteOfDay < w.sunriseMin - 12 || minuteOfDay > w.sunsetMin + 12
        }
        return minuteOfDay >= 19 * 60 + 30 || minuteOfDay < 6 * 60 + 30
    }

    /** Called by the day/night quick tile. */
    fun toggleManual(ctx: Context) {
        val p = prefs ?: LauncherPrefs.get(ctx)
        p.themeMode = when (p.themeMode) {
            LauncherPrefs.THEME_LIGHT -> LauncherPrefs.THEME_DARK
            LauncherPrefs.THEME_DARK -> LauncherPrefs.THEME_AMOLED
            LauncherPrefs.THEME_AMOLED -> LauncherPrefs.THEME_AUTO
            else -> LauncherPrefs.THEME_LIGHT
        }
        apply()
    }

    fun setManual(mode: Int) {
        prefs?.themeMode = mode
        apply()
    }

    /** Re-evaluates the palette; recreates AppCompat themes only when the night flag really flips. */
    fun apply(notifyOnly: Boolean = false) {
        val ctx = app ?: return
        val night = shouldBeNight(ctx)
        val changed = lastNight != night
        lastNight = night
        Palette.refresh()
        if (notifyOnly || !changed) return
        try {
            AppCompatDelegate.setDefaultNightMode(
                if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        } catch (_: Throwable) {
        }
    }

    /** Timer hook — re-check sunrise/sunset every few minutes even with no other event. */
    fun tick() {
        val ctx = app ?: return
        if (lastNight != shouldBeNight(ctx)) apply()
    }
}
