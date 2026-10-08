package com.arena.carlauncher.permission

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.arena.carlauncher.R
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.home.ForegroundAppTracker
import com.arena.carlauncher.home.ForegroundAppWatcher
import com.arena.carlauncher.loc.LocationHub
import com.arena.carlauncher.media.MediaHub

/**
 * One list of every permission the launcher can use, whether it is granted, and how to ask.
 *
 * A head unit is not a phone: the ROM's permission dialogs vary, several "normal" dialogs are simply
 * missing on SPRD firmware, and the manufacturer build often blocks `MANAGE_OVERLAY_PERMISSION`. So the
 * hub treats each entry as *optional with a visible consequence* — the summary states what stops working
 * — and falls back to the app-details screen whenever the vendor intent is absent. That way a missing
 * dialog degrades into "open Settings and find it yourself" instead of a crash.
 */
object PermissionHub {

    private const val TAG = "PermissionHub"

    data class Item(
        val key: String,
        val title: String,
        val summary: String,
        val granted: Boolean,
        val detail: String = "",
        val critical: Boolean = false,
        val request: (Context) -> Unit
    )

    // ------------------------------------------------------------------ checks

    fun hasLocation(ctx: Context): Boolean = LocationHub.hasPermission(ctx)

    fun hasNotificationAccess(ctx: Context): Boolean = MediaHub.notificationAccessGranted(ctx)

    fun hasUsageAccess(ctx: Context): Boolean = ForegroundAppWatcher.hasUsageAccess(ctx)

    fun hasAccessibility(ctx: Context): Boolean = ForegroundAppTracker.enabled(ctx)

    fun hasOverlay(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Throwable) {
        false
    }

    fun hasWriteSettings(ctx: Context): Boolean = try {
        Settings.System.canWrite(ctx)
    } catch (_: Throwable) {
        false
    }

    fun hasBatteryExemption(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    } catch (_: Throwable) {
        true
    }

    fun isDefaultHome(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val matches = try {
            pm.queryIntentActivities(intent, 0)
        } catch (_: Throwable) {
            emptyList()
        }
        if (matches.size <= 1) return true
        val resolved = try {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0)?.activityInfo?.packageName
        } catch (_: Throwable) {
            null
        }
        return resolved == ctx.packageName || resolved == null
    }

    // ------------------------------------------------------------------ status list

    @SuppressLint("BatteryLife")
    fun status(ctx: Context): List<Item> {
        val out = ArrayList<Item>(8)
        out.add(
            Item(
                KEY_LOCATION,
                ctx.getString(R.string.perm_location),
                ctx.getString(R.string.perm_location_sum),
                hasLocation(ctx),
                critical = true
            ) { openLocation(ctx) }
        )
        out.add(
            Item(
                KEY_NOTIFICATION,
                ctx.getString(R.string.perm_notification),
                ctx.getString(R.string.perm_notification_sum),
                hasNotificationAccess(ctx),
                critical = true
            ) { MediaHub.openNotificationAccessSettings(ctx) }
        )
        out.add(
            Item(
                KEY_USAGE,
                ctx.getString(R.string.perm_usage),
                ctx.getString(R.string.perm_usage_sum),
                hasUsageAccess(ctx)
            ) { ForegroundAppWatcher.openUsageAccessSettings(ctx) }
        )
        out.add(
            Item(
                KEY_ACCESSIBILITY,
                ctx.getString(R.string.perm_accessibility),
                ctx.getString(R.string.perm_accessibility_sum),
                hasAccessibility(ctx),
                detail = ctx.getString(R.string.perm_accessibility_detail)
            ) { ForegroundAppTracker.openSettings(ctx) }
        )
        out.add(
            Item(
                KEY_OVERLAY,
                ctx.getString(R.string.perm_overlay),
                ctx.getString(R.string.perm_overlay_sum),
                hasOverlay(ctx)
            ) { openOverlay(ctx) }
        )
        out.add(
            Item(
                KEY_WRITE_SETTINGS,
                ctx.getString(R.string.perm_write_settings),
                ctx.getString(R.string.perm_write_settings_sum),
                hasWriteSettings(ctx)
            ) { openWriteSettings(ctx) }
        )
        out.add(
            Item(
                KEY_BATTERY,
                ctx.getString(R.string.perm_battery),
                ctx.getString(R.string.perm_battery_sum),
                hasBatteryExemption(ctx)
            ) { openBattery(ctx) }
        )
        out.add(
            Item(
                KEY_HOME,
                ctx.getString(R.string.perm_default_home),
                ctx.getString(R.string.perm_default_home_sum),
                isDefaultHome(ctx),
                critical = true
            ) { askDefaultHome(ctx) }
        )
        return out
    }

    fun missingCritical(ctx: Context): List<Item> = status(ctx).filter { it.critical && !it.granted }

    fun allGranted(ctx: Context): Boolean = status(ctx).none { !it.granted && it.critical }

    fun grant(ctx: Context, key: String): Boolean {
        val item = status(ctx).firstOrNull { it.key == key } ?: return false
        item.request(ctx)
        return item.granted
    }

    // ------------------------------------------------------------------ intents

    private fun start(ctx: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ContextCompat.startActivity(ctx, intent, null)
            true
        } catch (t: Throwable) {
            Log.d(TAG, "no permission screen: ${t.message}")
            openAppDetails(ctx)
            false
        }
    }

    fun openAppDetails(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }

    /** Runtime permissions need an Activity; every other route is a Settings screen. */
    private fun openLocation(ctx: Context) {
        val act = ctx as? android.app.Activity
        if (act != null) {
            try {
                androidx.core.app.ActivityCompat.requestPermissions(
                    act,
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    REQ_LOCATION
                )
                return
            } catch (_: Throwable) {
            }
        }
        if (!start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", ctx.packageName, null)))
        ) {
            start(ctx, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    private fun openOverlay(ctx: Context) {
        if (!start(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))) {
            openAppDetails(ctx)
        }
    }

    private fun openWriteSettings(ctx: Context) {
        if (!start(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))) {
            openAppDetails(ctx)
        }
    }

    @SuppressLint("BatteryLife")
    private fun openBattery(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 23) {
            if (start(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))) return
        }
        if (!start(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppDetails(ctx)
    }

    private const val REQ_LOCATION = 1201

    fun askDefaultHome(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 25) {
            if (start(ctx, Intent(Settings.ACTION_HOME_SETTINGS))) return
        }
        start(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
    }

    /** Called after onboarding so the "first run" flag is not re-shown on every boot. */
    fun complete(ctx: Context) {
        LauncherPrefs.get(ctx).firstRunDone = true
    }
}
