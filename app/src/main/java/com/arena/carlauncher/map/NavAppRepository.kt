package com.arena.carlauncher.map

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.data.NavAppInfo
import com.arena.carlauncher.data.Place

/**
 * Discovers and drives whatever navigation app is actually installed.
 *
 * Discovery is intent-based (who answers `geo:`), not package-name based, so a ROM with a Chinese
 * map app or a modded Neshan build still shows up in the picker. Package names below are only used
 * to pick nicer deep links and to label the entries — the launcher never refuses to work because a
 * hard-coded id changed.
 *
 * Deep links use a template per app with `{slat} {slon} {lat} {lng} {label}` placeholders, editable
 * in Settings, because Neshan/Balad document their schemes in Persian and they do move.
 */
object NavAppRepository {

    private const val TAG = "NavAppRepository"

    /** Well-known ids, only used for nicer labels + the right deep-link template. */
    private val KNOWN = mapOf(
        "com.google.android.apps.maps" to Known("Google Maps", "google.navigation:q={lat},{lng}"),
        "org.rajman.neshan.traffic.tehran.navigator" to Known(
            "نشان | Neshan",
            "https://nshn.ir/maps?origin={slat},{slon}&destination={lat},{lng}&type=drive"
        ),
        "com.baladmaps" to Known("بلد | Balad", null),
        "com.waze" to Known("Waze", "waze://?ll={lat},{lng}&navigate=yes"),
        "net.osmand" to Known("OsmAnd", null),
        "net.osmand.plus" to Known("OsmAnd+", null),
        "app.organicmaps" to Known("Organic Maps", null),
        "com.mapswithme.maps.pro" to Known("MAPS.ME", null),
        "com.google.android.car.navigation" to Known("Android Auto Navigation", null),
        "com.here.app.maps" to Known("HERE Maps", null),
        "com.nhn.android.map" to Known("Naver Map", null),
        "com.yandex.mapcar" to Known("Yandex Maps", null)
    )

    private data class Known(val label: String, val routeTemplate: String?)

    private var app: Context? = null
    private var prefs: LauncherPrefs? = null
    private var cached: List<NavAppInfo> = emptyList()
    private var loaded = false

    fun install(ctx: Context) {
        if (app == null) app = ctx.applicationContext
        prefs = LauncherPrefs.get(ctx)
    }

