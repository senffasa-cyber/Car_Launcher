package com.arena.carlauncher.ui.card

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.arena.carlauncher.R
import com.arena.carlauncher.actions.ActionRouter
import com.arena.carlauncher.data.AppRepository
import com.arena.carlauncher.data.Cards
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.data.Place
import com.arena.carlauncher.data.TileConfig
import com.arena.carlauncher.data.TripStats
import com.arena.carlauncher.home.WidgetSlot
import com.arena.carlauncher.map.NavAppRepository
import com.arena.carlauncher.map.PlacePicker
import com.arena.carlauncher.loc.LocationHub
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.ui.Views
import com.arena.carlauncher.util.Format
import com.arena.carlauncher.vehicle.ClimateControl
import com.arena.carlauncher.vehicle.TripComputer
import org.json.JSONObject
import kotlin.math.roundToInt

/** Trip A/B computer: distance, average, maximum, moving vs idling time. */
class TripCard(context: Context, full: Boolean) : BaseCard(context, Cards.TRIP, full) {

    override val iconRes = R.drawable.ic_trip

    private val big = Views.text(context, "0", if (full) 42f else 22f, Palette.colors.onSurface, bold = true, condensed = true)
    private val rows = Views.column(context, paddingDp = 0)

    override fun onCreate() {
        body.addView(header(context.getString(R.string.card_trip)), matchWrap())
        val head = Views.row(context, paddingDp = 0)
        head.gravity = Gravity.BOTTOM
        head.addView(big, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        head.addView(
            Views.text(context, "km", if (full) 14f else 10f, Palette.colors.onSurfaceMuted).apply {
                setPadding(Views.dp(context, 5f), 0, 0, Views.dp(context, if (full) 6f else 3f))
            }, matchWrap()
        )
        body.addView(head, matchWrap())
        body.addView(rows, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Views.dp(context, if (full) 8f else 4f) })
        setOnClickListener { /* swallow: the card itself is not a button */ }
        setOnLongClickListener {
            TripComputer.reset()
            Views.toast(context, R.string.trip_reset)
            true
        }
    }

    override fun onBind() {
        observe(TripComputer.state) { render(it) }
        render(TripComputer.state.value)
    }

