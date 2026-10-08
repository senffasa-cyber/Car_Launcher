package com.arena.carlauncher.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.text.Collator
import java.util.Locale

/**
 * The installed-app list.
 *
 * Two details matter more than the query itself on a head unit:
 *  * Icons are rasterised once into fixed-size bitmaps. Reusing the same `Drawable` instance in a
 *    RecyclerView *and* the dock at once corrupts bounds (and adaptive icons are worse), so each
 *    size gets its own cached bitmap.
 *  * `PACKAGE_ADDED/REMOVED` are received with `DATA_SPECIFIC`-free filters so the drawer updates
 *    while it is on screen instead of on the next reboot.
 */
object AppRepository {

    private const val TAG = "AppRepository"

    data class AppEntry(
        val packageName: String,
        val activityName: String,
        val label: String,
        val isSystem: Boolean
    ) {
        val component: ComponentName get() = ComponentName(packageName, activityName)
        val token: String get() = "$packageName|$activityName"
        val sortKey: String get() = label.lowercase(Locale.ROOT)
    }

    private var app: Context? = null
    private var prefs: LauncherPrefs? = null
    private val main = Handler(Looper.getMainLooper())

    private var entries: List<AppEntry> = emptyList()
    private var raw: List<AppEntry> = emptyList()
    private var byToken: Map<String, AppEntry> = emptyMap()
    private var byPackage: Map<String, AppEntry> = emptyMap()
    private var loaded = false
    private var loading = false
    private val listeners = ArrayList<() -> Unit>()

    private val iconCache = object : LruCache<String, Bitmap>(220) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }

    private val collator: Collator by lazy {
        try {
            Collator.getInstance(Locale("fa", "IR"))
        } catch (_: Throwable) {
            Collator.getInstance()
        }
    }

    fun install(ctx: Context) {
        if (app == null) {
            app = ctx.applicationContext
            prefs = LauncherPrefs.get(ctx)
            registerPackageReceiver(ctx.applicationContext)
        }
    }

    fun addChangeListener(l: () -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun removeChangeListener(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }

    fun all(): List<AppEntry> = entries

    /** Everything launchable, including the user's hidden apps — used by the hidden-apps editor. */
    fun allIncludingHidden(): List<AppEntry> = raw
    fun entry(token: String): AppEntry? = byToken[token]
    fun entryFor(pkg: String): AppEntry? = byPackage[pkg]

    /** Reload in the background; safe to call from `onResume`. */
    fun load(force: Boolean = false) {
        val ctx = app ?: return
        if (loading) return
        if (loaded && !force) return
        loading = true
        Thread {
            val pm = ctx.packageManager
            val out = ArrayList<AppEntry>(64)
            try {
                @Suppress("DEPRECATION")
                val resolved: List<ResolveInfo> = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    PackageManager.MATCH_DEFAULT_ONLY
                )
                val seen = HashSet<String>()
                for (ri in resolved) {
                    val ai = ri.activityInfo ?: continue
                    val pkg = ai.packageName
                    val cls = ai.name ?: continue
                    val key = "$pkg|$cls"
                    if (!seen.add(key)) continue
                    val label = try {
                        ri.loadLabel(pm).toString()
                    } catch (_: Throwable) {
                        pkg
                    }
                    out.add(
                        AppEntry(
                            packageName = pkg,
                            activityName = cls,
                            label = label.ifBlank { pkg },
                            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "query failed: ${t.message}")
            }
            val hidden = prefs?.hiddenApps?.toSet() ?: emptySet()
            raw = out.sortedWith { a, b -> collator.compare(a.label, b.label) }
            val sorted = out.filter { !hidden.contains(it.packageName) }
                .sortedWith { a, b ->
                    val sysA = if (a.isSystem) 1 else 0
                    val sysB = if (b.isSystem) 1 else 0
                    if (sysA != sysB) sysA - sysB else collator.compare(a.label, b.label)
                }
            entries = sorted
            byToken = sorted.associateBy { it.token }
            byPackage = sorted.associateBy { it.packageName }
            loaded = true
            loading = false
            main.post { listeners.toList().forEach { try { it() } catch (_: Throwable) {} } }
        }.apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }.start()
    }

    fun search(query: String): List<AppEntry> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return entries
        return entries.filter {
            it.label.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q)
        }
    }

    fun favorites(tokens: List<String>): List<AppEntry> =
        tokens.mapNotNull { byToken[it] ?: byPackage[it] }

    /** Fixed-size raster icon; adaptive icons get the background layer composited. */
    fun icon(ctx: Context, entry: AppEntry?, sizePx: Int): Bitmap? {
        entry ?: return null
        val key = "${entry.token}@$sizePx"
        iconCache.get(key)?.let { return if (it.isRecycled) null else it }
        val bmp = try {
            val d = drawableFor(ctx, entry)
            if (d == null) null else rasterize(d, sizePx)
        } catch (t: Throwable) {
            Log.d(TAG, "icon failed for ${entry.packageName}: ${t.message}")
            null
        }
        if (bmp != null) iconCache.put(key, bmp)
        return bmp
    }

    private fun drawableFor(ctx: Context, entry: AppEntry): Drawable? {
        val pm = ctx.packageManager
        return try {
            pm.getActivityIcon(ComponentName(entry.packageName, entry.activityName))
        } catch (_: Throwable) {
            try {
                pm.getApplicationIcon(entry.packageName)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun rasterize(d: Drawable, sizePx: Int): Bitmap? {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Paint with no colour: we only need the bitmap to exist for filter flags.
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        d.setBounds(Rect(0, 0, sizePx, sizePx))
        d.draw(canvas)
        return bmp
    }

    fun launch(ctx: Context, entry: AppEntry?, adjacent: Boolean = false, newTask: Boolean = true): Boolean {
        entry ?: return false
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(entry.packageName, entry.activityName)
            .apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        if (newTask) Intent.FLAG_ACTIVITY_NEW_TASK else 0
                )
                if (adjacent) addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            }
        return try {
            ctx.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "launch ${entry.packageName} failed: ${t.message}")
            try {
                ctx.startActivity(
                    ctx.packageManager.getLaunchIntentForPackage(entry.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun launchPackage(ctx: Context, pkg: String, adjacent: Boolean = false): Boolean {
        val entry = entryFor(pkg)
        if (entry != null) return launch(ctx, entry, adjacent)
        return try {
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (adjacent) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            ctx.startActivity(i)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun appLabel(ctx: Context, pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0) != null
    } catch (_: Throwable) {
        false
    }

    private fun registerPackageReceiver(ctx: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                // Throttle: a Play-Store-free ROM can fire dozens of these at boot.
                main.removeCallbacks(reloadRunnable)
                main.postDelayed(reloadRunnable, 900L)
            }
        }
        try {
            ctx.registerReceiver(receiver, filter)
        } catch (t: Throwable) {
            Log.d(TAG, "package receiver unavailable: ${t.message}")
        }
    }

    private val reloadRunnable = Runnable { load(force = true) }

    fun debugDump(): String = "apps=${entries.size} loaded=$loaded icons=${iconCache.size()}"
}

