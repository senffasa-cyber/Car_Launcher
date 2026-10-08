package com.arena.carlauncher.data

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Card identifiers used in the layout CSVs in [LauncherPrefs]. */
object Cards {
    const val MUSIC = "music"
    const val MAP = "map"
    const val CLOCK = "clock"
    const val VEHICLE = "vehicle"
    const val WEATHER = "weather"
    const val TRIP = "trip"
    const val TILES = "tiles"
    const val NAV = "nav"
    const val PHONE = "phone"
    const val CAMERA = "camera"
    const val WIDGET = "widget"
    const val SETTINGS = "settings"

    val ALL = listOf(MUSIC, MAP, CLOCK, VEHICLE, WEATHER, TRIP, TILES, NAV, PHONE, CAMERA, WIDGET, SETTINGS)
    fun isKnown(id: String) = id in ALL
}

/** Which card the big centre panel should show right now. */
enum class CenterReason { MANUAL, MEDIA, NAVIGATION, IDLE, LOCKED, TRIP }

data class MediaSnapshot(
    val ownerPackage: String = "",
    val ownerLabel: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
    val hasSession: Boolean = false,
    val canSeek: Boolean = false,
    val canSkipNext: Boolean = true,
    val canSkipPrev: Boolean = true,
    val artwork: Bitmap? = null,
    val queueSize: Int = 0,
    val repeat: Int = 0,
    val shuffle: Boolean = false,
    val source: String = SOURCE_NONE,
    /** SystemClock.elapsedRealtime() at the moment [positionMs] was read. */
    val updatedAtElapsed: Long = 0,
    val playingSpeed: Float = 1f
) {
    val hasText: Boolean get() = title.isNotBlank()

    /** Progress is extrapolated in the view, so the bar glides at 60 fps with 1 Hz IPC. */
    fun positionNow(): Long =
        if (isPlaying && updatedAtElapsed > 0L)
            positionMs + ((android.os.SystemClock.elapsedRealtime() - updatedAtElapsed) * playingSpeed).toLong()
        else positionMs

    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionNow().toFloat() / durationMs).coerceIn(0f, 1f)
    val subtitle: String
        get() = listOf(album, ownerLabel).filter { it.isNotBlank() }.distinct().joinToString(" · ")

    /** "-2:31" countdown shown on the right end of the seek bar. */
    val remaining: String
        get() = if (durationMs <= 0L) ""
        else com.arena.carlauncher.util.Format.clock(
            (durationMs - (if (isPlaying) positionNow() else positionMs)).coerceAtLeast(0L)
        )

    companion object {
        const val SOURCE_NONE = "none"
        const val SOURCE_SESSION = "session"
        const val SOURCE_NOTIFICATION = "notification"
        val EMPTY = MediaSnapshot()
    }
}

data class LocationSnapshot(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val hasFix: Boolean = false,
    val speedKmh: Float = 0f,
    val bearing: Float = 0f,
    val accuracyM: Float = 0f,
    val altitudeM: Float = 0f,
    val time: Long = 0,
    val provider: String = "",
    val satellites: Int = -1
) {
    val isMoving: Boolean get() = hasFix && speedKmh > 3f
    val ageSec: Long get() = if (time == 0L) Long.MAX_VALUE else ((System.currentTimeMillis() - time) / 1000L)
}

data class VehicleSnapshot(
    val source: String = SOURCE_NONE,
    val speedKmh: Float? = null,
    val rpm: Float? = null,
    val fuelPct: Float? = null,
    val rangeKm: Float? = null,
    val coolantC: Float? = null,
    val oilBar: Float? = null,
    val odometerKm: Float? = null,
    val tires: FloatArray? = null, // FL, FR, RL, RR (kPa)
    val tireTemps: FloatArray? = null,
    val doors: Int = 0,            // bitmask: 1 FL 2 FR 4 RL 8 RR 16 trunk 32 hood
    val lightsOn: Boolean = false,
    val batteryPct: Float? = null,
    val extTempC: Float? = null,
    val ac: Int = 0,
    val fanLevel: Int = -1,
    val targetC: Float? = null,
    val reverse: Boolean = false,
    val updatedAt: Long = 0
) {
    val hasData: Boolean
        get() = speedKmh != null || rpm != null || fuelPct != null || coolantC != null || tires != null || doors != 0
    val doorCount: Int get() = Integer.bitCount(doors)

    companion object {
        const val SOURCE_NONE = "none"
        const val SOURCE_GPS = "gps"
        const val SOURCE_BROADCAST = "broadcast"
        const val SOURCE_CAR_API = "carapi"
        const val DOOR_FL = 1
        const val DOOR_FR = 2
        const val DOOR_RL = 4
        const val DOOR_RR = 8
        const val DOOR_TRUNK = 16
        const val DOOR_HOOD = 32
    }
}

data class DayForecast(val dayIndex: Int, val maxC: Float, val minC: Float, val code: Int, val precipPct: Int)

data class WeatherSnapshot(
    val tempC: Float = 0f,
    val feelsC: Float = 0f,
    val code: Int = 0,
    val humidity: Int = 0,
    val windKmh: Float = 0f,
    val isDay: Boolean = true,
    val sunriseMin: Int = 6 * 60 + 30,
    val sunsetMin: Int = 18 * 60 + 30,
    val label: String = "",
    val daily: List<DayForecast> = emptyList(),
    val updatedAt: Long = 0
) {
    val valid: Boolean get() = updatedAt > 0
    val ageMinutes: Int get() = if (updatedAt == 0L) Int.MAX_VALUE else ((System.currentTimeMillis() - updatedAt) / 60000L).toInt()
}

/** A geocoded point: used for Home/Work/recent/search results. */
data class Place(
    val label: String,
    val lat: Double,
    val lon: Double,
    val address: String = "",
    val source: String = "search"
) {
    fun toJson() = org.json.JSONObject().apply {
        put("label", label); put("lat", lat); put("lon", lon); put("address", address); put("src", source)
    }

    companion object {
        fun fromJson(o: org.json.JSONObject?): Place? {
            o ?: return null
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return null
            return Place(
                label = o.optString("label", o.optString("name", "")),
                lat = lat,
                lon = lon,
                address = o.optString("address", o.optString("display_name", "")),
                source = o.optString("src", "json")
            )
        }
    }
}

data class NavAppInfo(
    val packageName: String,
    val label: String,
    val supportsGeo: Boolean,
    val directionsUrlTemplate: String? = null
) {
    val isGoogleMaps: Boolean get() = packageName == "com.google.android.apps.maps"
    val isWaze: Boolean get() = packageName == "com.waze"
}

data class TripStats(
    val km: Float = 0f,
    val startedAt: Long = 0,
    val durationMin: Int = 0,
    val avgKmh: Float = 0f,
    val maxKmh: Float = 0f,
    val idleMin: Int = 0
)

data class TileConfig(
    val id: String,
    val label: String,
    val icon: String,
    val action: String,
    val extra: String,
    val packageName: String = ""
) {
    companion object {
        fun fromJson(o: org.json.JSONObject): TileConfig = TileConfig(
            id = o.optString("id", o.optString("label", "tile")),
            label = o.optString("label", ""),
            icon = o.optString("icon", "dot"),
            action = o.optString("action", ""),
            extra = o.optString("extra", ""),
            packageName = o.optString("package", "")
        )
    }
}
