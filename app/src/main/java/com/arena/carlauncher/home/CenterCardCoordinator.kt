package com.arena.carlauncher.home

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.arena.carlauncher.data.Cards
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.media.MediaHub
import com.arena.carlauncher.map.NavAppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides which card owns the centre of the screen — the requirement the whole launcher is built
 * around: music in the middle while it plays, the map in the middle while navigating, clock
 * otherwise.
 *
 * Three rules that took real head-unit time to learn:
 *
 *  1. **Only pick from what the user configured.** If the carousel is `clock,map` then "show music"
 *     must not silently jump to page 0; the user's layout wins over the heuristic.
 *  2. **Hysteresis on both edges.** A session that pauses for a second (buffering, a phone call
 *     interrupt, `STATE_PAUSED` for 400 ms) must not flip the screen, and a nav app that briefly
 *     loses focus to a notification must not evict the map. So each source has a hold-down timer.
 *  3. **A manual swipe buys quiet time.** After the user swipes or taps a dot, the coordinator stops
 *     overriding for [LauncherPrefs.manualOverrideSec]; without that, a music start yanks the page
 *     mid-gesture and the launcher feels haunted.
 */
object CenterCardCoordinator {

    private const val TAG = "CenterCard"

    enum class Reason { INITIAL, MANUAL, MUSIC, NAVIGATION, IDLE }

    private var prefs: LauncherPrefs? = null
    private var app: Context? = null

    private val _center = MutableStateFlow(Cards.CLOCK)

    /** The card id that must be visible in the centre right now. */
    val center = _center.asStateFlow()

    private var reason = Reason.INITIAL
    private var manualUntil = 0L
    private var musicSince = 0L
    private var musicHoldUntil = 0L
    private var navSince = 0L
    private var navHoldUntil = 0L
    private var configured = listOf(Cards.CLOCK)

    /** Debounce: a 1 Hz media poll plus a state-flow emission must not re-evaluate twice. */
    private var lastRequested = ""

    fun install(ctx: Context) {
        app = ctx.applicationContext
        prefs = LauncherPrefs.get(ctx)
        configured = prefs?.centerCards ?: listOf(Cards.CLOCK)
        _center.value = configured.firstOrNull() ?: Cards.CLOCK
        reason = Reason.INITIAL
    }

    /** Called whenever the settings editor changes the card list, and on layout reload. */
    fun setConfigured(ids: List<String>) {
        configured = ids.ifEmpty { listOf(Cards.CLOCK) }
        if (!configured.contains(_center.value)) {
            _center.value = configured.first()
            reason = Reason.IDLE
        }
        lastRequested = ""
        evaluate()
    }

    fun currentReason(): Reason = reason

    /** The centre card index inside the carousel, for the pager. */
    fun centerIndex(): Int = configured.indexOf(_center.value).coerceAtLeast(0)

    /**
     * Re-decide. Safe to call from a 1 Hz ticker, a media state change and a focus change; it is
     * idempotent unless the answer really changed.
     */
    fun evaluate() {
        val p = prefs ?: return
        val now = SystemClock.elapsedRealtime()
        val media = runCatching { MediaHub.state.value }.getOrNull()
        val mediaPlaying = media != null && media.isPlaying && media.hasSession
        val nav = isNavigating()

        val wanted: Pair<String, Reason> = when {
            nav -> Cards.MAP to Reason.NAVIGATION
            mediaPlaying -> Cards.MUSIC to Reason.MUSIC
            else -> (p.centerCards.firstOrNull() ?: Cards.CLOCK) to Reason.IDLE
        }

        if (!p.autoSwitchCenter) {
            // Manual mode: the coordinator never changes the page, the user does.
            return
        }

        if (now < manualUntil) {
            // The user is browsing; leave the page they chose alone until the override expires.
            return
        }

        val candidate = firstConfiguredOrFallback(wanted.first)
        if (!configured.contains(candidate)) return

        // Hysteresis: require the source to be stable for a moment, and keep it for a moment after.
        when (wanted.second) {
            Reason.MUSIC -> {
                if (mediaPlaying) {
                    if (musicSince == 0L) musicSince = now
                    if (now - musicSince < STABLE_MS && _center.value != Cards.MUSIC) return
                    musicHoldUntil = now + MUSIC_HOLD_MS
                } else {
                    musicSince = 0L
                    if (now < musicHoldUntil && _center.value == Cards.MUSIC) return
                }
            }
            Reason.NAVIGATION -> {
                if (nav) {
                    if (navSince == 0L) navSince = now
                    if (now - navSince < STABLE_MS && _center.value != Cards.MAP) return
                    navHoldUntil = now + NAV_HOLD_MS
                } else {
                    navSince = 0L
                    if (now < navHoldUntil && _center.value == Cards.MAP) return
                }
            }
            else -> Unit
        }

        // Never fight a source that is currently holding the screen for something weaker.
        if (_center.value == Cards.MUSIC && !mediaPlaying && now < musicHoldUntil) return
        if (_center.value == Cards.MAP && !nav && now < navHoldUntil) return
        if (candidate == _center.value) {
            lastRequested = candidate
            return
        }
        if (wanted.second == Reason.IDLE && candidate == lastRequested) return
        lastRequested = candidate
        reason = wanted.second
        emit(candidate)
    }

