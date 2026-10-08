package com.arena.carlauncher.ui

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.util.Format

/**
 * Programmatic view construction.
 *
 * The launcher has ~40 small labels that all follow the palette; expressing them in XML would mean
 * 40 layout files plus a theme per night mode, and every colour change would need a recreate. These
 * builders keep the look in one place and let a card be rebuilt in `onPaletteChanged()` cheaply.
 */
object Views {

    fun toast(ctx: Context, resId: Int) =
        android.widget.Toast.makeText(ctx, resId, android.widget.Toast.LENGTH_SHORT).show()

    fun toast(ctx: Context, text: String) =
        android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show()


    fun dp(ctx: Context, v: Float): Int = Format.dp(ctx, v).toInt()
    fun sp(ctx: Context, v: Float): Float = Format.sp(ctx, v) * scale(ctx)

    private fun scale(ctx: Context): Float = try {
        LauncherPrefs.get(ctx).textScale
    } catch (_: Throwable) {
        1.0f
    }

    fun column(ctx: Context, vertical: Boolean = true, paddingDp: Int = 14): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            val p = dp(ctx, paddingDp.toFloat())
            setPadding(p, p, p, p)
            gravity = if (vertical) Gravity.START else Gravity.CENTER_VERTICAL
        }

    fun row(ctx: Context, paddingDp: Int = 0): LinearLayout = column(ctx, vertical = false, paddingDp = paddingDp)

    fun text(
        ctx: Context,
        value: String = "",
        sizeSp: Float = 14f,
        color: Int = Palette.colors.onSurface,
        bold: Boolean = false,
        gravity: Int = Gravity.START,
        condensed: Boolean = false
    ): TextView = AppCompatTextView(ctx).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(ctx, sizeSp))
        typeface = when {
            bold && condensed -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            condensed -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
        this.gravity = gravity
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun multiline(ctx: Context, value: String = "", sizeSp: Float = 12f, lines: Int = 3): TextView =
        text(ctx, value, sizeSp, Palette.colors.onSurfaceMuted).apply {
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.06f)
        }

    fun marquee(ctx: Context, value: String = "", sizeSp: Float = 16f, color: Int = Palette.colors.onSurface, bold: Boolean = true): TextView =
        text(ctx, value, sizeSp, color, bold).apply {
            isSelected = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSingleLine = true
            setHorizontalFadingEdgeEnabled(true)
            setFadingEdgeLength(dp(ctx, 18f))
        }

    fun icon(ctx: Context, resId: Int, sizeDp: Int, tint: Int = Palette.colors.onSurface): ImageView =
        AppCompatImageView(ctx).apply {
            setImageResource(resId)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp.toFloat()), dp(ctx, sizeDp.toFloat()))
        }

    fun iconButton(
        ctx: Context,
        resId: Int,
        sizeDp: Int = 44,
        iconDp: Int = 22,
        tint: Int = Palette.colors.onSurface,
        background: Boolean = true,
        onClick: () -> Unit
    ): ImageView = AppCompatImageView(ctx).apply {
        setImageResource(resId)
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val s = dp(ctx, sizeDp.toFloat())
        val lp = LinearLayout.LayoutParams(s, s)
        layoutParams = lp
        val pad = dp(ctx, iconDp.toFloat() / 2f)
        setPadding(pad, pad, pad, pad)
        if (background) this.background = Palette.chip(ctx)
        isClickable = true
        isFocusable = true
        contentDescription = null
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
    }

    /** Circular media button (play/pause) — bigger accent fill, white glyph. */
    fun accentButton(ctx: Context, resId: Int, sizeDp: Int, onClick: () -> Unit): ImageView {
        val v = AppCompatImageView(ctx)
        v.setImageResource(resId)
        v.setColorFilter(Palette.colors.onAccent)
        v.scaleType = ImageView.ScaleType.CENTER_INSIDE
        v.background = Palette.fill(ctx, Palette.colors.accent, sizeDp / 2f)
        v.layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp.toFloat()), dp(ctx, sizeDp.toFloat()))
        v.isClickable = true
        v.setOnClickListener {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        return v
    }

    fun spacer(ctx: Context, sizeDp: Float = 8f): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            if (sizeDp > 0) dp(ctx, sizeDp) else 0,
            if (sizeDp > 0) dp(ctx, sizeDp) else 0
        )
    }

    fun weightSpacer(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
    }

    fun chipLabel(ctx: Context, value: String, accent: Boolean = false): TextView =
        text(ctx, value, 10.5f, if (accent) Palette.colors.onAccent else Palette.colors.onSurfaceMuted).apply {
            val px = dp(ctx, 8f)
            val py = dp(ctx, 3f)
            setPadding(px, py, px, py)
            background = if (accent) Palette.fill(ctx, Palette.colors.accent, 8f)
            else Palette.fill(ctx, Palette.colors.surfaceRaised, 8f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
        }

    fun separator(ctx: Context, vertical: Boolean = false): View = View(ctx).apply {
        setBackgroundColor(Palette.colors.outline)
        layoutParams = if (vertical) LinearLayout.LayoutParams(dp(ctx, 1f), ViewGroup.LayoutParams.MATCH_PARENT)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f))
    }

    /** Label / value pair used in the vehicle, trip and weather rows. */
    fun statRow(ctx: Context, label: String, value: String): LinearLayout {
        val row = row(ctx)
        row.addView(
            text(ctx, label, 12f, Palette.colors.onSurfaceMuted),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            text(ctx, value, 13f, Palette.colors.onSurface, bold = true, condensed = true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(ctx, 8f) }
        )
        return row
    }
}
