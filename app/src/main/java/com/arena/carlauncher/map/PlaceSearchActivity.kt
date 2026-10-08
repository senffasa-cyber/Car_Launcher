package com.arena.carlauncher.map

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arena.carlauncher.R
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.data.Place
import com.arena.carlauncher.loc.LocationHub
import com.arena.carlauncher.theme.DayNightController
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.ui.Views
import com.arena.carlauncher.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Place search: type a name (Persian or Latin), pick a result, it becomes the launcher's destination.
 *
 * Nominatim is used directly rather than through a maps SDK — the head unit has no Play Services, and an
 * SDK key would tie the launcher to a Google account. Persian input works because Nominatim answers
 * `accept-language=fa,en` queries against OSM's bilingual data; the query itself is sent URL-encoded.
 *
 * Requests are debounced 550 ms and serialised: Nominatim's usage policy is one request per second, and
 * a launcher that fires on every keystroke gets the device's IP rate-limited within a minute.
 */
class PlaceSearchActivity : Activity() {

    private lateinit var prefs: LauncherPrefs
    private var target = PlacePicker.TARGET_DESTINATION
    private var navigateAfter = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var results: List<Place> = emptyList()
    private lateinit var list: RecyclerView
    private lateinit var status: android.widget.TextView
    private lateinit var input: EditText
    private var lastQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        DayNightController.install(application)
        prefs = LauncherPrefs.get(this)
        super.onCreate(savedInstanceState)
        target = intent.getStringExtra(PlacePicker.EXTRA_TARGET) ?: PlacePicker.TARGET_DESTINATION
        navigateAfter = intent.getBooleanExtra(EXTRA_NAVIGATE, target == PlacePicker.TARGET_DESTINATION)

