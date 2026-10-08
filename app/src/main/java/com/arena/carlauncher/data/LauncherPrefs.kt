package com.arena.carlauncher.data

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import com.arena.carlauncher.map.TileSources
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for user configuration.
 *
 * A launcher re-reads its settings constantly (every card, every frame of the clock), so this is
 * a thin typed facade over [SharedPreferences] with an in-process change fan-out instead of any
 * persistence framework. All getters are cheap and safe to call from `onDraw`.
 */
class LauncherPrefs private constructor(private val sp: SharedPreferences) {

    private val listeners = ArrayList<() -> Unit>()

    // ---------------------------------------------------------------- theme
    var themeMode: Int
        get() = sp.getInt(K_THEME_MODE, THEME_AUTO)
        set(v) = put(K_THEME_MODE, v)

    var accentColor: Int
        get() = sp.getInt(K_ACCENT, DEFAULT_ACCENT)
        set(v) = put(K_ACCENT, v)

    var wallpaperUri: String?
        get() = sp.getString(K_WALLPAPER, null)
        set(v) = put(K_WALLPAPER, v)

    /** 0..80 — black scrim painted over the wallpaper so cards stay readable. */
    var wallpaperDim: Int
        get() = sp.getInt(K_WALLPAPER_DIM, 24)
        set(v) = put(K_WALLPAPER_DIM, v)

    var cornerRadiusDp: Int
        get() = sp.getInt(K_CORNER_RADIUS, 22)
        set(v) = put(K_CORNER_RADIUS, v)

    var cardAlphaPercent: Int
        get() = sp.getInt(K_CARD_ALPHA, 82)
        set(v) = put(K_CARD_ALPHA, v)

    var textScale: Float
        get() = sp.getFloat(K_TEXT_SCALE, 1.0f)
        set(v) = put(K_TEXT_SCALE, v)

    var usePersianDigits: Boolean
        get() = sp.getBoolean(K_FA_DIGITS, false)
        set(v) = put(K_FA_DIGITS, v)

    // ---------------------------------------------------------------- layout
    /**
     * Minimal boot: the three cards the launcher is *for*, no wallpaper, no side strips, no services.
     * [com.arena.carlauncher.util.CrashLog] turns it on after two starts that never finished building a
     * home screen; Settings → Debug can force it either way. The getters below honour it rather than
     * rewriting the saved lists, because a launcher that wipes your layout on the way past a crash is
     * worse than one that ignores it for a boot.
     */
    var safeMode: Boolean
        get() = sp.getBoolean(K_SAFE_MODE, false)
        set(v) = put(K_SAFE_MODE, v)

    /** One runtime prompt per install, so a denied dialog does not turn into a nag. */
    var locationAsked: Boolean
        get() = sp.getBoolean(K_LOCATION_ASKED, false)
        set(v) = put(K_LOCATION_ASKED, v)

    var centerCards: List<String>
        get() = if (safeMode) SAFE_CARDS else csv(K_CENTER_CARDS, DEFAULT_CENTER)
        set(v) = putCsv(K_CENTER_CARDS, v)

    var leftCards: List<String>
        get() = if (safeMode) EMPTY else csv(K_LEFT_CARDS, DEFAULT_LEFT)
        set(v) = putCsv(K_LEFT_CARDS, v)

    var rightCards: List<String>
        get() = if (safeMode) EMPTY else csv(K_RIGHT_CARDS, DEFAULT_RIGHT)
        set(v) = putCsv(K_RIGHT_CARDS, v)

    /** `pkg|/class` tokens, order matters. */
    var dockApps: List<String>
        get() = csv(K_DOCK_APPS, "")
        set(v) = putCsv(K_DOCK_APPS, v)

    var hiddenApps: List<String>
        get() = csv(K_HIDDEN_APPS, "")
        set(v) = putCsv(K_HIDDEN_APPS, v)

    var appGridColumns: Int
        get() = sp.getInt(K_GRID_COLUMNS, 0) // 0 = auto
        set(v) = put(K_GRID_COLUMNS, v)

