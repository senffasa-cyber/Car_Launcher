package com.arena.carlauncher.map

import android.content.Context
import android.net.Uri
import android.util.Log
import com.arena.carlauncher.data.Place
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Key-free geocoding and routing.
 *
 *  * Search goes to Nominatim with `accept-language=fa,en`, which is what makes Persian queries
 *    like «برج میلاد» work without any Google API key (and Google's key-less days are over).
 *  * The route polyline comes from the public OSRM demo server — used only to *draw* the route on
 *    our tile map. Turn-by-turn guidance is always handed to the real navigation app, which does it
 *    far better than any launcher can.
 *
 * Both hosts are public services with fair-use limits. For a production fleet, point the
 * [Nominatim.BASE]/[Osrm.BASE] constants below at your own instances — deliberately without adding a
 * settings field for them: a typo in a free-text field turns search into a silent no-result, while a
 * changed constant is compiled, reviewed and tested like everything else.

 */
object PlaceSearch {

    private const val TAG = "PlaceSearch"

    object Nominatim {
        const val BASE = "https://nominatim.openstreetmap.org/search"
    }

    fun search(ctx: Context, query: String, nearLat: Double? = null, nearLon: Double? = null, limit: Int = 10): List<Place> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        // "35.6997,51.3903" style input is already a coordinate — no network round trip needed.
        parseCoords(q)?.let { return listOf(Place(q, it[0], it[1], q, "coords")) }

        val builder = StringBuilder(Nominatim.BASE)
            .append("?format=jsonv2&limit=").append(limit)
            .append("&accept-language=fa,en")
            .append("&addressdetails=0")
            .append("&q=").append(Uri.encode(q))
        if (nearLat != null && nearLon != null) {
            builder.append("&viewbox=").append(nearLon - 0.35).append(',')
                .append(nearLat + 0.25).append(',')
                .append(nearLon + 0.35).append(',')
                .append(nearLat - 0.25)
                .append("&bounded=0&prefer_param=1")
        }
        val body = get(ctx, builder.toString()) ?: return emptyList()
        return try {
            val arr = JSONArray(body)
            val out = ArrayList<Place>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val lat = o.optString("lat").toDoubleOrNull() ?: continue
                val lon = o.optString("lon").toDoubleOrNull() ?: continue
                val name = o.optString("name").ifBlank { o.optString("display_name").substringBefore(',') }
                out.add(
                    Place(
                        label = name.ifBlank { o.optString("display_name").substringBefore(',') },
                        lat = lat,
                        lon = lon,
                        address = o.optString("display_name"),
                        source = "nominatim"
                    )
                )
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "parse failed: ${t.message}")
            emptyList()
        }
    }

    /** Reverse geocode for "current position" — one call, cached by the caller. */
    fun reverse(ctx: Context, lat: Double, lon: Double): Place? {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon&accept-language=fa,en&zoom=17"
        val body = get(ctx, url) ?: return null
        return try {
            val o = JSONObject(body)
            val display = o.optString("display_name")
            Place(
                label = o.optString("name").ifBlank { display.substringBefore(',') }.ifBlank {
                    String.format(Locale.US, "%.5f, %.5f", lat, lon)
                },
                lat = lat,
                lon = lon,
                address = display,
                source = "reverse"
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseCoords(q: String): DoubleArray? {
        val parts = q.split(',', '،', ' ').filter { it.isNotBlank() }
        if (parts.size != 2) return null
        val a = parts[0].toDoubleOrNull() ?: return null
        val b = parts[1].toDoubleOrNull() ?: return null
        if (a !in -90.0..90.0 || b !in -180.0..180.0) return null
        return doubleArrayOf(a, b)
    }

    internal fun get(ctx: Context, url: String): String? = HttpJson.get(ctx, url, null)
}

object RouteProvider {

    private const val TAG = "RouteProvider"

    object Osrm {
        const val BASE = "https://router.project-osrm.org/route/v1/driving"
    }

    /** @return list of `lat,lon` pairs, or null when no route could be computed. */
    fun drivingRoute(ctx: Context, slat: Double, slon: Double, dlat: Double, dlon: Double): List<DoubleArray>? {
        val url = StringBuilder(Osrm.BASE)
            .append('/').append(slon).append(',').append(slat)
            .append(';').append(dlon).append(',').append(dlat)
            .append("?overview=smooth&geometries=geojson&steps=false&alternatives=false")
            .toString()
        val body = HttpJson.get(ctx, url, null) ?: return null
        return try {
            val root = JSONObject(body)
            if (root.optString("code") != "Ok") return null
            val route = root.optJSONArray("routes")?.optJSONObject(0) ?: return null
            val coords = route.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
            val out = ArrayList<DoubleArray>(coords.length())
            for (i in 0 until coords.length()) {
                val pair = coords.optJSONArray(i) ?: continue
                val lon = pair.optDouble(0, Double.NaN)
                val lat = pair.optDouble(1, Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                out.add(doubleArrayOf(lat, lon))
            }
            // Decimate long routes: a 720 px card cannot show more than ~400 vertices anyway.
            if (out.size > 400) {
                val step = out.size / 400 + 1
                ArrayList<DoubleArray>(400).apply {
                    var i = 0
                    while (i < out.size) {
                        add(out[i])
                        i += step
                    }
                    add(out.last())
                }
            } else out
        } catch (t: Throwable) {
            Log.w(TAG, "route parse failed: ${t.message}")
            null
        }
    }

    /** Distance/duration from the same call, used by the nav card ("12 km · 24 min"). */
    fun summary(ctx: Context, slat: Double, slon: Double, dlat: Double, dlon: Double): Pair<Float, Int>? {
        val url = StringBuilder(Osrm.BASE)
            .append('/').append(slon).append(',').append(slat)
            .append(';').append(dlon).append(',').append(dlat)
            .append("?overview=false").toString()
        val body = HttpJson.get(ctx, url, null) ?: return null
        return try {
            val r = JSONObject(body).optJSONArray("routes")?.optJSONObject(0) ?: return null
            val dist = r.optDouble("distance", 0.0)
            val dur = r.optDouble("duration", 0.0)
            (dist / 1000.0).toFloat() to (dur / 60.0).toInt()
        } catch (_: Throwable) {
            null
        }
    }
}

/** Minimal GET helper: one HttpURLConnection, gzip-aware, never throws. */
internal object HttpJson {

    private const val TAG = "HttpJson"
    val UA: String get() = MapEngine.USER_AGENT

    fun get(ctx: Context, url: String, headers: Map<String, String>?): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 8000
                instanceFollowRedirects = true
                useCaches = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Encoding", "gzip")
                headers?.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.d(TAG, "$code for $url")
                return null
            }
            val stream = conn.inputStream
            val gz = "gzip".equals(conn.contentEncoding, ignoreCase = true)
            val bytes = (if (gz) java.util.zip.GZIPInputStream(stream) else stream).use { it.readBytes() }
            String(bytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.d(TAG, "get failed (${t.javaClass.simpleName}) for $url")
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }
}
