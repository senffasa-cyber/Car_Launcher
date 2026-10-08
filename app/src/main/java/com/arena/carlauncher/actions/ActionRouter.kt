package com.arena.carlauncher.actions

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import com.arena.carlauncher.data.AppRepository
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.data.Place
import com.arena.carlauncher.home.HomeActivity
import com.arena.carlauncher.map.NavAppRepository
import com.arena.carlauncher.media.MediaHub
import com.arena.carlauncher.services.ScreenOffHelper
import com.arena.carlauncher.theme.DayNightController
import com.arena.carlauncher.vehicle.ClimateControl
import org.json.JSONObject

/**
 * The single command language of the launcher.
 *
 * Quick tiles, home-screen gestures, steering-wheel remappers, Tasker and the
 * `arenacar://card/<id>` deep link all funnel through here, which is what makes the launcher
 * scriptable from other apps without a public API surface of its own. Anything it cannot handle
 * internally is treated as a package name and launched — so a tile whose action is
 * `com.mydvr.app` just opens the dashcam.
 */
object ActionRouter {

    private const val TAG = "ActionRouter"
    private val main = Handler(Looper.getMainLooper())

    /** Implemented by [HomeActivity] for actions that need the live UI. */
    interface UiHooks {
        fun showCenterCard(id: String)
        fun swipeCenterCard(forward: Boolean)
        fun toggleAllApps(open: Boolean)
        fun setBrightnessBoost(on: Boolean)
        fun openRecentApps()
        fun toggleDriveLockAck()
        fun pickWidget() {}
        fun removeWidget() {}
    }

    var ui: UiHooks? = null

    const val ACTIVITY_EXTRA_ACTION = "arena.car.ACTION"

    /**
     * Other spellings for the same verb.
     *
     * The launcher's own naming is a bit idiosyncratic (`card`/`navcard`/`recents`), while the people
     * writing these strings are copying them from an existing Tasker profile, a CarStream/MagicDroid
     * macro, or a head-unit forum post. One wrong word silently does nothing, because anything the
     * router does not know is taken for a package name and launched — so a wrong verb ends up opening
     * nothing and the user has no idea why. The common aliases are folded in instead.
     */
    private val aliases = mapOf(
        "open" to "open",
        "launch" to "open",
        "startapp" to "open",
        "dvr" to "open",
        "camera" to "open",
        "allapps" to "allapps",
        "drawer" to "allapps",
        "appdrawer" to "allapps",
        "recents" to "recents",
        "recentapps" to "recents",
        "overview" to "recents",
        "notifications" to "shade",
        "shade" to "shade",
        "quicksettings" to "shade",
        "qs" to "shade",
        "media" to "media",
        "music" to "media",
        "player" to "media",
        "nowplaying" to "media",
        "theme" to "theme",
        "dark" to "theme",
        "light" to "theme",
        "auto" to "theme",
        "settings" to "settings",
        "prefs" to "settings",
        "preferences" to "settings",
        "wallpaper" to "settings",
        "lock" to "lock",
        "childlock" to "lock",
        "voice" to "voice",
        "assistant" to "voice",
        "siri" to "voice",
        "okgoogle" to "voice",
        "climate" to "climate",
        "hvac" to "climate",
        "ac" to "climate",
        "vehicle" to "climate",
        "card" to "card",
        "navcard" to "card_next",
        "card_next" to "card_next",
        "card_prev" to "card_next",
        "nav_next" to "card_next",
        "nav_prev" to "card_next",
        "next" to "next",
        "prev" to "prev",
        "skip" to "next",
        "previous" to "prev"
    )

    fun canonical(head: String): String = aliases[head.lowercase()] ?: head.lowercase()

