package com.arena.carlauncher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.util.Format
import kotlin.math.roundToInt

/**
 * Arc speedometer that doubles as the "no car bus" gauge.
 *
 * The ring is driven by GPS speed when the CAN bridge / VHAL is absent — the value is honest but it
 * is *not* the wheel speed, so [digitalOnly] mode exists for units whose owner only wants the number
 * and the trip line. Sweeping is animated locally between the 1 Hz samples, which hides the
 * staircase that a raw GPS feed would otherwise show.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var speedKmh: Float = 0f
        set(value) {
            val v = if (value.isNaN()) 0f else value.coerceAtLeast(0f)
            field = v
            animateTo(v)
        }
    var maxSpeed: Float = 180f
    var digitalOnly = false
    var rpm: Float? = null
    var fuelPct: Float? = null
    var label: String = ""

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    private var shown = 0f
    private var target = 0f
    private var animating = false

    private val animator = object : Runnable {
        override fun run() {
            val d = target - shown
            if (abs(d) < 0.6f) {
                shown = target
                animating = false
                invalidate()
                return
            }
            shown += d * 0.18f
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun abs(v: Float) = if (v < 0) -v else v

    private fun animateTo(v: Float) {
        target = v
        if (!animating) {
            animating = true
            postOnAnimation(animator)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animator)
        animating = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val c = Palette.colors
        val mph = LauncherPrefs.get(context).speedUnitMph
        val w = width.toFloat()
        val h = height.toFloat()

        if (digitalOnly) {
            text.color = c.onSurface
            text.textSize = minOf(w, h) * 0.52f
            text.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            val value = Format.speed(target, mph)
            canvas.drawText(
                if (Format.localized(context, value) == value) value else Format.toPersianDigits(value),
                w / 2f,
                h / 2f - (text.descent() + text.ascent()) / 2f,
                text
            )
            text.typeface = Typeface.DEFAULT
            text.textSize = minOf(w, h) * 0.14f
            text.color = c.onSurfaceMuted
            canvas.drawText(Format.speedUnit(mph), w / 2f, h * 0.84f, text)
            return
        }

        val pad = minOf(w, h) * 0.10f
        val left = pad
        val top = pad * 0.7f
        val right = w - pad
        val bottom = h - pad * 1.1f
        rect.set(left, top, right, bottom)
        val sweepTotal = 250f
        val startAngle = 145f

        // track
        arc.strokeWidth = minOf(w, h) * 0.085f
        arc.color = Format.withAlpha(c.onSurface, if (c.night) 30 else 22)
        canvas.drawArc(rect, startAngle, sweepTotal, false, arc)

        // progress
        val frac = Format.clamp(shown / maxSpeed, 0f, 1f)
        arc.color = if (frac > 0.86f) c.danger else if (frac > 0.66f) c.warn else c.accent
        canvas.drawArc(rect, startAngle, sweepTotal * frac, false, arc)

        // ticks
        arc.strokeWidth = dp(1.6f)
        arc.color = Format.withAlpha(c.onSurface, 120)
        val cx = w / 2f
        val cy = (top + bottom) / 2f
        val rOuter = (right - left) / 2f + dp(2f)
        var i = 0
        while (i <= 10) {
            val a = Math.toRadians((startAngle + sweepTotal * i / 10f).toDouble())
            val sx = cx + Math.cos(a) * (rOuter - dp(6f))
            val sy = cy + Math.sin(a) * (rOuter - dp(6f))
            val ex = cx + Math.cos(a) * (rOuter + dp(1f))
            val ey = cy + Math.sin(a) * (rOuter + dp(1f))
            canvas.drawLine(sx.toFloat(), sy.toFloat(), ex.toFloat(), ey.toFloat(), arc)
            i++
        }

        val value = Format.speed(target, mph)
        text.color = c.onSurface
        text.textSize = minOf(w, h) * 0.34f
        text.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        canvas.drawText(value, cx, cy + text.textSize * 0.05f, text)

        text.textSize = minOf(w, h) * 0.105f
        text.typeface = Typeface.DEFAULT
        text.color = c.onSurfaceMuted
        canvas.drawText(Format.speedUnit(mph), cx, cy + minOf(w, h) * 0.2f, text)

        if (label.isNotBlank()) {
            text.textSize = sp(10f)
            canvas.drawText(label, cx, bottom + dp(12f), text)
        }

        // fuel arc inside the gauge — the one car number drivers check most
        fuelPct?.let { f ->
            if (!f.isNaN()) {
                val rr = rect
                val inset = minOf(w, h) * 0.16f
                rr.inset(inset, inset)
                arc.strokeWidth = dp(4f)
                arc.color = Format.withAlpha(c.onSurface, 30)
                canvas.drawArc(rr, startAngle, sweepTotal, false, arc)
                val fp = Format.clamp(f / 100f, 0f, 1f)
                arc.color = if (fp < 0.13f) c.danger else c.ok
                canvas.drawArc(rr, startAngle, sweepTotal * fp, false, arc)
                text.textSize = sp(9f)
                text.color = c.onSurfaceMuted
                canvas.drawText("${f.roundToInt()}%", rr.centerX(), rr.bottom + sp(11f), text)
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