    private fun render(s: TripStats) {
        val fa = LauncherPrefs.get(context).usePersianDigits
        big.text = Format.localized(context, String.format(java.util.Locale.US, "%.1f", s.km))
        rows.removeAllViews()
        val grid = Views.row(context, paddingDp = 0)
        fun cell(label: String, value: String) {
            grid.addView(
                stat(label, value),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        cell(context.getString(R.string.trip_avg), Format.localized(context, "${s.avgKmh.roundToInt()}"))
        cell(context.getString(R.string.trip_max), Format.localized(context, "${s.maxKmh.roundToInt()}"))
        cell(context.getString(R.string.trip_time), Format.localized(context, durationText(s.durationMin)))
        cell(context.getString(R.string.trip_idle), Format.localized(context, durationText(s.idleMin)))
        rows.addView(grid, matchWrap())
        if (full) {
            rows.addView(
                Views.separator(context),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Views.dp(context, 1f)).apply {
                    topMargin = Views.dp(context, 8f)
                }
            )
            rows.addView(
                Views.text(
                    context,
                    context.getString(R.string.trip_hint),
                    10.5f, Palette.colors.onSurfaceMuted
                ).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    private fun durationText(min: Int): String {
        val h = min / 60
        val m = min % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    override fun onPaletteChanged() {
        super.onPaletteChanged()
        big.setTextColor(Palette.colors.onSurface)
    }
}

/**
 * Quick tiles — climate plus a handful of launcher-level switches.
 *
 * The climate half is a *contract*, not a feature: the head unit's MCU has to broadcast it (see
 * docs/vehicle-bus.md). When nothing answers, the tile still routes the intent so a user who wires
 * it up later sees it start working without a reinstall.
 */
class TilesCard(context: Context, full: Boolean) : BaseCard(context, Cards.TILES, full) {

    override val iconRes = R.drawable.ic_snowflake

    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    override fun onCreate() {
        body.addView(header(context.getString(R.string.card_tiles)), matchWrap())
        body.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
    }

    override fun onBind() {
        rebuild()
    }

    private fun rebuild() {
        grid.removeAllViews()
        val tiles = ArrayList<TileConfig>()
        tiles.addAll(ClimateControl.tiles(context))
        tiles.addAll(systemTiles())
        if (tiles.isEmpty()) {
            body.addView(
                emptyState(context.getString(R.string.tiles_empty)),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            return
        }
        val perRow = if (full) 4 else 3
        var i = 0
        while (i < tiles.size) {
            val row = Views.row(context, paddingDp = 0)
            row.gravity = Gravity.CENTER_VERTICAL
            var added = 0
            while (i < tiles.size && added < perRow) {
                row.addView(tile(tiles[i]), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginEnd = if (added == perRow - 1) 0 else Views.dp(context, 6f)
                })
                i++
                added++
            }
            while (added < perRow) {
                row.addView(Views.weightSpacer(context), LinearLayout.LayoutParams(0, 1, 1f))
                added++
            }
            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = if (grid.childCount == 0) 0 else Views.dp(context, 6f) })
        }
    }

    private fun tile(t: TileConfig): View {
        val active = false
        val cell = Views.row(context, paddingDp = 0).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Palette.chip(context, active)
            isClickable = true
            isFocusable = true
            setOnClickListener { fire(t) }
            setOnLongClickListener {
                onOpenSettings?.invoke()
                true
            }
        }
        cell.addView(
            Views.icon(context, iconFor(t.icon), if (full) 24 else 20, if (active) Palette.colors.onAccent else Palette.colors.accent),
            matchWrap()
        )
        cell.addView(
            Views.text(
                context, t.label, if (full) 10.5f else 9f,
                if (active) Palette.colors.onAccent else Palette.colors.onSurface, bold = true
            ).apply { gravity = Gravity.CENTER; maxLines = 2 },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 3f) })
        return cell
    }

    private fun fire(t: TileConfig) {
        val spec = when {
            t.action.isBlank() -> "climate:${t.extra}"
            t.action == "arena.car.CLIMATE" -> "climate:${t.extra}"
            t.action.contains(":") -> t.action
            else -> "broadcast:${t.action}|${t.extra}"
        }
        if (t.packageName.isNotBlank()) AppRepository.launchPackage(context, t.packageName)
        else ActionRouter.route(context, spec)
        rebuild()
    }

