package com.arena.carlauncher.onboarding

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.arena.carlauncher.R
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.home.WidgetSlot
import com.arena.carlauncher.map.NavAppRepository
import com.arena.carlauncher.map.PlacePicker
import com.arena.carlauncher.media.MediaHub
import com.arena.carlauncher.permission.PermissionHub
import com.arena.carlauncher.services.LauncherService
import com.arena.carlauncher.settings.SettingsActivity
import com.arena.carlauncher.theme.DayNightController
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.ui.Views
import com.arena.carlauncher.ui.card.CardFactory
import com.arena.carlauncher.weather.WeatherRepository

/**
 * First-run setup, in eight steps that can be skipped in any order.
 *
 * The order is not decoration. Location and notification access are what make the launcher *look*
 * right on the first screen — without them the music card is empty and the map has nowhere to centre —
 * so they come before any aesthetic choice. "Set as default home" is last because on several head-unit
 * ROMs the picker only appears once the launcher has been opened from the home button at least once.
 *
 * Every step re-checks its own condition when the activity resumes, so a user who grants permission in
 * Settings and comes back sees a tick rather than a stale prompt.
 */
class OnboardingActivity : Activity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var body: LinearLayout
    private lateinit var progress: android.widget.TextView
    private var step = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        DayNightController.install(application)
        prefs = LauncherPrefs.get(this)
        super.onCreate(savedInstanceState)
        step = intent.getIntExtra(EXTRA_STEP, 0).coerceIn(0, STEPS - 1)
        build()
        render()
    }

    private fun build() {
        val root = Views.column(this, paddingDp = 0).apply {
            setBackgroundColor(Palette.colors.background)
        }
        val head = Views.row(this, paddingDp = 0).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(
            Views.text(this, getString(R.string.onboarding_title), 20f, Palette.colors.onSurface, bold = true)
                .apply { setPadding(Views.dp(this@OnboardingActivity, 18f), Views.dp(this@OnboardingActivity, 12f), 0, 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        progress = Views.text(this, "", 12f, Palette.colors.accent, bold = true).apply {
            setPadding(0, Views.dp(this@OnboardingActivity, 14f), Views.dp(this@OnboardingActivity, 18f), 0)
        }
        head.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(head, matchWrap())

        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = Views.column(this, paddingDp = 0).apply {
            setPadding(Views.dp(this@OnboardingActivity, 18f), Views.dp(this@OnboardingActivity, 6f), Views.dp(this@OnboardingActivity, 18f), Views.dp(this@OnboardingActivity, 6f))
        }
        scroll.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val foot = Views.row(this, paddingDp = 0).apply { gravity = Gravity.CENTER_VERTICAL }
        foot.addView(
            button(R.string.skip) { finish() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        foot.addView(Views.weightSpacer(this))
        foot.addView(
            button(R.string.back) {
                step = (step - 1).coerceAtLeast(0)
                render()
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = Views.dp(this@OnboardingActivity, 8f) }
        )
        foot.addView(
            button(if (step == STEPS - 1) R.string.finish else R.string.next) { onNext() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        root.addView(foot, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(Views.dp(this@OnboardingActivity, 18f), 0, Views.dp(this@OnboardingActivity, 18f), Views.dp(this@OnboardingActivity, 14f))
        })
        setContentView(root)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun button(labelRes: Int, onClick: () -> Unit): View =
        Views.text(this, getString(labelRes), 14f, Palette.colors.onAccent, bold = true).apply {
            background = Palette.chip(this@OnboardingActivity, true)
            gravity = Gravity.CENTER
            val px = Views.dp(this@OnboardingActivity, 20f)
            val py = Views.dp(this@OnboardingActivity, 11f)
            setPadding(px, py, px, py)
            isClickable = true
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    override fun onResume() {
        super.onResume()
        render()
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        progress.text = "${step + 1} / $STEPS"
        body.removeAllViews()
        val title: String
        val note: String
        when (step) {
            0 -> {
                title = getString(R.string.onb_welcome_title)
                note = getString(R.string.onb_welcome_body)
                card(title, note)
                row(R.drawable.ic_music, getString(R.string.onb_feat_music), getString(R.string.onb_feat_music_sum))
                row(R.drawable.ic_map, getString(R.string.onb_feat_map), getString(R.string.onb_feat_map_sum))
                row(R.drawable.ic_clock, getString(R.string.onb_feat_clock), getString(R.string.onb_feat_clock_sum))
            }
            1 -> {
                title = getString(R.string.onb_perm_title)
                note = getString(R.string.onb_perm_body)
                card(title, note)
                PermissionHub.status(this).filter { it.critical }.forEach { permRow(it) }
            }
            2 -> {
                title = getString(R.string.onb_media_title)
                note = getString(R.string.onb_media_body)
                card(title, note)
                actionRow(
                    R.drawable.ic_check,
                    getString(R.string.perm_notification),
                    if (MediaHub.notificationAccessGranted(this)) getString(R.string.perm_granted) else getString(R.string.perm_needed)
                ) { MediaHub.openNotificationAccessSettings(this) }
                actionRow(R.drawable.ic_moon, getString(R.string.pref_theme), getString(R.string.onb_dark_hint)) {
                    prefs.themeMode = LauncherPrefs.THEME_DARK
                    DayNightController.apply()
                }
            }
            3 -> {
                title = getString(R.string.onb_map_title)
                note = getString(R.string.onb_map_body)
                card(title, note)
                val apps = NavAppRepository.apps(true)
                if (apps.isEmpty()) {
                    note2(getString(R.string.onb_nav_none))
                } else {
                    apps.forEach { app ->
                        actionRow(R.drawable.ic_navigation, app.label, app.packageName) {
                            NavAppRepository.setPreferred(app.packageName)
                        }
                    }
                }
                actionRow(R.drawable.ic_home, getString(R.string.pref_home_place), PlacePickerLabel(PlacePicker.home(this))) {
                    PlacePicker.open(this, PlacePicker.TARGET_HOME)
                }
                actionRow(R.drawable.ic_work, getString(R.string.pref_work_place), PlacePickerLabel(PlacePicker.work(this))) {
                    PlacePicker.open(this, PlacePicker.TARGET_WORK)
                }
            }
            4 -> {
                title = getString(R.string.onb_vehicle_title)
                note = getString(R.string.onb_vehicle_body)
                card(title, note)
                actionRow(R.drawable.ic_gauge, getString(R.string.pref_speed_source), getString(R.string.onb_vehicle_gps)) {
                    prefs.speedSource = LauncherPrefs.SPEED_SRC_GPS
                }
                actionRow(R.drawable.ic_settings, getString(R.string.settings_vehicle), getString(R.string.onb_vehicle_bus)) {
                    SettingsActivity.open(this, "vehicle")
                }
            }
            5 -> {
                title = getString(R.string.onb_layout_title)
                note = getString(R.string.onb_layout_body)
                card(title, note)
                PRESETS.forEach { (key, label) ->
                    actionRow(R.drawable.ic_layout, label, presetSummary(key)) {
                        LauncherPrefs.applyPreset(this, key)
                        prefs.put("preset", key)
                        render()
                    }
                }
                actionRow(R.drawable.ic_apps, getString(R.string.pref_dock_edit), getString(R.string.onb_dock_hint)) {
                    SettingsActivity.open(this, "layout")
                }
            }
            6 -> {
                title = getString(R.string.onb_gestures_title)
                note = getString(R.string.onb_gestures_body)
                card(title, note)
                com.arena.carlauncher.home.GestureLayout.DEFAULTS.forEach { (k, v) ->
                    row(R.drawable.ic_navigation, k.replace('_', ' '), v.ifBlank { getString(R.string.gesture_none) })
                }
                actionRow(R.drawable.ic_settings, getString(R.string.pref_gestures), getString(R.string.onb_gestures_more)) {
                    SettingsActivity.open(this, "behaviour")
                }
            }
            else -> {
                title = getString(R.string.onb_done_title)
                note = getString(R.string.onb_done_body)
                card(title, note)
                val missing = PermissionHub.missingCritical(this)
                if (missing.isNotEmpty()) {
                    note2(getString(R.string.onb_missing, missing.joinToString(", ") { it.title }))
                }
                row(R.drawable.ic_weather_sun, getString(R.string.pref_weather), if (prefs.weatherEnabled) getString(R.string.onb_weather_on) else getString(R.string.onb_weather_off))
                row(R.drawable.ic_widget, getString(R.string.card_widget), if (WidgetSlot.hasWidget) getString(R.string.widget_added) else getString(R.string.widget_empty))
            }
        }
    }

    private fun PlacePickerLabel(place: com.arena.carlauncher.data.Place?): String =
        place?.label ?: getString(R.string.place_not_set)

    /** Preview text only: the real cards are written when the row is tapped, never while measuring. */
    private fun presetSummary(key: String): String {
        val ids = when (key) {
            "agama" -> listOf("music", "map", "clock", "vehicle", "weather")
            "wide" -> listOf("map", "music")
            "minimal" -> listOf("clock", "music")
            else -> listOf("map", "music", "phone")
        }
        return getString(R.string.onb_preset_sum, ids.joinToString(" · ") { getString(CardFactory.titleRes(it)) })
    }

    private fun card(title: String, note: String) {
        val col = Views.column(this, paddingDp = 14).apply {
            background = Palette.card(this@OnboardingActivity, radiusDp = 18f, alphaPercent = 88, clickable = false)
        }
        col.addView(Views.text(this, title, 17f, Palette.colors.onSurface, bold = true), matchWrap())
        col.addView(
            Views.multiline(this, note, 12.5f, 6).apply { setTextColor(Palette.colors.onSurfaceMuted) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Views.dp(this@OnboardingActivity, 4f) }
        )
        body.addView(col, matchWrap())
    }

    private fun note2(text: String) {
        body.addView(
            Views.multiline(this, text, 11.5f, 4).apply { setTextColor(Palette.colors.warn) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Views.dp(this@OnboardingActivity, 8f) }
        )
    }

    private fun row(icon: Int, label: String, value: String) {
        actionRow(icon, label, value) {}
    }

    private fun permRow(item: PermissionHub.Item) {
        actionRow(if (item.granted) R.drawable.ic_check else R.drawable.ic_warning, item.title, item.summary) {
            item.request(this)
        }
    }

    private fun actionRow(icon: Int, label: String, value: String, onClick: () -> Unit) {
        val line = Views.row(this, paddingDp = 0).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Palette.chip(this@OnboardingActivity, false)
            isClickable = true
            setOnClickListener { onClick() }
            val p = Views.dp(this@OnboardingActivity, 12f)
            setPadding(p, Views.dp(this@OnboardingActivity, 10f), p, Views.dp(this@OnboardingActivity, 10f))
        }
        line.addView(Views.icon(this, icon, 20, Palette.colors.accent), LinearLayout.LayoutParams(Views.dp(this, 20f), Views.dp(this, 20f)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Views.dp(this@OnboardingActivity, 10f), 0, 0, 0)
        }
        texts.addView(Views.text(this, label, 14f, Palette.colors.onSurface, bold = true), matchWrap())
        if (value.isNotBlank()) {
            texts.addView(
                Views.multiline(this, value, 11f, 2).apply { setTextColor(Palette.colors.onSurfaceMuted) },
                matchWrap()
            )
        }
        line.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(
            line,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Views.dp(this@OnboardingActivity, 6f) }
        )
    }

    private fun onNext() {
        if (step < STEPS - 1) {
            step++
            render()
            return
        }
        finishSetup()
    }

    private fun finishSetup() {
        PermissionHub.complete(this)
        if (prefs.weatherEnabled) WeatherRepository.maybeRefresh(true)
        LauncherService.start(this, "onboarding")
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_STEP = "step"
        const val STEPS = 8
        private val PRESETS = listOf(
            "agama" to "Agama",
            "wide" to "Wide",
            "minimal" to "Minimal",
            "carplay" to "CarPlay"
        )
    }
}
