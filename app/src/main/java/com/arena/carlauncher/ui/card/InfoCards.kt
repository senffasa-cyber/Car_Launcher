package com.arena.carlauncher.ui.card

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.arena.carlauncher.R
import com.arena.carlauncher.data.Cards
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.data.VehicleSnapshot
import com.arena.carlauncher.data.WeatherSnapshot
import com.arena.carlauncher.loc.LocationHub
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.ui.Views
import com.arena.carlauncher.ui.widget.AnalogClockView
import com.arena.carlauncher.ui.widget.SpeedometerView
import com.arena.carlauncher.util.Format
import com.arena.carlauncher.util.Jalali
import com.arena.carlauncher.vehicle.TripComputer
import com.arena.carlauncher.vehicle.VehicleHub
import com.arena.carlauncher.weather.WeatherRepository
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Time, sun and weather. The clock card is the "engine off, waiting" screen — the one state a car
 * launcher is in more often than any other — so it carries the Jalali date, the weekday, the sunrise
 * / sunset window and the year progress, none of which any generic clock widget gives you.
 */
class ClockCard(context: Context, full: Boolean) : BaseCard(context, Cards.CLOCK, full) {

    override val iconRes = R.drawable.ic_clock

    private val clock = AnalogClockView(context)
    private val time = Views.text(context, "00:00", if (full) 62f else 26f, Palette.colors.onSurface, bold = true, condensed = true)
    private val seconds = Views.text(context, "", if (full) 22f else 11f, Palette.colors.accent, condensed = true)
    private val jalaliDate = Views.text(context, "", if (full) 16f else 11f, Palette.colors.accent, bold = true)
    private val gregDate = Views.text(context, "", if (full) 11.5f else 9f, Palette.colors.onSurfaceMuted)
    private val sunLine = Views.text(context, "", if (full) 11f else 9f, Palette.colors.onSurfaceMuted, condensed = true)
    private val weatherLine = Views.text(context, "", if (full) 12f else 9.5f, Palette.colors.onSurfaceMuted)
    private val cal = Calendar.getInstance()

