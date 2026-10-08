package com.arena.carlauncher.media

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap

/**
 * Extracts "now playing" from a media notification.
 *
 * This is the fallback that makes the music card work with *every* player — including ones whose
 * session token cannot be wrapped, players that never post a `MediaStyle` notification correctly,
 * and local music apps shipped with Chinese head units. The notification also carries the pending
 * intents behind the play/pause/next buttons, so transport works even without a controller.
 */
object NotificationMedia {

    data class Parsed(
        val key: String,
        val pkg: String,
        val title: String,
        val artist: String,
        val album: String,
        val artwork: Bitmap?,
        val isMedia: Boolean,
        val hasSessionToken: Boolean,
        val playPauseIntent: android.app.PendingIntent?,
        val nextIntent: android.app.PendingIntent?,
        val prevIntent: android.app.PendingIntent?,
        val stopIntent: android.app.PendingIntent?,
        val actions: List<Pair<String, android.app.PendingIntent>> = emptyList(),
        val lyricLine: String = ""
    ) {
        fun matching(vararg words: String): android.app.PendingIntent? {
            for ((label, pi) in actions) {
                val l = label.lowercase()
                if (words.any { l.contains(it) }) return pi
            }
            return null
        }
    }

    private val MEDIA_HINTS = listOf("play", "pause", "next", "previous", "skip", "stop", "_shuffle", "repeat")

    fun parse(ctx: Context, sbn: StatusBarNotification?): Parsed? {
        sbn ?: return null
        val n = sbn.notification ?: return null
        val extras: Bundle = n.extras ?: return null

        val sessionToken = try {
            @Suppress("DEPRECATION")
            extras.get(Keys.MEDIA_SESSION) != null
        } catch (_: Throwable) {
            false
        }
        val styleName = try {
            n.style?.javaClass?.name?.lowercase().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val looksLikeMedia = sessionToken || styleName.contains("media")

        val title = extras.charSequence(Keys.TITLE)
        val text = firstOf(extras.charSequence(Keys.TEXT), extras.charSequence(Keys.SUB_TEXT))
        val info = extras.charSequence(Keys.INFO)
        val lyricLine = extras.charSequenceArray(Keys.TEXT_LINES).lastOrNull()
            ?: extras.charSequence(Keys.BIG_TEXT)

        // Some players only fill the action titles; require a plausible media shape.
        val actionTitles = ArrayList<Pair<String, android.app.PendingIntent>>()
        n.actions?.forEach { a ->
            val label = a?.title?.toString().orEmpty()
            val pi = a?.actionIntent
            if (label.isNotEmpty() && pi != null) actionTitles.add(label to pi)
        }
        val mediaByActions = actionTitles.size in 2..6 &&
            actionTitles.count { e -> MEDIA_HINTS.any { e.first.lowercase().contains(it) } } >= 2
        val isMedia = looksLikeMedia || (mediaByActions && title.isNotEmpty())
        if (!isMedia) return null

        val artwork = artwork(ctx, n, extras)

        return Parsed(
            key = sbn.key ?: "${sbn.packageName}#${sbn.id}",
            pkg = sbn.packageName ?: "",
            title = title,
            artist = if (info.isNotEmpty()) info else text,
            album = if (info.isNotEmpty() && text.isNotEmpty()) text else "",
            artwork = artwork,
            isMedia = true,
            hasSessionToken = sessionToken,
            playPauseIntent = null,
            nextIntent = null,
            prevIntent = null,
            stopIntent = null,
            actions = actionTitles,
            lyricLine = lyricLine
        ).let { p ->
            p.copy(
                playPauseIntent = p.matching("pause", "play") ?: actionTitles.getOrNull(1)?.second,
                nextIntent = p.matching("next", "skip next") ?: actionTitles.getOrNull(2)?.second,
                prevIntent = p.matching("previous", "prev") ?: actionTitles.getOrNull(0)?.second,
                stopIntent = p.matching("stop", "close")
            )
        }
    }

    private fun firstOf(a: String, b: String) = if (a.isNotBlank()) a else b

    private fun Bundle.charSequence(key: String): String = try {
        (get(key) as? CharSequence)?.toString().orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun Bundle.charSequenceArray(key: String): List<String> = try {
        val v = get(key)
        when (v) {
            is Array<*> -> v.filterIsInstance<CharSequence>().map { it.toString() }
            is CharSequence -> listOf(v.toString())
            else -> emptyList()
        }
    } catch (_: Throwable) {
        emptyList()
    }

    /** Album art: EXTRA_PICTURE → EXTRA_LARGE_ICON (Bitmap or Icon) → notification largeIcon. */
    private fun artwork(ctx: Context, n: Notification, extras: Bundle): Bitmap? {
        try {
            @Suppress("DEPRECATION")
            val picture = extras.get(Keys.PICTURE)
            (picture as? Bitmap)?.let { if (!it.isRecycled) return it }
        } catch (_: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                @Suppress("DEPRECATION")
                val icon = extras.get(Keys.LARGE_ICON)
                if (icon is Icon) {
                    val d = try {
                        icon.loadDrawable(ctx)
                    } catch (_: Throwable) {
                        null
                    }
                    d?.let {
                        val size = 320
                        return try {
                            it.toBitmap(size, size, Bitmap.Config.ARGB_8888)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                } else if (icon is Bitmap && !icon.isRecycled) return icon
            } catch (_: Throwable) {
            }
        }
        @Suppress("DEPRECATION")
        return try {
            n.largeIcon?.takeIf { !it.isRecycled }
        } catch (_: Throwable) {
            null
        }
    }

    object Keys {
        const val TITLE = "android.title"
        const val TEXT = "android.text"
        const val INFO = "android.info"
        const val SUB_TEXT = "android.subText"
        const val BIG_TEXT = "android.bigText"
        const val TEXT_LINES = "android.textLines"
        const val LARGE_ICON = "android.largeIcon"
        const val PICTURE = "android.picture"
        const val MEDIA_SESSION = "android.mediaSession"
    }
}
