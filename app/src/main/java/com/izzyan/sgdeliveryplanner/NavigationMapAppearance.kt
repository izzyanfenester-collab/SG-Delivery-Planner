package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import java.time.ZonedDateTime
import java.time.LocalDate
import kotlin.math.*
import java.util.Locale

/** Theme preference affects navigation BASMAP paints only. No routing/data changes. */
internal enum class NavigationMapAppearance { DAY, NIGHT, AUTO }

internal object NavigationMapThemeLogic {
    fun isNight(mode: NavigationMapAppearance, localHour: Int): Boolean = when (mode) {
        NavigationMapAppearance.DAY -> false
        NavigationMapAppearance.NIGHT -> true
        NavigationMapAppearance.AUTO -> localHour < 7 || localHour >= 19
    }

    fun currentLocalHour(): Int = ZonedDateTime.now().hour

    /** Use actual local sunrise/sunset when trusted coordinates are available.
     * Fall back to the existing 07:00–19:00 local clock rule without location.
     * A stable five-minute twilight buffer prevents sunrise/sunset flutter.
     */
    fun isNightAtLocation(
        mode: NavigationMapAppearance, now: ZonedDateTime,
        latitude: Double?, longitude: Double?
    ): Boolean {
        if (mode != NavigationMapAppearance.AUTO) return mode == NavigationMapAppearance.NIGHT
        val lat = latitude?.takeIf { it.isFinite() && it in -65.0..65.0 }
        val lon = longitude?.takeIf { it.isFinite() && it in -180.0..180.0 }
        if (lat == null || lon == null) return isNight(mode, now.hour)
        val sunrise = NavigationSunClock.localMinute(
            now.toLocalDate(), lat, lon, now.offset.totalSeconds, sunrise = true)
        val sunset = NavigationSunClock.localMinute(
            now.toLocalDate(), lat, lon, now.offset.totalSeconds, sunrise = false)
        if (sunrise == null || sunset == null || sunrise >= sunset)
            return isNight(mode, now.hour)
        val minutes = now.hour * 60 + now.minute
        return minutes < sunrise + 5 || minutes >= sunset + 5
    }
}

/** Approximate civil sunrise/sunset (sun centre -0.833 degrees), no network/API.
 * Calculation is geographical; timezone offset comes from the local clock.
 */
internal object NavigationSunClock {
    fun localMinute(date: LocalDate, latitude: Double, longitude: Double,
        offsetSeconds: Int, sunrise: Boolean): Int? {
        fun normalize(deg: Double): Double = (deg % 360.0 + 360.0) % 360.0
        fun sinDeg(x: Double) = sin(Math.toRadians(x))
        fun cosDeg(x: Double) = cos(Math.toRadians(x))
        val lngHour = longitude / 15.0
        val t = date.dayOfYear + ((if (sunrise) 6.0 else 18.0) - lngHour) / 24.0
        val m = (0.9856 * t) - 3.289
        val l = normalize(m + 1.916 * sinDeg(m) + 0.020 * sinDeg(2 * m) + 282.634)
        val rawRa = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l))))
        val ra = normalize(rawRa + (floor(l / 90.0) - floor(rawRa / 90.0)) * 90.0) / 15.0
        val sinDec = 0.39782 * sinDeg(l)
        val cosDec = cos(asin(sinDec))
        val cosH = (cosDeg(90.833) - sinDec * sinDeg(latitude)) /
            (cosDec * cosDeg(latitude))
        if (!cosH.isFinite() || cosH !in -1.0..1.0) return null
        val hourAngle = (if (sunrise) 360.0 - Math.toDegrees(acos(cosH))
            else Math.toDegrees(acos(cosH))) / 15.0
        val utcHour = (hourAngle + ra - 0.06571 * t - 6.622 - lngHour + 48.0) % 24.0
        return ((utcHour * 60.0 + offsetSeconds / 60.0).roundToInt() % 1440 + 1440) % 1440
    }
}

internal class NavigationMapAppearanceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "sg_delivery_navigation_appearance", Context.MODE_PRIVATE)

    fun read(): NavigationMapAppearance =
        runCatching {
            NavigationMapAppearance.valueOf(
                prefs.getString("mode", NavigationMapAppearance.AUTO.name)
                    ?: NavigationMapAppearance.AUTO.name)
        }.getOrDefault(NavigationMapAppearance.AUTO)

    fun save(mode: NavigationMapAppearance) {
        prefs.edit().putString("mode", mode.name).apply()
    }
}

