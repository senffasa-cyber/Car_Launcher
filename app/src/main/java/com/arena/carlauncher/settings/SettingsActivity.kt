package com.arena.carlauncher.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.FileProvider
import com.arena.carlauncher.R
import com.arena.carlauncher.data.AppRepository
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.map.MapEngine
import com.arena.carlauncher.map.NavAppRepository
import com.arena.carlauncher.map.PlacePicker
import com.arena.carlauncher.map.TileSources
import com.arena.carlauncher.media.MediaHub
import com.arena.carlauncher.permission.PermissionHub
import com.arena.carlauncher.services.LauncherService
import com.arena.carlauncher.theme.DayNightController
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.ui.Views
import com.arena.carlauncher.util.Format
import com.arena.carlauncher.vehicle.VehicleHub
import com.arena.carlauncher.weather.WeatherRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The settings screen: a section strip on the left, one scrollable column of rows on the right.
 *
 * Two rules that come from the device, not from taste. First, every control is inline — a dialog on a
 * 1208x720 panel behind a keyboard is unusable with gloves, so choices are chips and numbers are fields
 * with an apply button. Second, nothing here requires restarting the launcher: each write goes straight
 * to [LauncherPrefs], whose listeners repaint the home screen, so the user can keep the settings open
 * next to the launcher (split screen) and watch the change land.
 */
class SettingsActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var content: LinearLayout
    private lateinit var tabs: LinearLayout
    private var section = SECTION_APPEARANCE

    override fun onCreate(savedInstanceState: Bundle?) {
        DayNightController.install(application)
        prefs = LauncherPrefs.get(this)
        super.onCreate(savedInstanceState)
        section = intent?.getStringExtra(EXTRA_SECTION)?.let { sectionForKey(it) } ?: SECTION_APPEARANCE
        buildShell()
        show()
    }

    private fun sectionForKey(key: String): Int = when (key) {
        "layout" -> SECTION_LAYOUT
        "media" -> SECTION_MEDIA
        "map" -> SECTION_MAP
        "vehicle" -> SECTION_VEHICLE
        "clock" -> SECTION_CLOCK
        "behaviour" -> SECTION_BEHAVIOUR
        "permissions" -> SECTION_PERMISSIONS
        "debug" -> SECTION_DEBUG
        else -> SECTION_APPEARANCE
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.colors.background)
        }
        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Views.dp(this@SettingsActivity, 10f), Views.dp(this@SettingsActivity, 10f), 0, Views.dp(this@SettingsActivity, 10f))
        }
        SECTION_KEYS.forEachIndexed { i, key ->
            val sel = i == section
            tabs.addView(
                Views.text(
                    this, getString(labelFor(key)), 13.5f,
                    if (sel) Palette.colors.onAccent else Palette.colors.onSurface, bold = sel
                ).apply {
                    background = Palette.chip(this@SettingsActivity, sel)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Views.dp(this@SettingsActivity, 14f), Views.dp(this@SettingsActivity, 12f), Views.dp(this@SettingsActivity, 14f), Views.dp(this@SettingsActivity, 12f))
                    isClickable = true
                    setOnClickListener {
                        section = i
                        show()
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Views.dp(this@SettingsActivity, 5f) }
            )
        }
        root.addView(tabs, LinearLayout.LayoutParams(Views.dp(this, 190f), LinearLayout.LayoutParams.MATCH_PARENT))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(Views.dp(this@SettingsActivity, 10f), Views.dp(this@SettingsActivity, 10f), Views.dp(this@SettingsActivity, 10f), Views.dp(this@SettingsActivity, 16f))
        }
        content = Views.column(this, paddingDp = 0)
        scroll.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)
    }

    private fun labelFor(key: String) = when (key) {
        "appearance" -> R.string.settings_appearance
        "layout" -> R.string.settings_layout
        "media" -> R.string.settings_media
        "map" -> R.string.settings_map
        "vehicle" -> R.string.settings_vehicle
        "clock" -> R.string.settings_clock
        "behaviour" -> R.string.settings_behaviour
        "permissions" -> R.string.settings_permissions
        else -> R.string.settings_debug
    }

    @SuppressLint("SetTextI18n")
    private fun show() {
        content.removeAllViews()
        val rows: List<View> = when (SECTION_KEYS[section]) {
            "appearance" -> appearance()
            "layout" -> layout()
            "media" -> media()
            "map" -> mapAndNav()
            "vehicle" -> vehicle()
            "clock" -> clockWeather()
            "behaviour" -> behaviour()
            "permissions" -> permissions()
            else -> debug()
        }
        rows.forEachIndexed { i, v ->
            content.addView(v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) topMargin = Views.dp(this@SettingsActivity, if (v.tag == "section") 2f else 6f) })
        }
        // Re-tint the tab strip after a palette flip.
        tabs.invalidate()
    }

    private fun row(v: View) {
        content.addView(v, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Views.dp(this@SettingsActivity, 6f) })
    }

    // ------------------------------------------------------------------ sections

    private fun appearance(): List<View> {
        val out = ArrayList<View>()
        out.add(sectionHeader(R.string.settings_appearance, getString(R.string.appearance_note)))
        out.add(
            PrefRows.choice(
                this, getString(R.string.pref_theme), getString(R.string.pref_theme_sum),
                listOf(
                    getString(R.string.theme_auto), getString(R.string.theme_day),
                    getString(R.string.theme_night), getString(R.string.theme_amoled)
                ),
                prefs.themeMode
            ) {
                prefs.themeMode = it
                DayNightController.setManual(it)
                show()
            }
        )
        out.add(
            PrefRows.choice(
                this, getString(R.string.pref_accent), getString(R.string.pref_accent_sum),
                ACCENT_NAMES, accentIndex(prefs.accentColor)
            ) {
                prefs.accentColor = ACCENTS[it]
                Palette.refresh()
                show()
            }
        )
        out.add(
            PrefRows.slider(this, getString(R.string.pref_corner), "", prefs.cornerRadiusDp, 0, 40, 1, "dp") {
                prefs.cornerRadiusDp = it
            }
        )
        out.add(
            PrefRows.slider(this, getString(R.string.pref_card_alpha), "", prefs.cardAlphaPercent, 30, 100, 2, "%") {
                prefs.cardAlphaPercent = it
            }
        )
        out.add(
            PrefRows.slider(this, getString(R.string.pref_dim), getString(R.string.pref_dim_sum), prefs.wallpaperDim, 0, 80, 2, "%") {
                prefs.wallpaperDim = it
            }
        )
        out.add(
            PrefRows.slider(this, getString(R.string.pref_text_scale), getString(R.string.pref_text_scale_sum),
                (prefs.textScale * 100).toInt(), 80, 140, 5, "%") {
                prefs.textScale = it / 100f
                recreateContent()
            }
        )
        out.add(
            PrefRows.switchRow(this, getString(R.string.pref_fa_digits), getString(R.string.pref_fa_digits_sum), prefs.usePersianDigits) {
                prefs.usePersianDigits = it
                show()
            }
        )
        out.add(
            PrefRows.action(this, getString(R.string.pref_wallpaper), prefs.wallpaperUri ?: getString(R.string.wallpaper_none), R.drawable.ic_wallpaper) {
                pickWallpaper()
            }
        )
        if (!prefs.wallpaperUri.isNullOrBlank()) {
            out.add(PrefRows.action(this, getString(R.string.wallpaper_remove), "") {
                prefs.wallpaperUri = null
                show()
            })
        }
        return out
    }

    private fun layout(): List<View> {
        val out = ArrayList<View>()
        out.add(sectionHeader(R.string.settings_layout, getString(R.string.layout_note)))
        out.add(
            PrefRows.choice(this, getString(R.string.pref_preset), getString(R.string.pref_preset_sum),
                PRESET_NAMES, PRESET_KEYS.indexOf(prefs.get("preset", ""))) {
                LauncherPrefs.applyPreset(this, PRESET_KEYS[it])
                prefs.put("preset", PRESET_KEYS[it])
                show()
            }
        )
        out.add(cardPicker(getString(R.string.pref_center_cards), getString(R.string.pref_center_cards_sum), SLOT_CENTER))
        out.add(cardPicker(getString(R.string.pref_left_cards), getString(R.string.pref_left_cards_sum), SLOT_LEFT))
        out.add(cardPicker(getString(R.string.pref_right_cards), getString(R.string.pref_right_cards_sum), SLOT_RIGHT))
        out.add(
            PrefRows.slider(this, getString(R.string.pref_dock_size), getString(R.string.pref_dock_size_sum),
                prefs.dockSizeDp, 40, 88, 2, "dp") { prefs.dockSizeDp = it }
        )
        out.add(
            PrefRows.choice(this, getString(R.string.pref_grid_columns), "",
                listOf(getString(R.string.auto), "5", "6", "7", "8"),
                when (prefs.appGridColumns) { 0 -> 0; 5 -> 1; 6 -> 2; 7 -> 3; else -> 4 }) {
                prefs.appGridColumns = when (it) { 0 -> 0; 1 -> 5; 2 -> 6; 3 -> 7; else -> 8 }
                show()
            }
        )
        out.add(PrefRows.action(this, getString(R.string.pref_dock_edit), getString(R.string.pref_dock_edit_sum), R.drawable.ic_apps) {
            openAppPicker(PICK_DOCK)
        })
        out.add(PrefRows.action(this, getString(R.string.pref_hidden_apps), getString(R.string.pref_hidden_apps_sum, prefs.hiddenApps.size), R.drawable.ic_close) {
            openAppPicker(PICK_HIDE)
        })
        return out
    }

    private fun media(): List<View> {
        val out = ArrayList<View>()
        out.add(sectionHeader(R.string.settings_media, getString(R.string.media_note)))
        val sources = MediaHub.availableSources(this)
        val labels = ArrayList<String>()
        labels.add(getString(R.string.media_source_auto))
        sources.forEach { labels.add(it.second.ifBlank { it.first }) }
        val chosen = if (prefs.mediaSessionPackage.isBlank()) 0
        else sources.indexOfFirst { it.first == prefs.mediaSessionPackage } + 1
        out.add(PrefRows.choice(this, getString(R.string.pref_media_source), getString(R.string.pref_media_source_sum), labels, chosen.coerceAtLeast(0)) { i ->
            if (i == 0) prefs.mediaSessionPackage = ""
            else sources.getOrNull(i - 1)?.let { prefs.mediaSessionPackage = it.first }
            MediaHub.fetchSessions(this, force = true)
            show()
        })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_blur_artwork), "", prefs.artworkBlur) { prefs.artworkBlur = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_lyrics_line), getString(R.string.pref_lyrics_line_sum), prefs.showLyricsLine) { prefs.showLyricsLine = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_wake_on_play), getString(R.string.pref_wake_on_play_sum), prefs.wakeOnPlay) { prefs.wakeOnPlay = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_pause_reverse), getString(R.string.pref_pause_reverse_sum), prefs.pauseOnReverse) { prefs.pauseOnReverse = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_wheel_play), getString(R.string.pref_wheel_play_sum), prefs.wheelKeyMusic) { prefs.wheelKeyMusic = it })
        out.add(
            PrefRows.action(
                this, getString(R.string.pref_notification_access),
                if (MediaHub.notificationAccessGranted(this)) getString(R.string.perm_granted) else getString(R.string.perm_needed),
                R.drawable.ic_check
            ) { MediaHub.openNotificationAccessSettings(this) }
        )
        return out
    }

    private fun mapAndNav(): List<View> {
        val out = ArrayList<View>()
        out.add(sectionHeader(R.string.settings_map, getString(R.string.map_note)))
        val ids = ArrayList<String>()
        val labels = ArrayList<String>()
        ids.add(TileSources.AUTO)
        labels.add(getString(R.string.tile_auto))
        TileSources.ALL.forEach { ids.add(it.id); labels.add(it.label) }
        ids.add(TileSources.CUSTOM)
        labels.add(getString(R.string.tile_custom))
        out.add(PrefRows.choice(this, getString(R.string.pref_tile_source), getString(R.string.pref_tile_source_sum), labels, ids.indexOf(prefs.tileSource).coerceAtLeast(0)) { i ->
            prefs.tileSource = ids[i]
            MapEngine.refreshSource()
            show()
        })
        if (prefs.tileSource == TileSources.CUSTOM) {
            out.add(PrefRows.text(this, getString(R.string.pref_tile_url), getString(R.string.pref_tile_url_sum), prefs.tileCustomUrl, InputType.TYPE_CLASS_TEXT) {
                prefs.tileCustomUrl = it
                MapEngine.refreshSource()
            })
        }
        out.add(PrefRows.switchRow(this, getString(R.string.pref_heading_up), getString(R.string.pref_heading_up_sum), prefs.mapHeadingUp) { prefs.mapHeadingUp = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_auto_zoom), "", prefs.mapAutoZoom) { prefs.mapAutoZoom = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_show_route), getString(R.string.pref_show_route_sum), prefs.mapShowRoute) { prefs.mapShowRoute = it })
        out.add(PrefRows.slider(this, getString(R.string.pref_zoom), "", prefs.mapZoom, 3, 19, 1) { prefs.mapZoom = it })
        val navs = NavAppRepository.apps(true)
        val navLabels = ArrayList<String>()
        navLabels.add(getString(R.string.nav_any))
        navs.forEach { navLabels.add(it.label) }
        out.add(PrefRows.choice(this, getString(R.string.pref_nav_app), getString(R.string.pref_nav_app_sum, navs.size), navLabels, navLabels.indexOf(NavAppRepository.preferred()?.label ?: "")) { i ->
            NavAppRepository.setPreferred(if (i == 0) "" else navs[i - 1].packageName)
            show()
        })
        NavAppRepository.preferred()?.let { app ->
            out.add(
                PrefRows.text(
                    this, getString(R.string.pref_nav_template, app.label),
                    getString(R.string.pref_nav_template_sum),
                    prefs.navTemplate(app.packageName).ifBlank { app.directionsUrlTemplate ?: "" },
                    InputType.TYPE_CLASS_TEXT
                ) {
                    prefs.setNavTemplate(app.packageName, it)
                    NavAppRepository.invalidate()
                }
            )
        }
        out.add(PrefRows.switchRow(this, getString(R.string.pref_split_nav), getString(R.string.pref_split_nav_sum), prefs.navOpenInSplitScreen) { prefs.navOpenInSplitScreen = it })
        out.add(PrefRows.action(this, getString(R.string.pref_home_place), PlacePicker.home(this)?.label ?: getString(R.string.place_not_set), R.drawable.ic_home) {
            PlacePicker.open(this, PlacePicker.TARGET_HOME)
        })
        out.add(PrefRows.action(this, getString(R.string.pref_work_place), PlacePicker.work(this)?.label ?: getString(R.string.place_not_set), R.drawable.ic_work) {
            PlacePicker.open(this, PlacePicker.TARGET_WORK)
        })
        out.add(PrefRows.action(this, getString(R.string.pref_clear_tiles), getString(R.string.pref_clear_tiles_sum, cacheSizeText()), R.drawable.ic_trash) {
            MapEngine.clearCaches()
            show()
        })
        return out
    }

    private fun vehicle(): List<View> {
        val out = ArrayList<View>()
        out.add(sectionHeader(R.string.settings_vehicle, getString(R.string.vehicle_note)))
        out.add(
            PrefRows.choice(this, getString(R.string.pref_speed_source), getString(R.string.pref_speed_source_sum),
                listOf(
                    getString(R.string.src_gps),
                    getString(R.string.src_broadcast) + (if (VehicleHub.isSourceAvailable(LauncherPrefs.SPEED_SRC_BROADCAST)) " ✓" else ""),
                    getString(R.string.src_carapi) + (if (VehicleHub.isSourceAvailable(LauncherPrefs.SPEED_SRC_CAR_API)) " ✓" else "")
                ),
                prefs.speedSource) {
                prefs.speedSource = it
                VehicleHub.refresh()
                show()
            }
        )
        out.add(PrefRows.switchRow(this, getString(R.string.pref_mph), "", prefs.speedUnitMph) { prefs.speedUnitMph = it })
        out.add(PrefRows.text(this, getString(R.string.pref_bus_action), getString(R.string.pref_bus_action_sum), prefs.vehicleBroadcastAction, InputType.TYPE_CLASS_TEXT) {
            prefs.vehicleBroadcastAction = it
            LauncherService.start(this, "bus-action")
            show()
        })
        out.add(PrefRows.text(this, getString(R.string.pref_bus_speed_extra), getString(R.string.pref_bus_speed_extra_sum), prefs.vehicleSpeedExtra, InputType.TYPE_CLASS_TEXT) {
            prefs.vehicleSpeedExtra = it
        })
        out.add(PrefRows.text(this, getString(R.string.pref_bus_json_extra), getString(R.string.pref_bus_json_extra_sum), prefs.vehicleJsonExtra, InputType.TYPE_CLASS_TEXT) {
            prefs.vehicleJsonExtra = it
        })
        out.add(PrefRows.text(this, getString(R.string.pref_climate_tiles), getString(R.string.pref_climate_tiles_sum), prefs.climateTiles.toString()) { raw ->
            val arr = try {
                JSONArray(raw)
            } catch (t: Throwable) {
                Views.toast(this, R.string.pref_json_bad)
                null
            }
            if (arr != null) {
                prefs.climateTiles = arr
                show()
            }
        })
        out.add(PrefRows.action(this, getString(R.string.pref_test_bus), getString(R.string.pref_test_bus_sum, VehicleHub.debugDump().replace('\n', ' ')), R.drawable.ic_refresh) {
            VehicleHub.refresh()
            Views.toast(this, R.string.veh_refreshed)
            show()
        })
        return out
    }

    private fun clockWeather(): List<View> {
        val out = ArrayList<View>()
        out.add(sectionHeader(R.string.settings_clock, getString(R.string.clock_note)))
        out.add(PrefRows.switchRow(this, getString(R.string.pref_clock_24), "", prefs.clock24h) { prefs.clock24h = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_seconds), "", prefs.showSeconds) { prefs.showSeconds = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_jalali), "", prefs.showJalaliDate) { prefs.showJalaliDate = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_gregorian), "", prefs.showGregorianDate) { prefs.showGregorianDate = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_analog), getString(R.string.pref_analog_sum), prefs.analogClock) { prefs.analogClock = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_weather), "", prefs.weatherEnabled) {
            prefs.weatherEnabled = it
            WeatherRepository.maybeRefresh(true)
        })
        out.add(PrefRows.choice(this, getString(R.string.pref_temp_unit), "", listOf("°C", "°F"), if (prefs.tempUnitF) 1 else 0) {
            prefs.tempUnitF = it == 1
            show()
        })
        out.add(PrefRows.text(this, getString(R.string.pref_manual_location), getString(R.string.pref_manual_location_sum), prefs.weatherManualLocation, InputType.TYPE_CLASS_TEXT) {
            prefs.weatherManualLocation = it
            WeatherRepository.maybeRefresh(true)
        })
        out.add(PrefRows.action(this, getString(R.string.pref_trip_reset), getString(R.string.pref_trip_reset_sum, Format.localized(this, String.format(java.util.Locale.US, "%.1f", prefs.tripOdometerKm))), R.drawable.ic_reset) {
            com.arena.carlauncher.vehicle.TripComputer.reset()
            show()
        })
        return out
    }

    private fun behaviour(): List<View> {
        val out = ArrayList<View>()
        out.add(sectionHeader(R.string.settings_behaviour, getString(R.string.behaviour_note)))
        out.add(PrefRows.switchRow(this, getString(R.string.pref_auto_center), getString(R.string.pref_auto_center_sum), prefs.autoSwitchCenter) { prefs.autoSwitchCenter = it })
        out.add(PrefRows.slider(this, getString(R.string.pref_override), getString(R.string.pref_override_sum), prefs.manualOverrideSec, 5, 180, 5, "s") {
            prefs.manualOverrideSec = it
        })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_keep_screen_on), "", prefs.keepScreenOn) { prefs.keepScreenOn = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_immersive), getString(R.string.pref_immersive_sum), prefs.immersive) { prefs.immersive = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_status_space), "", prefs.showStatusBarSpace) { prefs.showStatusBarSpace = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_boot), getString(R.string.pref_boot_sum), prefs.bootStart) { prefs.bootStart = it })
        out.add(PrefRows.number(this, getString(R.string.pref_idle_dim), getString(R.string.pref_idle_dim_sum), prefs.idleDimSec.toFloat(), 0, 0f, 900f, getString(R.string.seconds)) {
            prefs.idleDimSec = it.toInt()
        })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_drive_lock), getString(R.string.pref_drive_lock_sum), prefs.lockWhileDriving) { prefs.lockWhileDriving = it })
        out.add(PrefRows.slider(this, getString(R.string.pref_drive_lock_speed), "", prefs.driveLockSpeedKmh, 0, 60, 5, "km/h") { prefs.driveLockSpeedKmh = it })
        out.add(PrefRows.switchRow(this, getString(R.string.pref_call_banner), getString(R.string.pref_call_banner_sum), prefs.callBanner) {
            prefs.callBanner = it
            if (it) com.arena.carlauncher.services.CallOverlayService.ensureRunning(this)
            else stopService(Intent(this, com.arena.carlauncher.services.CallOverlayService::class.java))
        })
        out.add(PrefRows.text(this, getString(R.string.pref_gestures), getString(R.string.pref_gestures_sum), prefs.gestures.toString()) { raw ->
            val o = try {
                JSONObject(raw)
            } catch (t: Throwable) {
                Views.toast(this, R.string.pref_json_bad)
                null
            }
            if (o != null) {
                prefs.gestures = o
                show()
            }
        })
        out.add(PrefRows.action(this, getString(R.string.pref_default_home), getString(R.string.pref_default_home_sum), R.drawable.ic_home) {
            askDefaultHome()
        })
        return out
    }

    private fun permissions(): List<View> {
        val out = ArrayList<View>()
        out.add(sectionHeader(R.string.settings_permissions, getString(R.string.permissions_note)))
        PermissionHub.status(this).forEach { item ->
            out.add(
                PrefRows.action(
                    this, item.title,
                    (if (item.granted) getString(R.string.perm_granted) else getString(R.string.perm_needed)) +
                        (if (item.detail.isBlank()) "" else "\n${item.detail}"),
                    if (item.granted) R.drawable.ic_check else R.drawable.ic_warning
                ) {
                    item.request(this)
                }
            )
        }
        return out
    }

    private fun debug(): List<View> {
        val out = ArrayList<View>()
        out.add(sectionHeader(R.string.settings_debug, getString(R.string.debug_note)))
        out.add(PrefRows.switchRow(this, getString(R.string.pref_debug_log), "", prefs.debugLog) { prefs.debugLog = it })
        out.add(PrefRows.action(this, getString(R.string.pref_dump), "", R.drawable.ic_info) {
            Views.toast(this, R.string.debug_dump_shown)
            LogSheet.show(this, dumpText())
        })
        out.add(PrefRows.action(this, getString(R.string.pref_export), getString(R.string.pref_export_sum), R.drawable.ic_arrow_up) {
            exportSettings()
        })
        out.add(PrefRows.action(this, getString(R.string.pref_restart_service), "", R.drawable.ic_refresh) {
            LauncherService.start(this, "manual-restart")
            Views.toast(this, R.string.debug_service_restarted)
        })
        return out
    }

    private fun dumpText(): String = buildString {
        appendLine("launcher ${versionName()}")
        appendLine(CenterCardDump())
        appendLine(LocationDump())
        appendLine(VehicleHub.debugDump())
        appendLine(MapEngine.debugDump())
        appendLine(com.arena.carlauncher.vehicle.TripComputer.debugDump())
        appendLine(com.arena.carlauncher.home.ForegroundAppWatcher.debugDump())
    }

    private fun CenterCardDump(): String = com.arena.carlauncher.home.CenterCardCoordinator.debugDump()
    private fun LocationDump(): String = com.arena.carlauncher.loc.LocationHub.debugDump()

    // ------------------------------------------------------------------ card editor

    /**
     * Cards are edited by toggling membership and re-ordering with arrows: dragging inside a
     * `ScrollView` on a panel with this much finger noise is unreliable, and the result is the same.
     */
    private fun cardPicker(title: String, summary: String, which: String): View {
        val col = Views.column(this, paddingDp = 0)
        col.addView(
            PrefRows.section(this, title, summary),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(this@SettingsActivity, 12f) }
        )
        val ids = cardsOf(which)
        val all = ArrayList<String>()
        all.addAll(ids)
        (com.arena.carlauncher.ui.card.CardFactory.ALL - ids.toSet()).forEach { all.add(it) }
        all.forEach { id ->
            val inList = ids.contains(id)
            val row = Views.row(this@SettingsActivity, paddingDp = 0).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Palette.chip(this@SettingsActivity, false)
                setPadding(Views.dp(this@SettingsActivity, 10f), Views.dp(this@SettingsActivity, 7f), Views.dp(this@SettingsActivity, 10f), Views.dp(this@SettingsActivity, 7f))
                isClickable = true
                setOnClickListener {
                    setCards(which, if (inList) ids - id else ids + id)
                    show()
                }
            }
            row.addView(
                Views.icon(this@SettingsActivity, com.arena.carlauncher.ui.card.CardFactory.iconRes(id), 18,
                    if (inList) Palette.colors.accent else Palette.colors.onSurfaceMuted),
                LinearLayout.LayoutParams(Views.dp(this@SettingsActivity, 18f), Views.dp(this@SettingsActivity, 18f))
            )
            row.addView(
                Views.text(this@SettingsActivity, getString(com.arena.carlauncher.ui.card.CardFactory.titleRes(id)), 13f,
                    if (inList) Palette.colors.onSurface else Palette.colors.onSurfaceMuted, bold = inList)
                    .apply { setPadding(Views.dp(this@SettingsActivity, 8f), 0, 0, 0) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (inList) {
                val pos = ids.indexOf(id)
                row.addView(miniButton(R.drawable.ic_chevron_left) {
                    val list = ArrayList(ids)
                    if (pos > 0) {
                        list.removeAt(pos)
                        list.add(pos - 1, id)
                    }
                    setCards(which, list)
                    show()
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = Views.dp(this@SettingsActivity, 4f) })
                row.addView(miniButton(R.drawable.ic_chevron_right) {
                    val list = ArrayList(ids)
                    if (pos < list.size - 1) {
                        list.removeAt(pos)
                        list.add(pos + 1, id)
                    }
                    setCards(which, list)
                    show()
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = Views.dp(this@SettingsActivity, 4f) })
                row.addView(miniButton(R.drawable.ic_close) {
                    setCards(which, ids - id)
                    show()
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            col.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Views.dp(this@SettingsActivity, 4f) })
        }
        return col
    }

    private fun miniButton(icon: Int, onClick: () -> Unit): View = Views.iconButton(
        this, icon, 30, 15, onClick = onClick
    )

    private fun cardsOf(which: String): List<String> = when (which) {
        SLOT_CENTER -> prefs.centerCards
        SLOT_LEFT -> prefs.leftCards
        else -> prefs.rightCards
    }

    private fun setCards(which: String, ids: List<String>) {
        when (which) {
            SLOT_CENTER -> prefs.centerCards = ids
            SLOT_LEFT -> prefs.leftCards = ids
            else -> prefs.rightCards = ids
        }
        prefs.put("preset", "")
    }

    // ------------------------------------------------------------------ misc actions

    private fun sectionHeader(titleRes: Int, note: String): View {
        val v = PrefRows.section(this, getString(titleRes), note)
        v.tag = "section"
        return v
    }

    private fun recreateContent() {
        show()
    }

    private fun accentIndex(color: Int): Int {
        val i = ACCENTS.indexOf(color)
        return if (i >= 0) i else 0
    }

    private fun cacheSizeText(): String {
        val b = MapEngine.diskBytes()
        return if (b < 1024) "$b B" else if (b < 1048576) "${b / 1024} KB" else String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
    }

    private fun pickWallpaper() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*"), REQ_WALLPAPER
            )
        } catch (_: Throwable) {
            Views.toast(this, R.string.wallpaper_unavailable)
        }
    }

    private fun askDefaultHome() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 25) {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } else {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .putExtra("android.intent.extra.shortcut.INTENT", Intent(this, com.arena.carlauncher.home.HomeActivity::class.java)))
            }
        } catch (_: Throwable) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        }
    }

    private fun exportSettings() {
        try {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val f = File(dir, "car-launcher-settings.json")
            f.writeText(prefs.exportJson())
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    getString(R.string.pref_export)
                )
            )
        } catch (t: Throwable) {
            Views.toast(this, getString(R.string.export_failed, t.message ?: ""))
        }
    }

    private fun openAppPicker(mode: Int) {
        val i = Intent(this, AppPickerActivity::class.java)
        i.putExtra(AppPickerActivity.EXTRA_MODE, mode)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_APPS)
    }

    @Deprecated("startActivityForResult is the right tool here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_WALLPAPER -> {
                val uri = data.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Throwable) {
                }
                prefs.wallpaperUri = uri.toString()
                show()
            }
            APP_PICKER -> {
                val tokens = data.getStringArrayListExtra(AppPickerActivity.EXTRA_RESULT) ?: return
                when (data.getIntExtra(AppPickerActivity.EXTRA_MODE, 0)) {
                    PICK_DOCK -> prefs.dockApps = tokens
                    PICK_HIDE -> prefs.hiddenApps = tokens
                }
                AppRepository.load(force = true)
                show()
            }
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    companion object {
        /** Card list slot keys; persisted as CSV in [LauncherPrefs]. */
        const val SLOT_CENTER = "center"
        const val SLOT_LEFT = "left"
        const val SLOT_RIGHT = "right"

        /** Entry point for tiles/actions: `SettingsActivity.open(ctx, "vehicle")`. */
        fun open(ctx: Context, section: String) {
            try {
                ctx.startActivity(
                    Intent(ctx, SettingsActivity::class.java)
                        .putExtra(EXTRA_SECTION, section)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
            }
        }

        private const val REQ_WALLPAPER = 71
        private const val REQ_APPS = 72
        const val APP_PICKER = 72
        const val PICK_DOCK = 1
        const val PICK_HIDE = 2

        const val EXTRA_SECTION = "section"

        private const val SECTION_APPEARANCE = 0
        private const val SECTION_LAYOUT = 1
        private const val SECTION_MEDIA = 2
        private const val SECTION_MAP = 3
        private const val SECTION_VEHICLE = 4
        private const val SECTION_CLOCK = 5
        private const val SECTION_BEHAVIOUR = 6
        private const val SECTION_PERMISSIONS = 7
        private const val SECTION_DEBUG = 8

        private val SECTION_KEYS = listOf(
            "appearance", "layout", "media", "map", "vehicle", "clock", "behaviour", "permissions", "debug"
        )
        private val PRESET_KEYS = listOf("agama", "wide", "minimal", "carplay")
        private val PRESET_NAMES = listOf("Agama", "Wide", "Minimal", "CarPlay")

        val ACCENTS = listOf(
            0xFF3DDC97.toInt(), 0xFF4FC3F7.toInt(), 0xFFFFB74D.toInt(), 0xFFEF5350.toInt(),
            0xFFBA68C8.toInt(), 0xFFFFD54F.toInt(), 0xFF66BB6A.toInt(), 0xFF90A4AE.toInt()
        )
        private val ACCENT_NAMES = listOf(
            "Mint", "Sky", "Amber", "Coral", "Violet", "Gold", "Green", "Steel"
        )
    }
}

/** A tiny full-screen text overlay: cheaper than a dialog and readable on a panel at arm's length. */
object LogSheet {
    fun show(ctx: Context, text: String) {
        try {
            val sheet = android.app.Dialog(ctx)
            val scroll = ScrollView(ctx)
            val tv = Views.multiline(ctx, text, 11f, 200).apply {
                setTextColor(Palette.colors.onSurface)
                setTextIsSelectable(true)
                val p = Views.dp(ctx, 14f)
                setPadding(p, p, p, p)
            }
            scroll.addView(tv)
            sheet.setContentView(scroll, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, Views.dp(ctx, 420)
            ))
            sheet.window?.setBackgroundDrawable(Palette.card(ctx, radiusDp = 18f, alphaPercent = 98, clickable = false))
            sheet.show()
        } catch (_: Throwable) {
        }
    }
}
