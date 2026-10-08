package com.arena.carlauncher.weather

import android.content.Context
import android.util.Log
import com.arena.carlauncher.data.DayForecast
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.data.WeatherSnapshot
import com.arena.carlauncher.loc.LocationHub
import com.arena.carlauncher.map.HttpJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Current conditions from Open-Meteo.
 *
 * Chosen deliberately for a car launcher: no API key, no account, works in Iran, and it returns
 * `is_day` + sunrise/sunset which the day/night controller uses to dim the screen at dusk without
 * any light sensor (most head units do not expose one). Results are cached on disk for 20 minutes
 * and survive reboots, because a head unit's network is frequently absent for the first minute.
 */
object WeatherRepository {

    private const val TAG = "Weather"
    private const val CACHE_KEY = "weather_cache"
    private const val REFRESH_MS = 20L * 60L * 1000L
    private const val RETRY_FAIL_MS = 5L * 60L * 1000L

    private var app: Context? = null
    private var prefs: LauncherPrefs? = null
    private val _state = MutableStateFlow(WeatherSnapshot())
    val state = _state.asStateFlow()

    private var lastAttempt = 0L
    private var lastResultOk = false
    private var inFlight = false

    fun install(ctx: Context) {
        if (app == null) app = ctx.applicationContext
        prefs = LauncherPrefs.get(ctx)
        restore()
    }

    fun peek(): WeatherSnapshot? = _state.value.takeIf { it.valid }

    private fun restore() {
        val p = prefs ?: return
        val raw = p.get(CACHE_KEY, "")
        if (raw.isBlank()) return
        try {
            _state.value = parse(raw)
        } catch (_: Throwable) {
        }
    }

    fun maybeRefresh(force: Boolean = false) {
        val p = prefs ?: return
        val ctx = app ?: return
        if (!p.weatherEnabled) return
        if (inFlight) return
        val age = System.currentTimeMillis() - _state.value.updatedAt
        val staleAfter = if (lastResultOk) REFRESH_MS else RETRY_FAIL_MS
        if (!force && age < staleAfter) return
        if (System.currentTimeMillis() - lastAttempt < 30_000 && !force) return
        lastAttempt = System.currentTimeMillis()

        val loc = resolveLocation(p) ?: run {
            Log.i(TAG, "no location yet — weather waits for a GPS fix")
            return
        }
        inFlight = true
        Thread {
            val url = build(loc[0], loc[1])
            val body = try {
                HttpJson.get(ctx, url, null)
            } catch (_: Throwable) {
                null
            }
            inFlight = false
            if (body.isNullOrBlank()) {
                lastResultOk = false
                return@Thread
            }
            try {
                val snap = parse(body)
                lastResultOk = true
                _state.value = snap
                p.put(CACHE_KEY, body)
            } catch (t: Throwable) {
                lastResultOk = false
                Log.w(TAG, "parse failed: ${t.message}")
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun resolveLocation(p: LauncherPrefs): DoubleArray? {
        val manual = p.weatherManualLocation.trim()
        if (manual.isNotEmpty()) {
            val parts = manual.split(',', '،', ' ').filter { it.isNotBlank() }
            if (parts.size >= 2) {
                val a = parts[0].toDoubleOrNull()
                val b = parts[1].toDoubleOrNull()
                if (a != null && b != null) return doubleArrayOf(a, b)
            }
        }
        val fix = LocationHub.state.value
        return if (fix.hasFix) doubleArrayOf(fix.lat, fix.lon) else null
    }

    private fun build(lat: Double, lon: Double): String = StringBuilder(
        "https://api.open-meteo.com/v1/forecast"
    )
        .append("?latitude=").append(lat)
        .append("&longitude=").append(lon)
        .append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m")
        .append("&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max")
        .append("&timezone=auto&forecast_days=4&wind_speed_unit=kmh").toString()

    private fun parse(body: String): WeatherSnapshot {
        val root = JSONObject(body)
        val cur = root.optJSONObject("current") ?: JSONObject()
        val daily = root.optJSONObject("daily") ?: JSONObject()
        val list = ArrayList<DayForecast>(4)
        val codes = daily.optJSONArray("weather_code")
        val maxs = daily.optJSONArray("temperature_2m_max")
        val mins = daily.optJSONArray("temperature_2m_min")
        val rain = daily.optJSONArray("precipitation_probability_max")
        val n = codes?.length() ?: 0
        for (i in 0 until n) {
            list.add(
                DayForecast(
                    dayIndex = i,
                    maxC = maxs?.optDouble(i, 0.0)?.toFloat()?.toFloat() ?: 0f,
                    minC = mins?.optDouble(i, 0.0)?.toFloat() ?: 0f,
                    code = codes?.optInt(i, 0) ?: 0,
                    precipPct = rain?.optInt(i, 0) ?: 0
                )
            )
        }
        val sunrise = minutesOf(daily.optJSONArray("sunrise")?.optString(0))
        val sunset = minutesOf(daily.optJSONArray("sunset")?.optString(0))

        return WeatherSnapshot(
            tempC = cur.optDouble("temperature_2m", 0.0).toFloat(),
            feelsC = cur.optDouble("apparent_temperature", cur.optDouble("temperature_2m", 0.0)).toFloat(),
            code = cur.optInt("weather_code", 0),
            humidity = cur.optInt("relative_humidity_2m", 0),
            windKmh = cur.optDouble("wind_speed_10m", 0.0).toFloat(),
            isDay = cur.optInt("is_day", 1) == 1,
            sunriseMin = sunrise,
            sunsetMin = sunset,
            label = cur.optString("time", ""),
            daily = list,
            updatedAt = System.currentTimeMillis()
        )
    }

    /** "2026-10-08T06:31+03:30" → minutes since local midnight. */
    private fun minutesOf(iso: String?): Int {
        if (iso.isNullOrBlank() || iso.length < 16) return -1
        val h = iso.substring(11, 13).toIntOrNull() ?: return -1
        val m = iso.substring(14, 16).toIntOrNull() ?: return -1
        return h * 60 + m
    }

    /** WMO weather codes → icon name in `res/drawable`. */
    fun iconFor(code: Int): String = when (code) {
        0 -> "sun"
        1, 2 -> "sun_cloud"
        3 -> "cloud"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67 -> "rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "showers"
        85, 86 -> "snow"
        95, 96, 99 -> "storm"
        else -> "cloud"
    }

    fun labelFor(ctx: Context, code: Int): String {
        val resId = when (code) {
            0 -> com.arena.carlauncher.R.string.wx_clear
            1, 2 -> com.arena.carlauncher.R.string.wx_partly
            3 -> com.arena.carlauncher.R.string.wx_cloudy
            45, 48 -> com.arena.carlauncher.R.string.wx_fog
            in 51..57 -> com.arena.carlauncher.R.string.wx_drizzle
            in 61..67 -> com.arena.carlauncher.R.string.wx_rain
            in 71..77 -> com.arena.carlauncher.R.string.wx_snow
            in 80..82 -> com.arena.carlauncher.R.string.wx_showers
            85, 86 -> com.arena.carlauncher.R.string.wx_snow
            in 95..99 -> com.arena.carlauncher.R.string.wx_storm
            else -> com.arena.carlauncher.R.string.wx_cloudy
        }
        return ctx.getString(resId)
    }

    fun debugDump(): String =
        "wx ok=$lastResultOk t=${_state.value.tempC} code=${_state.value.code} age=${_state.value.ageMinutes}min"
}