    var dockSizeDp: Int
        get() = sp.getInt(K_DOCK_SIZE, 62)
        set(v) = put(K_DOCK_SIZE, v)

    var showStatusBarSpace: Boolean
        get() = sp.getBoolean(K_STATUS_GAP, false)
        set(v) = put(K_STATUS_GAP, v)

    // ---------------------------------------------------------------- behaviour
    var autoSwitchCenter: Boolean
        get() = sp.getBoolean(K_AUTO_SWITCH, true)
        set(v) = put(K_AUTO_SWITCH, v)

    /** How long a manual swipe wins over the automatic priority engine (seconds). */
    var manualOverrideSec: Int
        get() = sp.getInt(K_OVERRIDE_SEC, 25)
        set(v) = put(K_OVERRIDE_SEC, v)

    var keepScreenOn: Boolean
        get() = sp.getBoolean(K_KEEP_SCREEN_ON, true)
        set(v) = put(K_KEEP_SCREEN_ON, v)

    var immersive: Boolean
        get() = sp.getBoolean(K_IMMERSIVE, true)
        set(v) = put(K_IMMERSIVE, v)

    var bootStart: Boolean
        get() = sp.getBoolean(K_BOOT_START, true)
        set(v) = put(K_BOOT_START, v)

    var idleDimSec: Int
        get() = sp.getInt(K_IDLE_DIM, 0) // 0 = never
        set(v) = put(K_IDLE_DIM, v)

    var lockWhileDriving: Boolean
        get() = sp.getBoolean(K_DRIVE_LOCK, false)
        set(v) = put(K_DRIVE_LOCK, v)

    /** The in-call strip (`services/CallOverlayService`). Needs the overlay permission. */
    var callBanner: Boolean
        get() = sp.getBoolean(K_CALL_BANNER, true)
        set(v) = put(K_CALL_BANNER, v)

    var driveLockSpeedKmh: Int
        get() = sp.getInt(K_DRIVE_LOCK_SPEED, 8)
        set(v) = put(K_DRIVE_LOCK_SPEED, v)

    var wakeOnPlay: Boolean
        get() = sp.getBoolean(K_WAKE_ON_PLAY, false)
        set(v) = put(K_WAKE_ON_PLAY, v)

    var gestures: JSONObject
        get() = json(K_GESTURES, DEFAULT_GESTURES)
        set(v) = spEdit().putString(K_GESTURES, v.toString()).applyQuiet()

    // ---------------------------------------------------------------- media
    var mediaSessionPackage: String
        get() = sp.getString(K_MEDIA_PKG, "") ?: ""
        set(v) = put(K_MEDIA_PKG, v)

    var artworkBlur: Boolean
        get() = sp.getBoolean(K_ART_BLUR, false)
        set(v) = put(K_ART_BLUR, v)

    var showLyricsLine: Boolean
        get() = sp.getBoolean(K_LYRICS, true)
        set(v) = put(K_LYRICS, v)

    var pauseOnReverse: Boolean
        get() = sp.getBoolean(K_PAUSE_REVERSE, true)
        set(v) = put(K_PAUSE_REVERSE, v)

    var resumeOnUnlock: Boolean
        get() = sp.getBoolean(K_RESUME_PLAY, false)
        set(v) = put(K_RESUME_PLAY, v)

    // ---------------------------------------------------------------- navigation
    var navPackage: String
        get() = sp.getString(K_NAV_PKG, "") ?: ""
        set(v) = put(K_NAV_PKG, v)

    var tileSource: String
        get() = sp.getString(K_TILE_SOURCE, TileSources.AUTO) ?: TileSources.AUTO
        set(v) = put(K_TILE_SOURCE, v)

    var tileCustomUrl: String
        get() = sp.getString(K_TILE_CUSTOM, "") ?: ""
        set(v) = put(K_TILE_CUSTOM, v)

    var mapHeadingUp: Boolean
        get() = sp.getBoolean(K_MAP_HEADING, true)
        set(v) = put(K_MAP_HEADING, v)

