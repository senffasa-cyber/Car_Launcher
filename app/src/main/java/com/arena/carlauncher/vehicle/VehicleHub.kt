package com.arena.carlauncher.vehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.data.VehicleSnapshot
import com.arena.carlauncher.loc.LocationHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Vehicle data, merged from up to three sources with per-field priority:
 *
 *  * **CAN broadcast bridge** — Unisoc/SPRD boxes rebroadcast the car bus as a normal
 *    `Intent` (`arena.car.*`, `com.android.car.CAN_*`, OEM names differ). The action and extra
 *    names are user-configurable in Settings → Vehicle, so no ROM recompile is needed. Every extra
 *    is matched *by name*, so whatever keys the firmware happens to use get picked up.
 *  * **CarProperty API** — polled through reflection when the APK is installed as a privileged
 *    app (that is the only supported way to get `CAR_SPEED`/`CAR_CLIMATE` on Android 10+ Automotive
 *    HAL units). Property ids are read from `android.car.VehiclePropertyIds` instead of hard-coded
 *    hex constants, because they differ between releases.
 *  * **GPS** — always available, gives an honest speedometer + trip even with zero car integration,
 *    which is the common case on a aftermarket Android 12 box.
 */
object VehicleHub {

    private const val TAG = "VehicleHub"
    private const val POLL_MS = 1000L

    private var app: Context? = null
    private var prefs: LauncherPrefs? = null
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(VehicleSnapshot())
    val state = _state.asStateFlow()

    /** Raw values from the external sources, before GPS/trip merging. */
    private var bus = VehicleSnapshot(source = VehicleSnapshot.SOURCE_NONE)
    private var started = false
    private var receiver: BroadcastReceiver? = null
    private var registeredAction: String? = null

    private val carApi by lazy { CarPropertyBridge(app) }

    // ------------------------------------------------------------------ lifecycle
    fun install(ctx: Context) {
        if (app == null) app = ctx.applicationContext
        prefs = LauncherPrefs.get(ctx)
    }

    fun start() {
        if (started) return
        started = true
        registerBus()
        main.postDelayed(poll, POLL_MS)
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(poll)
        unregisterBus()
    }

    fun refresh() {
        registerBus()
        merge()
    }

