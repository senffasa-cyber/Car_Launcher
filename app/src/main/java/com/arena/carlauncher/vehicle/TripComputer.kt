package com.arena.carlauncher.vehicle

import android.content.Context
import android.os.SystemClock
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.data.TripStats
import com.arena.carlauncher.util.Jalali
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * integrates GPS/vehicle speed into a trip: distance, duration, average, top speed and idle time.
 *
 * There is no CAN odometer on most aftermarket units, so the launcher keeps its own totals, split
 * per calendar day (Persian date, because that is what a driver in Iran expects when asking "how far
 * did I drive today"). Integration is time-weighted, which stays accurate at 1 Hz updates.
 */
object TripComputer {

    private var prefs: LauncherPrefs? = null
    private val _state = MutableStateFlow(TripStats())
    val state = _state.asStateFlow()

    private var lastElapsed = 0L
    private var lastSpeed = 0f
    private var sessionStart = 0L
    private var idleMs = 0L
    private var maxSeen = 0f
    private var dayKey = ""

    /** Metres travelled in the current ignition session, kept in memory only. */
    private var liveMeters = 0f

    fun install(ctx: Context) {
        if (prefs == null) prefs = LauncherPrefs.get(ctx)
        dayKey = Jalali.today().iso
        sessionStart = SystemClock.elapsedRealtime()
        _state.value = TripStats(
            km = prefs?.tripOdometerKm ?: 0f,
            startedAt = System.currentTimeMillis(),
            avgKmh = 0f,
            maxKmh = 0f
        )
    }

    /** Called at ~1 Hz from [VehicleHub.merge]; safe to call faster. */
    fun tick(speedKmh: Float) {
        val p = prefs ?: return
        val now = SystemClock.elapsedRealtime()
        val dtMs = if (lastElapsed == 0L) 0L else (now - lastElapsed)
        lastElapsed = now
        val speed = if (speedKmh.isNaN()) 0f else speedKmh.coerceAtLeast(0f)
        lastSpeed = speed

        val today = Jalali.today().iso
        if (today != dayKey) {
            // Day rollover: archive what we have and start a fresh day counter.
            dayKey = today
            p.tripOdometerKm = 0f
            p.lastTripKm = _state.value.km
            liveMeters = 0f
        }

        if (dtMs > 0 && dtMs < 5000) {
            val hours = dtMs / 3600000.0
            // Average of both samples: smoother than either one alone.
            val avg = (speed + prevSpeed) / 2f
            liveMeters += (avg * hours * 1000.0).toFloat()
            if (avg > 2f) {
                maxSeen = max(maxSeen, avg)
            } else {
                idleMs += dtMs
            }
            prevSpeed = avg
            val minutes = ((now - sessionStart) / 60000L).toInt()
            val movingMin = ((minutes * 60000L - idleMs) / 60000L).coerceAtLeast(1L).toInt()
            val km = liveMeters / 1000f
            p.tripOdometerKm = km
            _state.value = TripStats(
                km = km,
                startedAt = sessionStart,
                durationMin = minutes,
                avgKmh = if (movingMin > 0) km * 60f / movingMin else 0f,
                maxKmh = maxSeen,
                idleMin = (idleMs / 60000L).toInt()
            )
        }
    }

    private var prevSpeed = 0f

    fun totalKm(): Float = _state.value.km

    fun reset() {
        val p = prefs ?: return
        p.lastTripKm = _state.value.km
        p.tripOdometerKm = 0f
        liveMeters = 0f
        maxSeen = 0f
        idleMs = 0L
        sessionStart = SystemClock.elapsedRealtime()
        lastElapsed = 0L
        prevSpeed = 0f
        _state.value = TripStats(km = 0f, startedAt = System.currentTimeMillis())
    }

    fun speedNow(): Float = lastSpeed
    fun debugDump(): String =
        "trip km=${_state.value.km} avg=${_state.value.avgKmh} max=${_state.value.maxKmh} idle=${_state.value.idleMin}min"
}
