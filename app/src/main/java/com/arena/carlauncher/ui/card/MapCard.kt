package com.arena.carlauncher.ui.card

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.arena.carlauncher.R
import com.arena.carlauncher.data.Cards
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.data.Place
import com.arena.carlauncher.loc.LocationHub
import com.arena.carlauncher.map.MapEngine
import com.arena.carlauncher.map.NavAppRepository
import com.arena.carlauncher.map.PlacePicker
import com.arena.carlauncher.map.RouteProvider
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.ui.Views
import com.arena.carlauncher.ui.widget.TileMapView
import com.arena.carlauncher.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The navigation card: a live map in the centre, one tap away from real turn-by-turn.
 *
 * Division of labour matters here. The launcher draws the map (position, heading, the planned
 * route, Home/Work) because that is what belongs *on* the home screen; the moment guidance starts,
 * the tap hands off to Neshan / Google Maps / Waze, which have the map data, the voice prompts and
 * the traffic. `LAUNCH_ADJACENT` is used where the ROM supports split multi-window so the map can
 * sit next to the launcher instead of covering it.
 */
class MapCard(context: Context, full: Boolean) : BaseCard(context, Cards.MAP, full) {

    override val iconRes = R.drawable.ic_map
    override val title = context.getString(R.string.card_map)

    private val map = TileMapView(context)
    private val stateChip = Views.chipLabel(context, "")
    private val etaRow = Views.row(context, paddingDp = 0)
    private val etaText = Views.text(context, "", if (full) 12f else 10.5f, Palette.colors.onSurfaceMuted, condensed = true)
    private val goButton = Views.text(
        context, context.getString(R.string.nav_go), if (full) 13f else 11f,
        Palette.colors.onAccent, bold = true
    )
    private val destination: Place?
        get() = PlacePicker.destination(context)

    private var routeJob: kotlinx.coroutines.Job? = null
    private var lastRouteKey = ""
    private var lastRouteAt = 0L
    private var offlineShown = false

    private val picker = object : PlacePicker.Listener {
        override fun onPlace(target: String, place: Place) {
            if (target == PlacePicker.TARGET_DESTINATION || target == PlacePicker.TARGET_RECENT) {
                map.destination = place.lat to place.lon
                map.moveTo(place.lat, place.lon, if (full) 15 else 14)
                refreshRoute(force = true)
                invalidateEta()
            }
        }
    }