    private fun systemTiles(): List<TileConfig> {
        val p = LauncherPrefs.get(context)
        val out = ArrayList<TileConfig>(4)
        out.add(TileConfig("theme", context.getString(R.string.tile_theme), "moon", "theme:auto", "", ""))
        out.add(TileConfig("screen", context.getString(R.string.tile_screen_off), "screen_off", "screen:off", "", ""))
        out.add(TileConfig("voice", context.getString(R.string.tile_voice), "mic", "voice", "", ""))
        out.add(TileConfig("allapps", context.getString(R.string.tile_apps), "apps", "allapps", "", ""))
        // user-defined extra tiles from the settings editor are appended verbatim
        try {
            val raw = p.get("extra_tiles_json", "")
            if (raw.isNotBlank()) {
                val arr = org.json.JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(TileConfig.fromJson(o))
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun iconFor(name: String): Int = when (name) {
        "snow" -> R.drawable.ic_snowflake
        "plus" -> R.drawable.ic_plus
        "minus" -> R.drawable.ic_minus
        "fan" -> R.drawable.ic_fan
        "recirc" -> R.drawable.ic_recirculate
        "heat" -> R.drawable.ic_seat_heat
        "moon" -> R.drawable.ic_moon
        "sun" -> R.drawable.ic_sun
        "mic" -> R.drawable.ic_mic
        "apps" -> R.drawable.ic_apps
        "screen_off" -> R.drawable.ic_screen_off
        "power" -> R.drawable.ic_power
        "car" -> R.drawable.ic_car
        "map" -> R.drawable.ic_map
        "music" -> R.drawable.ic_music
        else -> R.drawable.ic_circle
    }
}

/** Home / work / recents with live distance, and the "Go" hand-off to Neshan or Google Maps. */
class NavCard(context: Context, full: Boolean) : BaseCard(context, Cards.NAV, full) {

    override val iconRes = R.drawable.ic_navigation

    private val list = Views.column(context, paddingDp = 0)

    override fun onCreate() {
        val app = NavAppRepository.preferred()
        val chip = Views.chipLabel(context, app?.label ?: context.getString(R.string.nav_no_app))
        body.addView(header(context.getString(R.string.card_nav), chip), matchWrap())
        body.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
    }

    override fun onBind() {
        observe(LocationHub.state) { render() }
        render()
        PlacePicker.addListener(pickerListener)
    }

    override fun onUnbind() {
        PlacePicker.removeListener(pickerListener)
    }

    private val pickerListener = object : PlacePicker.Listener {
        override fun onPlace(target: String, place: Place) {
            if (target == PlacePicker.TARGET_DESTINATION) post { render() }
        }
    }

    private fun render() {
        list.removeAllViews()
        val home = PlacePicker.home(context)
        val work = PlacePicker.work(context)
        var used = 0
        for ((place, label) in listOf(
            home to context.getString(R.string.place_home),
            work to context.getString(R.string.place_work)
        )) {
            val row = actionRow(
                if (place == null) R.drawable.ic_plus else if (label == context.getString(R.string.place_home)) R.drawable.ic_home else R.drawable.ic_work,
                label,
                if (place == null) context.getString(R.string.place_not_set) else distanceLabel(place),
                accent = place != null
            ) {
                if (place == null) PlacePicker.open(context, if (home == null) PlacePicker.TARGET_HOME else PlacePicker.TARGET_WORK)
                else go(place)
            }
            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { if (used++ > 0) topMargin = Views.dp(context, if (full) 8f else 5f) })
        }
        val dest = PlacePicker.destination(context)
        if (dest != null) {
            list.addView(
                actionRow(R.drawable.ic_pin, dest.label, distanceLabel(dest)) { go(dest) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    topMargin = Views.dp(context, if (full) 8f else 5f)
                }
            )
        }
        val recents = LauncherPrefs.get(context).recentPlaces.mapNotNull { Place.fromJson(it) }
        if (recents.isNotEmpty() && full) {
            val chips = Views.row(context, paddingDp = 0)
            recents.take(3).forEach { p ->
                chips.addView(
                    Views.text(context, p.label, 11f, Palette.colors.accent, bold = true)
                        .apply {
                            background = Palette.chip(context, false)
                            setPadding(Views.dp(context, 10f), Views.dp(context, 6f), Views.dp(context, 10f), Views.dp(context, 6f))
                            isClickable = true
                            setOnClickListener { go(p) }
                            maxLines = 1
                        },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = Views.dp(context, 6f) }
                )
            }
            list.addView(chips, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 8f) })
        }
    }

    private fun distanceLabel(place: Place): String {
        if (!LocationHub.state.value.hasFix) return place.address.ifBlank { context.getString(R.string.nav_open) }
        val km = LocationHub.distanceKmTo(place.lat, place.lon)
        return Format.localized(context, Format.distance(km, LauncherPrefs.get(context).usePersianDigits)) +
            (if (place.address.isNotBlank()) " · ${place.address}" else "")
    }

    private fun go(place: Place) {
        PlacePicker.publish(context, PlacePicker.TARGET_DESTINATION, place)
        NavAppRepository.navigateTo(context, place)
    }

    override fun onPaletteChanged() {
        super.onPaletteChanged()
        render()
    }
}

/** Bluetooth status, dialer and voice assistant — the phone half of the dash. */
class PhoneCard(context: Context, full: Boolean) : BaseCard(context, Cards.PHONE, full) {

