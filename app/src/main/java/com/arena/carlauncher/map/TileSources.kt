package com.arena.carlauncher.map

/**
 * Raster tile providers that need **no API key** — the whole point for a head unit: no Google
 * Play Services, no billing, no key to register, and it keeps working on Iranian networks where
 * Google tiles are blocked (that is why Neshan/CARTO-style layers are on the list).
 *
 * If you ship this to customers, honour each provider's usage policy: cache tiles (we do),
 * send a real User-Agent, and for heavy fleets self-host a tile server and use [TileSources.CUSTOM].
 */
data class TileSource(
    val id: String,
    val label: String,
    val url: String,
    val attribution: String,
    val requiresSecureUserAgent: Boolean = true,
    val isSatellite: Boolean = false,
    val minZoom: Int = 1,
    val maxZoom: Int = 19,
    val nightFriendly: Boolean = false
)

object TileSources {

    const val OSM = "osm"
    const val CARTO_LIGHT = "carto_light"
    const val CARTO_DARK = "carto_dark"
    const val CARTO_VOYAGER = "carto_voyager"
    const val ESRI_SATELLITE = "esri_sat"
    const val OPEN_TILE = "opentile"
    const val CUSTOM = "custom"
    const val AUTO = "auto"

    val ALL = listOf(
        TileSource(
            id = OSM,
            label = "OpenStreetMap",
            url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors",
            maxZoom = 19,
            nightFriendly = false
        ),
        TileSource(
            id = CARTO_LIGHT,
            label = "CARTO Positron (سبک)",
            url = "https://basemaps.cartocdn.com/light_all/{z}/{x}/{y}@2x.png",
            attribution = "© CARTO © OpenStreetMap",
            maxZoom = 20,
            nightFriendly = false
        ),
        TileSource(
            id = CARTO_DARK,
            label = "CARTO Dark Matter (شب)",
            url = "https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png",
            attribution = "© CARTO © OpenStreetMap",
            maxZoom = 20,
            nightFriendly = true
        ),
        TileSource(
            id = CARTO_VOYAGER,
            label = "CARTO Voyager",
            url = "https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}@2x.png",
            attribution = "© CARTO © OpenStreetMap",
            maxZoom = 20,
            nightFriendly = false
        ),
        TileSource(
            id = ESRI_SATELLITE,
            label = "تصویر ماهواره‌ای (Esri)",
            url = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            attribution = "© Esri, Maxar, Earthstar Geographics",
            isSatellite = true,
            maxZoom = 19,
            nightFriendly = true
        ),
        TileSource(
            id = OPEN_TILE,
            label = "OpenTopoMap",
            url = "https://tile.opentopomap.org/{z}/{x}/{y}.png",
            attribution = "© OpenTopoMap (CC-BY-SA)",
            maxZoom = 16
        )
    )

    fun byId(id: String, customUrl: String = "", night: Boolean = false): TileSource {
        if (id == AUTO) {
            // Day/night aware basemap: dark tiles at night stop the map glaring through sunglasses.
            return if (night) byId(CARTO_DARK, customUrl, night) else byId(CARTO_LIGHT, customUrl, night)
        }
        if (id == CUSTOM && customUrl.isNotBlank()) {
            return TileSource(
                id = CUSTOM,
                label = "منبع سفارشی",
                url = customUrl.trim(),
                attribution = ""
            )
        }
        return ALL.firstOrNull { it.id == id } ?: ALL[0]
    }

    /** Fills `{z}/{x}/{y}` (and optional `{r}` = @2x) for a tile. */
    fun urlFor(source: TileSource, z: Int, x: Int, y: Int, retina: Boolean): String {
        val count = 1 shl z.coerceIn(0, 22)
        val tx = ((x % count) + count) % count
        val ty = y.coerceIn(0, count - 1)
        return source.url
            .replace("{z}", z.toString())
            .replace("{x}", tx.toString())
            .replace("{y}", ty.toString())
            .replace("{r}", if (retina) "@2x" else "")
    }
}