    override fun onCreate() {
        if (full) {
            val row = Views.row(context, paddingDp = 0)
            row.addView(
                clock,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT, 0.85f)
            )
            val right = Views.column(context, paddingDp = 0).apply { gravity = Gravity.CENTER }
            val timeRow = Views.row(context)
            timeRow.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            timeRow.addView(time, matchWrap())
            timeRow.addView(seconds, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Views.dp(context, 8f); bottomMargin = Views.dp(context, 6f) })
            right.addView(timeRow, matchWrap())
            right.addView(jalaliDate, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 2f) })
            right.addView(gregDate, matchWrap())
            right.addView(Views.separator(context), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Views.dp(context, 1f)
            ).apply { topMargin = Views.dp(context, 10f); bottomMargin = Views.dp(context, 10f) })
            right.addView(weatherLine, matchWrap())
            right.addView(sunLine, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 3f) })
            row.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f))
            body.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            clock.showYearRing = true
            clock.numerals = true
        } else {
            val row = Views.row(context, paddingDp = 0)
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(
                clock,
                LinearLayout.LayoutParams(Views.dp(context, 52f), Views.dp(context, 52f)).apply { marginEnd = Views.dp(context, 10f) }
            )
            val col = Views.column(context, paddingDp = 0)
            val timeRow = Views.row(context)
            timeRow.gravity = Gravity.CENTER_VERTICAL
            timeRow.addView(time, matchWrap())
            timeRow.addView(seconds, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Views.dp(context, 4f) })
            col.addView(timeRow, matchWrap())
            col.addView(jalaliDate, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 1f) })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            body.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            body.addView(weatherLine, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 8f) })
            clock.showYearRing = false
            clock.showSeconds = false
            clock.numerals = false
        }

        setOnClickListener {
            onRequestCard?.invoke(Cards.WEATHER)
        }
    }

    override fun onBind() {
        observe(WeatherRepository.state) { render(it) }
        render(WeatherRepository.state.value)
        tick()
    }

    override fun onUnbind() {
        removeCallbacks(tickRunnable)
    }

    private fun tick() {
        removeCallbacks(tickRunnable)
        val p = LauncherPrefs.get(context)
        cal.timeInMillis = System.currentTimeMillis()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        val use24 = p.clock24h || DateFormat.is24HourFormat(context)
        val fa = p.usePersianDigits
        time.text = Format.hourMinute(h, m, use24, fa)
        seconds.text = if (p.showSeconds) Format.localized(context, String.format("%02d", s)) else ""
        seconds.visibility = if (p.showSeconds) View.VISIBLE else View.GONE
        clock.showSeconds = p.showSeconds

        val jd = Jalali.fromGregorian(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
        jalaliDate.text = if (p.showJalaliDate) Jalali.formatLong(jd, true, fa) else ""
        jalaliDate.visibility = if (jalaliDate.text.isNullOrBlank()) View.GONE else View.VISIBLE
        gregDate.text = if (p.showGregorianDate)
            Format.localized(context, "${cal.get(Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(Calendar.MONTH)]} ${cal.get(Calendar.YEAR)}")
        else Format.localized(context, Jalali.formatShort(jd, fa))

        val w = WeatherRepository.state.value
        if (w.valid && p.weatherEnabled) {
            weatherLine.text = "${WeatherRepository.labelFor(context, w.code)}  ${Format.temp(w.tempC, p.tempUnitF)}${Format.tempUnit(p.tempUnitF)}"
            weatherLine.visibility = View.VISIBLE
            val sunrise = Format.hourMinute(w.sunriseMin / 60, w.sunriseMin % 60, use24, fa)
            val sunset = Format.hourMinute(w.sunsetMin / 60, w.sunsetMin % 60, use24, fa)
            sunLine.text = context.getString(R.string.clock_sun, sunrise, sunset)
            sunLine.visibility = if (full) View.VISIBLE else View.GONE
        } else {
            weatherLine.visibility = if (full) View.VISIBLE else View.GONE
            weatherLine.text = context.getString(R.string.clock_no_weather)
            sunLine.visibility = View.GONE
        }

        // re-run one second later; the ticker drives only the hands, not the whole layout
        postDelayed(tickRunnable, 1000L - (System.currentTimeMillis() % 1000L))
    }

    private val tickRunnable = Runnable { if (isAttachedToWindow) tick() }

    private fun render(@Suppress("UNUSED_PARAMETER") w: WeatherSnapshot) {
        if (isAttachedToWindow) tick()
    }

    override fun onPaletteChanged() {
        super.onPaletteChanged()
        time.setTextColor(Palette.colors.onSurface)
        seconds.setTextColor(Palette.colors.accent)
        jalaliDate.setTextColor(Palette.colors.accent)
        gregDate.setTextColor(Palette.colors.onSurfaceMuted)
        weatherLine.setTextColor(Palette.colors.onSurfaceMuted)
        sunLine.setTextColor(Palette.colors.onSurfaceMuted)
        clock.invalidate()
    }

    companion object {
        private val MONTHS = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
    }
}

/** Speed, engine and tyre data — with an explicit "where did this come from" chip. */
class VehicleCard(context: Context, full: Boolean) : BaseCard(context, Cards.VEHICLE, full) {

    override val iconRes = R.drawable.ic_gauge

    private val gauge = SpeedometerView(context)
    private val sourceChip = Views.chipLabel(context, "")
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var last: VehicleSnapshot? = null