    override val iconRes = R.drawable.ic_phone

    private val status = Views.text(context, "", if (full) 13f else 10.5f, Palette.colors.onSurfaceMuted)

    override fun onCreate() {
        body.addView(header(context.getString(R.string.card_phone)), matchWrap())
        if (full) {
            val big = Views.row(context, paddingDp = 0)
            big.gravity = Gravity.CENTER_VERTICAL
            big.addView(
                actionCell(R.drawable.ic_dialpad, context.getString(R.string.phone_dial)) {
                    launch(Intent(Intent.ACTION_DIAL))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            )
            big.addView(Views.spacer(context, 8f))
            big.addView(
                actionCell(R.drawable.ic_mic, context.getString(R.string.phone_voice)) {
                    ActionRouter.route(context, "voice")
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            )
            body.addView(big, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = Views.dp(context, 8f) })
            body.addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 8f) })
        } else {
            val row = Views.row(context, paddingDp = 0)
            row.addView(
                Views.iconButton(context, R.drawable.ic_dialpad, 30, 14) { launch(Intent(Intent.ACTION_DIAL)) },
                matchWrap()
            )
            row.addView(Views.spacer(context, 6f))
            row.addView(
                Views.iconButton(context, R.drawable.ic_mic, 30, 14) { ActionRouter.route(context, "voice") },
                matchWrap()
            )
            row.addView(Views.weightSpacer(context))
            body.addView(row, matchWrap())
            body.addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 6f) })
        }
    }

    override fun onBind() {
        render()
    }

    private fun actionCell(icon: Int, label: String, onClick: () -> Unit): View {
        val v = Views.row(context, paddingDp = 0).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Palette.chip(context, false)
            isClickable = true
            setOnClickListener { onClick() }
        }
        v.addView(Views.icon(context, icon, if (full) 30 else 20, Palette.colors.accent), matchWrap())
        v.addView(
            Views.text(context, label, if (full) 12f else 9.5f, Palette.colors.onSurface, bold = true),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 4f) })
        return v
    }

    @SuppressLint("MissingPermission")
    private fun render() {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = mgr?.adapter
        status.text = when {
            adapter == null -> context.getString(R.string.phone_bt_unavailable)
            !adapter.isEnabled -> context.getString(R.string.phone_bt_off)
            else -> {
                val name = try {
                    adapter.bondedDevices?.firstOrNull { it.address == connectedAddress(adapter) }?.name
                        ?: adapter.bondedDevices?.firstOrNull()?.name
                } catch (_: Throwable) {
                    null
                }
                if (name.isNullOrBlank()) context.getString(R.string.phone_bt_paired_none) else name
            }
        }
    }

    /** No public API for the connected device before API 33; the hidden getter still works. */
    private fun connectedAddress(adapter: BluetoothAdapter): String? = try {
        val m = adapter.javaClass.getMethod("getConnectedDevices")
        @Suppress("UNCHECKED_CAST")
        val list = m.invoke(adapter) as? List<android.bluetooth.BluetoothDevice>
        list?.firstOrNull()?.address
    } catch (_: Throwable) {
        null
    }

    private fun launch(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Throwable) {
            Views.toast(context, R.string.phone_no_dialer)
        }
    }

    override fun onPaletteChanged() {
        super.onPaletteChanged()
        status.setTextColor(Palette.colors.onSurfaceMuted)
    }
}

/** Dashcam / DVR shortcut with a live recording dot when the vendor app broadcasts one. */
class CameraCard(context: Context, full: Boolean) : BaseCard(context, Cards.CAMERA, full) {

    override val iconRes = R.drawable.ic_video

    private val slot = Views.column(context, paddingDp = 0)

