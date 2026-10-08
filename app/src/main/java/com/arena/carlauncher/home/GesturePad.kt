package com.arena.carlauncher.home

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import com.arena.carlauncher.data.LauncherPrefs
import kotlin.math.abs

/**
 * Root container that turns gestures on *empty* areas into launcher actions.
 *
 * `onTouchEvent` is only reached when no child consumed the sequence, which is exactly the
 * semantics a car launcher wants: a swipe that starts on a card belongs to that card (the map
 * pans, the pager scrolls), a swipe that starts on the wallpaper belongs to the launcher.
 */
class GestureLayout(context: Context) : FrameLayout(context) {

    enum class Gesture { SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT, DOUBLE_TAP, LONG_PRESS }

    var onGesture: ((Gesture) -> Boolean)? = null

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val dx = e2.x - (e1?.x ?: 0f)
            val dy = e2.y - (e1?.y ?: 0f)
            val slop = 28 * resources.displayMetrics.density
            if (abs(dx) < slop && abs(dy) < slop) return false
            val g = if (abs(dx) > abs(dy)) {
                if (dx > 0) Gesture.SWIPE_RIGHT else Gesture.SWIPE_LEFT
            } else {
                if (dy > 0) Gesture.SWIPE_DOWN else Gesture.SWIPE_UP
            }
            return onGesture?.invoke(g) ?: false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean =
            onGesture?.invoke(Gesture.DOUBLE_TAP) ?: false

        override fun onLongPress(e: MotionEvent) {
            onGesture?.invoke(Gesture.LONG_PRESS)
        }
    })

    /**
     * Children are never intercepted: a gesture is decided only from events nobody else wanted, so a
     * ViewPager2 or the map keeps 100 % of its own handling. Claiming ACTION_DOWN is enough to be
     * offered the rest of a sequence that every child declined.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) return true
        return detector.onTouchEvent(ev) || ev.actionMasked == MotionEvent.ACTION_UP
    }

    /** Reads the user's mapping from preferences (keys: swipe_up / swipe_down / …). */
    fun actionFor(g: Gesture): String {
        val p = LauncherPrefs.get(context)
        val key = when (g) {
            Gesture.SWIPE_UP -> "swipe_up"
            Gesture.SWIPE_DOWN -> "swipe_down"
            Gesture.SWIPE_LEFT -> "swipe_left"
            Gesture.SWIPE_RIGHT -> "swipe_right"
            Gesture.DOUBLE_TAP -> "double_tap"
            Gesture.LONG_PRESS -> "long_press"
        }
        return p.gestures.optString(key, DEFAULTS[key] ?: "")
    }

    companion object {
        /** Sensible defaults for a dash: up = app list, down = shade, double tap = sleep. */
        val DEFAULTS = mapOf(
            "swipe_up" to "allapps",
            "swipe_down" to "notifications",
            "swipe_left" to "card:next",
            "swipe_right" to "card:prev",
            "double_tap" to "sleep",
            "long_press" to "settings"
        )

        fun mapFromJson(ctx: Context): Map<String, String> {
            val o = LauncherPrefs.get(ctx).gestures
            val out = HashMap(DEFAULTS)
            for (k in out.keys.toList()) if (o.has(k)) out[k] = o.optString(k, "")
            return out
        }
    }
}
