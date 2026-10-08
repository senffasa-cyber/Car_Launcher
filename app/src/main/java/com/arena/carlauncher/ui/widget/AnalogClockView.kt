package com.arena.carlauncher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.ui.TimeTicker
import com.arena.carlauncher.util.Format
import com.arena.carlauncher.util.Jalali
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Vector analogue clock with an optional Persian-year progress ring.
 *
 * Drawn by hand on purpose: the framework's `AnalogClock`/`TextClock` ignore the launcher's
 * palette, cannot show a Jalali ring, and re-read the system 12/24 setting (which on these units is
 * frequently wrong after a firmware update). One Canvas with cached paints costs a fraction of a
 * millisecond per beat.
 */
class AnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), TimeTicker.Tick {

    var showSeconds = true
    var smoothSeconds = false
    var showYearRing = true
    var numerals = true
    var accentHands = true

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val arc = RectF()
    private val cal = Calendar.getInstance()

    private var hour = -1
    private var minute = -1
    private var second = -1
    private var subSecond = 0f

    private val paletteListener: () -> Unit = { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Palette.addListener(paletteListener)
        TimeTicker.register(this, this)
        showSeconds = LauncherPrefs.get(context).showSeconds
        onTick(System.currentTimeMillis())
    }

    override fun onDetachedFromWindow() {
        Palette.removeListener(paletteListener)
        TimeTicker.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun onTick(nowMillis: Long) {
        cal.timeInMillis = nowMillis
        hour = cal.get(Calendar.HOUR_OF_DAY)
        minute = cal.get(Calendar.MINUTE)
        second = cal.get(Calendar.SECOND)
        subSecond = if (smoothSeconds) (nowMillis % 1000) / 1000f else 0f
        invalidate()
        if (smoothSeconds) postInvalidateDelayed(33L)
    }

    override fun onDraw(canvas: Canvas) {
        val c = Palette.colors
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - dp(3f)
        if (r <= 6f) return

        val faDigits = LauncherPrefs.get(context).usePersianDigits

        // dial plate
        fill.color = if (Palette.isAmoled()) Color.TRANSPARENT
        else if (c.night) Color.argb(46, 255, 255, 255) else Color.argb(56, 0, 0, 0)
        canvas.drawCircle(cx, cy, r, fill)

        stroke.strokeWidth = dp(1.4f)
        stroke.color = Format.withAlpha(c.outline, 110)
        canvas.drawCircle(cx, cy, r, stroke)

        // 60 minute ticks, every fifth one hour-length
        for (i in 0 until 60) {
            val major = i % 5 == 0
            stroke.strokeWidth = if (major) dp(2.4f) else dp(1f)
            stroke.color = if (major) Format.withAlpha(c.onSurface, 200)
            else Format.withAlpha(c.onSurface, if (c.night) 70 else 45)
            val a = Math.toRadians(i * 6.0 - 90.0)
            val outer = r - dp(3f)
            val inner = outer - if (major) dp(8.5f) else dp(4f)
            canvas.drawLine(
                cx + (cos(a) * outer).toFloat(), cy + (sin(a) * outer).toFloat(),
                cx + (cos(a) * inner).toFloat(), cy + (sin(a) * inner).toFloat(),
                stroke
            )
        }

        if (numerals && r > dp(34f)) {
            text.color = Format.withAlpha(c.onSurface, 205)
            text.textSize = r * 0.2f
            text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            val tr = r - dp(20f)
            for (i in 1..12) {
                val a = Math.toRadians(i * 30.0 - 90.0)
                val label = if (faDigits) Format.toPersianDigits("$i") else "$i"
                canvas.drawText(
                    label,
                    cx + (cos(a) * tr).toFloat(),
                    cy + (sin(a) * tr).toFloat() + text.textSize * 0.35f,
                    text
                )
            }
        }

        if (showYearRing && r > dp(44f)) {
            val rr = r + dp(5f)
            arc.set(cx - rr, cy - rr, cx + rr, cy + rr)
            stroke.strokeWidth = dp(3f)
            stroke.color = Format.withAlpha(c.onSurface, 22)
            canvas.drawArc(arc, 0f, 360f, false, stroke)
            stroke.color = c.accent
            canvas.drawArc(arc, -90f, 360f * Jalali.yearProgress(Jalali.today()), false, stroke)
        }

        val hourAngle = (hour % 12 + minute / 60f) * 30f - 90f
        val minuteAngle = (minute + second / 60f) * 6f - 90f
        val secondAngle = (second + subSecond) * 6f - 90f

        hand(canvas, cx, cy, hourAngle, r * 0.5f, dp(5f), c.onSurface)
        hand(canvas, cx, cy, minuteAngle, r * 0.76f, dp(3f), c.onSurface)
        if (showSeconds) {
            val secColor = if (accentHands) c.accent else c.onSurfaceMuted
            hand(canvas, cx, cy, secondAngle, r * 0.85f, dp(1.6f), secColor)
            hand(canvas, cx, cy, secondAngle + 180f, r * 0.19f, dp(1.6f), secColor)
        }

        fill.color = if (accentHands) c.accent else c.onSurface
        canvas.drawCircle(cx, cy, dp(4.2f), fill)
        fill.color = c.surfaceSolid
        canvas.drawCircle(cx, cy, dp(1.7f), fill)
    }

    private fun hand(canvas: Canvas, cx: Float, cy: Float, deg: Float, len: Float, width: Float, color: Int) {
        val a = Math.toRadians(deg.toDouble())
        stroke.strokeWidth = width
        stroke.color = color
        canvas.drawLine(cx, cy, cx + (cos(a) * len).toFloat(), cy + (sin(a) * len).toFloat(), stroke)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
