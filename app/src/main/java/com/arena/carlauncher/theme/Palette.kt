package com.arena.carlauncher.theme

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.util.Format
import java.util.ArrayList

/**
 * Runtime colour engine.
 *
 * A car launcher must repaint instantly when the headlights flip the unit into night mode, so
 * colours are not frozen into XML themes: [Palette] computes a [Colors] set from prefs +
 * configuration on demand. XML `values-night` still exists as a floor for the window background so
 * there is never a white flash when an Activity recreates.
 */
object Palette {

    data class Colors(
        val background: Int,
        val surface: Int,
        val surfaceSolid: Int,
        val surfaceRaised: Int,
        val onSurface: Int,
        val onSurfaceMuted: Int,
        val outline: Int,
        val accent: Int,
        val onAccent: Int,
        val ok: Int,
        val warn: Int,
        val danger: Int,
        val glass: Int,
        val night: Boolean
    )

    private var prefs: LauncherPrefs? = null
    private var context: Context? = null
    private val listeners = ArrayList<() -> Unit>()

    var colors: Colors = darkColors(0xFF3DDC97.toInt())
        private set

    var night: Boolean = true
        private set

    fun install(ctx: Context, prefs: LauncherPrefs) {
        this.context = ctx.applicationContext
        this.prefs = prefs
        refresh()
        prefs.addChangeListener { refresh() }
    }

    fun addListener(listener: () -> Unit) = synchronized(listeners) { listeners.add(listener) }

    fun removeListener(listener: () -> Unit) = synchronized(listeners) { listeners.remove(listener) }

    fun isAmoled() = prefs?.themeMode == LauncherPrefs.THEME_AMOLED
    fun mode() = prefs?.themeMode ?: LauncherPrefs.THEME_AUTO
    fun prefs() = prefs

    fun refresh() {
        val p = prefs ?: return
        val ctx = context ?: return
        night = when (p.themeMode) {
            LauncherPrefs.THEME_LIGHT -> false
            LauncherPrefs.THEME_DARK, LauncherPrefs.THEME_AMOLED -> true
            else -> systemNight(ctx) || DayNightController.astronomicalNight(ctx)
        }
        val accent = p.accentColor
        colors = when {
            !night -> lightColors(accent)
            p.themeMode == LauncherPrefs.THEME_AMOLED -> amoledColors(accent)
            else -> darkColors(accent)
        }
        val snapshot = synchronized(listeners) { ArrayList(listeners) }
        snapshot.forEach { try { it() } catch (_: Throwable) {} }
    }

    fun systemNight(ctx: Context): Boolean = try {
        val mode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        mode == Configuration.UI_MODE_NIGHT_YES
    } catch (_: Throwable) {
        false
    }

    private fun lightColors(accent: Int) = Colors(
        background = 0xFFF1F4F9.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceSolid = 0xFFFFFFFF.toInt(),
        surfaceRaised = 0xFFEFF3F9.toInt(),
        onSurface = 0xFF12161D.toInt(),
        onSurfaceMuted = 0x9912161D.toInt(),
        outline = 0x1F000000,
        accent = accent,
        onAccent = Format.onColor(accent),
        ok = 0xFF1A9E5C.toInt(),
        warn = 0xFFC9861B.toInt(),
        danger = 0xFFD2413D.toInt(),
        glass = 0xE6FFFFFF.toInt(),
        night = false
    )

    private fun darkColors(accent: Int) = Colors(
        background = 0xFF0F131A.toInt(),
        surface = 0xFF1A2029.toInt(),
        surfaceSolid = 0xFF1A2029.toInt(),
        surfaceRaised = 0xFF232A35.toInt(),
        onSurface = 0xFFEAEFF6.toInt(),
        onSurfaceMuted = 0xB3EAEFF6.toInt(),
        outline = 0x33FFFFFF,
        accent = accent,
        onAccent = Format.onColor(accent),
        ok = 0xFF49D68E.toInt(),
        warn = 0xFFF2C14E.toInt(),
        danger = 0xFFFF6B6B.toInt(),
        glass = 0xE610141B.toInt(),
        night = true
    )

    private fun amoledColors(accent: Int) = Colors(
        background = Color.BLACK,
        surface = 0xFF05070A.toInt(),
        surfaceSolid = Color.BLACK,
        surfaceRaised = 0xFF0D1116.toInt(),
        onSurface = 0xFFF2F5FA.toInt(),
        onSurfaceMuted = 0xB3F2F5FA.toInt(),
        outline = 0x2AFFFFFF,
        accent = accent,
        onAccent = Format.onColor(accent),
        ok = 0xFF49D68E.toInt(),
        warn = 0xFFF2C14E.toInt(),
        danger = 0xFFFF6B6B.toInt(),
        glass = 0xF2000000.toInt(),
        night = true
    )

    /** Translucent card background with a hairline border and a soft top-to-bottom sheen. */
    fun card(
        ctx: Context,
        radiusDp: Float = -1f,
        alphaPercent: Int = -1,
        border: Boolean = true,
        clickable: Boolean = true
    ): Drawable {
        val p = prefs
        val r = Format.dp(ctx, if (radiusDp < 0) (p?.cornerRadiusDp ?: 22).toFloat() else radiusDp)
        val a = if (alphaPercent < 0) (p?.cardAlphaPercent ?: 82) else alphaPercent
        val fill = Format.withAlpha(colors.surface, a)
        val top = Format.withAlpha(colors.surfaceRaised, if (a > 12) (a * 0.55f).toInt() else a)
        val shape = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, fill)).apply {
            cornerRadius = r
            if (border) setStroke(Format.dp(ctx, 1f).toInt().coerceAtLeast(1), Format.withAlpha(colors.outline, 90))
        }
        if (!clickable) return shape
        val mask = GradientDrawable().apply {
            cornerRadius = r
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(Format.withAlpha(colors.accent, 70)), shape, mask)
    }

    fun fill(ctx: Context, color: Int, radiusDp: Float = -1f, strokeColor: Int = 0, strokeDp: Float = 1f): GradientDrawable {
        val r = Format.dp(ctx, if (radiusDp < 0) (prefs?.cornerRadiusDp ?: 22).toFloat() else radiusDp)
        return GradientDrawable().apply {
            cornerRadius = r
            setColor(color)
            if (strokeColor != 0) setStroke(Format.dp(ctx, strokeDp).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun chip(ctx: Context, selected: Boolean = false): Drawable {
        val fill = if (selected) colors.accent
        else Format.withAlpha(colors.surfaceRaised, if (night) 90 else 78)
        val d = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Format.dp(ctx, 999f)
            setColor(fill)
            if (!selected) setStroke(Format.dp(ctx, 1f).toInt(), Format.withAlpha(colors.outline, 90))
        }
        return if (selected) d else RippleDrawable(
            ColorStateList.valueOf(Format.withAlpha(colors.accent, 55)), d, d
        )
    }

    fun scrimColor(): Int = Format.withAlpha(Color.BLACK, prefs?.wallpaperDim ?: 24)

    fun accent(): Int = colors.accent
    fun onSurface(): Int = colors.onSurface
    fun muted(): Int = colors.onSurfaceMuted
    fun danger(): Int = colors.danger
    fun ok(): Int = colors.ok
}
