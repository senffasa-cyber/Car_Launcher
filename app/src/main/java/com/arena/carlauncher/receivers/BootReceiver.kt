package com.arena.carlauncher.receivers

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.arena.carlauncher.CarApp
import com.arena.carlauncher.R
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.services.LauncherService

/**
 * Starts the background hubs when the head unit boots — before anyone touches the screen.
 *
 * Also nags (once, politely) when the launcher is installed but not selected as the default home,
 * which is the single most common "why is my work not showing" report for car launchers.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        val p = LauncherPrefs.get(ctx)
        if (!p.bootStart) return
        LauncherService.start(ctx, "boot:${intent?.action?.substringAfterLast('.')}")

        // Give the unit a second to finish unlocking before we poke the media session manager.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            LauncherService.start(ctx, "boot-late")
            warnIfNotDefaultHome(ctx, p)
        }, 2500L)
    }

    private fun warnIfNotDefaultHome(ctx: Context, p: LauncherPrefs) {
        if (!p.firstRunDone) {
            notifySetup(ctx)
            return
        }
        val home = try {
            val pm = ctx.packageManager
            val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolve = pm.resolveActivity(probe, 0)
            resolve?.activityInfo?.packageName
        } catch (_: Throwable) {
            null
        }
        if (home == null || home == ctx.packageName) return
        notifySetup(ctx)
    }

    private fun notifySetup(ctx: Context) {
        CarApp.ensureChannels(ctx)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(
            ctx, 100,
            Intent(ctx, com.arena.carlauncher.onboarding.OnboardingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        try {
            NotificationCompat.Builder(ctx, CarApp.CHANNEL_SILENT)
                .setSmallIcon(R.drawable.ic_car)
                .setContentTitle(ctx.getString(R.string.notify_setup_title))
                .setContentText(ctx.getString(R.string.notify_setup_text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_GUIDANCE)
                .setContentIntent(pi)
                .build()
                .let { nm ->
                    val manager = android.app.NotificationManagerCompat.from(ctx)
                    try {
                        manager.notify(1001, nm)
                    } catch (t: Throwable) {
                        // POST_NOTIFICATIONS on 13+ without the grant: not fatal.
                        android.util.Log.d("BootReceiver", "notify refused: ${t.message}")
                    }
                }
        } catch (t: Throwable) {
            android.util.Log.d("BootReceiver", "setup notification skipped: ${t.message}")
        }
    }
}
