package com.arena.carlauncher.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference

/**
 * Process-wide bitmap cache.
 *
 * Memory is the scarce resource on a 2.7 GB unit (the system kills the launcher otherwise), so the
 * cache is sized from the heap budget instead of a fixed number, and every bitmap that enters the
 * UI is pre-downsampled to the size actually drawn.
 */
class ImageCache private constructor(maxBytes: Int) {

    private val memory = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount.coerceAtLeast(1)
    }
    private val waiters = HashMap<String, ArrayList<WeakReference<(Bitmap) -> Unit>>>()
    private val main = Handler(Looper.getMainLooper())

    fun get(key: String): Bitmap? = memory.get(key)

    fun put(key: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        memory.put(key, bitmap)
        val pending = synchronized(waiters) { waiters.remove(key) }
        pending?.forEach { ref -> ref.get()?.invoke(bitmap) }
    }

    /** Deliver when ready (same tick if it is already cached). */
    fun request(key: String, load: () -> Bitmap?, callback: (Bitmap) -> Unit) {
        get(key)?.let { main.post { callback(it) }; return }
        synchronized(waiters) {
            waiters.getOrPut(key) { ArrayList() }.add(WeakReference(callback))
        }
        Thread {
            val bmp = try {
                load()
            } catch (_: Throwable) {
                null
            }
            if (bmp != null && !bmp.isRecycled) put(key, bmp) else fail(key)
        }.apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    private fun fail(key: String) {
        synchronized(waiters) { waiters.remove(key) }
    }

    fun trim() = memory.trimMemory()
    fun evictAll() = memory.evictAll()

    fun snapshotCount() = memory.size()

    companion object {
        @Volatile
        private var instance: ImageCache? = null

        fun install(ctx: Context) {
            if (instance == null) {
                val budget = (Runtime.getRuntime().maxMemory() / 5).toInt()
                instance = ImageCache(budget.coerceAtMost(28 * 1024 * 1024))
            }
        }

        fun get(ctx: Context): ImageCache =
            instance ?: ImageCache((Runtime.getRuntime().maxMemory() / 6).toInt()).also { instance = it }

        /** Downsampled decode — the single most important OOM guard in the whole app. */
        fun decodeSampled(path: String, reqW: Int, reqH: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
            }
            return BitmapFactory.decodeFile(path, opts)
        }

        fun decodeBytes(data: ByteArray, reqW: Int, reqH: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
            }
            return BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        }

        fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
            var sample = 1
            while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) sample *= 2
            return sample
        }

        fun rounded(src: Bitmap, radius: Float): Bitmap {
            if (radius <= 0f) return src
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val rect = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(src, null, rect, paint)
            return out
        }

        /**
         * Soft blur used for the artwork backdrop. RenderScript is deprecated on newer APIs but
         * still the fastest option on the Android 10 firmware these units run; fall back to a
         * downscale-upscale blur which is good enough and available everywhere.
         */
        fun blur(ctx: Context, src: Bitmap, radius: Float): Bitmap {
            if (src.width < 4 || src.height < 4) return src
            return try {
                blurRenderScript(ctx, src, radius)
            } catch (_: Throwable) {
                blurScale(src)
            }
        }

        private fun blurRenderScript(ctx: Context, src: Bitmap, radius: Float): Bitmap {
            val rs = RenderScript.create(ctx)
            try {
                val scaled = Bitmap.createScaledBitmap(src, 96, 96, true)
                val blurBmp = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
                val inAlloc = Allocation.createFromBitmap(rs, scaled)
                val outAlloc = Allocation.createFromBitmap(rs, blurBmp)
                val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
                script.setRadius(radius.coerceIn(1f, 25f))
                script.setInput(inAlloc)
                script.forEach(outAlloc)
                outAlloc.copyTo(blurBmp)
                inAlloc.destroy(); outAlloc.destroy(); script.destroy()
                return blurBmp
            } finally {
                rs.destroy()
            }
        }

        private fun blurScale(src: Bitmap): Bitmap {
            val small = Bitmap.createScaledBitmap(src, 24, 24, true)
            return Bitmap.createScaledBitmap(small, src.width, src.height, true)
        }

        fun fromDrawable(d: Drawable?, size: Int): Bitmap? =
            d?.let {
                try {
                    it.toBitmap(size, size, Bitmap.Config.ARGB_8888)
                } catch (_: Throwable) {
                    null
                }
            }

        fun savePng(ctx: Context, bitmap: Bitmap, name: String): File? = try {
            val f = File(ctx.cacheDir, name)
            FileOutputStream(f).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            f
        } catch (_: Throwable) {
            null
        }

        /** First frame of a video file — used by the dashcam card's thumbnails. */
        fun videoThumbnail(path: String, size: Int): Bitmap? = try {
            val r = MediaMetadataRetriever()
            r.setDataSource(path)
            val bmp = r.getFrameAtTime(1_000_000L)
            r.release()
            if (bmp == null) null else Bitmap.createScaledBitmap(bmp, size, (size * bmp.height / bmp.width), true)
        } catch (_: Throwable) {
            null
        }

        fun drawableOf(bitmap: Bitmap?, density: Int): Drawable? =
            bitmap?.let { BitmapDrawable(android.content.res.Resources.getSystem(), it).apply { setDensity(density) } }

        fun rotate(src: Bitmap, degrees: Float): Bitmap {
            if (degrees % 360f == 0f) return src
            val m = Matrix().apply { postRotate(degrees) }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }
    }
}
