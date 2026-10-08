package com.arena.carlauncher.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.viewpager2.widget.ViewPager2
import com.arena.carlauncher.R
import com.arena.carlauncher.actions.ActionRouter
import com.arena.carlauncher.data.AppRepository
import com.arena.carlauncher.data.Cards
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.media.MediaHub
import com.arena.carlauncher.services.CallOverlayService
import com.arena.carlauncher.services.LauncherService
import com.arena.carlauncher.services.ScreenOffHelper
import com.arena.carlauncher.theme.DayNightController
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.ui.TimeTicker
import com.arena.carlauncher.ui.Views
import com.arena.carlauncher.util.CrashLog
import com.arena.carlauncher.util.Format
import com.arena.carlauncher.vehicle.VehicleHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The home screen. Everything the driver sees is assembled here, in code, in one pass.
 *
 * On the 1208x720 panel the layout is a weight grid: two fixed mini-card columns (only on wide
 * screens) around a centre carousel, a top strip above and the dock below. Weights rather than
 * ConstraintLayout because the tree is rebuilt when the panel resolution or orientation changes and a
 * weight grid costs one measure pass instead of two.
 *
 * The activity is intentionally dumb: it owns views, palette listening and the UiHooks that
 * [ActionRouter] calls back into. Every decision about *what* to show lives in
 * [CenterCardCoordinator], so the centre-card logic is testable and reusable without an Activity.
 */