        val root = Views.column(this, paddingDp = 12).apply {
            setBackgroundColor(Palette.colors.background)
        }
        val head = Views.row(this, paddingDp = 0).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(
            Views.icon(this, R.drawable.ic_search, 20, Palette.colors.accent),
            LinearLayout.LayoutParams(Views.dp(this@PlaceSearchActivity, 20f), Views.dp(this@PlaceSearchActivity, 20f))
        )
        input = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine()
            textSize = 16f
            setTextColor(Palette.colors.onSurface)
            setHintTextColor(Palette.colors.onSurfaceMuted)
            background = Palette.chip(this@PlaceSearchActivity, false)
            val p = Views.dp(this@PlaceSearchActivity, 10f)
            setPadding(p + Views.dp(this@PlaceSearchActivity, 8f), p, p, p)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = schedule(s?.toString().orEmpty())
            })
        }
        head.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Views.dp(this@PlaceSearchActivity, 10f)
        })
        head.addView(
            Views.iconButton(this, R.drawable.ic_close, 38, 18) { finish() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Views.dp(this@PlaceSearchActivity, 8f) }
        )
        root.addView(head, matchWrap())

        val actions = Views.row(this, paddingDp = 0).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(
            chip(R.drawable.ic_crosshair, getString(R.string.search_here)) { pickCurrentLocation() },
            matchWrap()
        )
        actions.addView(
            chip(R.drawable.ic_home, getString(R.string.place_home)) {
                PlacePicker.home(this)?.let { choose(it) } ?: Views.toast(this, R.string.place_not_set)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Views.dp(this@PlaceSearchActivity, 6f) })
        actions.addView(
            chip(R.drawable.ic_work, getString(R.string.place_work)) {
                PlacePicker.work(this)?.let { choose(it) } ?: Views.toast(this, R.string.place_not_set)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Views.dp(this@PlaceSearchActivity, 6f) })
        root.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Views.dp(this@PlaceSearchActivity, 8f) })

        status = Views.text(this, "", 11.5f, Palette.colors.onSurfaceMuted).apply { gravity = Gravity.CENTER }
        root.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Views.dp(this@PlaceSearchActivity, 6f) })

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PlaceSearchActivity)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        list.adapter = Rows()
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = Views.dp(this@PlaceSearchActivity, 6f)
        })
        setContentView(root)
        renderRecents()
        intent.getStringExtra(PlacePicker.EXTRA_QUERY)?.let {
            input.setText(it)
            input.setSelection(it.length)
            schedule(it)
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun chip(icon: Int, label: String, onClick: () -> Unit): View {
        val v = Views.row(this, paddingDp = 0).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Palette.chip(this@PlaceSearchActivity, false)
            isClickable = true
            setOnClickListener { onClick() }
            val p = Views.dp(this@PlaceSearchActivity, 9f)
            setPadding(p + Views.dp(this@PlaceSearchActivity, 4f), p, p + Views.dp(this@PlaceSearchActivity, 4f), p)
        }
        v.addView(Views.icon(this, icon, 16, Palette.colors.accent), matchWrap())
        v.addView(
            Views.text(this, label, 12f, Palette.colors.onSurface, bold = true).apply {
                setPadding(Views.dp(this@PlaceSearchActivity, 6f), 0, 0, 0)
            }, matchWrap()
        )
        return v
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        scope.cancel()
    }

    // ------------------------------------------------------------------ search

    private fun schedule(query: String) {
        job?.cancel()
        val q = query.trim()
        lastQuery = q
        if (q.length < 2) {
            renderRecents()
            return
        }
        job = scope.launch {
            delay(550)
            status.text = getString(R.string.search_working)
            val found = withContext(Dispatchers.IO) {
                val fix = LocationHub.lastLocation
                try {
                    PlaceSearch.search(
                        applicationContext, q,
                        fix?.latitude, fix?.longitude,
                        limit = 8
                    )
                } catch (t: Throwable) {
                    Log0("search failed: ${t.message}")
                    emptyList()
                }
            }
            if (q != lastQuery) return@launch
            results = found
            list.adapter?.notifyDataSetChanged()
            status.text = if (found.isEmpty()) getString(R.string.search_none) else getString(R.string.search_results, found.size)
        }
    }

    private fun Log0(msg: String) = android.util.Log.d("PlaceSearch", msg)

    private fun renderRecents() {
        results = prefs.recentPlaces.mapNotNull { Place.fromJson(it) }
        list.adapter?.notifyDataSetChanged()
        status.text = if (results.isEmpty()) getString(R.string.search_hint_empty) else getString(R.string.search_recent)
    }

    private fun pickCurrentLocation() {
        val loc = LocationHub.lastLocation
        if (loc == null) {
            Views.toast(this, R.string.map_need_gps)
            return
        }
        status.text = getString(R.string.search_working)
        scope.launch {
            val place = withContext(Dispatchers.IO) {
                PlaceSearch.reverse(applicationContext, loc.latitude, loc.longitude)
                    ?: Place(
                        String.format(java.util.Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude),
                        loc.latitude, loc.longitude, "", "gps"
                    )
            }
            status.text = ""
            choose(place)
        }
    }

    // ------------------------------------------------------------------ choosing

    private fun choose(place: Place) {
        PlacePicker.publish(this, target, place)
        if (navigateAfter && target == PlacePicker.TARGET_DESTINATION) {
            NavAppRepository.navigateTo(this, place)
        }
        finish()
    }

    private inner class Rows : RecyclerView.Adapter<Row>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            val line = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Views.dp(parent.context, 12f), Views.dp(parent.context, 10f), Views.dp(parent.context, 12f), Views.dp(parent.context, 10f))
                background = Palette.chip(parent.context, false)
                isClickable = true
            }
            val title = Views.text(parent.context, "", 14.5f, Palette.colors.onSurface, bold = true)
            val sub = Views.multiline(parent.context, "", 11f, 2).apply { setTextColor(Palette.colors.onSurfaceMuted) }
            line.addView(title, matchWrap2(parent.context))
            line.addView(sub, matchWrap2(parent.context))
            return Row(line, title, sub)
        }

        private fun matchWrap2(ctx: Context) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: Row, position: Int) {
            val p = results[position]
            holder.title.text = p.label
            val dist = if (LocationHub.state.value.hasFix)
                " · " + Format.localized(
                    holder.itemView.context,
                    Format.distance(LocationHub.distanceKmTo(p.lat, p.lon), prefs.usePersianDigits)
                ) else ""
            holder.sub.text = p.address.ifBlank { "${p.lat.round5()}, ${p.lon.round5()}" } + dist
            val lp = holder.itemView.layoutParams as LinearLayout.LayoutParams
            lp.bottomMargin = Views.dp(holder.itemView.context, 6f)
            holder.itemView.layoutParams = lp
            holder.itemView.setOnClickListener { choose(p) }
        }

        override fun getItemCount(): Int = results.size
    }

    class Row(val view: View, val title: android.widget.TextView, val sub: android.widget.TextView) :
        RecyclerView.ViewHolder(view)

    private fun Double.round5(): String = String.format(java.util.Locale.US, "%.5f", this)

    companion object {
        const val EXTRA_NAVIGATE = "navigate_after"
    }
}
