package com.arena.carlauncher.vehicle

import android.content.Context
import android.util.Log
import com.arena.carlauncher.data.VehicleSnapshot

/**
 * Optional `android.car` CarProperty client.
 *
 * Reading VHAL properties needs the `android.car.permission.CAR_*` permissions, which are
 * `signature|privileged`: they are only ever granted when this APK lives in `/system/priv-app` (or
 * `/product/priv-app`) with a matching permissions XML. On a stock aftermarket launcher partition
 * every call below throws `SecurityException`, so the whole bridge is reflective and failure just
 * means "no car data", never a crash.
 *
 * Property ids are looked up from `android.car.VehiclePropertyIds` by name instead of hard-coded
 * hex, because the numeric ids moved between Android 10, 12 and 13 automotive releases.
 */
class CarPropertyBridge(private val contextRef: Context?) {

    private var car: Any? = null
    private var propertyManager: Any? = null
    private var tried = false
    private var failure: String = ""

    var lastSnapshot: VehicleSnapshot? = null
        private set

    private fun ensureConnected(): Boolean {
        if (propertyManager != null) return true
        if (tried) return false
        tried = true
        val ctx = contextRef ?: run {
            failure = "no context"
            return false
        }
        try {
            val carCls = Class.forName("android.car.Car")
            val create = carCls.getMethod("createCar", Context::class.java)
            val created = create.invoke(null, ctx) ?: run {
                failure = "createCar returned null"
                return false
            }
            val propService = carCls.getField("PROPERTY_SERVICE").get(null) as? String ?: "property"
            val mgr = created.javaClass
                .getMethod("getCarManager", String::class.java)
                .invoke(created, propService) ?: run {
                failure = "no property service (not an Automotive build)"
                return false
            }
            try {
                created.javaClass.getMethod("connect").invoke(created)
            } catch (_: Throwable) {
            }
            val connected = try {
                created.javaClass.getMethod("isConnected").invoke(created) as? Boolean ?: true
            } catch (_: Throwable) {
                true
            }
            if (!connected) {
                failure = "Car service not connected"
                return false
            }
            car = created
            propertyManager = mgr
            Log.i(TAG, "CarPropertyManager ready")
            return true
        } catch (t: Throwable) {
            failure = t.javaClass.simpleName + ": " + t.message
            Log.i(TAG, "android.car unavailable -> $failure")
            return false
        }
    }

    fun available(): Boolean = propertyManager != null || !tried

    private fun propertyId(name: String): Int? = try {
        val cls = Class.forName("android.car.VehiclePropertyIds")
        val f = cls.getField(name)
        f.getInt(null)
    } catch (_: Throwable) {
        null
    }

    private fun readFloat(name: String): Float? {
        val pm = propertyManager ?: return null
        val id = propertyId(name) ?: return null
        return try {
            val m = pm.javaClass.getMethod("getPropertyFloat", Int::class.javaPrimitiveType)
            (m.invoke(pm, id) as? Float)?.takeIf { !it.isNaN() && it > -1e6f }
        } catch (t: Throwable) {
            debugOnce("read $name: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun readInt(name: String): Int? {
        val pm = propertyManager ?: return null
        val id = propertyId(name) ?: return null
        return try {
            val m = pm.javaClass.getMethod("getPropertyInt", Int::class.javaPrimitiveType)
            m.invoke(pm, id) as? Int
        } catch (_: Throwable) {
            null
        }
    }

    private var logged = false
    private fun debugOnce(msg: String) {
        if (logged) return
        logged = true
        Log.d(TAG, msg)
    }

    /** 1 Hz pull. Called from [VehicleHub]'s ticker so no extra thread exists. */
    fun poll() {
        if (!ensureConnected()) return
        val speedRaw = readFloat("PERF_VEHICLE_SPEED")
        val speedKmh = speedRaw?.let {
            // VHAL reports m/s for PERF_VEHICLE_SPEED on most stacks, km/h on a few Chinese ones.
            when {
                it > 400f -> it / 10f
                it < 0f -> 0f
                else -> it * 3.6f
            }
        }
        val fuelPct = readFloat("PERCENT_FUEL") ?: readFloat("FUEL_LEVEL")
        val odometer = readFloat("ODOMETER")
        val rpm = readFloat("ENGINE_RPM")?.let { if (it < 0f) null else it }
        val coolant = readFloat("ENGINE_COOLANT_TEMP") ?: readFloat("COOLANT_TEMP")
        val extTemp = readFloat("AMBIENT_TEMP") ?: readFloat("ENV_OUTSIDE_TEMPERATURE")
        val gear = readInt("GEAR_SELECTION")
        val parked = gear?.let { it == 0 || it == 1 }

        val snap = VehicleSnapshot(
            source = VehicleSnapshot.SOURCE_CAR_API,
            speedKmh = speedKmh,
            rpm = rpm,
            fuelPct = fuelPct,
            coolantC = coolant,
            odometerKm = odometer,
            extTempC = extTemp,
            reverse = gear?.let { it == -1 } ?: false,
            lightsOn = readInt("KEYLIGHT_STATUS")?.let { it > 0 } ?: false,
            updatedAt = System.currentTimeMillis()
        )
        if (parked == true) lastSnapshot = snap.copy(speedKmh = 0f) else lastSnapshot = snap
    }

    fun debugDump(): String = when {
        failure.isNotEmpty() -> "carapi: unavailable ($failure)"
        propertyManager == null -> "carapi: not connected"
        else -> "carapi: ok ${lastSnapshot?.let { "speed=${it.speedKmh} fuel=${it.fuelPct}" } ?: "no reading yet"}"
    }

    companion object {
        private const val TAG = "CarPropertyBridge"
    }
}
