package com.arena.carlauncher.ui.card

import android.content.Context
import com.arena.carlauncher.R
import com.arena.carlauncher.data.Cards

/**
 * The one place that knows which card class answers to which id.
 *
 * Ids are what gets persisted in preferences, so they are stable strings and never ordinals: a user
 * who exported their layout on an older build must still get the same cards after an upgrade.
 */
object CardFactory {

    /** Every id the settings editor offers, in display order. */
    val ALL = listOf(
        Cards.MUSIC,
        Cards.MAP,
        Cards.CLOCK,
        Cards.VEHICLE,
        Cards.WEATHER,
        Cards.TRIP,
        Cards.TILES,
        Cards.NAV,
        Cards.PHONE,
        Cards.CAMERA,
        Cards.WIDGET,
        Cards.SETTINGS
    )

    fun create(ctx: Context, id: String, full: Boolean): BaseCard = when (id) {
        Cards.MUSIC -> MusicCard(ctx, full)
        Cards.MAP -> MapCard(ctx, full)
        Cards.CLOCK -> ClockCard(ctx, full)
        Cards.VEHICLE -> VehicleCard(ctx, full)
        Cards.WEATHER -> WeatherCard(ctx, full)
        Cards.TRIP -> TripCard(ctx, full)
        Cards.TILES -> TilesCard(ctx, full)
        Cards.NAV -> NavCard(ctx, full)
        Cards.PHONE -> PhoneCard(ctx, full)
        Cards.CAMERA -> CameraCard(ctx, full)
        Cards.WIDGET -> WidgetCard(ctx, full)
        Cards.SETTINGS -> SettingsCard(ctx, full)
        else -> PlaceholderCard(ctx, if (id.isBlank()) "?" else id)
    }

    fun titleRes(id: String): Int = when (id) {
        Cards.MUSIC -> R.string.card_music
        Cards.MAP -> R.string.card_map
        Cards.CLOCK -> R.string.card_clock
        Cards.VEHICLE -> R.string.card_vehicle
        Cards.WEATHER -> R.string.card_weather
        Cards.TRIP -> R.string.card_trip
        Cards.TILES -> R.string.card_tiles
        Cards.NAV -> R.string.card_nav
        Cards.PHONE -> R.string.card_phone
        Cards.CAMERA -> R.string.card_camera
        Cards.WIDGET -> R.string.card_widget
        Cards.SETTINGS -> R.string.card_settings
        else -> R.string.card_unknown_short
    }

    fun iconRes(id: String): Int = when (id) {
        Cards.MUSIC -> R.drawable.ic_music
        Cards.MAP -> R.drawable.ic_map
        Cards.CLOCK -> R.drawable.ic_clock
        Cards.VEHICLE -> R.drawable.ic_gauge
        Cards.WEATHER -> R.drawable.ic_weather_partly
        Cards.TRIP -> R.drawable.ic_trip
        Cards.TILES -> R.drawable.ic_snowflake
        Cards.NAV -> R.drawable.ic_navigation
        Cards.PHONE -> R.drawable.ic_phone
        Cards.CAMERA -> R.drawable.ic_video
        Cards.WIDGET -> R.drawable.ic_widget
        Cards.SETTINGS -> R.drawable.ic_settings
        else -> R.drawable.ic_info
    }

    fun isKnown(id: String): Boolean = ALL.contains(id)
}
