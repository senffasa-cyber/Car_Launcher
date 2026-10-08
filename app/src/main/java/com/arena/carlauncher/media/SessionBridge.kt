package com.arena.carlauncher.media

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent

/**
 * The one thing MediaHub cannot do with a [android.media.MediaController]: reach a session when no
 * controller could be built at all (no notification access, or a ROM whose service will not bind).
 *
 * A media key injected through `AudioManager.dispatchMediaKeyEvent` is routed by the audio policy to
 * whichever app currently owns the media-button session — exactly the "whatever is playing" semantic a
 * head unit wants. It is a @hide-ish entry point kept public since API 19 on every ROM we target, and
 * it needs no token, no binding and no permission, which is why it is the fallback rather than a
 * reflection maze. If the call is missing on a given firmware, the method reports false and the card
 * keeps showing metadata from the notification.
 */
object SessionBridge {

    private const val TAG = "SessionBridge"

    fun sendMediaKey(ctx: Context, keyCode: Int): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            val m = am.javaClass.getMethod("dispatchMediaKeyEvent", KeyEvent::class.java)
            m.invoke(am, down)
            m.invoke(am, up)
            true
        } catch (t: Throwable) {
            Log.d(TAG, "dispatchMediaKeyEvent unavailable: ${'$'}{t.message}")
            false
        }
    }

    /** Transport action -> media key code; SEEK has no key and is handled by the controller. */
    fun keyFor(action: MediaHub.Action): Int = when (action) {
        MediaHub.Action.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
        MediaHub.Action.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
        MediaHub.Action.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        MediaHub.Action.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        MediaHub.Action.PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        MediaHub.Action.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        MediaHub.Action.SEEK -> -1
        MediaHub.Action.FAST_FORWARD -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        MediaHub.Action.REWIND -> KeyEvent.KEYCODE_MEDIA_REWIND
    }
}