/**
 * Genuine MapLibre paint changes on the *existing* OpenFreeMap Liberty style.
 *
 * We deliberately never replace sources, style JSON, layer visibility,
 * source-layer IDs, minZoom/maxZoom, labels' text expressions, or tile URLs.
 * In-place paint changes require no new network/style load, preserve native
 * location tracking, camera position, route annotations and glyph collision.
 *
 * The categorisation supports common OpenMapTiles / Liberty naming variants
 * without depending on a proprietary Google map style.
 */
internal object NavigationMapPalette {
    const val DAY_BASE_STYLE = NAVIGATION_STYLE

    internal fun layerColor(id: String, kind: String, night: Boolean): String {
        val key = id.lowercase(Locale.ROOT)
        val isWater = listOf("water", "river", "lake", "ocean", "sea", "stream")
            .any { it in key }
        val isPark = listOf("park", "wood", "forest", "grass", "garden", "nature", "green",
            "landcover", "landuse", "scrub", "vegetation").any { it in key }
        val isBuilding = listOf("building", "house", "residential_area").any { it in key }
        val isRoad = listOf("road", "street", "highway", "motorway", "trunk", "primary",
            "secondary", "tertiary", "minor", "service", "transportation", "bridge",
            "tunnel", "path", "footway", "track").any { it in key }
        val isHighway = listOf("motorway", "highway", "trunk", "expressway").any { it in key }
        val isMajorRoad = listOf("primary", "secondary", "major", "arterial").any { it in key }
        val isCasing = listOf("casing", "outline", "border", "edge").any { it in key }
        return when (kind) {
            "background" -> if (night) "#133F52" else "#EDF3F0"
            "fill" -> when {
                isWater -> if (night) "#164973" else "#A7D4EA"
                isPark -> if (night) "#235A51" else "#CDE6CA"
                isBuilding -> if (night) "#2A5864" else "#DDDCD7"
                isRoad -> if (night) "#577E90" else "#F8F7F2"
                else -> if (night) "#1C4D5B" else "#E6EBE6"
            }
            "line" -> when {
                isWater -> if (night) "#33789A" else "#79BBD9"
                isRoad && isCasing -> if (night) "#234E65" else "#A6B7B7"
                isHighway -> if (night) "#8BCBC1" else "#E4BD83"
                isMajorRoad -> if (night) "#9CBACA" else "#FFF9E5"
                isRoad -> if (night) "#7398B4" else "#FCFCFA"
                "rail" in key -> if (night) "#61879D" else "#9FA9A9"
                "boundary" in key -> if (night) "#5E8696" else "#ABB8B8"
                else -> if (night) "#527F91" else "#BBCBCB"
            }
            "symbol" -> when {
                isRoad -> if (night) "#F3FBFF" else "#243A48"
                isWater -> if (night) "#B4DCFF" else "#245D86"
                isPark -> if (night) "#D5F5E4" else "#2E6045"
                else -> if (night) "#E6F4FF" else "#2D4751"
            }
            else -> if (night) "#214D5F" else "#E5EAE6"
        }
    }

    /**
     * Repaint existing layers in-place. Preserve sprite icons, road name text,
     * shield sprites, visibility rules, camera and collision settings.
     * Label halos improve legibility in both daylight and the teal-blue night.
     */
    /**
     * MapLibre's annotation layers are NOT basemap roads. Repainting them with
     * generic road colours made the active navigation polyline appear grey.
     * Keep route, traffic and location overlays in their own authored colours.
     */
    internal fun isNavigationAnnotation(id: String): Boolean {
        val name = id.lowercase(Locale.ROOT)
        return name.startsWith("mapbox-android-") ||
            name.startsWith("maplibre-android-") ||
            "annotation" in name ||
            name.startsWith("sg-navigation-")
    }

    fun apply(style: Style, night: Boolean) {
        for (layer in style.layers) {
            val id = layer.id
            if (isNavigationAnnotation(id)) continue
            when (layer) {
                is BackgroundLayer -> layer.setProperties(
                    PropertyFactory.backgroundColor(Color.parseColor(layerColor(id, "background", night))))
                is FillLayer -> layer.setProperties(
                    PropertyFactory.fillColor(Color.parseColor(layerColor(id, "fill", night))))
                is LineLayer -> layer.setProperties(
                    PropertyFactory.lineColor(Color.parseColor(layerColor(id, "line", night))))
                is SymbolLayer -> {
                    val labelColor = Color.parseColor(layerColor(id, "symbol", night))
                    val halo = Color.parseColor(if (night) "#143747" else "#F4F8F6")
                    layer.setProperties(
                        PropertyFactory.textColor(labelColor),
                        PropertyFactory.textHaloColor(halo),
                        PropertyFactory.textHaloWidth(if (night) 1.5f else 1.2f))
                }
            }
        }
    }
}
