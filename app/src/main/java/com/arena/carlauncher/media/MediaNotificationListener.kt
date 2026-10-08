package com.arena.carlauncher.media

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.arena.carlauncher.CarApp

/**
 * The gateway to every other app's media state.
 *
 * Android only lets an enabled `NotificationListenerService` call
 * `MediaSessionManager.getActiveSessions()`, and the same channel gives us the player's own
 * notification (title, artist, artwork, button intents). The user enables it once in
 * Settings → Notification access; until then the launcher still runs, just without the music card.
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        CarApp.ensureChannels(this)
        MediaHub.onListenerConnected(ComponentName(this, MediaNotificationListener::class.java))
    }

    override fun onListenerDisconnected() {
        MediaHub.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        MediaHub.onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        MediaHub.onNotificationRemoved(sbn)
    }

    companion object {
        fun component(ctx: android.content.Context) = ComponentName(ctx, MediaNotificationListener::class.java)

        /** Settings page that lists the listener toggles. */
        fun isEnabled(ctx: android.content.Context): Boolean =
            MediaHub.notificationAccessGranted(ctx)
    }
}