    override fun onCreate() {
        body.addView(header(context.getString(R.string.card_vehicle), sourceChip), matchWrap())
        if (full) {
            body.addView(
                gauge,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            body.addView(rows, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 6f) })
        } else {
            val row = Views.row(context, paddingDp = 0)
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(gauge, LinearLayout.LayoutParams(Views.dp(context, 74f), Views.dp(context, 74f)))
            row.addView(rows, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Views.dp(context, 8f)
            })
            body.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        setOnClickListener { VehicleHub.refresh() }
    }

    override fun onBind() {
        observe(VehicleHub.state) { render(it) }
        render(VehicleHub.state.value)
    }

    @SuppressLint("SetTextI18n")
    private fun render(s: VehicleSnapshot) {
        if (s == last) return
        last = s
        val p = LauncherPrefs.get(context)
        gauge.speedKmh = s.speedKmh ?: 0f
        gauge.rpm = s.rpm
        gauge.fuelPct = s.fuelPct
        gauge.digitalOnly = !s.hasData
        gauge.label = if (s.source == VehicleSnapshot.SOURCE_GPS) context.getString(R.string.veh_from_gps) else ""

        sourceChip.text = when (s.source) {
            VehicleSnapshot.SOURCE_BROADCAST -> context.getString(R.string.veh_from_bus)
            VehicleSnapshot.SOURCE_CAR_API -> context.getString(R.string.veh_from_vhal)
            else -> context.getString(R.string.veh_from_gps_short)
        }

        rows.removeAllViews()
        fun add(label: String, value: String, accent: Boolean = false) {
            rows.addView(
                stat(label, value, accent),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = Views.dp(context, if (full) 18f else 10f)
                    topMargin = Views.dp(context, if (full) 6f else 0f)
                }
            )
        }
        if (full) {
            val grid = Views.row(context, paddingDp = 0)
            grid.addView(stat(context.getString(R.string.veh_rpm), if (s.rpm == null) "--" else Format.localized(context, "${s.rpm!!.roundToInt()}")))
            grid.addView(Views.weightSpacer(context))
            grid.addView(stat(context.getString(R.string.veh_coolant), if (s.coolantC == null) "--" else Format.localized(context, Format.temp(s.coolantC, p.tempUnitF) + Format.tempUnit(p.tempUnitF))))
            grid.addView(Views.weightSpacer(context))
            grid.addView(stat(context.getString(R.string.veh_fuel), Format.localized(context, Format.percent(s.fuelPct))))
            rows.addView(grid, matchWrap())
        } else {
            add(context.getString(R.string.veh_fuel), Format.localized(context, Format.percent(s.fuelPct)))
            add(context.getString(R.string.veh_temp), Format.localized(context, Format.temp(s.coolantC, p.tempUnitF)))
        }

        if (s.tires != null && full) {
            val t = s.tires!!
            val tyreRow = Views.row(context).apply { gravity = Gravity.CENTER }
            val names = arrayOf("FL", "FR", "RL", "RR")
            for (i in 0 until 4) {
                val v = t.getOrElse(i) { Float.NaN }
                val color = when {
                    v.isNaN() -> Palette.colors.onSurfaceMuted
                    v < 195f || v > 275f -> Palette.colors.danger
                    else -> Palette.colors.ok
                }
                tyreRow.addView(
                    Views.text(
                        context,
                        "${names[i]}\n${if (v.isNaN()) "--" else Format.localized(context, v.roundToInt().toString())}",
                        11f, color, bold = true
                    ).apply { gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            rows.addView(tyreRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 8f) })
        }

        if (s.doors != 0) {
            rows.addView(
                Views.text(
                    context,
                    context.getString(R.string.veh_doors_open, s.doorCount),
                    11.5f, Palette.colors.warn, bold = true
                ),
                matchWrap()
            )
        }
        if (!s.hasData && full) {
            rows.addView(
                Views.text(context, context.getString(R.string.veh_no_bus), 11f, Palette.colors.onSurfaceMuted),
                matchWrap()
            )
        }
    }

    override fun onPaletteChanged() {
        super.onPaletteChanged()
        last = null
        gauge.invalidate()
    }
}

/** Four-day forecast strip; falls back to "no GPS / offline" copy instead of a spinner. */
class WeatherCard(context: Context, full: Boolean) : BaseCard(context, Cards.WEATHER, full) {

    override val iconRes = R.drawable.ic_weather_sun

    private val icon = Views.icon(context, R.drawable.ic_weather_sun, if (full) 54 else 28, Palette.colors.accent)
    private val temp = Views.text(context, "--", if (full) 40f else 20f, Palette.colors.onSurface, bold = true, condensed = true)
    private val desc = Views.text(context, "", if (full) 13f else 10f, Palette.colors.onSurfaceMuted)
    private val detail = Views.text(context, "", 11f, Palette.colors.onSurfaceMuted)
    private val days = Views.row(context, paddingDp = 0)

