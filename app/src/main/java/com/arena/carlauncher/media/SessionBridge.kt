package com.arena.carlauncher.media

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent

/**
 * One job: turn a framework [MediaSession.Token] — the only token type
 * `MediaSessionManager.getActiveSessions()` returns — into an
 * `androidx.media.session.MediaSessionCompat.Token`, which is what `MediaControllerCompat` needs.
 *
 * The AndroidX library exposes a conversion for this, but *which* entry point exists changed
 * between media1.0 → media1.7, and every head-unit ROM links a different version. Hard-coding one
 * call would break the build on someone's fork, so the lookup happens at runtime and every known
 * shape is tried in order. If nothing works, [MediaHub] keeps working through the media
 * notification + injected media keys, which needs no token at all.
 */
object SessionBridge {

    private const val TAG = "SessionBridge"
    private const val AX_TOKEN = "androidx.media.session.MediaSessionCompat\$Token"

    @Volatile
    private var strategy: Int = -1
    private var lastError: String? = null

    /** @return the AndroidX token, or null when no conversion strategy is available. */
    fun toAndroidXToken(ctx: Context, fw: MediaSession.Token): Any? {
        if (strategy >= 0) return runStrategy(strategy, ctx, fw)
        for (s in 0 until STRATEGY_COUNT) {
            val out = runStrategy(s, ctx, fw)
            if (out != null) {
                strategy = s
                Log.i(TAG, "media session bridge ready via strategy $s")
                return out
            }
        }
        Log.w(TAG, "no MediaSessionCompat.Token bridge available (${lastError ?: "unknown"})")
        return null
    }

    private const val STRATEGY_COUNT = 4

    private fun runStrategy(s: Int, ctx: Context, fw: MediaSession.Token): Any? = try {
        val tokenCls = Class.forName(AX_TOKEN)
        when (s) {
            // static MediaSessionCompat.Token fromToken(Object)
            0 -> tokenCls.getMethod("fromToken", Any::class.java).invoke(null, fw)
            // static MediaSessionCompat.fromToken(Context, MediaSession.Token) on some versions
            1 -> Class.forName("androidx.media.session.MediaSessionCompat")
                .getMethod("fromToken", Context::class.java, MediaSession.Token::class.java)
                .invoke(null, ctx, fw)
            // constructor Token(MediaSession.Token)
            2 -> tokenCls.getDeclaredConstructor(MediaSession.Token::class.java)
                .apply { isAccessible = true }.newInstance(fw)
            // constructor Token(IBinder, int) — the historical @Deprecated path
            else -> {
                val binder = fw.javaClass.getMethod("getBinder").invoke(fw) as? IBinder
                val sessionId = fw.javaClass.getMethod("getSessionId").invoke(fw) as? Int ?: 0
                if (binder == null) null
                else tokenCls.getDeclaredConstructor(IBinder::class.java, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.newInstance(binder, sessionId)
            }
        }
    } catch (t: Throwable) {
        lastError = t.javaClass.simpleName + ": " + t.message
        null
    }

    /** Which package owns `fw` — used for the source picker and for the preferred-package match. */
    fun ownerOf(fw: MediaSession.Token): String = try {
        val pkg = fw.javaClass.getMethod("getPackageName")
        (pkg.invoke(fw) as? String).orEmpty()
    } catch (_: Throwable) {
        // MediaSession.Token exposes the package only as a Parcelable field on some ROMs;
        // the description fallback is good enough for the picker.
        try {
            val desc = fw.javaClass.getMethod("getDescription")
            val d = desc.invoke(fw)
            (d?.javaClass?.getMethod("getPackageName")?.invoke(d) as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Injects a media key. The audio policy routes it to whichever app currently owns the
     * media-button session — precisely the "whatever is playing" semantic a head unit wants, and
     * it is the only transport available when no controller could be constructed.
     */
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
            Log.d(TAG, "dispatchMediaKeyEvent unavailable: ${t.message}")
            false
        }
    }

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

    fun hasFrameworkSessions(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
}
