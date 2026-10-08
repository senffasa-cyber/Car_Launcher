package com.arena.carlauncher.map

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.data.Place
import org.json.JSONObject

/**
 * "Which place did the user just pick?" — an in-process channel between the search Activity and the
 * cards.
 *
 * The search screen is its own Activity so the soft keyboard and IME behave on head-unit firmware,
 * but a card is not an Activity and cannot use `startActivityForResult`. Both live in the launcher's
 * process, so a plain listener registry (main-thread delivery, explicit register/unregister) is
 * enough — no broadcast receiver, no exported surface, nothing another app can spoof.
 */
object PlacePicker {

    const val EXTRA_TARGET = "target"
    const val EXTRA_QUERY = "query"

    const val TARGET_DESTINATION = "destination"
    const val TARGET_HOME = "home"
    const val TARGET_WORK = "work"
    const val TARGET_RECENT = "recent"

    const val KEY_DESTINATION = "destination_place"

    interface Listener {
        fun onPlace(target: String, place: Place)
    }

    private val listeners = ArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())

    fun open(ctx: Context, target: String, query: String = "") {
        try {
            ctx.startActivity(
                Intent(ctx, PlaceSearchActivity::class.java).apply {
                    putExtra(EXTRA_TARGET, target)
                    putExtra(EXTRA_QUERY, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Throwable) {
        }
    }

    fun addListener(l: Listener) = synchronized(listeners) { if (!listeners.contains(l)) listeners.add(l) }

    fun removeListener(l: Listener) = synchronized(listeners) { listeners.remove(l) }

    fun publish(ctx: Context, target: String, place: Place) {
        when (target) {
            TARGET_HOME -> LauncherPrefs.get(ctx).homePlace = place.toJson()
            TARGET_WORK -> LauncherPrefs.get(ctx).workPlace = place.toJson()
            TARGET_DESTINATION -> LauncherPrefs.get(ctx).put(KEY_DESTINATION, place.toJson().toString())
        }
        rememberRecent(ctx, place)
        val snapshot = synchronized(listeners) { ArrayList(listeners) }
        main.post {
            snapshot.forEach {
                try {
                    it.onPlace(target, place)
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun destination(ctx: Context): Place? {
        val raw = LauncherPrefs.get(ctx).get(KEY_DESTINATION, "")
        if (raw.isBlank()) return null
        return try {
            Place.fromJson(JSONObject(raw))
        } catch (_: Throwable) {
            null
        }
    }

    fun clearDestination(ctx: Context) {
        LauncherPrefs.get(ctx).put(KEY_DESTINATION, null as String?)
    }

    private fun rememberRecent(ctx: Context, place: Place) {
        val p = LauncherPrefs.get(ctx)
        val out = ArrayList<JSONObject>(8)
        out.add(place.toJson())
        p.recentPlaces.forEach { other ->
            val same = other.optString("label") == place.label && other.optDouble("lat") == place.lat
            if (!same && out.size < 8) out.add(other)
        }
        p.recentPlaces = out
    }

    fun home(ctx: Context): Place? = Place.fromJson(LauncherPrefs.get(ctx).homePlace)
    fun work(ctx: Context): Place? = Place.fromJson(LauncherPrefs.get(ctx).workPlace)

}
