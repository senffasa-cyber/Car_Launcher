package com.arena.carlauncher.loc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.arena.carlauncher.data.LocationSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One GPS consumer for the whole launcher.
 *
 * Multiple `requestLocationUpdates` callers on a head unit drain the (often tiny) GPS power budget
 * and fight over the serial NMEA/GPS hal. This hub is therefore the only listener: it feeds the
 * speedometer, the trip computer, the map's "you are here" dot and the navigation auto-switch from
 * a single subscription, with a passive fallback for units whose GPS is provided by a BT receiver
 * or the car bus rather than `gps`.
 */
object LocationHub {

    private const val TAG = "LocationHub"
    private const val MIN_TIME_MS = 1000L
    private const val MIN_DISTANCE_M = 0f

    private var app: Context? = null
    private var manager: LocationManager? = null
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(LocationSnapshot())
    val state = _state.asStateFlow()

    var lastLocation: Location? = null
        private set

    private var started = false
    private var listening = false
    private var paused = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = update(location)

        @Deprecated("Kept so pre-API-30 firmware does not hit AbstractMethodError")
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = markStale()

        override fun onProviderEnabled(provider: String) {
            subscribe()
        }

        override fun onProviderDisabled(provider: String) = markStale()
    }

    fun install(ctx: Context) {
        if (app == null) app = ctx.applicationContext
        manager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        val ctx = app ?: return
        if (started) return
        started = true
        if (!hasPermission(ctx)) {
            Log.i(TAG, "no location permission — speedometer falls back to --")
            return
        }
        subscribe()
        seedFromLastKnown()
        main.postDelayed(staleCheck, 2000L)
        paused = false
    }

    fun stop() {
        if (!started) return
        started = false
        paused = false
        main.removeCallbacks(staleCheck)
        releaseUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun releaseUpdates() {
        try {
            if (listening) manager?.removeUpdates(listener)
        } catch (_: Throwable) {
        }
        listening = false
    }

    /**
     * Sampling off, hub alive. Called when the screen turns off: a parked car with the display asleep
     * has nothing to render, yet this was the launcher's one continuous hardware draw — a 1 Hz GPS
     * subscription burning milliamps (and, on these boards, keeping the GPS serial power rail up) for
     * nobody. The last fix is kept, so the cards still show a plausible position on wake, and
     * [markStale] takes care of the rest.
     */
    fun pauseForIdle() {
        if (!started || paused) return
        paused = true
        main.removeCallbacks(staleCheck)
        releaseUpdates()
    }

    fun resumeFromIdle() {
        if (!started || !paused) return
        paused = false
        subscribe()
        main.postDelayed(staleCheck, 1000L)
    }

    val pausedForIdle: Boolean get() = paused

    @SuppressLint("MissingPermission")
    private fun subscribe() {
        val mgr = manager ?: return
        val ctx = app ?: return
        if (!hasPermission(ctx)) return
        val providers = ArrayList<String>(3)
        try {
            if (mgr.allProviders?.contains(LocationManager.GPS_PROVIDER) == true) providers.add(LocationManager.GPS_PROVIDER)
            if (mgr.allProviders?.contains(LocationManager.NETWORK_PROVIDER) == true) providers.add(LocationManager.NETWORK_PROVIDER)
            if (mgr.allProviders?.contains(LocationManager.PASSIVE_PROVIDER) == true) providers.add(LocationManager.PASSIVE_PROVIDER)
        } catch (_: Throwable) {
        }
        for (p in providers) {
            try {
                mgr.requestLocationUpdates(p, MIN_TIME_MS, MIN_DISTANCE_M, listener)
                listening = true
            } catch (t: Throwable) {
                Log.d(TAG, "requestLocationUpdates($p) failed: ${t.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedFromLastKnown() {
        val mgr = manager ?: return
        val ctx = app ?: return
        if (!hasPermission(ctx)) return
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = mgr.getLastKnownLocation(p)
                if (l != null && (lastLocation == null || l.time > (lastLocation?.time ?: 0))) update(l)
            } catch (_: Throwable) {
            }
        }
    }

    private fun update(loc: Location) {
        lastLocation = loc
        val accuracy = try {
            if (loc.accuracy > 0f) loc.accuracy else 999f
        } catch (_: Throwable) {
            999f
        }
        val speed = try {
            if (loc.hasSpeed()) loc.speed * 3.6f else _state.value.speedKmh
        } catch (_: Throwable) {
            _state.value.speedKmh
        }
        val bearing = try {
            if (loc.hasBearing()) loc.bearing else _state.value.bearing
        } catch (_: Throwable) {
            _state.value.bearing
        }
        _state.value = LocationSnapshot(
            lat = loc.latitude,
            lon = loc.longitude,
            hasFix = true,
            speedKmh = speed,
            bearing = bearing,
            accuracyM = accuracy,
            altitudeM = loc.altitude.toFloat(),
            time = loc.time,
            provider = loc.provider ?: ""
        )
    }

    /** A lost fix must read as *stopped*: the drive-lock and the speedometer trust this value. */
    private fun markStale() {
        val cur = _state.value
        if (!cur.hasFix && cur.speedKmh == 0f) return
        _state.value = cur.copy(hasFix = false, speedKmh = 0f)
    }

    private val staleCheck = object : Runnable {
        override fun run() {
            val l = lastLocation
            if (l != null) {
                val ageMs = System.currentTimeMillis() - l.time
                if (ageMs in 1..8000) update(l) else markStale()
            }
            main.postDelayed(this, 1500L)
        }
    }

    /** Distance to a point in km — used by the Home/Work cards. */
    fun distanceKmTo(lat: Double, lon: Double): Float {
        val from = lastLocation ?: return -1f
        val out = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, lat, lon, out)
        return out[0] / 1000f
    }

    fun bearingTo(lat: Double, lon: Double): Float {
        val from = lastLocation ?: return 0f
        val out = FloatArray(2)
        Location.distanceBetween(from.latitude, from.longitude, lat, lon, out)
        return out[1]
    }

    fun debugDump(): String = "fix=${_state.value.hasFix} age=${_state.value.ageSec}s " +
        "speed=${_state.value.speedKmh}km/h acc=${_state.value.accuracyM}m provider=${_state.value.provider}"
}