    fun apps(force: Boolean = false): List<NavAppInfo> {
        val ctx = app ?: return emptyList()
        if (loaded && !force) return cached
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, NavAppInfo>()

        val probes = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=35.6997,51.3903")),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:35.6997,51.3903?z=16")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=35.6997,51.3903"))
        )
        for (probe in probes) {
            try {
                @Suppress("DEPRECATION")
                val infos = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                for (ri in infos) {
                    val pkg = ri.activityInfo?.packageName ?: continue
                    if (pkg == ctx.packageName) continue
                    if (found.containsKey(pkg)) continue
                    val label = try {
                        ri.loadLabel(pm).toString()
                    } catch (_: Throwable) {
                        KNOWN[pkg]?.label ?: pkg
                    }
                    found[pkg] = NavAppInfo(
                        packageName = pkg,
                        label = KNOWN[pkg]?.label ?: label,
                        supportsGeo = true,
                        directionsUrlTemplate = KNOWN[pkg]?.routeTemplate
                    )
                }
            } catch (t: Throwable) {
                Log.d(TAG, "probe failed: ${t.message}")
            }
        }

        // Anything installed but not answering `geo:` still deserves a slot if the user names it.
        val preferred = prefs?.navPackage.orEmpty()
        if (preferred.isNotEmpty() && !found.containsKey(preferred)) {
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(preferred, 0)).toString()
            } catch (_: Throwable) {
                KNOWN[preferred]?.label ?: preferred
            }
            found[preferred] = NavAppInfo(preferred, label, false, KNOWN[preferred]?.routeTemplate)
        }

        cached = found.values.sortedWith(
            compareByDescending<NavAppInfo> { KNOWN.containsKey(it.packageName) }.thenBy { it.label.lowercase() }
        )
        loaded = true
        return cached
    }

    fun invalidate() {
        loaded = false
    }

    fun preferred(): NavAppInfo? {
        val list = apps()
        val want = prefs?.navPackage.orEmpty()
        return list.firstOrNull { it.packageName == want } ?: list.firstOrNull()
    }

    fun setPreferred(pkg: String) {
        prefs?.navPackage = pkg
    }

    fun icon(ctx: Context, pkg: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (_: Throwable) {
        null
    }

    /** @return true when *any* navigation app exists (drives the empty-state copy). */
    fun hasAny(): Boolean = apps().isNotEmpty()

    fun launchIntentFor(ctx: Context, place: Place, app: NavAppInfo?): Intent? {
        val target = app ?: preferred()
        val from = com.arena.carlauncher.loc.LocationHub.lastLocation
        val template = prefs?.navTemplate(target?.packageName ?: "")?.takeIf { it.isNotBlank() }
            ?: target?.directionsUrlTemplate

        if (template != null) {
            val url = template
                .replace("{lat}", place.lat.toString())
                .replace("{lng}", place.lon.toString())
                .replace("{lon}", place.lon.toString())
                .replace("{slat}", (from?.latitude ?: 0.0).toString())
                .replace("{slon}", (from?.longitude ?: 0.0).toString())
                .replace("{label}", Uri.encode(place.label))
            try {
                return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (target != null) setPackage(target.packageName)
                }
            } catch (t: Throwable) {
                Log.d(TAG, "template intent failed: ${t.message}")
            }
        }

        // Standard fallback every real map app registers for.
        val uri = if (from != null) {
            Uri.parse("geo:${from.latitude},${from.longitude}?q=${place.lat},${place.lon}(${Uri.encode(place.label)})")
        } else {
            Uri.parse("geo:0,0?q=${place.lat},${place.lon}(${Uri.encode(place.label)})")
        }
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (target != null) setPackage(target.packageName)
        }
    }

    /** Opens turn-by-turn navigation in the chosen app; returns false if nothing answered. */
    fun navigateTo(ctx: Context, place: Place, app: NavAppInfo? = null): Boolean {
        val intent = launchIntentFor(ctx, place, app) ?: return false
        val target = app ?: preferred()
        val p = prefs ?: LauncherPrefs.get(ctx)
        return startSafely(ctx, intent, target, p.navOpenInSplitScreen)
    }

    /** Opens the app centred on a point without starting navigation. */
    fun showOnMap(ctx: Context, place: Place): Boolean {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("geo:${place.lat},${place.lon}?z=17&q=${place.lat},${place.lon}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (_: Throwable) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=${place.lat},${place.lon}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * Split-screen hand-off: `LAUNCH_ADJACENT` lets the map sit next to the launcher when the ROM
     * supports split multi-window (most 10/12 head units do) and degrades to a plain launch when it
     * does not — a launcher that refuses to start navigation is useless.
     */
    private fun startSafely(
        ctx: Context,
        intent: Intent,
        app: NavAppInfo?,
        preferAdjacent: Boolean
    ): Boolean {
        if (preferAdjacent) {
            try {
                val withFlags = Intent(intent).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                }
                if (app != null) withFlags.component = null
                ctx.startActivity(withFlags)
                return true
            } catch (_: Throwable) {
            }
        }
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            app?.let { Log.w(TAG, "${it.packageName} not launchable: ${e.message}") }
            // Last resort: let the system pick any geo handler.
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${intent.data?.query ?: ""}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun packageLabel(ctx: Context, pkg: String): String = try {
        KNOWN[pkg]?.label ?: ctx.packageManager.getApplicationLabel(
            ctx.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Throwable) {
        pkg
    }

    fun isNavigationPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return apps().any { it.packageName == pkg }
    }

    /** Component of the preferred nav app — used to open it from the dock. */
    fun launchComponent(ctx: Context): ComponentName? {
        val pkg = preferred()?.packageName ?: return null
        return try {
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.component
        } catch (_: Throwable) {
            null
        }
    }
}