    var mapAutoZoom: Boolean
        get() = sp.getBoolean(K_MAP_AUTOZOOM, true)
        set(v) = put(K_MAP_AUTOZOOM, v)

    var mapZoom: Int
        get() = sp.getInt(K_MAP_ZOOM, 15)
        set(v) = put(K_MAP_ZOOM, v)

    var mapTrafficOverlay: Boolean
        get() = sp.getBoolean(K_MAP_TRAFFIC, false)
        set(v) = put(K_MAP_TRAFFIC, v)

    var mapShowRoute: Boolean
        get() = sp.getBoolean(K_MAP_ROUTE, true)
        set(v) = put(K_MAP_ROUTE, v)

    var navOpenInSplitScreen: Boolean
        get() = sp.getBoolean(K_NAV_SPLIT, true)
        set(v) = put(K_NAV_SPLIT, v)

    /** Editable deep-link template per navigation app ({lat} {lng} {slat} {slon} {label}). */
    fun navTemplate(pkg: String): String = sp.getString("nav_template_$pkg", "") ?: ""
    fun setNavTemplate(pkg: String, value: String) = put("nav_template_$pkg", value.ifBlank { null })

    var homePlace: JSONObject?
        get() = optJson(K_HOME_PLACE)
        set(v) = putJson(K_HOME_PLACE, v)

    var workPlace: JSONObject?
        get() = optJson(K_WORK_PLACE)
        set(v) = putJson(K_WORK_PLACE, v)

    var recentPlaces: List<JSONObject>
        get() = jsonList(K_RECENT_PLACES)
        set(v) = putJsonList(K_RECENT_PLACES, v)

    // ---------------------------------------------------------------- vehicle
    var speedUnitMph: Boolean
        get() = sp.getBoolean(K_SPEED_MPH, false)
        set(v) = put(K_SPEED_MPH, v)

    var tempUnitF: Boolean
        get() = sp.getBoolean(K_TEMP_F, false)
        set(v) = put(K_TEMP_F, v)

    var speedSource: Int
        get() = sp.getInt(K_SPEED_SOURCE, SPEED_SRC_GPS)
        set(v) = put(K_SPEED_SOURCE, v)

    /** Broadcast that carries CAN data (many Unisoc/SPRD boxes rebroadcast the car bus). */
    var vehicleBroadcastAction: String
        get() = sp.getString(K_VEH_ACTION, "") ?: ""
        set(v) = put(K_VEH_ACTION, v)

    /** Extra name inside that broadcast holding speed in km/h. */
    var vehicleSpeedExtra: String
        get() = sp.getString(K_VEH_SPEED_EXTRA, "speed") ?: "speed"
        set(v) = put(K_VEH_SPEED_EXTRA, v)

    /** Extra name holding a JSON object with the full CAN snapshot. */
    var vehicleJsonExtra: String
        get() = sp.getString(K_VEH_JSON_EXTRA, "car_data") ?: "car_data"
        set(v) = put(K_VEH_JSON_EXTRA, v)

    var climateTiles: JSONArray
        get() = arr(K_CLIMATE_TILES, DEFAULT_CLIMATE_TILES)
        set(v) = spEdit().putString(K_CLIMATE_TILES, v.toString()).applyQuiet()

    var wheelKeyMusic: Boolean
        get() = sp.getBoolean(K_WHEEL_MUSIC, true)
        set(v) = put(K_WHEEL_MUSIC, v)

    // ---------------------------------------------------------------- misc
    var weatherEnabled: Boolean
        get() = sp.getBoolean(K_WEATHER, true)
        set(v) = put(K_WEATHER, v)

    var weatherManualLocation: String
        get() = sp.getString(K_WEATHER_LOC, "") ?: ""
        set(v) = put(K_WEATHER_LOC, v)

    var clock24h: Boolean
        get() = sp.getBoolean(K_CLOCK_24, true)
        set(v) = put(K_CLOCK_24, v)

    var showSeconds: Boolean
        get() = sp.getBoolean(K_CLOCK_SECONDS, false)
        set(v) = put(K_CLOCK_SECONDS, v)