    private fun emit(id: String) {
        if (_center.value == id) return
        _center.value = id
        if (prefs?.debugLog == true) Log.d(TAG, "centre -> $id ($reason)")
    }

    private fun firstConfiguredOrFallback(id: String): String =
        if (configured.contains(id)) id else (configured.firstOrNull() ?: Cards.CLOCK)

    /** The map is centre-worthy when a nav app has focus *or* the car is moving with a destination. */
    private fun isNavigating(): Boolean {
        val ctx = app ?: return false
        val fg = ForegroundAppWatcher.current()
        if (fg != null && NavAppRepository.isNavigationPackage(fg)) return true
        val driving = (runCatching { com.arena.carlauncher.vehicle.VehicleHub.state.value.speedKmh ?: 0f }.getOrNull() ?: 0f) > 6f
        return driving && HasDestination(ctx)
    }

    /** Called by the pager when the user swipes or taps a page dot. */
    fun onUserSelected(id: String, manual: Boolean = true) {
        val p = prefs ?: return
        configured.indexOf(id).takeIf { it >= 0 }?.let {
            _center.value = id
            lastRequested = id
            reason = if (manual) Reason.MANUAL else reason
            if (manual) manualUntil = SystemClock.elapsedRealtime() + p.manualOverrideSec * 1000L
            if (p.debugLog) Log.d(TAG, "manual -> $id, quiet for ${p.manualOverrideSec}s")
        }
    }

    /** The `music`/`nav` actions from [com.arena.carlauncher.actions.ActionRouter] land here. */
    fun force(id: String) {
        val candidate = firstConfiguredOrFallback(id)
        reason = if (id == Cards.MUSIC) Reason.MUSIC else Reason.NAVIGATION
        manualUntil = 0L
        emit(candidate)
    }

    fun skipMusicHold() {
        musicSince = 0L
        musicHoldUntil = 0L
    }

    fun debugDump(): String {
        val now = SystemClock.elapsedRealtime()
        return "center=${_center.value} reason=$reason configured=${configured.joinToString(",")} " +
            "manualLeft=${((manualUntil - now) / 1000L).coerceAtLeast(0)}s " +
            "musicHold=${((musicHoldUntil - now) / 1000L).coerceAtLeast(0)}s " +
            "navHold=${((navHoldUntil - now) / 1000L).coerceAtLeast(0)}s"
    }

    private const val STABLE_MS = 700L
    private const val MUSIC_HOLD_MS = 12_000L
    private const val NAV_HOLD_MS = 25_000L

    /** Reads the persisted destination without importing the map module's snapshot types. */
    private object HasDestination {
        operator fun invoke(ctx: Context): Boolean = try {
            val raw = LauncherPrefs.get(ctx).get(com.arena.carlauncher.map.PlacePicker.KEY_DESTINATION, "")
            raw.isNotBlank()
        } catch (_: Throwable) {
            false
        }
    }
}
