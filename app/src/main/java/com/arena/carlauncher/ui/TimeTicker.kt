package com.arena.carlauncher.ui

import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * One shared 1 Hz beat for every time-driven view (clock, weather, trip, ETA).
 *
 * A launcher should never run six independent `postDelayed` loops: the wakeups add up on a
 * battery-backed head unit and drift apart, so the clock and the date line would visibly disagree.
 * Ticking from one place also means a doze/pause gap heals itself on the next beat.
 */
object TimeTicker {

    interface Tick {
        fun onTick(nowMillis: Long)
    }

    private val main = Handler(Looper.getMainLooper())
    private val views = ArrayList<Entry>()
    private var running = false
    private var generation = 0

    private class Entry(val tick: Tick, val view: View?)

    private val beat = object : Runnable {
        override fun run() {
            if (!running) return
            generation++
            val now = System.currentTimeMillis()
            val snapshot = synchronized(views) { ArrayList(views) }
            snapshot.forEach { e ->
                e.view?.let { v -> if (!v.isAttachedToWindow) return@forEach }
                try {
                    e.tick.onTick(now)
                } catch (_: Throwable) {
                }
            }
            synchronized(views) {
                views.removeAll { it.view != null && !it.view.isAttachedToWindow }
                if (views.isEmpty()) {
                    running = false
                    return
                }
            }
            main.postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L))
        }
    }

    val beats: Int get() = generation

    fun register(tick: Tick, view: View?) {
        synchronized(views) {
            if (views.any { it.tick === tick }) return
            views.add(Entry(tick, view))
        }
        start()
    }

    fun unregister(tick: Tick) {
        synchronized(views) { views.removeAll { it.tick === tick } }
    }

    private fun start() {
        if (running) return
        running = true
        main.removeCallbacks(beat)
        main.post(beat)
    }

    fun stopAll() {
        running = false
        main.removeCallbacks(beat)
    }
}