    var showJalaliDate: Boolean
        get() = sp.getBoolean(K_JALALI, true)
        set(v) = put(K_JALALI, v)

    var showGregorianDate: Boolean
        get() = sp.getBoolean(K_GREGORIAN, false)
        set(v) = put(K_GREGORIAN, v)

    var analogClock: Boolean
        get() = sp.getBoolean(K_ANALOG, true)
        set(v) = put(K_ANALOG, v)

    var firstRunDone: Boolean
        get() = sp.getBoolean(K_FIRST_RUN, false)
        set(v) = put(K_FIRST_RUN, v)

    var tripOdometerKm: Float
        get() = sp.getFloat(K_TRIP_ODOMETER, 0f)
        set(v) = put(K_TRIP_ODOMETER, v)

    var lastTripKm: Float
        get() = sp.getFloat(K_TRIP_LAST, 0f)
        set(v) = put(K_TRIP_LAST, v)

    var debugLog: Boolean
        get() = sp.getBoolean(K_DEBUG, false)
        set(v) = put(K_DEBUG, v)

    // ---------------------------------------------------------------- plumbing
    fun put(key: String, value: Any?) = spEdit().let { e ->
        when (value) {
            null -> e.remove(key)
            is Int -> e.putInt(key, value)
            is Long -> e.putLong(key, value)
            is Float -> e.putFloat(key, value)
            is Boolean -> e.putBoolean(key, value)
            is String -> e.putString(key, value)
            else -> e.putString(key, value.toString())
        }
        e.applyQuiet()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, fallback: T): T = when (fallback) {
        is Int -> sp.getInt(key, fallback) as T
        is Boolean -> sp.getBoolean(key, fallback) as T
        is Float -> sp.getFloat(key, fallback) as T
        is Long -> sp.getLong(key, fallback) as T
        else -> sp.getString(key, fallback as? String) as T
    }

    private fun putCsv(key: String, values: List<String>) =
        put(key, TextUtils.join(",", values))

