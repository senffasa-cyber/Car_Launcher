package com.arena.carlauncher.settings

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import com.arena.carlauncher.R
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.ui.Views
import com.arena.carlauncher.util.Format

/**
 * Hand-rolled preference rows.
 *
 * `androidx.preference` would mean a Fragment lifecycle, an XML resource per row and a theme that
 * fights the launcher's palette: on a 2.7 GB head unit that is a few MB of APK and a second view
 * system for nothing. A settings screen for a car launcher is a list of rows, and five builders cover
 * every control type the launcher needs.
 */
object PrefRows {

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun weightWrap() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    // ------------------------------------------------------------------ shells

    fun section(ctx: Context, title: String, note: String = ""): View {
        val col = Views.column(ctx, paddingDp = 0)
        col.addView(
            Views.text(ctx, title.uppercase(java.util.Locale.ROOT), 11.5f, Palette.colors.accent, bold = true).apply {
                letterSpacing = 0.08f
            },
            matchWrap()
        )
        if (note.isNotBlank()) {
            col.addView(
                Views.multiline(ctx, note, 10.5f, 4).apply { setTextColor(Palette.colors.onSurfaceMuted) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Views.dp(ctx, 3f) })
        }
        return col
    }

    /**
     * The only real row layout: optional leading icon, title + summary, optional trailing view.
     * Every control below is this row plus something, so the screen stays visually consistent.
     */
    private fun row(
        ctx: Context,
        title: String,
        summary: String,
        iconRes: Int = 0,
        trailing: View? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Views.dp(ctx, 12f), Views.dp(ctx, 10f), Views.dp(ctx, 12f), Views.dp(ctx, 10f))
            background = Palette.chip(ctx, false)
            isClickable = onClick != null
            isFocusable = onClick != null
            onClick?.let { block ->
                setOnClickListener { v ->
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    block()
                }
            }
        }
        if (iconRes != 0) {
            val size = Views.dp(ctx, 22f)
            line.addView(
                ImageView(ctx).apply {
                    setImageResource(iconRes)
                    setColorFilter(Palette.colors.accent)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(0, 0, Views.dp(ctx, 10f), 0)
                },
                LinearLayout.LayoutParams(size + Views.dp(ctx, 10f), size)
            )
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        texts.addView(Views.text(ctx, title, 14f, Palette.colors.onSurface, bold = true), matchWrap())
        if (summary.isNotBlank()) {
            texts.addView(
                Views.multiline(ctx, summary, 11f, 3).apply { setTextColor(Palette.colors.onSurfaceMuted) },
                matchWrap()
            )
        }
        line.addView(texts, weightWrap())
        if (trailing != null) line.addView(trailing, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = Views.dp(ctx, 8f) })
        return line
    }

    // ------------------------------------------------------------------ controls

    fun action(ctx: Context, title: String, summary: String = "", iconRes: Int = 0, onClick: () -> Unit): View =
        row(ctx, title, summary, iconRes, Views.icon(ctx, R.drawable.ic_chevron_right, 16, Palette.colors.onSurfaceMuted), onClick)

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    fun switchRow(
        ctx: Context,
        title: String,
        summary: String = "",
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val sw = Switch(ctx).apply {
            isChecked = checked
            setOnClickListener { onChange(isChecked) }
        }
        return row(ctx, title, summary, 0, sw)
    }

    /** Single choice, rendered as inline chips: no dialog, no fragment, glove-sized targets. */
    fun choice(
        ctx: Context,
        title: String,
        summary: String,
        labels: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit
    ): View {
        val col = Views.column(ctx, paddingDp = 0)
        col.addView(row(ctx, title, summary), matchWrap())
        val chips = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Views.dp(ctx, 12f), Views.dp(ctx, 8f), Views.dp(ctx, 12f), Views.dp(ctx, 10f))
        }
        labels.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            chips.addView(
                Views.text(
                    ctx, label, 12.5f,
                    if (selected) Palette.colors.onAccent else Palette.colors.onSurface, bold = true
                ).apply {
                    background = Palette.chip(ctx, selected)
                    setPadding(Views.dp(ctx, 14f), Views.dp(ctx, 8f), Views.dp(ctx, 14f), Views.dp(ctx, 8f))
                    isClickable = true
                    setOnClickListener { onPick(i) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = Views.dp(ctx, 6f) }
            )
        }
        col.addView(chips, matchWrap())
        return col
    }

    /** Free text (also used for numbers via [number]). */
    fun text(
        ctx: Context,
        title: String,
        summary: String,
        value: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        units: String = "",
        onCommit: (String) -> Unit
    ): View {
        val edit = EditText(ctx).apply {
            setText(value)
            setSingleLine()
            this.inputType = inputType
            textSize = 14f
            setTextColor(Palette.colors.onSurface)
            background = Palette.chip(ctx, true)
            val p = Views.dp(ctx, 10f)
            setPadding(p, p, p, p)
            gravity = Gravity.CENTER_VERTICAL
        }
        val ok = Views.text(
            ctx, if (units.isBlank()) ctx.getString(R.string.ok) else units,
            12.5f, Palette.colors.onAccent, bold = true
        ).apply {
            isClickable = true
            setOnClickListener { onCommit(edit.text.toString().trim()) }
        }
        val fields = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Views.dp(ctx, 12f), Views.dp(ctx, 4f), Views.dp(ctx, 12f), Views.dp(ctx, 10f))
        }
        fields.addView(edit, weightWrap())
        fields.addView(ok, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = Views.dp(ctx, 8f) })
        val col = Views.column(ctx, paddingDp = 0)
        col.addView(row(ctx, title, summary), matchWrap())
        col.addView(fields, matchWrap())
        return col
    }

    fun number(
        ctx: Context,
        title: String,
        summary: String,
        value: Float,
        decimals: Int = 0,
        min: Float = Float.MIN_VALUE,
        max: Float = Float.MAX_VALUE,
        units: String = "",
        onCommit: (Float) -> Unit
    ): View {
        val text = if (decimals > 0) String.format(java.util.Locale.US, "%.${decimals}f", value)
        else value.toInt().toString()
        return text(
            ctx, title, summary, text,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED,
            units
        ) { raw ->
            val v = raw.toFloatOrNull()
            if (v == null) {
                Views.toast(ctx, R.string.pref_needs_value)
                return@text
            }
            onCommit(Format.clamp(v, min, max))
        }
    }

    fun slider(
        ctx: Context,
        title: String,
        summary: String,
        value: Int,
        min: Int,
        max: Int,
        step: Int = 1,
        unit: String = "",
        onChange: (Int) -> Unit
    ): View {
        var current = value.coerceIn(min, max)
        val label = Views.text(ctx, "", 13f, Palette.colors.accent, bold = true, condensed = true)
        fun render() {
            label.text = "$current$unit"
        }
        render()
        val minus = stepButton(ctx, R.drawable.ic_minus) {
            current = (current - step).coerceAtLeast(min)
            render()
            onChange(current)
        }
        val plus = stepButton(ctx, R.drawable.ic_plus) {
            current = (current + step).coerceAtMost(max)
            render()
            onChange(current)
        }
        val trailing = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(minus, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Views.dp(ctx, 8f); marginEnd = Views.dp(ctx, 8f) })
            addView(plus, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        return row(ctx, title, summary, 0, trailing)
    }

    private fun stepButton(ctx: Context, icon: Int, onClick: () -> Unit): ImageView =
        Views.iconButton(ctx, icon, 34, 16, onClick = onClick)

    fun spacer(ctx: Context, dp: Int = 10): View = View(ctx).apply {
        layoutParams = matchWrap()
        minimumHeight = Views.dp(ctx, dp.toFloat())
    }
}
