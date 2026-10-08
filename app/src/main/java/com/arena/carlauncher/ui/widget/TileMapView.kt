package com.arena.carlauncher.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.loc.LocationHub
import com.arena.carlauncher.map.MapEngine
import com.arena.carlauncher.map.Mercator
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.util.Format
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max as imax
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A live, pannable, zoomable raster map drawn on a plain Canvas.
 *
 * Why not the Google Maps / Neshan SDK? Both need an API key bound to a signing certificate, both
 * want Play Services (absent on most Unisoc boxes), and the Neshan SDK ships .so files for ABIs
 * this board may not have. XYZ tiles need none of that: they render from the disk cache inside a
 * tunnel, they work on Iranian networks where Google tiles do not, and the whole visible area costs
 * a handful of 256 px bitmaps.
 *
 * Zoom is kept integral on purpose — half-zoom would mean resampling every tile every frame, which
 * is the sort of thing that makes a 4-core head unit stutter.
 */
class TileMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var centerLat = 35.6997
    var centerLon = 51.3903
    var zoom: Int = 15
        set(value) {
            field = value.coerceIn(2, 19)
        }
    var rotationDeg = 0f
    var bearingDeg = 0f
    var headingUp = true
    var follow = true
    var showAccuracy = true
    var autoZoomBySpeed = true

    var destination: Pair<Double, Double>? = null
        set(value) {
            field = value
            invalidate()
        }
    var routePoints: List<DoubleArray>? = null
        set(value) {
            field = value
            invalidate()
        }
    var attribution: String = "© OpenStreetMap contributors"
        set(value) {
            field = value
            invalidate()
        }

    var onUserPan: (() -> Unit)? = null
    var onLongPress: ((lat: Double, lon: Double) -> Unit)? = null
    var onZoomChanged: ((Int) -> Unit)? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private var generation = 0
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prev = zoom
                val f = detector.scaleFactor
                if (f > 1.06f) zoom = prev + 1
                else if (f < 0.94f) zoom = prev - 1
                if (zoom != prev) {
                    follow = false
                    onZoomChanged?.invoke(zoom)
                    onUserPan?.invoke()
                    invalidate()
                }
                return true
            }
        }
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean = false
            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomBy(if (e.action == MotionEvent.ACTION_DOWN) 1 else -1)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val p = screenToWorld(e.x, e.y)
                onLongPress?.invoke(p[0], p[1])
            }
        }
    )

    init {
        val p = LauncherPrefs.get(context)
        zoom = p.mapZoom.coerceIn(2, 19)
        headingUp = p.mapHeadingUp
        isHapticFeedbackEnabled = true
    }

    // ------------------------------------------------------------------ public API
    fun followLocation() {
        val loc = LocationHub.state.value
        if (!loc.hasFix) return
        centerLat = loc.lat
        centerLon = loc.lon
        follow = true
        invalidate()
    }

    fun moveTo(lat: Double, lon: Double, zoomTo: Int? = null) {
        centerLat = lat
        centerLon = lon
        if (zoomTo != null) zoom = zoomTo
        invalidate()
    }

    fun zoomBy(delta: Int) {
        val prev = zoom
        zoom = zoom + delta
        if (zoom != prev) {
            LauncherPrefs.get(context).mapZoom = zoom
            onZoomChanged?.invoke(zoom)
            invalidate()
        }
    }

    /** Map rotation follows the bearing only in heading-up mode. */
    fun onLocationUpdate(bearing: Float, speedKmh: Float) {
        if (!bearing.isNaN()) bearingDeg = normalize(bearing)
        val target = if (headingUp) bearingDeg else 0f
        val delta = shortestDelta(rotationDeg, target)
        if (abs(delta) > 1.2f) {
            rotationDeg = normalize(rotationDeg + delta * 0.3f)
            invalidate()
        }
        if (follow) {
            val loc = LocationHub.state.value
            if (loc.hasFix) {
                centerLat = loc.lat
                centerLon = loc.lon
                invalidate()
            }
        }
        if (autoZoomBySpeed) {
            val want = when {
                speedKmh > 110f -> 11
                speedKmh > 70f -> 13
                speedKmh > 35f -> 14
                else -> 16
            }
            if (abs(zoom - want) >= 2) {
                zoom = zoom + if (want > zoom) 1 else -1
                invalidate()
            }
        }
    }

    fun setHeadingUp(on: Boolean) {
        headingUp = on
        LauncherPrefs.get(context).mapHeadingUp = on
        if (!on) rotationDeg = 0f
        invalidate()
    }

    private fun normalize(d: Float): Float {
        var v = d % 360f
        if (v < 0) v += 360f
        return v
    }

    private fun shortestDelta(from: Float, to: Float): Float {
        var d = to - from
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    private fun panBy(screenDx: Float, screenDy: Float) {
        val rad = Math.toRadians(rotationDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val wx = screenDx * c - screenDy * s
        val wy = screenDx * s + screenDy * c
        val z = zoom
        val world = Mercator.worldSizePx(z)
        val px = (((Mercator.projectX(centerLon, z) - wx) % world) + world) % world
        val py = (Mercator.projectY(centerLat, z) - wy).coerceIn(1.0, world - 1)
        centerLon = Mercator.unprojectX(px, z)
        centerLat = Mercator.unprojectY(py, z).coerceIn(-85.0, 85.0)
        invalidate()
    }

    fun screenToWorld(x: Float, y: Float): DoubleArray {
        val z = zoom
        val rad = Math.toRadians((-rotationDeg).toDouble())
        val dx = x - width / 2f
        val dy = y - height / 2f
        val rx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ry = (dx * sin(rad) + dy * cos(rad)).toFloat()
        val wx = Mercator.projectX(centerLon, z) + rx
        val wy = Mercator.projectY(centerLat, z) + ry
        return doubleArrayOf(Mercator.unprojectY(wy, z), Mercator.unprojectX(wx, z))
    }

    // ------------------------------------------------------------------ drawing
    override fun onDraw(canvas: Canvas) {
        val c = Palette.colors
        val z = zoom
        val tile = 256f
        val count = Mercator.tileCount(z)
        canvas.drawColor(if (c.night) 0xFF14181F.toInt() else 0xFFE6E9EE.toInt())
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height / 2f
        val centerWx = Mercator.projectX(centerLon, z)
        val centerWy = Mercator.projectY(centerLat, z)
        generation++

        canvas.save()
        if (rotationDeg != 0f) canvas.rotate(-rotationDeg, cx, cy)

        // Tiles that can reach the viewport even while rotated (hence the diagonal overscan).
        val reach = max(width, height) / 2f + tile
        val tx0 = ((centerWx - reach) / tile).floorToInt()
        val tx1 = ((centerWx + reach) / tile).floorToInt()
        val ty0 = ((centerWy - reach) / tile).floorToInt()
        val ty1 = ((centerWy + reach) / tile).floorToInt()

        for (tx in tx0..tx1) {
            for (ty in ty0..ty1) {
                if (ty < 0 || ty >= count) continue
                val wrapped = ((tx % count) + count) % count
                val screenX = (tx * tile - centerWx) + cx
                val screenY = (ty * tile - centerWy) + cy
                rect.set(screenX, screenY, screenX + tile, screenY + tile)
                val bmp: Bitmap? = MapEngine.peek(z, wrapped, ty)
                if (bmp != null && !bmp.isRecycled) {
                    canvas.drawBitmap(bmp, null, rect, bitmapPaint)
                } else {
                    drawPlaceholder(canvas, rect)
                    val g = generation
                    MapEngine.request(z, wrapped, ty, g) { b -> if (b != null) invalidate() }
                }
            }
        }

        drawRoute(canvas, z, centerWx, centerWy, cx, cy, c)
        destination?.let { (dLat, dLon) ->
            val px = (Mercator.projectX(dLon, z) - centerWx) + cx
            val py = (Mercator.projectY(dLat, z) - centerWy) + cy
            drawPin(canvas, px, py, c.danger)
        }
        canvas.restore()

        drawOwnPosition(canvas, cx, cy, c)
        drawScaleBar(canvas, c)
        drawAttribution(canvas, c)
    }

    private fun drawRoute(
        canvas: Canvas,
        z: Int,
        centerWx: Double,
        centerWy: Double,
        cx: Float,
        cy: Float,
        c: Palette.Colors
    ) {
        val route = routePoints ?: return
        if (route.size < 2) return
        path.reset()
        var started = false
        for (p in route) {
            val px = (Mercator.projectX(p[1], z) - centerWx) + cx
            val py = (Mercator.projectY(p[0], z) - centerWy) + cy
            if (!started) {
                path.moveTo(px, py)
                started = true
            } else path.lineTo(px, py)
        }
        stroke.strokeWidth = dp(8f)
        stroke.color = Color.argb(96, 0, 0, 0)
        canvas.drawPath(path, stroke)
        stroke.strokeWidth = dp(4.5f)
        stroke.color = c.accent
        canvas.drawPath(path, stroke)
    }

    private fun drawPlaceholder(canvas: Canvas, r: RectF) {
        val c = Palette.colors
        fill.color = if (c.night) 0xFF1B212B.toInt() else 0xFFEDEFF3.toInt()
        canvas.drawRect(r, fill)
        stroke.strokeWidth = dp(0.7f)
        stroke.color = if (c.night) 0x22FFFFFF else 0x18000000
        canvas.drawRect(r, stroke)
    }

    private fun drawOwnPosition(canvas: Canvas, cx: Float, cy: Float, c: Palette.Colors) {
        val loc = LocationHub.state.value
        if (!loc.hasFix) return
        if (showAccuracy && loc.accuracyM in 1f..80f) {
            val mPerPx = Mercator.metersPerPixel(loc.lat, zoom).toFloat()
            if (mPerPx > 0f) {
                val accPx = (loc.accuracyM / mPerPx)
                fill.color = Format.withAlpha(c.accent, 22)
                canvas.drawCircle(cx, cy, accPx.coerceIn(dp(16f), imax(width, height) * 1.5f), fill)
            }
        }
        canvas.save()
        canvas.rotate(if (headingUp) 0f else bearingDeg, cx, cy)
        val s = dp(12f)
        path.reset()
        path.moveTo(cx, cy - s * 1.55f)
        path.lineTo(cx - s, cy + s * 0.95f)
        path.lineTo(cx, cy + s * 0.4f)
        path.lineTo(cx + s, cy + s * 0.95f)
        path.close()
        fill.color = c.accent
        canvas.drawPath(path, fill)
        stroke.strokeWidth = dp(2f)
        stroke.color = Color.WHITE
        canvas.drawPath(path, stroke)
        canvas.restore()
    }

    private fun drawPin(canvas: Canvas, x: Float, y: Float, color: Int) {
        val s = dp(10f)
        path.reset()
        path.moveTo(x, y)
        path.cubicTo(x - s * 1.5f, y - s * 1.4f, x - s * 0.95f, y - s * 3.0f, x, y - s * 3.0f)
        path.cubicTo(x + s * 0.95f, y - s * 3.0f, x + s * 1.5f, y - s * 1.4f, x, y)
        path.close()
        fill.color = Color.argb(110, 0, 0, 0)
        canvas.drawCircle(x, y, s * 0.42f, fill)
        fill.color = color
        canvas.drawPath(path, fill)
        fill.color = Color.WHITE
        canvas.drawCircle(x, y - s * 2.0f, s * 0.44f, fill)
    }

    private fun drawScaleBar(canvas: Canvas, c: Palette.Colors) {
        val lat = if (LocationHub.state.value.hasFix) LocationHub.state.value.lat else centerLat
        val mPerPx = Mercator.metersPerPixel(lat, zoom)
        if (mPerPx.isNaN() || mPerPx <= 0.0) return
        val nice = doubleArrayOf(5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0, 25000.0, 50000.0)
        var meters = nice[0]
        for (n in nice) if (n / mPerPx <= width * 0.30) meters = n
        val px = (meters / mPerPx).toFloat()
        val x = dp(10f)
        val y = height - dp(15f)
        stroke.strokeWidth = dp(2f)
        stroke.color = Format.withAlpha(c.onSurface, 200)
        canvas.drawLine(x, y, x + px, y, stroke)
        canvas.drawLine(x, y - dp(4f), x, y + dp(4f), stroke)
        canvas.drawLine(x + px, y - dp(4f), x + px, y + dp(4f), stroke)
        textPaint.textSize = sp(10f)
        textPaint.color = Format.withAlpha(c.onSurface, 220)
        textPaint.textAlign = Paint.Align.LEFT
        val label = if (meters >= 1000.0) "${(meters / 1000.0).roundToInt()} km" else "${meters.roundToInt()} m"
        canvas.drawText(label, x, y - dp(8f), textPaint)
    }

    private fun drawAttribution(canvas: Canvas, c: Palette.Colors) {
        val a = attribution
        if (a.isBlank()) return
        textPaint.textSize = sp(9f)
        textPaint.color = Format.withAlpha(c.onSurface, 150)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(a, width - dp(8f), height - dp(6f), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    // ------------------------------------------------------------------ touch
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging && !scaleDetector.isInProgress) {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (abs(dx) > 0.5f || abs(dy) > 0.5f) {
                    panBy(dx, dy)
                    follow = false
                    onUserPan?.invoke()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
    private fun Double.floorToInt() = kotlin.math.floor(this).toInt()
}