    override fun onCreate() {
        val chip = Views.chipLabel(context, "")
        body.addView(header(context.getString(R.string.card_camera), chip), matchWrap())
        slot.addView(chip, matchWrap())
        val found = findCamera()
        if (found == null) {
            slot.addView(
                emptyState(context.getString(R.string.camera_none)),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        } else {
            slot.addView(
                actionRow(R.drawable.ic_video, found.second, context.getString(R.string.camera_launch)) {
                    launch(found.first)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            slot.addView(
                Views.row(context, paddingDp = 0).apply {
                    addView(
                        Views.text(context, context.getString(R.string.camera_hint), 10f, Palette.colors.onSurfaceMuted),
                        weightWrap()
                    )
                },
                matchWrap()
            )
        }
        body.addView(slot, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
    }

    private fun findCamera(): Pair<ComponentName, String>? {
        val prefs = LauncherPrefs.get(context)
        val saved = prefs.get("camera_component", "")
        if (saved.isNotBlank()) {
            val c = ComponentName.unflattenFromString(saved) ?: ComponentName(saved, saved)
            return c to NavAppRepository.packageLabel(context, c.packageName)
        }
        val key = listOf("dashcam", "dvr", "camera", "行车记录仪", "记录仪")
        for (e in AppRepository.all()) {
            val l = e.label.lowercase(java.util.Locale.ROOT)
            if (key.any { l.contains(it) }) {
                return ComponentName(e.packageName, e.activityName) to e.label
            }
        }
        return null
    }

    private fun launch(component: ComponentName) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
            Views.toast(context, R.string.camera_launch_failed)
        }
    }
}

/** The card that replaces "I need to find the settings app": five rows, nothing to hunt for. */
class SettingsCard(context: Context, full: Boolean) : BaseCard(context, Cards.SETTINGS, full) {

    override val iconRes = R.drawable.ic_settings

    override fun onCreate() {
        body.addView(header(context.getString(R.string.card_settings)), matchWrap())
        val col = Views.column(context, paddingDp = 0)
        fun add(icon: Int, label: String, sub: String, onClick: () -> Unit) {
            col.addView(
                actionRow(icon, label, sub, onClick = onClick),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply { if (col.childCount > 0) topMargin = Views.dp(context, if (full) 6f else 4f) }
            )
        }
        add(R.drawable.ic_settings, context.getString(R.string.settings_title), context.getString(R.string.card_settings_sub)) {
            ActionRouter.openSettingsActivity(context)
        }
        add(R.drawable.ic_lock, context.getString(R.string.perm_title), context.getString(R.string.perm_summary)) {
            com.arena.carlauncher.settings.SettingsActivity.open(context, "permissions")
        }
        add(R.drawable.ic_wallpaper, context.getString(R.string.wallpaper_title), context.getString(R.string.wallpaper_summary)) {
            ActionRouter.route(context, "settings:wallpaper")
        }
        add(R.drawable.ic_layout, context.getString(R.string.cards_title), context.getString(R.string.cards_summary)) {
            onOpenSettings?.invoke()
        }
        add(R.drawable.ic_auto_mode, context.getString(R.string.theme_title), context.getString(R.string.theme_summary)) {
            com.arena.carlauncher.theme.DayNightController.toggleManual(context)
        }
        body.addView(col, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }
}

/** Hosts a single third-party AppWidget in the carousel (see [WidgetSlot]). */
class WidgetCard(context: Context, full: Boolean) : BaseCard(context, Cards.WIDGET, full) {

    override val iconRes = R.drawable.ic_widget
    private val container = FrameLayout(context)

    override fun onCreate() {
        body.addView(header(context.getString(R.string.card_widget), Views.chipLabel(context, "")), matchWrap())
        body.addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        container.addView(WidgetSlot.view(context), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    override fun onBind() {
        WidgetSlot.attach(container)
    }

    override fun onUnbind() {
        WidgetSlot.detach(container)
    }
}

/** Fallback for an unknown id so a bad preference never crashes the carousel. */
class PlaceholderCard(context: Context, id: String) : BaseCard(context, id, true) {
    override val iconRes = R.drawable.ic_info
    override fun onCreate() {
        body.addView(
            emptyState(context.getString(R.string.card_unknown, id), context.getString(R.string.card_fix)) {
                onOpenSettings?.invoke()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }
}
