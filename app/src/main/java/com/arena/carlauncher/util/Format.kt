package com.arena.carlauncher.util

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import com.arena.carlauncher.data.LauncherPrefs
import kotlin.math.abs
import kotlin.math.roundToInt

/** Small, allocation-frugal helpers shared by every card. */
object Format {

    private val FA_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    fun toPersianDigits(text: CharSequence): String {
        val c = CharArray(text.length)
        for (i in text.indices) {
            val ch = text[i]
            c[i] = if (ch in '0'..'9') FA_DIGITS[ch - '0'] else ch
        }
        return String(c)
    }

    /** Applies the user's digit preference (Settings → Display → Persian digits). */
    fun localized(ctx: Context, text: CharSequence): String =
        if (LauncherPrefs.get(ctx).usePersianDigits) toPersianDigits(text) else text.toString()

    /** mm:ss below an hour, h:mm:ss above it. */
    fun clock(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000L
        val s = total % 60
        val m = (total / 60) % 60
        val h = total / 3600
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    fun hourMinute(hour: Int, minute: Int, use24: Boolean, faDigits: Boolean): String {
        var h = hour
        var suffix = ""
        if (!use24) {
            suffix = if (h >= 12) " PM" else " AM"
            h %= 12
            if (h == 0) h = 12
        }
        val body = String.format("%02d:%02d", h, minute)
        return if (faDigits) toPersianDigits("$body$suffix") else "$body$suffix"
    }

    fun speed(kmh: Float?, mph: Boolean): String {
        if (kmh == null || kmh.isNaN()) return "--"
        val v = if (mph) kmh * 0.621371f else kmh
        return abs(v).roundToInt().toString()
    }

    fun speedUnit(mph: Boolean) = if (mph) "mph" else "km/h"

    fun temp(celsius: Float?, fahrenheit: Boolean): String {
        if (celsius == null || celsius.isNaN()) return "--"
        val v = if (fahrenheit) celsius * 1.8f + 32f else celsius
        return v.roundToInt().toString()
    }

    fun tempUnit(fahrenheit: Boolean) = if (fahrenheit) "°F" else "°C"

    fun distance(km: Float, faDigits: Boolean): String {
        val s = if (km < 1f) "${(km * 1000).roundToInt()} m"
        else String.format("%.1f km", km)
        return if (faDigits) toPersianDigits(s) else s
    }

    fun percent(v: Float?): String = if (v == null || v.isNaN()) "--" else "${v.roundToInt()}%"

    fun hex(color: Int): String = String.format("#%08X", color)

    fun parseColor(text: String?, fallback: Int): Int = try {
        if (text.isNullOrBlank()) fallback else Color.parseColor(text)
    } catch (_: Throwable) {
        fallback
    }

    fun clamp(v: Float, min: Float, max: Float): Float = if (v < min) min else if (v > max) max else v

    fun lerp(a: Float, t: Float, b: Float) = a + (b - a) * clamp(t, 0f, 1f)

    fun lerpColor(from: Int, to: Int, t: Float): Int {
        val k = clamp(t, 0f, 1f)
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * k).roundToInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * k).roundToInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * k).roundToInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k).roundToInt()
        return Color.argb(a, r, g, b)
    }

    fun withAlpha(color: Int, alphaPercent: Int): Int {
        val a = (255f * clamp(alphaPercent.toFloat(), 0f, 100f) / 100f).roundToInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    fun darken(color: Int, factor: Float): Int = Color.argb(
        Color.alpha(color),
        (Color.red(color) * factor).roundToInt().coerceIn(0, 255),
        (Color.green(color) * factor).roundToInt().coerceIn(0, 255),
        (Color.blue(color) * factor).roundToInt().coerceIn(0, 255)
    )

    /** Relative luminance — used to pick black/white text over the accent colour. */
    fun luminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    fun onColor(background: Int): Int =
        if (luminance(background) > 0.58f) Color.argb(255, 18, 20, 24) else Color.WHITE

    fun isRtl(ctx: Context): Boolean =
        TextUtils.getLayoutDirectionFromLocale(ctx.resources.configuration.locales[0]) ==
            View.LAYOUT_DIRECTION_RTL

    fun join(vararg parts: String?, sep: String = " · "): String =
        parts.filter { !it.isNullOrBlank() }.joinToString(sep)

    fun sp(ctx: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, ctx.resources.displayMetrics)

    fun dp(ctx: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics)
}