    override fun onCreate() {
        val p = LauncherPrefs.get(context)
        body.addView(header(context.getString(R.string.card_weather), Views.chipLabel(context, "")), matchWrap())
        val main = Views.row(context, paddingDp = 0)
        main.gravity = Gravity.CENTER_VERTICAL
        main.addView(icon, LinearLayout.LayoutParams(Views.dp(context, if (full) 54f else 28f), Views.dp(context, if (full) 54f else 28f)))
        val col = Views.column(context, paddingDp = 0).apply { setPadding(Views.dp(context, 12f), 0, 0, 0) }
        col.addView(temp, matchWrap())
        col.addView(desc, matchWrap())
        main.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(main, matchWrap())

        if (full) {
            detail.text = ""
            body.addView(detail, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 8f) })
            days.gravity = Gravity.CENTER_VERTICAL
            body.addView(days, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 10f) })
        }
        if (p.weatherEnabled) WeatherRepository.maybeRefresh()
        setOnClickListener { WeatherRepository.maybeRefresh(true) }
    }

    override fun onBind() {
        observe(WeatherRepository.state) { render(it) }
        render(WeatherRepository.state.value)
    }

    @SuppressLint("SetTextI18n")
    private fun render(w: WeatherSnapshot) {
        val p = LauncherPrefs.get(context)
        if (!w.valid) {
            desc.text = context.getString(R.string.clock_no_weather)
            temp.text = "--"
            days.removeAllViews()
            detail.text = ""
            return
        }
        icon.setImageResource(iconFor(w.code))
        temp.text = Format.localized(context, Format.temp(w.tempC, p.tempUnitF)) + Format.tempUnit(p.tempUnitF)
        desc.text = WeatherRepository.labelFor(context, w.code)
        if (full) {
            detail.text = context.getString(
                R.string.weather_detail,
                Format.localized(context, Format.temp(w.feelsC, p.tempUnitF)) + Format.tempUnit(p.tempUnitF),
                Format.localized(context, "${w.humidity}%"),
                Format.localized(context, "${w.windKmh.roundToInt()} km/h")
            )
            days.removeAllViews()
            w.daily.take(4).forEachIndexed { i, d ->
                val cell = Views.column(context, paddingDp = 0).apply { gravity = Gravity.CENTER }
                val dayLabel = when (i) {
                    0 -> context.getString(R.string.day_today)
                    1 -> context.getString(R.string.day_tomorrow)
                    else -> {
                        val c = Calendar.getInstance()
                        c.add(Calendar.DAY_OF_MONTH, i)
                        Jalali.WEEKDAYS_SHORT_FA[Jalali.weekdayOf(Jalali.daysFromCivil(
                            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
                        ))]
                    }
                }
                cell.addView(Views.text(context, dayLabel, 10f, Palette.colors.onSurfaceMuted), matchWrap())
                cell.addView(Views.icon(context, iconFor(d.code), 22, Palette.colors.accent), matchWrap())
                cell.addView(
                    Views.text(
                        context,
                        Format.localized(context, "${Format.temp(d.maxC, p.tempUnitF)}° / ${Format.temp(d.minC, p.tempUnitF)}°"),
                        10.5f, Palette.colors.onSurface, condensed = true
                    ), matchWrap()
                )
                days.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }

    private fun iconFor(code: Int): Int = when (code) {
        0 -> R.drawable.ic_weather_sun
        1, 2 -> R.drawable.ic_weather_partly
        3 -> R.drawable.ic_weather_cloud
        45, 48 -> R.drawable.ic_weather_fog
        in 51..57 -> R.drawable.ic_weather_rain
        in 61..67, in 80..82 -> R.drawable.ic_weather_rain
        in 71..86 -> R.drawable.ic_weather_snow
        in 95..99 -> R.drawable.ic_weather_storm
        else -> R.drawable.ic_weather_cloud
    }

    override fun onPaletteChanged() {
        super.onPaletteChanged()
        temp.setTextColor(Palette.colors.onSurface)
        desc.setTextColor(Palette.colors.onSurfaceMuted)
    }
}
