package com.arena.carlauncher.map

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.util.ImageCache
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * Tile fetcher + two-level cache (RAM → `cacheDir/maptiles` → network).
 *
 * Raster tiles are the one way to get a live map on these units: no Google Play Services, no API
 * key, no NDK map SDK to cross-compile for the Unisoc ABI, and — decisively for Iran — it works
 * with tile hosts that are not blocked there. Cached tiles also mean the map keeps drawing in a
 * tunnel or on the parking lot wifi, which is exactly when a car launcher needs it.
 */
object MapEngine {

    private const val TAG = "MapEngine"
    private const val MAX_MEM_TILES = 96
    private const val DISK_BUDGET = 48L * 1024 * 1024

    /**
     * Sent on every tile request. The public tile policies of OSM/CARTO require a *reachable* contact,
     * and both rate-limit anonymous agents — so before publishing a build, put your own address here
     * (this placeholder is deliberate: leaving it in means somebody will get blocked and notice).
     */
    const val USER_AGENT = "ArenaCarLauncher/1.0 (in-car map widget; contact: you@example.com)"

    private var app: Context? = null
    private var prefs: LauncherPrefs? = null
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newScheduledThreadPool(3) { r ->
        Thread(r, "map-tile-${threadCount.incrementAndGet()}").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 2 }
    }
    private val threadCount = AtomicInteger(0)
    private val pending = Collections.synchronizedSet(HashSet<String>())
    private val failed = HashMap<String, Long>()

    private val mem = object : LruCache<String, Bitmap>(MAX_MEM_TILES) {
        override fun sizeOf(key: String, value: Bitmap) = 1
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            // Tiles are owned by the cache: recycling keeps the heap flat on a 2.7 GB unit.
            if (evicted && !oldValue.isRecycled) oldValue.recycle()
        }
    }

    private var disk: TileStore? = null
    private var generation = 0
    private var requests = AtomicInteger(0)
    private var hits = AtomicInteger(0)
    private var misses = AtomicInteger(0)

    var source: TileSource = TileSources.ALL[0]
        private set

    fun install(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        prefs = LauncherPrefs.get(ctx)
        disk = TileStore(File(ctx.cacheDir, "maptiles"), DISK_BUDGET)
        refreshSource()
    }

    fun refreshSource() {
        val p = prefs ?: return
        source = TileSources.byId(p.tileSource, p.tileCustomUrl, com.arena.carlauncher.theme.Palette.night)
    }

    fun newGeneration(): Int = generation++

    fun isOnline(ctx: Context? = app): Boolean {
        val c = ctx ?: return false
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return try {
            val n = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(n) ?: return true
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            true
        }
    }

    /** Memory-only fast path, called from `onDraw`. */
    fun peek(z: Int, x: Int, y: Int): Bitmap? = mem.get(key(z, x, y))

    /**
     * Ensures the tile is decoded and cached, then invokes [done] on the main thread.
     * [gen] lets the view drop answers for a view state that is already gone.
     */
    fun request(z: Int, x: Int, y: Int, gen: Int, done: ((Bitmap?) -> Unit)?) {
        val key = key(z, x, y)
        mem.get(key)?.let { b ->
            hits.incrementAndGet()
            if (done != null) main.post { done(b) }
            return
        }
        if (gen < generation - 2) return
        if (!pending.add(key)) return // someone else is already fetching it
        requests.incrementAndGet()
        misses.incrementAndGet()
        pool.execute {
            var bmp: Bitmap? = null
            try {
                bmp = loadFromDisk(key, z, x, y)
                if (bmp == null) bmp = fetch(z, x, y, key)
                if (bmp != null) mem.put(key, bmp)
            } catch (t: Throwable) {
                Log.d(TAG, "tile $key failed: ${t.message}")
            } finally {
                pending.remove(key)
            }
            if (bmp != null && done != null) main.post { done(bmp) }
        }
    }

    /** Prefetch ring around a point so panning never shows holes. */
    fun prefetch(lat: Double, lon: Double, zoom: Int, radius: Int, gen: Int) {
        val tx = Mercator.tileX(lon, zoom)
        val ty = Mercator.tileY(lat, zoom)
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > radius + 1) continue
                request(zoom, tx + dx, ty + dy, gen, null)
            }
        }
    }

    private fun loadFromDisk(key: String, z: Int, x: Int, y: Int): Bitmap? {
        val d = disk ?: return null
        val f = d.fileFor(key) ?: return null
        return try {
            val size = targetSize()
            ImageCache.decodeSampled(f.absolutePath, size, size)
        } catch (t: Throwable) {
            Log.d(TAG, "disk decode failed: ${t.message}")
            null
        }
    }

    private fun fetch(z: Int, x: Int, y: Int, key: String): Bitmap? {
        val ctx = app ?: return null
        val lastFail = failed[key] ?: 0L
        if (lastFail > 0 && System.currentTimeMillis() - lastFail < 5 * 60_000L) return null
        if (!isOnline(ctx)) return null

        val url = TileSources.urlFor(source, z, x, y, false)
        val conn = try {
            val c = URL(url).openConnection()
            if (c is HttpsURLConnection) c.apply {
                instanceFollowRedirects = true
                connectTimeout = 4000
                readTimeout = 6000
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "image/png,image/*;q=0.8,*/*;q=0.5")
            } else if (c is HttpURLConnection) c.apply {
                connectTimeout = 4000
                readTimeout = 6000
                setRequestProperty("User-Agent", USER_AGENT)
            } else null
        } catch (t: Throwable) {
            Log.d(TAG, "open failed: ${t.message}")
            null
        } ?: return null

        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                failed[key] = System.currentTimeMillis()
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (bytes.size < 100) return null
            disk?.write(key, bytes)
            val size = targetSize()
            ImageCache.decodeBytes(bytes, size, size)
        } catch (t: Throwable) {
            failed[key] = System.currentTimeMillis()
            null
        } finally {
            try {
                conn.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** Tiles are decoded at (at most) the on-screen size: 1208x720 units never need 512 px tiles. */
    private fun targetSize(): Int {
        val ctx = app ?: return 256
        val dm = ctx.resources.displayMetrics
        val tilesAcross = (dm.widthPixels / 256f) + 2f
        val cap = (dm.widthPixels / tilesAcross.coerceAtLeast(1f))
        return cap.toInt().coerceIn(192, 512)
    }

    private fun key(z: Int, x: Int, y: Int) = "${source.id}/$z/$x/$y"

    fun clearCaches() {
        mem.evictAll()
        disk?.clear()
        failed.clear()
    }

    fun diskBytes(): Long = disk?.bytes() ?: 0L

    fun debugDump(): String =
        "src=${source.id} mem=${mem.size()} pend=${pending.size} req=${requests.get()} hit=${hits.get()}/${misses.get()} disk=${disk?.bytes() ?: 0L}B"

    /** Called from `onTrimMemory`: drop half the bitmaps rather than emptying the cache, so a
     * low-memory warning does not turn every visible tile into a fresh network request. */
    fun trimMemory() {
        mem.trimToSize(mem.maxSize() / 2)
    }
}

/**
 * Flat file tile cache. Files are named after the cache key so a day of navigation stays warm
 * across reboots — head units power-cycle constantly and re-downloading tiles on every start is
 * both slow and rude to the tile server.
 */
class TileStore(private val root: File, private val budget: Long) {

    private var writes = 0

    init {
        if (!root.exists()) root.mkdirs()
    }

    private fun fileName(key: String): String =
        key.replace('/', '_').replace('@', 'a') + ".png"

    fun fileFor(key: String): File? {
        val f = File(root, fileName(key))
        return if (f.isFile && f.length() > 60) f else null
    }

    fun write(key: String, bytes: ByteArray) {
        try {
            val f = File(root, fileName(key))
            FileOutputStream(f).use { it.write(bytes) }
            if (++writes % 64 == 0) evictIfNeeded()
        } catch (_: Throwable) {
        }
    }

    private fun evictIfNeeded() {
        try {
            val files = root.listFiles()?.toList() ?: return
            var total = files.sumOf { it.length() }
            if (total <= budget) return
            val sorted = files.sortedBy { it.lastModified() }
            for (f in sorted) {
                if (total <= budget * 0.85) break
                total -= f.length()
                f.delete()
            }
        } catch (_: Throwable) {
        }
    }

    fun bytes(): Long = try {
        root.listFiles()?.sumOf { it.length() } ?: 0L
    } catch (_: Throwable) {
        0L
    }

    fun clear() {
        try {
            root.listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }
}