class HomeActivity : androidx.appcompat.app.AppCompatActivity(),
    CardPagerAdapter.Callback,
    ActionRouter.UiHooks {

    private lateinit var prefs: LauncherPrefs
    private lateinit var root: GestureLayout

    /** False until `buildUi` has actually finished; every lifecycle hook that touches `root` checks it. */
    private var uiReady = false
    private var wallpaper: ImageView? = null
    private var scrim: View? = null
    private var topBar: TopBar? = null
    private var center: ViewPager2? = null
    private var centerAdapter: CardPagerAdapter? = null
    private var leftStrip: MiniCardStrip? = null
    private var rightStrip: MiniCardStrip? = null
    private var dock: DockView? = null
    private var allApps: AllAppsView? = null
    private var driveLock: View? = null
    private var lockAck = false
    private var lastLocked = false
    private var brightnessBoost = false
    private var lastCenterId = ""
    private var volumeBeforeMute = -1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val prefsListener: () -> Unit = { runOnUiThread { onPrefsChanged() } }
    private val appsListener: () -> Unit = { runOnUiThread { dock?.rebuild() } }
    private val paletteListener: () -> Unit = { runOnUiThread { onPaletteChanged() } }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        DayNightController.install(application)
        prefs = LauncherPrefs.get(this)
        prefs.addChangeListener(prefsListener)
        Palette.addListener(paletteListener)
        super.onCreate(savedInstanceState)
        AppRepository.load()
        AppRepository.addChangeListener(appsListener)
        // A card that throws must not become a launcher that will not open: buildUi is guarded, and the
        // first failure retries once in safe mode (three cards, no wallpaper, no service).
        if (!buildUiGuarded()) return
        ActionRouter.ui = this
        if (!prefs.safeMode) LauncherService.start(this, "home")
        refreshWallpaper()
        applyWindowFlags()
        handleIntent(intent)
        CenterCardCoordinator.evaluate()
        scope.launch {
            CenterCardCoordinator.center.collect { onCenterChanged(it) }
        }
    }

    /** @return false when the UI was not built — the caller stops setup, because the views do not exist. */
    private fun buildUiGuarded(): Boolean {
        return try {
            buildUi()
            uiReady = true
            CrashLog.bootOk()
            true
        } catch (t: Throwable) {
            CrashLog.failure("home build failed (safeMode=${prefs.safeMode})", t)
            if (prefs.safeMode) {
                showFatalFallback(t)
            } else {
                prefs.safeMode = true
                CrashLog.note("retrying the boot with the minimal card set")
                recreate()
            }
            false
        }
    }

    /**
     * Last resort: a readable screen instead of a crash loop. The stack trace is on it because on these
     * units there is usually no logcat at hand, and tapping anywhere reboots into safe mode — which is
     * how you get a home screen back when a single card is the thing that dies.
     */
    private fun showFatalFallback(t: Throwable) {
        try {
            val box = Views.column(this, paddingDp = 16)
            val head = Views.multiline(this, getString(R.string.launcher_will_not_open), 15f, 4)
            head.setTextColor(Palette.colors.onSurface)
            val hint = Views.multiline(this, getString(R.string.safe_mode_retry), 12f, 3)
            hint.setTextColor(Palette.colors.accent)
            val trace = Views.multiline(this, Log.getStackTraceString(t), 10f, 500)
            trace.setTextColor(Palette.colors.onSurfaceMuted)
            box.addView(head)
            box.addView(hint)
            box.addView(Views.separator(this))
            box.addView(trace)
            val scroll = android.widget.ScrollView(this)
            scroll.addView(box)
            scroll.setOnClickListener {
                prefs.safeMode = true
                CrashLog.note("safe mode forced from the fallback screen")
                recreate()
            }
            setContentView(scroll)
        } catch (_: Throwable) {
            // Nothing left to fall back to; the trace is already in filesDir/crash.log.
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.getStringExtra(ActionRouter.ACTIVITY_EXTRA_ACTION)
            ?: intent?.dataString?.takeIf { it.startsWith("arenacar://") }?.let {
                // arenacar://card/music  ==  card:music
                it.removePrefix("arenacar://").replace('/', ':')
            }
            ?: return
        when {
            action.startsWith("card:") -> CenterCardCoordinator.force(action.removePrefix("card:"))
            action == "allapps" -> toggleAllApps(true)
            action == "home" -> toggleAllApps(false)
            else -> ActionRouter.route(this, action)
        }
    }

    override fun onStart() {
        super.onStart()
        // The safe-mode fallback screen has no root view; touching `root` while it is showing is an
        // UninitializedPropertyAccessException, which would replace a readable error with a crash loop.
        if (!uiReady) return
        TimeTicker.register(driveTick, root)
        leftStrip?.bindAll()
        rightStrip?.bindAll()
    }

    override fun onResume() {
        super.onResume()
        if (!uiReady) return
        applyWindowFlags()
        refreshWallpaper()
        dock?.rebuild()
        DayNightController.tick()
        CenterCardCoordinator.evaluate()
        MediaHub.fetchSessions(this, force = true)
        // The call strip has to be alive *before* the phone rings: a started service cannot be launched
        // out of nowhere when the screen is off, and the ring intent would be missed. onResume is the one
        // moment we are guaranteed to be foreground, so the service is (re)started here and simply
        // idles otherwise.
        if (prefs.callBanner && !prefs.safeMode) CallOverlayService.ensureRunning(this)
    }

    override fun onPause() {
        super.onPause()
        if (allApps?.isOpen() == true) allApps?.close()
    }

    override fun onStop() {
        TimeTicker.unregister(driveTick)
        leftStrip?.unbindAll()
        rightStrip?.unbindAll()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        prefs.removeChangeListener(prefsListener)
        Palette.removeListener(paletteListener)
        AppRepository.removeChangeListener(appsListener)
        if (ActionRouter.ui === this) ActionRouter.ui = null
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            allApps?.isOpen() == true -> toggleAllApps(false)
            driveLock?.visibility == View.VISIBLE -> Unit // locked: no exit while moving
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ construction

    private fun buildUi() {
        val ctx: Context = this
        root = GestureLayout(ctx).apply {
            onGesture = { g -> onGesture(g) }
        }

        val wp = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        wallpaper = wp
        root.addView(wp, matchParent())
        val sc = View(ctx)
        scrim = sc
        root.addView(sc, matchParent())

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, statusBarInset(), 0, 0)
        }
        val bar = TopBar(ctx).apply { callback = topCallback }
        topBar = bar
        column.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val wide = resources.displayMetrics.widthPixels / resources.displayMetrics.density > 640f

        if (wide && prefs.leftCards.isNotEmpty()) {
            val l = MiniCardStrip(ctx).apply {
                onRequestCard = { CenterCardCoordinator.force(it) }
                onOpenSettings = { ActionRouter.openSettingsActivity(ctx) }
                setCards(ctx, prefs.leftCards)
            }
            leftStrip = l
            mid.addView(l, LinearLayout.LayoutParams(dp(168f), LinearLayout.LayoutParams.MATCH_PARENT))
        }

        val pager = ViewPager2(ctx)
        val adapter = CardPagerAdapter(ctx, full = true).apply {
            callback = this@HomeActivity
            ids = prefs.centerCards
        }
        centerAdapter = adapter
        pager.adapter = adapter
        adapter.applyNoRecycle(pager)
        center = pager
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val id = adapter.pageId(position) ?: return
                if (id != lastCenterId) {
                    lastCenterId = id
                    CenterCardCoordinator.onUserSelected(id)
                }
            }
        })
        mid.addView(
            pager,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(8f)
                marginEnd = dp(8f)
            }
        )

        if (wide && prefs.rightCards.isNotEmpty()) {
            val r = MiniCardStrip(ctx).apply {
                onRequestCard = { CenterCardCoordinator.force(it) }
                onOpenSettings = { ActionRouter.openSettingsActivity(ctx) }
                setCards(ctx, prefs.rightCards)
            }
            rightStrip = r
            mid.addView(r, LinearLayout.LayoutParams(dp(168f), LinearLayout.LayoutParams.MATCH_PARENT))
        }
        column.addView(mid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply {
            topMargin = dp(6f)
            bottomMargin = dp(6f)
        })

        val d = DockView(ctx).apply { callback = dockCallback }
        dock = d
        column.addView(d, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(column, matchParent())

        val apps = AllAppsView(ctx).apply {
            callback = appsCallback
            visibility = View.GONE
        }
        allApps = apps
        root.addView(apps, matchParent())

        driveLock = buildDriveLock(ctx)

        setContentView(root)
        CenterCardCoordinator.install(ctx)
        CenterCardCoordinator.setConfigured(prefs.centerCards)
        applyPalette()
        center?.setCurrentItem(CenterCardCoordinator.centerIndex(), false)
    }

    private fun buildDriveLock(ctx: Context): View {
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            isClickable = true
            background = Palette.card(ctx, radiusDp = 26f, alphaPercent = 96, clickable = false)
            val p = dp(22f)
            setPadding(p, p, p, p)
        }
        panel.addView(
            Views.text(ctx, ctx.getString(R.string.lock_title), 22f, Palette.colors.onSurface, bold = true)
                .apply { gravity = Gravity.CENTER }, wrapWrap()
        )
        panel.addView(
            Views.text(ctx, ctx.getString(R.string.lock_body), 13f, Palette.colors.onSurfaceMuted)
                .apply {
                    gravity = Gravity.CENTER
                    maxLines = 4
                },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) })
        panel.addView(
            Views.text(
                ctx, ctx.getString(R.string.lock_i_am_parked), 14f, Palette.colors.onAccent, bold = true
            ).apply {
                gravity = Gravity.CENTER
                background = Palette.fill(ctx, Palette.colors.accent, 14f)
                val px = dp(16f)
                val py = dp(9f)
                setPadding(px, py, px, py)
                isClickable = true
                setOnClickListener {
                    lockAck = true
                    lastLocked = false
                    panel.visibility = View.GONE
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16f)
                gravity = Gravity.CENTER
            })
        root.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ).apply {
            marginStart = dp(180f)
            marginEnd = dp(180f)
        })
        return panel
    }

    // ------------------------------------------------------------------ palette & window

    private fun applyPalette() {
        val c = Palette.colors
        window.decorView.setBackgroundColor(c.background)
        if (uiReady) root.setBackgroundColor(c.background)
        scrim?.setBackgroundColor(Format.withAlpha(Color.BLACK, prefs.wallpaperDim))
        topBar?.refresh()
        allApps?.repaint()
        dock?.rebuild()
    }

    private fun applyWindowFlags() {
        val immersive = prefs.immersive && !prefs.showStatusBarSpace
        try {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (immersive) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            } else {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        } catch (_: Throwable) {
        }
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode = if (immersive) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
                window.attributes = lp
            } catch (_: Throwable) {
            }
        }
    }

    private fun statusBarInset(): Int = if (prefs.showStatusBarSpace) dp(24f) else dp(4f)

    private fun refreshWallpaper() {
        val uri = prefs.wallpaperUri
        val wp = wallpaper ?: return
        if (prefs.safeMode) {
            // A 12 MP wallpaper decode is exactly the kind of allocation that kills a 2.72 GB unit during
            // boot, and it is the one piece of decoration safe mode exists to drop.
            wp.setImageDrawable(null)
            scrim?.setBackgroundColor(Format.withAlpha(Color.BLACK, 70))
            return
        }
        if (uri.isNullOrBlank()) {
            wp.setImageDrawable(null)
            scrim?.setBackgroundColor(Format.withAlpha(Color.BLACK, prefs.wallpaperDim))
            return
        }
        scope.launch {
            val bmp: Bitmap? = try {
                applicationContext.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(
                        input, null,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                    )
                }
            } catch (t: Throwable) {
                Log.d(TAG, "wallpaper: ${t.message}")
                null
            }
            if (isFinishing || isDestroyed) return@launch
            wp.setImageBitmap(bmp)
        }
    }

    private fun onPaletteChanged() {
        applyPalette()
        centerAdapter?.repaint()
        leftStrip?.repaint()
        rightStrip?.repaint()
        topBar?.refresh()
    }

    private fun onPrefsChanged() {
        val centerIds = prefs.centerCards
        if (centerIds != centerAdapter?.ids) {
            centerAdapter?.reload(centerIds)
            CenterCardCoordinator.setConfigured(centerIds)
            center?.setCurrentItem(CenterCardCoordinator.centerIndex(), false)
        }
        leftStrip?.setCards(this, prefs.leftCards)
        rightStrip?.setCards(this, prefs.rightCards)
        dock?.rebuild()
        applyWindowFlags()
        applyPalette()
    }

    // ------------------------------------------------------------------ view callbacks

    private val topCallback = object : TopBar.Callback {
        override fun onTheme() {
            DayNightController.toggleManual(this@HomeActivity)
        }

        override fun onMute() {
            toggleMute()
        }

        override fun onScreenOff() {
            ScreenOffHelper.turnOff(applicationContext)
        }

        override fun onSettings() {
            ActionRouter.openSettingsActivity(this@HomeActivity)
        }

        override fun onClockTap() {
            CenterCardCoordinator.force(Cards.CLOCK)
        }
    }

    private val dockCallback = object : DockView.Callback {
        override fun onLaunch(entry: AppRepository.AppEntry) {
            AppRepository.launch(this@HomeActivity, entry)
        }

        override fun onLongPress(entry: AppRepository.AppEntry) {
            val list = ArrayList(prefs.dockApps)
            val added = !list.remove(entry.token)
            if (added) list.add(entry.token)
            prefs.dockApps = list
            dock?.rebuild()
            Views.toast(this@HomeActivity, if (added) R.string.dock_added else R.string.dock_removed)
        }

        override fun onEmptySlot(index: Int) {
            toggleAllApps(true)
            Views.toast(this@HomeActivity, R.string.dock_pick_hint)
        }

        override fun onAllApps() = toggleAllApps(true)
    }

    private val appsCallback = object : AllAppsView.Callback {
        override fun onLaunch(entry: AppRepository.AppEntry) {
            toggleAllApps(false)
            AppRepository.launch(this@HomeActivity, entry)
        }

        override fun onLongPress(entry: AppRepository.AppEntry) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", entry.packageName, null))
                )
            }
        }

        override fun onClose() = toggleAllApps(false)
    }

    private fun toggleMute() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (cur > 0) {
                volumeBeforeMute = cur
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                topBar?.setMuted(true)
            } else {
                val restore = if (volumeBeforeMute > 0) volumeBeforeMute else (max / 2)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
                topBar?.setMuted(false)
            }
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ behaviour hooks

    private fun onGesture(g: GestureLayout.Gesture): Boolean {
        val spec = root.actionFor(g)
        if (spec.isBlank()) return false
        if (spec == "recents") {
            openRecentApps()
            return true
        }
        if (spec == "allapps") {
            toggleAllApps(allApps?.isOpen() != true)
            return true
        }
        return ActionRouter.route(this, spec)
    }

    override fun showCenterCard(id: String) = CenterCardCoordinator.force(id)

    override fun swipeCenterCard(forward: Boolean) {
        val pager = center ?: return
        val n = centerAdapter?.itemCount ?: 0
        if (n == 0) return
        pager.setCurrentItem((pager.currentItem + if (forward) 1 else -1).coerceIn(0, n - 1), true)
    }

    override fun toggleAllApps(open: Boolean) {
        val apps = allApps ?: return
        if (open) apps.open(null, getString(R.string.title_all_apps)) else apps.close()
    }

    override fun setBrightnessBoost(on: Boolean) {
        brightnessBoost = on
        try {
            val lp = window.attributes
            lp.screenBrightness = if (on) 1.0f else -1f
            window.attributes = lp
        } catch (_: Throwable) {
        }
    }

    override fun openRecentApps() {
        val recent = recentPackages()
        if (recent.isEmpty()) {
            toggleAllApps(true)
            return
        }
        allApps?.open(recent, getString(R.string.allapps_recent))
    }

    /** Recent launchables, from the same usage-stats feed the foreground watcher polls. */
    private fun recentPackages(): List<AppRepository.AppEntry> {
        return try {
            val mgr = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
                ?: return emptyList()
            val now = System.currentTimeMillis()
            val events = mgr.queryEvents(now - 6L * 3600_000L, now)
            val seen = LinkedHashMap<String, Long>()
            val e = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                // MOVE_TO_FOREGROUND; the constant is deprecated but the value is stable.
                if (e.eventType == 1) seen[e.packageName] = e.timeStamp
            }
            seen.entries.sortedByDescending { it.value }
                .mapNotNull { AppRepository.entryFor(it.key) }
                .distinctBy { it.packageName }
                .take(24)
        } catch (t: Throwable) {
            Log.d(TAG, "recents: ${t.message}")
            emptyList()
        }
    }

    override fun toggleDriveLockAck() {
        lockAck = !lockAck
        if (!lockAck) evaluateDriveLock()
    }

    override fun pickWidget() {
        val intent = WidgetSlot.beginPick(this) ?: return
        runCatching { startActivityForResult(intent, REQ_WIDGET) }
    }

    override fun removeWidget() {
        WidgetSlot.clear(this)
        centerAdapter?.reload(prefs.centerCards)
    }

    @Deprecated("startActivityForResult is fine here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_WIDGET) return
        if (WidgetSlot.handlePickResult(this, resultCode, data)) {
            centerAdapter?.reload(prefs.centerCards)
            Views.toast(this, R.string.widget_added)
        }
    }

    override fun onRequestCenter(id: String) {
        CenterCardCoordinator.force(id)
    }

    override fun onCardSelected(id: String) {
        val idx = centerAdapter?.indexOf(id) ?: -1
        if (idx >= 0) center?.setCurrentItem(idx, true)
    }

    override fun openSettings() {
        ActionRouter.openSettingsActivity(this)
    }

    private fun onCenterChanged(id: String) {
        val idx = centerAdapter?.indexOf(id) ?: -1
        val pager = center ?: return
        if (idx < 0) return
        lastCenterId = id
        if (pager.currentItem != idx) pager.setCurrentItem(idx, true)
    }

    /**
     * Drive lock: above the threshold the screen accepts only the few controls that are safe to use
     * while moving. It is a distraction guard for the launcher, not a security feature, and the copy
     * says exactly that — a driver must never think the car has been disabled.
     */
    private fun evaluateDriveLock() {
        val speed = runCatching { VehicleHub.state.value.speedKmh ?: 0f }.getOrNull() ?: 0f
        val shouldLock = prefs.lockWhileDriving && !lockAck && speed >= prefs.driveLockSpeedKmh
        if (shouldLock == lastLocked) return
        lastLocked = shouldLock
        driveLock?.visibility = if (shouldLock) View.VISIBLE else View.GONE
    }

    private val driveTick = object : TimeTicker.Tick {
        override fun onTick(nowMillis: Long) {
            evaluateDriveLock()
            CenterCardCoordinator.evaluate()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun matchParent() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
    )

    private fun wrapWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "Home"
        private const val REQ_WIDGET = 4417
    }
}