    fun route(ctx: Context, spec: String?): Boolean {
        val raw = spec?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val p = LauncherPrefs.get(ctx)

        val head = raw.substringBefore(':', missingDelimiterValue = raw)
        val rest = if (raw.contains(':')) raw.substringAfter(':') else ""
        val verb = canonical(head)

        when (verb) {
            "card" -> if (rest.isNotBlank()) {
                main.post { ui?.showCenterCard(rest) }
                return true
            }

            "card_next" -> {
                // `card_next:prev`, `nav_prev` and `card_prev` all mean "previous card": three ways
                // people write it, and none of them should have to be memorised.
                val back = rest == "prev" || head.equals("nav_prev", true) || head.equals("card_prev", true)
                main.post { ui?.swipeCenterCard(!back) }
                return true
            }

            "allapps", "apps" -> {
                main.post { ui?.toggleAllApps(rest != "close") }
                return true
            }

            "recents" -> {
                main.post { ui?.openRecentApps() }
                return true
            }

            "notifications", "shade" -> return expandShade(ctx, rest == "settings")

            "music", "media" -> {
                val action = when (rest.lowercase()) {
                    "play" -> MediaHub.Action.PLAY
                    "pause" -> MediaHub.Action.PAUSE
                    "next", "skip" -> MediaHub.Action.NEXT
                    "prev", "previous" -> MediaHub.Action.PREV
                    "stop" -> MediaHub.Action.STOP
                    "open" -> {
                        MediaHub.openPlayer(ctx)
                        return true
                    }
                    "volup" -> {
                        MediaHub.adjustVolume(ctx, true)
                        return true
                    }
                    "voldn" -> {
                        MediaHub.adjustVolume(ctx, false)
                        return true
                    }
                    else -> MediaHub.Action.PLAY_PAUSE
                }
                MediaHub.act(action)
                return true
            }

            "nav", "route", "map" -> when (rest.lowercase()) {
                "home" -> return navigateToSaved(ctx, p.homePlace, "Home")
                "work" -> return navigateToSaved(ctx, p.workPlace, "Work")
                "recent" -> {
                    val last = p.recentPlaces.firstOrNull()?.let { Place.fromJson(it) }
                    if (last != null) return NavAppRepository.navigateTo(ctx, last)
                    return false
                }
                "open" -> return openNavApp(ctx)
                "stop" -> {
                    main.post { ui?.showCenterCard(com.arena.carlauncher.data.Cards.MAP) }
                    return true
                }
                else -> return openNavApp(ctx)
            }

            "theme" -> {
                // A bare `dark`/`light` arrives through the alias table with no argument, so the head is
                // the sub-command in that case.
                when (if (rest.isBlank()) head.lowercase() else rest.lowercase()) {
                    "day", "light" -> DayNightController.setManual(LauncherPrefs.THEME_LIGHT)
                    "night", "dark" -> DayNightController.setManual(LauncherPrefs.THEME_DARK)
                    "amoled" -> DayNightController.setManual(LauncherPrefs.THEME_AMOLED)
                    "auto" -> DayNightController.setManual(LauncherPrefs.THEME_AUTO)
                    else -> DayNightController.toggleManual(ctx)
                }
                return true
            }

            "screen" -> when (rest.lowercase()) {
                "off" -> {
                    ScreenOffHelper.turnOff(ctx)
                    return true
                }
                "dim" -> {
                    main.post { ui?.setBrightnessBoost(false) }
                    return true
                }
                "bright" -> {
                    main.post { ui?.setBrightnessBoost(true) }
                    return true
                }
            }

            "brightness" -> return SettingsBrightness.adjust(ctx, rest)

            "volume" -> return volume(ctx, rest)

            "voice", "assistant" -> return voice(ctx)

            "settings" -> {
                openSettingsActivity(ctx)
                return true
            }

            "lock" -> {
                main.post { ui?.toggleDriveLockAck() }
                return true
            }

            "sleep", "idle" -> {
                // "Sleep" = dim + short screen timeout; safe without root on every ROM.
                ScreenOffHelper.quickDim(ctx)
                return true
            }

            "climate", "car", "hvac" -> return ClimateControl.send(ctx, rest)

            "broadcast" -> return sendRaw(ctx, rest)

            "keyevent" -> return sendKey(ctx, rest)

            "home" -> {
                main.post { ui?.showCenterCard(com.arena.carlauncher.data.Cards.CLOCK) }
                return true
            }
        }

        // Anything else: a package name (dashcam, torque, a radio app...) or an explicit component.
        if (raw.contains('.') || raw.contains('/')) {
            if (raw.contains('/')) {
                val comp = raw.split('/', limit = 2)
                val launched = AppRepository.launch(
                    ctx,
                    AppRepository.AppEntry(comp[0], comp[1], comp[0], false)
                )
                if (launched) return true
            }
            return AppRepository.launchPackage(ctx, raw, adjacent = false)
        }

        Log.d(TAG, "unrouted action: $raw")
        return false
    }

    // ------------------------------------------------------------------ helpers
    private fun navigateToSaved(ctx: Context, json: JSONObject?, fallbackLabel: String): Boolean {
        val place = Place.fromJson(json) ?: return false
        val from = com.arena.carlauncher.loc.LocationHub.lastLocation
        val withOrigin = if (from != null) place.copy(
            label = place.label.ifBlank { fallbackLabel }
        ) else place
        return NavAppRepository.navigateTo(ctx, withOrigin)
    }

    private fun openNavApp(ctx: Context): Boolean {
        val app = NavAppRepository.preferred() ?: return false
        val from = com.arena.carlauncher.loc.LocationHub.lastLocation
        return if (from != null) {
            NavAppRepository.navigateTo(
                ctx,
                Place("current", from.latitude, from.longitude, "", "gps")
            ) || NavAppRepository.showOnMap(
                ctx,
                Place(app.label, from.latitude, from.longitude, "", "gps")
            )
        } else {
            NavAppRepository.launchComponent(ctx)?.let {
                ctx.startActivitySafely(ctx.packageManager.getLaunchIntentForPackage(it.packageName))
            } ?: false
        }
    }

