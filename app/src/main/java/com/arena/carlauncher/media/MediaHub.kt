package com.arena.carlauncher.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import androidx.media.session.MediaControllerCompat
import androidx.media.session.MediaDescriptionCompat
import androidx.media.session.MediaMetadataCompat
import androidx.media.session.MediaSessionCompat
import androidx.media.session.PlaybackStateCompat
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.data.MediaSnapshot
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "now playing" hub.
 *
 * Two independent feeds are merged into one [MediaSnapshot]:
 *
 *  1. **MediaSession feed** — `MediaSessionManager.getActiveSessions()` (needs notification
 *     access, which we need anyway to see the player's buttons). Gives exact state, duration,
 *     position, artwork and seek.
 *  2. **Notification feed** — parsed by [NotificationMedia] from whatever the player posts. Gives
 *     title/artist/artwork and the pending intents behind its buttons even when a session
 *     controller cannot be constructed.
 *
 * Transport goes: session controller → injected media key → notification action. That ladder is
 * what makes the card behave on stock ROMs, where each layer is missing in a different way.
 */
object MediaHub {

    enum class Action { PLAY, PAUSE, PLAY_PAUSE, NEXT, PREV, STOP, SEEK, FAST_FORWARD, REWIND }

    private const val TAG = "MediaHub"
    private const val TICK_MS = 1000L

    private var app: Context? = null
    private var prefs: LauncherPrefs? = null
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(MediaSnapshot.EMPTY)
    val state = _state.asStateFlow()

    /** Package list of registered media sessions, for the "source" picker. */
    private val _sessions = MutableSharedFlow<List<String>>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sessions = _sessions.asSharedFlow()

    private var msm: MediaSessionManager? = null
    private var listenerComponent: ComponentName? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private var controller: MediaControllerCompat? = null
    private var controllerPkg: String = ""
    private var controllerToken: MediaSession.Token? = null
    private var knownTokens: List<MediaSession.Token> = emptyList()
    private var notif: NotificationMedia.Parsed? = null
    private var tracking = false
    private var lastSessionFetch = 0L

    private var artCache: Bitmap? = null
    private var artCacheKey: String = ""

    fun install(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        prefs = LauncherPrefs.get(ctx)
        msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }

    fun appContext(): Context? = app
    fun isTracking(): Boolean = tracking
    fun hasController(): Boolean = controller != null

    // ------------------------------------------------------------------ lifecycle
    fun startTracking() {
        val ctx = app ?: return
        if (tracking) return
        tracking = true
        registerSessionsListener(ctx)
        fetchSessions(ctx, force = true)
        main.postDelayed(tick, TICK_MS)
    }

    fun stopTracking() {
        if (!tracking) return
        tracking = false
        main.removeCallbacks(tick)
        try {
            val l = sessionsListener
            val c = listenerComponent
            if (l != null && c != null && Build.VERSION.SDK_INT >= 30) {
                msm?.removeOnActiveSessionsChangedListener(l, c)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "session listener removal unavailable: ${t.message}")
        }
        sessionsListener = null
        detachController()
    }

    fun onListenerConnected(component: ComponentName) {
        listenerComponent = component
        app?.let { ctx ->
            registerSessionsListener(ctx)
            fetchSessions(ctx, force = true)
        }
    }

    fun onListenerDisconnected() {
        listenerComponent = null
        detachController()
        publish()
    }

    private fun registerSessionsListener(ctx: Context) {
        val comp = listenerComponent ?: return
        if (sessionsListener != null) return
        try {
            val l = MediaSessionManager.OnActiveSessionsChangedListener { tokens ->
                onSessionsChanged(tokens ?: emptyList())
            }
            sessionsListener = l
            msm?.addOnActiveSessionsChangedListener(l, comp)
        } catch (t: Throwable) {
            Log.w(TAG, "session listener unavailable: ${t.message}")
        }
    }

    /** True when the user granted "Notification access" — required for active-session lookup. */
    fun notificationAccessGranted(ctx: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    } catch (_: Throwable) {
        false
    }

    fun openNotificationAccessSettings(ctx: Context) {
        val intents = listOf(
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
            Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS"),
            Intent("android.settings.NOTIFICATION_ACCESS_SETTINGS")
        )
        for (i in intents) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------ sessions
    private fun onSessionsChanged(tokens: List<MediaSession.Token>) {
        knownTokens = tokens
        _sessions.tryEmit(tokens.map { SessionBridge.ownerOf(it) }.filter { it.isNotEmpty() }.distinct())
        pickAndAttach()
    }

    fun fetchSessions(ctx: Context, force: Boolean = false) {
        val comp = listenerComponent ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSessionFetch < 1500L) return
        lastSessionFetch = now
        try {
            val tokens = msm?.getActiveSessions(comp) ?: emptyList()
            onSessionsChanged(tokens)
        } catch (t: Throwable) {
            Log.w(TAG, "getActiveSessions failed: ${t.message}")
        }
    }

    fun availableSources(ctx: Context): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val pm = ctx.packageManager
        for (t in knownTokens) {
            val pkg = SessionBridge.ownerOf(t)
            if (pkg.isEmpty() || out.any { it.first == pkg }) continue
            out.add(pkg to labelFor(ctx, pkg))
        }
        notif?.takeIf { n -> n.pkg.isNotEmpty() && out.none { it.first == n.pkg } }?.let { n ->
            out.add(n.pkg to labelFor(ctx, n.pkg))
        }
        return out
    }

    fun setPreferredPackage(pkg: String) {
        prefs?.mediaSessionPackage = pkg
        pickAndAttach()
    }

    private fun pickAndAttach() {
        val ctx = app ?: return
        val preferred = prefs?.mediaSessionPackage.orEmpty()
        val candidate = knownTokens.firstOrNull { SessionBridge.ownerOf(it) == preferred }
            ?: bestByPriority()

        if (candidate == null) {
            detachController()
            publish()
            return
        }
        if (candidate === controllerToken) {
            if (controller != null) publish()
            return
        }
        attach(ctx, candidate)
    }

    /**
     * `getActiveSessions()` is ordered by recency, so the first entry is what the driver is
     * listening to; an actively playing session always wins over a merely registered one.
     */
    private fun bestByPriority(): MediaSession.Token? {
        val tokens = knownTokens
        if (tokens.isEmpty()) return null
        return tokens.firstOrNull { isPlayingToken(it) } ?: tokens.first()
    }

    private fun isPlayingToken(token: MediaSession.Token): Boolean {
        val ctx = app ?: return false
        return try {
            val ax = SessionBridge.toAndroidXToken(ctx, token) as? MediaSessionCompat.Token
                ?: return false
            val c = MediaControllerCompat(ctx, ax)
            val s = c.playbackState?.state
            try {
                c.destroy()
            } catch (_: Throwable) {
            }
            s == PlaybackStateCompat.STATE_PLAYING
        } catch (_: Throwable) {
            false
        }
    }

    private fun attach(ctx: Context, token: MediaSession.Token) {
        detachController()
        controllerToken = token
        controllerPkg = SessionBridge.ownerOf(token)
        val ax = try {
            SessionBridge.toAndroidXToken(ctx, token) as? MediaSessionCompat.Token
        } catch (t: Throwable) {
            Log.w(TAG, "token bridge failed: ${t.message}")
            null
        }
        if (ax != null) {
            try {
                val c = MediaControllerCompat(ctx, ax)
                c.registerCallback(callback)
                controller = c
                controllerPkg = controllerPkg.ifBlank { c.packageName.orEmpty() }
                Log.i(TAG, "media controller attached: $controllerPkg")
            } catch (t: Throwable) {
                Log.w(TAG, "controller attach failed: ${t.message}")
                controller = null
            }
        }
        publish()
    }

    private fun detachController() {
        controller?.let { c ->
            try {
                c.unregisterCallback(callback)
            } catch (_: Throwable) {
            }
            try {
                c.destroy()
            } catch (_: Throwable) {
            }
        }
        controller = null
        controllerToken = null
        controllerPkg = ""
    }

    private val callback = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            publish()
        }

        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            publish()
        }

        override fun onSessionDestroyed() {
            detachController()
            publish()
            app?.let { fetchSessions(it) }
        }
    }

    // ------------------------------------------------------------------ notifications
    fun onNotificationPosted(sbn: StatusBarNotification?) {
        val ctx = app ?: return
        val parsed = NotificationMedia.parse(ctx, sbn) ?: return
        if (parsed.title.isBlank() && parsed.artwork == null) return
        val preferred = prefs?.mediaSessionPackage.orEmpty()
        if (preferred.isNotEmpty() && parsed.pkg != preferred && controllerPkg != parsed.pkg) return
        notif = parsed

        if (controller == null && parsed.hasSessionToken && sbn != null) {
            val token = try {
                @Suppress("DEPRECATION")
                sbn.notification?.extras?.get(NotificationMedia.Keys.MEDIA_SESSION) as? MediaSession.Token
            } catch (_: Throwable) {
                null
            }
            if (token != null && token !== controllerToken) {
                attach(ctx, token)
                return
            }
        }
        publish()
    }

    fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (notif?.pkg == pkg) {
            notif = null
            publish()
        }
    }

    // ------------------------------------------------------------------ publish
    private fun publish() {
        val snapshot = build()
        if (snapshot == _state.value) return
        _state.value = snapshot
    }

    private fun build(): MediaSnapshot {
        val ctx = app ?: return MediaSnapshot.EMPTY
        val n = notif
        val owner = if (controllerPkg.isNotBlank()) controllerPkg else n?.pkg.orEmpty()
        val fallback = MediaSnapshot(
            ownerPackage = owner,
            ownerLabel = labelFor(ctx, owner),
            title = n?.title.orEmpty(),
            artist = n?.artist.orEmpty(),
            album = n?.album.orEmpty(),
            artwork = n?.artwork,
            hasSession = false,
            source = if (n != null) MediaSnapshot.SOURCE_NOTIFICATION else MediaSnapshot.SOURCE_NONE,
            updatedAtElapsed = SystemClock.elapsedRealtime()
        )
        val c = controller ?: return fallback
        return try {
            fromController(c, fallback, ctx)
        } catch (t: Throwable) {
            Log.d(TAG, "controller read failed: ${t.message}")
            fallback
        }
    }

    private fun fromController(c: MediaControllerCompat, fallback: MediaSnapshot, ctx: Context): MediaSnapshot {
        val md = c.metadata
        val desc: MediaDescriptionCompat? = md?.description
        val ps = c.playbackState
        val actions = ps?.actions ?: 0L

        val title = desc?.title?.toString().orEmpty()
            .ifBlank { md?.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty() }
        val artist = desc?.subtitle?.toString().orEmpty()
            .ifBlank { md?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST).orEmpty() }
        val album = md?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM).orEmpty()
            .ifBlank { desc?.description?.toString().orEmpty() }
        val duration = md?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L

        val state = ps?.state ?: PlaybackStateCompat.STATE_NONE
        val playing = state == PlaybackStateCompat.STATE_PLAYING
        val position = ps?.position ?: 0L
        val lastUpdate = ps?.lastPositionUpdateTime ?: 0L
        val speed = try {
            ps?.playbackSpeed ?: 1f
        } catch (_: Throwable) {
            1f
        }
        val extrapolated = if (playing && lastUpdate > 0L) {
            position + ((SystemClock.elapsedRealtime() - lastUpdate) * speed).toLong()
        } else position

        return fallback.copy(
            ownerPackage = controllerPkg.ifBlank { fallback.ownerPackage },
            ownerLabel = labelFor(ctx, controllerPkg.ifBlank { fallback.ownerPackage }),
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            positionMs = extrapolated,
            isPlaying = playing,
            hasSession = true,
            canSeek = actions and PlaybackStateCompat.ACTION_SEEK_TO != 0L,
            canSkipNext = actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L,
            canSkipPrev = actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS != 0L,
            artwork = pickArtwork(md, desc, fallback),
            source = MediaSnapshot.SOURCE_SESSION,
            updatedAtElapsed = SystemClock.elapsedRealtime(),
            playingSpeed = if (speed <= 0f) 1f else speed
        )
    }

    /** Artwork is downsampled exactly once here so the card and the backdrop glow share one bitmap. */
    private fun pickArtwork(
        md: MediaMetadataCompat?,
        desc: MediaDescriptionCompat?,
        fallback: MediaSnapshot
    ): Bitmap? {
        val raw: Bitmap? = md?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
            ?: desc?.iconBitmap
            ?: fallback.artwork
        if (raw == null) {
            artCache = null
            artCacheKey = ""
            return null
        }
        if (raw.isRecycled) return fallback.artwork
        val key = "${fallback.title}|${raw.width}x${raw.height}"
        if (key == artCacheKey) {
            artCache?.let { cached -> if (!cached.isRecycled) return cached }
        }
        if (raw.width <= ART_MAX && raw.height <= ART_MAX) {
            artCache = raw
            artCacheKey = key
            return raw
        }
        val scaled = try {
            Bitmap.createScaledBitmap(raw, ART_MAX, ART_MAX, true)
        } catch (_: Throwable) {
            raw
        }
        artCache = scaled
        artCacheKey = key
        return scaled
    }

    private fun labelFor(ctx: Context, pkg: String): String = try {
        if (pkg.isEmpty()) ""
        else ctx.packageManager.getApplicationLabel(
            ctx.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Throwable) {
        ""
    }

    // ------------------------------------------------------------------ transport
    fun act(action: Action) {
        val ctx = app ?: return
        if (action == Action.SEEK) return
        val c = controller
        if (c != null && useController(c, action)) {
            publish()
            return
        }
        if (SessionBridge.sendMediaKey(ctx, SessionBridge.keyFor(action))) return
        val intent = when (action) {
            Action.PLAY, Action.PAUSE, Action.PLAY_PAUSE -> notif?.playPauseIntent
            Action.NEXT -> notif?.nextIntent
            Action.PREV -> notif?.prevIntent
            Action.STOP -> notif?.stopIntent
            else -> notif?.playPauseIntent
        }
        try {
            intent?.send()
        } catch (t: Throwable) {
            Log.d(TAG, "notification action send failed: ${t.message}")
        }
    }

    private fun useController(c: MediaControllerCompat, action: Action): Boolean = try {
        val tc = c.transportControls
        when (action) {
            Action.PLAY -> {
                tc.play(); true
            }
            Action.PAUSE -> {
                tc.pause(); true
            }
            Action.PLAY_PAUSE -> {
                if (c.playbackState?.state == PlaybackStateCompat.STATE_PLAYING) tc.pause() else tc.play()
                true
            }
            Action.NEXT -> {
                tc.skipToNext(); true
            }
            Action.PREV -> {
                tc.skipToPrevious(); true
            }
            Action.STOP -> {
                tc.stop(); true
            }
            else -> false // rewind / fast-forward: use the media key path
        }
    } catch (t: Throwable) {
        Log.d(TAG, "transport unavailable: ${t.message}")
        false
    }

    fun seekTo(ms: Long) {
        val c = controller ?: return
        try {
            val allowed = (c.playbackState?.actions ?: 0L) and PlaybackStateCompat.ACTION_SEEK_TO
            if (allowed != 0L) {
                c.transportControls.seekTo(ms.coerceAtLeast(0L))
                publish()
            }
        } catch (_: Throwable) {
        }
    }

    fun togglePlayPause() = act(Action.PLAY_PAUSE)
    fun next() = act(Action.NEXT)
    fun previous() = act(Action.PREV)

    /** Opens the owning player app (the "…" button on the music card). */
    fun openPlayer(ctx: Context) {
        val pkg = prefs?.mediaSessionPackage?.ifBlank { null } ?: controllerPkg.ifBlank { null }
        if (pkg == null) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
            }
            return
        }
        try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launch)
            }
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ volume
    fun volumePercent(ctx: Context): Int {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return -1
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) -1 else am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        } catch (_: Throwable) {
            -1
        }
    }

    fun setVolumePercent(ctx: Context, percent: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val v = max * percent.coerceIn(0, 100) / 100
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        } catch (_: Throwable) {
        }
    }

    fun adjustVolume(ctx: Context, raise: Boolean) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (_: Throwable) {
        }
    }

    fun isMusicActive(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive == true
    } catch (_: Throwable) {
        false
    }

    /** Hardware / steering-wheel keys land here while the launcher has focus. */
    fun onKeyDown(keyCode: Int): Boolean {
        val mapped = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> Action.PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY -> Action.PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> Action.PAUSE
            KeyEvent.KEYCODE_MEDIA_NEXT -> Action.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Action.PREV
            KeyEvent.KEYCODE_MEDIA_STOP -> Action.STOP
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Action.FAST_FORWARD
            KeyEvent.KEYCODE_MEDIA_REWIND -> Action.REWIND
            else -> return false
        }
        act(mapped)
        return true
    }

    // ------------------------------------------------------------------ ticking
    private val tick = object : Runnable {
        override fun run() {
            if (!tracking) return
            val playing = _state.value.isPlaying
            val none = _state.value.source == MediaSnapshot.SOURCE_NONE
            publish()
            if (none && knownTokens.isEmpty() && listenerComponent != null) {
                app?.let { fetchSessions(it, force = true) }
            }
            main.postDelayed(this, if (playing) TICK_MS else TICK_MS * 3L)
        }
    }

    fun debugDump(): String = buildString {
        appendLine("tracking=$tracking access=${app?.let { notificationAccessGranted(it) }}")
        appendLine("controller=$controllerPkg tokens=${knownTokens.size} notif=${notif?.pkg}")
        val s = _state.value
        appendLine("${s.title} / ${s.artist} playing=${s.isPlaying} src=${s.source}")
    }

    private const val ART_MAX = 360
}