    // ------------------------------------------------------------------ CAN broadcast bridge
    private fun registerBus() {
        val ctx = app ?: return
        val action = prefs?.vehicleBroadcastAction.orEmpty()
        if (action.isBlank()) {
            unregisterBus()
            return
        }
        if (registeredAction == action && receiver != null) return
        unregisterBus()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                i?.extras?.let { ingestExtras(it, i) }
            }
        }
        try {
            ctx.registerReceiver(r, IntentFilter(action))
            receiver = r
            registeredAction = action
            Log.i(TAG, "listening for CAN rebroadcast: $action")
        } catch (t: Throwable) {
            Log.w(TAG, "registering $action failed: ${t.message}")
        }
    }

    private fun unregisterBus() {
        val r = receiver ?: return
        try {
            app?.unregisterReceiver(r)
        } catch (_: Throwable) {
        }
        receiver = null
        registeredAction = null
    }

    /**
     * Name-based extraction. Supported aliases cover the usual OEM spellings so a user only has to
     * type the broadcast action, not a mapping table.
     */
    private fun ingestExtras(extras: Bundle, intent: Intent) {
        var speed = bus.speedKmh
        var rpm = bus.rpm
        var fuel = bus.fuelPct
        var coolant = bus.coolantC
        var odometer = bus.odometerKm
        var battery = bus.batteryPct
        var extTemp = bus.extTempC
        var doors = bus.doors
        var reverse = bus.reverse
        var ac = bus.ac
        var fan = bus.fanLevel
        var target = bus.targetC
        val tires = bus.tires?.copyOf(4) ?: FloatArray(4) { Float.NaN }
        var tiresSeen = false

        // 1) a JSON blob in the configured extra (Tasker / OEM bridges like this a lot)
        val jsonKey = prefs?.vehicleJsonExtra.orEmpty()
        if (jsonKey.isNotBlank()) {
            val blob = try {
                extras.getString(jsonKey)
            } catch (_: Throwable) {
                null
            }
            if (!blob.isNullOrBlank()) applyJson(blob)
        }

        // 2) individual extras
        for (key in extras.keySet().toList()) {
            val v = try {
                extras.get(key)
            } catch (_: Throwable) {
                null
            } ?: continue
            val num = (v as? Number)?.toFloat()
                ?: v.toString().trim().toFloatOrNull()
            when (normalize(key)) {
                "speed", "vehiclespeed", "currentspeed", "gpspeed" ->
                    num?.let { speed = if (it > 200f) it / 100f else it }
                "rpm", "enginerpm" -> num?.let { rpm = it }
                "fuel", "fuelpct", "fuellvl", "fuellevel" -> num?.let { fuel = it }
                "fuelrange", "range", "dte" -> num?.let { bus = bus.copy(rangeKm = it) }
                "coolant", "coolanttemp", "watertemp", "ect" -> num?.let { coolant = it }
                "odometer", "odometerkm", "totalkm" -> num?.let { odometer = it }
                "battery", "voltage", "batterypct" -> num?.let { battery = it }
                "exttemp", "ambienttemp", "outdoortemp", "temp" -> num?.let { extTemp = it }
                "doors", "dooropen" -> num?.let { doors = it.toInt() }
                "doorfl", "doorfrontleft" -> num?.let { if (it > 0.5f) doors = doors or VehicleSnapshot.DOOR_FL }
                "doorfr", "doorfrontright" -> num?.let { if (it > 0.5f) doors = doors or VehicleSnapshot.DOOR_FR }
                "doorrl", "doorrearleft" -> num?.let { if (it > 0.5f) doors = doors or VehicleSnapshot.DOOR_RL }
                "doorrr", "doorrearright" -> num?.let { if (it > 0.5f) doors = doors or VehicleSnapshot.DOOR_RR }
                "trunk", "hatch" -> num?.let { if (it > 0.5f) doors = doors or VehicleSnapshot.DOOR_TRUNK }
                "hood", "bonnet" -> num?.let { if (it > 0.5f) doors = doors or VehicleSnapshot.DOOR_HOOD }
                "reverse", "gearreverse", "backup", "rvc" -> num?.let { reverse = it > 0.5f }
                "ac", "acon" -> num?.let { ac = it.toInt() }
                "fan", "blower", "fanspeed" -> num?.let { fan = it.toInt() }
                "targettemp", "settemp", "temperature" -> num?.let { target = it }
                "tirefl", "tpmsfl", "tirepressurefl" -> { tires[0] = num ?: Float.NaN; tiresSeen = true }
                "tirefr", "tpmsfr", "tirepressurefr" -> { tires[1] = num ?: Float.NaN; tiresSeen = true }
                "tirerl", "tpmsrl", "tirepressurerl", "rearleft" -> { tires[2] = num ?: Float.NaN; tiresSeen = true }
                "tirerr", "tpmsrr", "tirepressurerr" -> { tires[3] = num ?: Float.NaN; tiresSeen = true }
                "lights", "lightson", "headlights" -> num?.let { bus = bus.copy(lightsOn = it > 0.5f) }
            }
        }
        if (intent.getBooleanExtra("reverse", false)) reverse = true

        bus = bus.copy(
            source = VehicleSnapshot.SOURCE_BROADCAST,
            speedKmh = speed,
            rpm = rpm,
            fuelPct = fuel,
            coolantC = coolant,
            odometerKm = odometer,
            batteryPct = battery,
            extTempC = extTemp,
            doors = doors,
            reverse = reverse,
            ac = ac,
            fanLevel = fan,
            targetC = target,
            tires = if (tiresSeen) tires else bus.tires,
            updatedAt = System.currentTimeMillis()
        )
        merge()
    }

    private fun applyJson(blob: String) {
        val o = try {
            JSONObject(blob)
        } catch (_: Throwable) {
            return
        }
        val bundle = Bundle()
        for (k in o.keys()) {
            when (val v = o.get(k)) {
                is Number -> bundle.putFloat(k, v.toFloat())
                is Boolean -> bundle.putFloat(k, if (v) 1f else 0f)
                else -> bundle.putString(k, v?.toString().orEmpty())
            }
        }
        // Nested arrays for tyre pressures.
        o.optJSONArray("tires")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 4)) {
                val key = arrayOf("tirefl", "tirefr", "tirerl", "tirerr")[i]
                bundle.putFloat(key, arr.optDouble(i, 0.0).toFloat())
            }
        }
        ingestExtras(bundle, Intent("internal.json"))
    }

    private fun normalize(key: String): String =
        key.lowercase().replace("_", "").replace("-", "").replace(".", "")

    // ------------------------------------------------------------------ polling / merging
    private val poll = object : Runnable {
        override fun run() {
            if (!started) return
            if (prefs?.speedSource == LauncherPrefs.SPEED_SRC_CAR_API) carApi.poll()
            merge()
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun merge() {
        val ctx = app ?: return
        val p = prefs ?: return
        val gps = LocationHub.state.value
        val car = carApi.lastSnapshot

        val speedFromGps = if (gps.hasFix) gps.speedKmh else null
        val speed = when (p.speedSource) {
            LauncherPrefs.SPEED_SRC_BROADCAST -> bus.speedKmh ?: speedFromGps
            LauncherPrefs.SPEED_SRC_CAR_API -> car?.speedKmh ?: bus.speedKmh ?: speedFromGps
            else -> speedFromGps ?: bus.speedKmh
        }

        val merged = VehicleSnapshot(
            source = when {
                p.speedSource == LauncherPrefs.SPEED_SRC_CAR_API && car != null -> VehicleSnapshot.SOURCE_CAR_API
                bus.source == VehicleSnapshot.SOURCE_BROADCAST -> VehicleSnapshot.SOURCE_BROADCAST
                else -> VehicleSnapshot.SOURCE_GPS
            },
            speedKmh = speed,
            rpm = car?.rpm ?: bus.rpm,
            fuelPct = car?.fuelPct ?: bus.fuelPct,
            rangeKm = car?.rangeKm ?: bus.rangeKm,
            coolantC = car?.coolantC ?: bus.coolantC,
            oilBar = bus.oilBar,
            odometerKm = car?.odometerKm ?: bus.odometerKm ?: TripComputer.totalKm(),
            tires = bus.tires ?: car?.tires,
            tireTemps = bus.tireTemps,
            doors = if (bus.doors != 0) bus.doors else (car?.doors ?: 0),
            lightsOn = bus.lightsOn || (car?.lightsOn ?: false),
            batteryPct = bus.batteryPct,
            extTempC = bus.extTempC,
            ac = bus.ac,
            fanLevel = if (bus.fanLevel >= 0) bus.fanLevel else (car?.fanLevel ?: -1),
            targetC = bus.targetC ?: car?.targetC,
            reverse = bus.reverse || (car?.reverse ?: false),
            updatedAt = maxOf(bus.updatedAt, car?.updatedAt ?: 0L)
        )
        if (merged == _state.value) return
        _state.value = merged
        TripComputer.tick(merged.speedKmh ?: 0f)
    }

    fun debugDump(): String = buildString {
        appendLine("src=${_state.value.source} speed=${_state.value.speedKmh} bus=${registeredAction}")
        appendLine(carApi.debugDump())
        append(TripComputer.debugDump())
    }

    fun isSourceAvailable(source: Int): Boolean = when (source) {
        LauncherPrefs.SPEED_SRC_GPS -> true
        LauncherPrefs.SPEED_SRC_BROADCAST -> registeredAction != null
        else -> carApi.available()
    }

}