    override fun onCreate() {
        val p = LauncherPrefs.get(context)
        map.headingUp = p.mapHeadingUp
        map.autoZoomBySpeed = p.mapAutoZoom
        map.attribution = MapEngine.source.attribution

        val stack = Views.column(context, paddingDp = 0)
        stack.addView(
            header(context.getString(R.string.card_map), stateChip),
            matchWrap()
        )

        val mapFrame = FrameLayout(context).apply {
            background = Palette.fill(context, Palette.colors.surfaceRaised, p.cornerRadiusDp.toFloat() * 0.6f)
            clipChildren = true
        }
        mapFrame.addView(
            map,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        mapFrame.addView(buildControls(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = Views.dp(context, 8f)
            marginEnd = Views.dp(context, 8f)
        })
        stack.addView(mapFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, if (full) 0 else Views.dp(context, 128f), 1f
        ).apply { topMargin = Views.dp(context, 8f) })

        if (full) {
            buildEtaRow(etaRow)
            stack.addView(etaRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 8f) })
        }
        body.addView(stack, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
        ))

        map.onUserPan = { stateChip.text = context.getString(R.string.map_free_pan); stateChip.visibility = View.VISIBLE }
        map.onLongPress = { lat, lon ->
            val label = String.format("%.5f, %.5f", lat, lon)
            PlacePicker.publish(context, PlacePicker.TARGET_DESTINATION, Place(label, lat, lon, label, "map"))
        }
        destination?.let { map.destination = it.lat to it.lon }
    }

    private fun buildControls(): View {
        val col = Views.column(context, paddingDp = 0)
        val size = if (full) 38 else 30
        col.addView(
            Views.iconButton(context, R.drawable.ic_plus, size, if (full) 18 else 15) { map.zoomBy(1) },
            margin()
        )
        col.addView(
            Views.iconButton(context, R.drawable.ic_minus, size, if (full) 18 else 15) { map.zoomBy(-1) },
            margin()
        )
        col.addView(
            Views.iconButton(context, R.drawable.ic_crosshair, size, if (full) 18 else 15) {
                map.followLocation()
                stateChip.visibility = View.GONE
            },
            margin()
        )
        val northButton = Views.iconButton(
            context,
            if (map.headingUp) R.drawable.ic_compass else R.drawable.ic_north,
            size, if (full) 18 else 15
        ) {
            map.headingUp = !map.headingUp
        }
        // The icon swap is a listener set *after* construction: `northButton` cannot be named inside its
        // own initialiser, and that is exactly what a click lambda passed to the factory would be.
        northButton.setOnClickListener {
            map.headingUp = !map.headingUp
            northButton.setImageResource(if (map.headingUp) R.drawable.ic_compass else R.drawable.ic_north)
        }
        col.addView(northButton, margin())
        if (full) {
            col.addView(
                Views.iconButton(context, R.drawable.ic_search, size, 18) {
                    PlacePicker.open(context, PlacePicker.TARGET_DESTINATION, "")
                },
                margin()
            )
        }
        return col
    }

    private fun margin(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Views.dp(context, 6f) }

    @SuppressLint("SetTextI18n")
    private fun buildEtaRow(row: LinearLayout) {
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(etaText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        goButton.apply {
            setBackgroundDrawable(Palette.fill(context, Palette.colors.accent, 12f))
            val px = Views.dp(context, 14f)
            val py = Views.dp(context, 7f)
            setPadding(px, py, px, py)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { startNavigation() }
        }
        row.addView(goButton, matchWrap())
        row.visibility = View.GONE
    }

    private fun startNavigation() {
        val place = destination ?: run {
            PlacePicker.open(context, PlacePicker.TARGET_DESTINATION, "")
            return
        }
        NavAppRepository.navigateTo(context, place)
    }

    override fun onBind() {
        PlacePicker.addListener(picker)
        observe(LocationHub.state) { loc ->
            map.onLocationUpdate(loc.bearing, loc.speedKmh)
            invalidateEta()
            refreshRoute(force = false)
            val online = MapEngine.isOnline()
            if (!online && !offlineShown) {
                stateChip.text = context.getString(R.string.map_offline)
                stateChip.visibility = View.VISIBLE
                offlineShown = true
            } else if (online && offlineShown) {
                offlineShown = false
                stateChip.visibility = View.GONE
            }
        }
        map.attribution = MapEngine.source.attribution
        map.invalidate()
    }

    override fun onUnbind() {
        PlacePicker.removeListener(picker)
        routeJob?.cancel()
        routeJob = null
    }

    private fun invalidateEta() {
        val place = destination ?: run {
            if (full) etaRow.visibility = View.GONE
            return
        }
        if (!full) return
        etaRow.visibility = View.VISIBLE
        val km = LocationHub.distanceKmTo(place.lat, place.lon)
        goButton.text = context.getString(R.string.nav_go_to, place.label.substringBefore(','))
        etaText.text = if (km < 0f) context.getString(R.string.map_need_gps)
        else Format.localized(context, Format.distance(km, LauncherPrefs.get(context).usePersianDigits)) +
                " · ${place.label}"
    }

    /**
     * Route geometry is fetched at most once per destination change (and never more than every
     * 90 s) — OSRM is a free public demo server and a launcher that hammers it is a bad citizen.
     */
    private fun refreshRoute(force: Boolean) {
        if (!full) return
        if (!LauncherPrefs.get(context).mapShowRoute) {
            map.routePoints = null
            return
        }
        val place = destination ?: run {
            map.routePoints = null
            lastRouteKey = ""
            return
        }
        val from = LocationHub.lastLocation ?: return
        val key = "${place.lat},${place.lon}"
        val now = System.currentTimeMillis()
        // Same destination, recent enough: reuse the geometry instead of re-asking OSRM every tick.
        if (key == lastRouteKey && !force && now - lastRouteAt < 60_000) return
        lastRouteKey = key
        lastRouteAt = now
        routeJob?.cancel()
        routeJob = launch {
            val pts = withContext(Dispatchers.IO) {
                RouteProvider.drivingRoute(context, from.latitude, from.longitude, place.lat, place.lon)
            }
            if (pts != null && pts.size > 1) map.routePoints = pts
        }
    }

    override fun onPaletteChanged() {
        super.onPaletteChanged()
        MapEngine.refreshSource()
        map.attribution = MapEngine.source.attribution
        goButton.background = Palette.fill(context, Palette.colors.accent, 12f)
        map.invalidate()
    }
}