    private fun csv(key: String, defaults: String): List<String> {
        val raw = sp.getString(key, defaults) ?: defaults
        if (raw.isEmpty()) return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun putJson(key: String, value: JSONObject?) =
        put(key, value?.toString())

    private fun optJson(key: String): JSONObject? {
        val raw = sp.getString(key, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (_: Throwable) {
            null
        }
    }

    private fun putJsonList(key: String, values: List<JSONObject>) {
        val a = JSONArray()
        values.forEach { a.put(it) }
        put(key, a.toString())
    }

    private fun jsonList(key: String): List<JSONObject> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            ArrayList<JSONObject>(a.length()).also { out ->
                for (i in 0 until a.length()) {
                    a.optJSONObject(i)?.let { out.add(it) }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun json(key: String, defaults: String): JSONObject =
        try {
            JSONObject(sp.getString(key, defaults) ?: defaults)
        } catch (_: Throwable) {
            try {
                JSONObject(defaults)
            } catch (_: Throwable) {
                JSONObject()
            }
        }

    private fun arr(key: String, defaults: String): JSONArray =
        try {
            JSONArray(sp.getString(key, defaults) ?: defaults)
        } catch (_: Throwable) {
            try {
                JSONArray(defaults)
            } catch (_: Throwable) {
                JSONArray()
            }
        }

    private fun put(key: String, value: String?) = put(key, value as Any?)

    private fun spEdit() = sp.edit()

    private fun android.content.SharedPreferences.Editor.applyQuiet() {
        try {
            apply()
        } catch (_: Throwable) {
            commit()
        }
    }

    private var listening = false

    fun addChangeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
        if (!listening) {
            listening = true
            sp.registerOnSharedPreferenceChangeListener(internal)
        }
    }

    fun removeChangeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private val internal =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val snapshot = synchronized(listeners) { ArrayList(listeners) }
            snapshot.forEach { try { it() } catch (_: Throwable) {} }
        }

    /** Bulk edit helper used by presets. */
    fun edit(block: (android.content.SharedPreferences.Editor) -> Unit) {
        val e = sp.edit()
        block(e)
        e.applyQuiet()
    }

    fun exportJson(): String {
        val root = JSONObject()
        for ((k, v) in sp.all) {
            root.put(k, if (v is Number || v is Boolean || v is String) v else v.toString())
        }
        return root.toString(2)
    }

    fun importJson(text: String): Int {
        val o = JSONObject(text)
        val e = sp.edit()
        var n = 0
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            when (val v = o.get(k)) {
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Float, is Double -> e.putFloat(k, (v as Number).toFloat())
                else -> e.putString(k, v.toString())
            }
            n++
        }
        e.applyQuiet()
        return n
    }

    companion object {
        const val FILE = "car_launcher_prefs"

        const val THEME_AUTO = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val THEME_AMOLED = 3

        const val SPEED_SRC_GPS = 0
        const val SPEED_SRC_BROADCAST = 1
        const val SPEED_SRC_CAR_API = 2

        const val DEFAULT_ACCENT = 0xFF3DDC97.toInt()
        const val DEFAULT_CENTER = "music,map,clock,vehicle,weather"
        private val EMPTY = emptyList<String>()
        private val SAFE_CARDS = listOf(Cards.MUSIC, Cards.MAP, Cards.CLOCK)
        const val DEFAULT_LEFT = "tiles,nav"
        const val DEFAULT_RIGHT = "clock,trip"

        const val DEFAULT_GESTURES =
            """{"swipe_up":"recents","swipe_down":"notifications","swipe_left":"nav_next","swipe_right":"nav_prev","double_tap":"voice","long_press":"allapps"}"""
        const val DEFAULT_CLIMATE_TILES =
            """[{"label":"AC","action":"arena.car.CLIMATE","extra":"ac","icon":"snow"},{"label":"+","action":"arena.car.CLIMATE","extra":"temp_up","icon":"plus"},{"label":"-","action":"arena.car.CLIMATE","extra":"temp_down","icon":"minus"},{"label":"FAN+","action":"arena.car.CLIMATE","extra":"fan_up","icon":"fan"},{"label":"FAN-","action":"arena.car.CLIMATE","extra":"fan_down","icon":"fan"},{"label":"REC","action":"arena.car.CLIMATE","extra":"recirc","icon":"recirc"}]"""

        private const val K_THEME_MODE = "theme_mode"
        private const val K_ACCENT = "accent"
        private const val K_WALLPAPER = "wallpaper_uri"
        private const val K_WALLPAPER_DIM = "wallpaper_dim"
        private const val K_CORNER_RADIUS = "corner_radius"
        private const val K_CARD_ALPHA = "card_alpha"
        private const val K_TEXT_SCALE = "text_scale"
        private const val K_FA_DIGITS = "fa_digits"
        private const val K_CENTER_CARDS = "center_cards"
        private const val K_LEFT_CARDS = "left_cards"
        private const val K_RIGHT_CARDS = "right_cards"
        private const val K_DOCK_APPS = "dock_apps"
        private const val K_HIDDEN_APPS = "hidden_apps"
        private const val K_GRID_COLUMNS = "grid_columns"
        private const val K_DOCK_SIZE = "dock_size"
        private const val K_STATUS_GAP = "status_gap"
        private const val K_AUTO_SWITCH = "auto_switch"
        private const val K_OVERRIDE_SEC = "override_sec"
        private const val K_KEEP_SCREEN_ON = "keep_screen_on"
        private const val K_IMMERSIVE = "immersive"
        private const val K_BOOT_START = "boot_start"
        private const val K_IDLE_DIM = "idle_dim"
        private const val K_DRIVE_LOCK = "drive_lock"
        private const val K_DRIVE_LOCK_SPEED = "drive_lock_speed"
        private const val K_WAKE_ON_PLAY = "wake_on_play"
        private const val K_GESTURES = "gestures"
        private const val K_MEDIA_PKG = "media_pkg"
        private const val K_ART_BLUR = "art_blur"
        private const val K_LYRICS = "lyrics_line"
        private const val K_PAUSE_REVERSE = "pause_on_reverse"
        private const val K_RESUME_PLAY = "resume_on_play"
        private const val K_NAV_PKG = "nav_pkg"
        private const val K_TILE_SOURCE = "tile_source"
        private const val K_TILE_CUSTOM = "tile_custom"
        private const val K_MAP_HEADING = "map_heading"
        private const val K_MAP_AUTOZOOM = "map_autozoom"
        private const val K_MAP_ZOOM = "map_zoom"
        private const val K_MAP_TRAFFIC = "map_traffic"
        private const val K_MAP_ROUTE = "map_route"
        private const val K_NAV_SPLIT = "nav_split"
        private const val K_HOME_PLACE = "home_place"
        private const val K_WORK_PLACE = "work_place"
        private const val K_RECENT_PLACES = "recent_places"
        private const val K_SPEED_MPH = "speed_mph"
        private const val K_TEMP_F = "temp_f"
        private const val K_SPEED_SOURCE = "speed_source"
        private const val K_VEH_ACTION = "veh_action"
        private const val K_VEH_SPEED_EXTRA = "veh_speed_extra"
        private const val K_VEH_JSON_EXTRA = "veh_json_extra"
        private const val K_CLIMATE_TILES = "climate_tiles"
        private const val K_WHEEL_MUSIC = "wheel_music"
        private const val K_WEATHER = "weather_enabled"
        private const val K_WEATHER_LOC = "weather_loc"
        private const val K_CLOCK_24 = "clock24"
        private const val K_CLOCK_SECONDS = "clock_seconds"
        private const val K_JALALI = "jalali"
        private const val K_GREGORIAN = "gregorian"
        private const val K_ANALOG = "analog_clock"
        private const val K_FIRST_RUN = "first_run_done"
        private const val K_TRIP_ODOMETER = "trip_odometer"
        private const val K_TRIP_LAST = "trip_last"
        private const val K_DEBUG = "debug_log"
        private const val K_CALL_BANNER = "call_banner"
        private const val K_SAFE_MODE = "safe_mode"
        private const val K_LOCATION_ASKED = "location_asked"

        @Volatile
        private var instance: LauncherPrefs? = null

        fun get(ctx: Context): LauncherPrefs = instance ?: synchronized(this) {
            instance ?: LauncherPrefs(
                ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            ).also { instance = it }
        }

        /** Presets, mirroring `pref_presets` in the settings UI. */
        fun applyPreset(ctx: Context, preset: String) {
            val p = get(ctx)
            when (preset) {
                "agama" -> p.edit {
                    it.putString(K_CENTER_CARDS, "music,map,clock,vehicle,weather")
                    it.putString(K_LEFT_CARDS, "tiles,nav")
                    it.putString(K_RIGHT_CARDS, "clock,trip")
                    it.putInt(K_CORNER_RADIUS, 22)
                    it.putInt(K_CARD_ALPHA, 82)
                }
                "wide" -> p.edit {
                    it.putString(K_CENTER_CARDS, "map,music")
                    it.putString(K_LEFT_CARDS, "clock")
                    it.putString(K_RIGHT_CARDS, "vehicle,tiles")
                    it.putInt(K_CORNER_RADIUS, 12)
                    it.putInt(K_CARD_ALPHA, 92)
                }
                "minimal" -> p.edit {
                    it.putString(K_CENTER_CARDS, "clock,music")
                    it.putString(K_LEFT_CARDS, "")
                    it.putString(K_RIGHT_CARDS, "")
                    it.putInt(K_CORNER_RADIUS, 26)
                    it.putInt(K_CARD_ALPHA, 60)
                }
                "carplay" -> p.edit {
                    it.putString(K_CENTER_CARDS, "map,music,phone")
                    it.putString(K_LEFT_CARDS, "tiles")
                    it.putString(K_RIGHT_CARDS, "vehicle")
                    it.putInt(K_CORNER_RADIUS, 30)
                    it.putInt(K_CARD_ALPHA, 88)
                }
            }
        }
    }
}