    private fun volume(ctx: Context, rest: String): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return false
        try {
            when {
                rest.equals("up", true) -> am.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                rest.equals("down", true) -> am.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                rest.equals("mute", true) -> am.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                rest.equals("unmute", true) -> am.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                else -> {
                    val pct = rest.filter { it.isDigit() }.toIntOrNull() ?: return false
                    MediaHub.setVolumePercent(ctx, pct)
                }
            }
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun voice(ctx: Context): Boolean = try {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
        true
    } catch (_: Throwable) {
        // No speech recognizer (very common on these units) → Google/assistant, then dial pad.
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Throwable) {
            try {
                ctx.startActivity(
                    Intent("android.search.action.GLOBAL_SEARCH").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    /** Expands the shade through the framework's StatusBarManager; a handful of ROMs deny it. */
    private fun expandShade(ctx: Context, settings: Boolean): Boolean {
        return try {
            val cls = Class.forName("android.app.StatusBarManager")
            val get = ctx.getSystemService("statusbar") ?: return false
            val name = if (settings) "expandSettingsPanel" else "expandNotificationsPanel"
            cls.getMethod(name).invoke(get)
            true
        } catch (t: Throwable) {
            Log.d(TAG, "statusbar expand unavailable: ${t.message}")
            false
        }
    }

    private fun sendRaw(ctx: Context, rest: String): Boolean {
        // broadcast:<action>[/<extra-key>=<value>]
        val action = rest.substringBefore('/')
        if (action.isBlank()) return false
        val intent = Intent(action).setPackage(null)
        val tail = rest.substringAfter('/', "")
        if (tail.contains('=')) {
            val k = tail.substringBefore('=').trim()
            val v = tail.substringAfter('=').trim()
            v.toLongOrNull()?.let { intent.putExtra(k, it) } ?: intent.putExtra(k, v)
        }
        return try {
            ctx.sendBroadcast(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun sendKey(ctx: Context, rest: String): Boolean {
        val code = rest.toIntOrNull() ?: when (rest.lowercase()) {
            "home" -> KeyEvent.KEYCODE_HOME
            "back" -> KeyEvent.KEYCODE_BACK
            "appswitch" -> KeyEvent.KEYCODE_APP_SWITCH
            "power" -> KeyEvent.KEYCODE_POWER
            else -> -1
        }
        if (code <= 0) return false
        // Injecting a key needs INJECT_EVENTS, which no normal app holds. We try anyway (it works
        // when the APK is installed as a platform-signed privileged app) and otherwise fall back to
        // the closest thing we can legally do.
        val injected = try {
            // sendKeyDownUpSync refuses to run on the main thread, and a key injection blocks until the
            // input pipeline has taken the event — so both the call and its exceptions live off ours.
            val done = java.util.concurrent.CountDownLatch(1)
            var ok = false
            val t = Thread {
                try {
                    android.app.Instrumentation().sendKeyDownUpSync(code)
                    ok = true
                } catch (_: Throwable) {
                } finally {
                    done.countDown()
                }
            }
            t.start()
            done.await(600L, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (t.isAlive) t.interrupt()
            ok
        } catch (_: Throwable) {
            false
        }
        if (injected) return true
        return when (code) {
            KeyEvent.KEYCODE_HOME -> {
                main.post { ui?.showCenterCard(com.arena.carlauncher.data.Cards.CLOCK) }
                true
            }
            else -> false
        }
    }

    fun openSettingsActivity(ctx: Context) {
        try {
            ctx.startActivity(
                Intent().setClassName(
                    ctx.packageName, "com.arena.carlauncher.settings.SettingsActivity"
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "settings not startable: ${t.message}")
        }
    }

    private fun Context.startActivitySafely(i: Intent?): Boolean = try {
        if (i == null) false else {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            true
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * `brightness:<0..100>` / `brightness:up` / `brightness:down`. Needs the WRITE_SETTINGS app-op;
     * without it the call throws and we report failure so the tile can show why.
     */
    object SettingsBrightness {
        fun adjust(ctx: Context, arg: String): Boolean {
            val cr = ctx.contentResolver
            val canWrite = Settings.System.canWrite(ctx)
            if (!canWrite) return false
            val current = try {
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Throwable) {
                128
            }
            val target = when {
                arg.isBlank() || arg.equals("toggle", true) -> if (current > 120) 40 else 200
                arg.equals("up", true) -> current + 32
                arg.equals("down", true) -> current - 32
                else -> arg.filter { it.isDigit() }.toIntOrNull()?.let { it * 255 / 100 } ?: return false
            }
            return try {
                Settings.System.putInt(
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target.coerceIn(3, 255))
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
