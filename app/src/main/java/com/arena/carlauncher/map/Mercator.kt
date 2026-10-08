package com.arena.carlauncher.map

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Web-Mercator (XYZ / "slippy map") projection.
 *
 * This is the projection every free tile server speaks — OSM, CARTO, Esri, Neshan's own raster
 * cache — so the launcher can render a real map without any SDK or API key.
 */
object Mercator {

    const val TILE = 256.0

    fun worldSizePx(zoom: Int): Double = TILE * (1 shl zoom.coerceIn(0, 22))

    fun latRad(lat: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        return clamped * PI / 180.0
    }

    /** @return pixel x in the whole-world image at [zoom]. */
    fun projectX(lon: Double, zoom: Int): Double {
        val w = worldSizePx(zoom)
        var x = (lon + 180.0) / 360.0 * w
        // keep it inside [0, w) so tile indices never go out of range
        x %= w
        if (x < 0) x += w
        return x
    }

    fun projectY(lat: Double, zoom: Int): Double {
        val w = worldSizePx(zoom)
        val s = sinSafe(latRad(lat))
        return (1.0 - ln(s + 1.0 / cosSafe(latRad(lat))) / PI) / 2.0 * w
    }

    fun tileX(lon: Double, zoom: Int): Int = (projectX(lon, zoom) / TILE).toInt()
    fun tileY(lat: Double, zoom: Int): Int = (projectY(lat, zoom) / TILE).toInt()

    fun tileCount(zoom: Int) = 1 shl zoom.coerceIn(0, 22)

    fun tileToWorld(tx: Int, ty: Int, zoom: Int): DoubleArray =
        doubleArrayOf(tx * TILE, ty * TILE)

    /** Inverse: longitude for a world pixel x. */
    fun unprojectX(x: Double, zoom: Int): Double {
        val w = worldSizePx(zoom)
        return x / w * 360.0 - 180.0
    }

    /** Inverse: latitude for a world pixel y. */
    fun unprojectY(y: Double, zoom: Int): Double {
        val w = worldSizePx(zoom)
        val n = PI - 2.0 * PI * (y / w)
        return n * 180.0 / PI
    }

    /** Ground resolution in metres per pixel at a latitude — used for the scale bar. */
    fun metersPerPixel(lat: Double, zoom: Int): Double =
        156543.03392 * cosSafe(latRad(lat)) / (1 shl zoom.coerceIn(0, 22))

    /** Zoom level that keeps ~[tilesWide] tiles of context across the card. */
    fun zoomFor(lat: Double, zoom: Int, pxPerTile: Double, targetMetersAcross: Double): Double {
        var z = zoom.toDouble()
        var current = metersPerPixel(lat, z.toInt()) * pxPerTile * 3
        var guard = 0
        while (current < targetMetersAcross && z > 1 && guard++ < 24) {
            z -= 1
            current = metersPerPixel(lat, z.toInt()) * pxPerTile * 3
        }
        while (current > targetMetersAcross && z < 19 && guard++ < 48) {
            z += 1
            current = metersPerPixel(lat, z.toInt()) * pxPerTile * 3
        }
        return z
    }

    /** Great-circle distance in km (haversine) — used to grey out far places. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sinSafe(dLat / 2) * sinSafe(dLat / 2) +
            cosSafe(lat1 * PI / 180.0) * cosSafe(lat2 * PI / 180.0) *
            sinSafe(dLon / 2) * sinSafe(dLon / 2)
        return 2 * r * atanSafe(atanSafe(a / max(1e-12, 1 - a)))
    }

    fun clampZoom(z: Int) = min(22, max(1, z))

    // java.lang.Math has these, but keeping one place avoids import churn.
    private fun sinSafe(v: Double) = Math.sin(v)
    private fun cosSafe(v: Double) = Math.cos(v)
    private fun atanSafe(v: Double) = Math.atan(v)
}
