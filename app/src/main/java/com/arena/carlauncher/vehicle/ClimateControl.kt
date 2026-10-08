package com.arena.carlauncher.vehicle

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.arena.carlauncher.R
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.data.TileConfig
import org.json.JSONArray

/**
 * Climate / body control for the quick tiles.
 *
 * There is no portable way to touch a car's HVAC from an unprivileged app, so this is a *bridge*:
 * every tile sends the broadcast your unit's CAN bridge expects (action + extra key configured in
 * Settings → Vehicle). When the CarProperty API is reachable — i.e. the APK was pushed into
 * `priv-app` — the same tiles also attempt a real `setProperty` write, which is why the failure
 * path is silent instead of an error dialog while driving.
 */
object ClimateControl {

    private const val TAG = "ClimateControl"

    fun tiles(ctx: Context): List<TileConfig> {
        val p = LauncherPrefs.get(ctx)
        val arr: JSONArray = p.climateTiles
        val out = ArrayList<TileConfig>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(TileConfig.fromJson(o))
        }
        return out
    }

    fun send(ctx: Context, spec: String): Boolean {
        val p = LauncherPrefs.get(ctx)
        val action = p.vehicleBroadcastAction
        val (key, value) = parseSpec(spec)

        // 1) privileged path, if the ROM lets us near the VHAL
        val wroteCar = writeCarProperty(ctx, key, value)

        // 2) broadcast bridge path (the one that actually works on stock units)
        var sent = false
        if (action.isNotBlank()) {
            sent = try {
                val i = Intent(action).apply {
                    putExtra(key, value)
                    putExtra("command", key)
                }
                ctx.sendBroadcast(i)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "broadcast $action failed: ${t.message}")
                false
            }
        }

        if (!sent && !wroteCar) {
            Log.i(TAG, "no vehicle action configured — set one in Settings → Vehicle")
        }
        return sent || wroteCar
    }

    /** `fan_up`, `temp=24`, `ac:1` — all accepted so tiles stay human-writable. */
    private fun parseSpec(spec: String): Pair<String, String> {
        val s = spec.ifBlank { "toggle" }
        return if (s.contains('=')) {
            s.substringBefore('=').trim() to s.substringAfter('=').trim()
        } else if (s.contains(':')) {
            s.substringBefore(':').trim() to s.substringAfter(':').trim()
        } else {
            s.trim() to "1"
        }
    }

    private fun writeCarProperty(ctx: Context, key: String, value: String): Boolean {
        val id = when (key.lowercase()) {
            "ac", "acon" -> "HVAC_POWER_ON"
            "fan_up", "fanup" -> "HVAC_FAN_SPEED"
            "fan_down", "fandown" -> "HVAC_FAN_SPEED"
            "temp_up" -> "HVAC_DRIVER_TARGET_TEMPERATURE"
            "temp_down" -> "HVAC_DRIVER_TARGET_TEMPERATURE"
            "recirc", "recirculation" -> "HVAC_RECIRC_ON"
            "defrost", "rear_defrost" -> "HVAC_DEFROSTER"
            "seat_heat" -> "HVAC_SEAT_TEMPERATURE"
            else -> null
        } ?: return false

        return try {
            val carCls = Class.forName("android.car.Car")
            val car = carCls.getMethod("createCar", Context::class.java).invoke(null, ctx) ?: return false
            val mgr = car.javaClass.getMethod("getCarManager", String::class.java)
                .invoke(car, carCls.getField("PROPERTY_SERVICE").get(null) as String) ?: return false
            val propId = Class.forName("android.car.VehiclePropertyIds").getField(id).getInt(null)
            // setProperty needs a CarPropertyValue object, built reflectively so this class still
            // compiles on SDKs where android.car is absent.
            val cpvCls = Class.forName("android.car.hardware.CarPropertyValue")
            val f = try {
                value.toFloat()
            } catch (_: Throwable) {
                if (value == "1") 1f else 0f
            }
            val ctor = cpvCls.getDeclaredConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Any::class.java
            )
            val cpv = ctor.newInstance(propId, 0, 0, f)
            mgr.javaClass.getMethod("setProperty", cpvCls).invoke(mgr, cpv)
            Log.i(TAG, "VHAL write $id=$f")
            true
        } catch (t: Throwable) {
            Log.d(TAG, "VHAL write skipped (${t.javaClass.simpleName})")
            false
        }
    }

    /** Used by the settings screen's "test tile" button. */
    fun toastUnsupported(ctx: Context) {
        try {
            Toast.makeText(ctx, R.string.toast_no_vehicle_bridge, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }
}
