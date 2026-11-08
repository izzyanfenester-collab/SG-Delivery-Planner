package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.maplibre.navigation.core.models.DirectionsRoute
import kotlin.math.*

/** OSRM-compatible route DTO for the existing MapLibre navigation SDK.
 * Uses the locally computed driving graph; no remote route response is involved.
 */
internal fun encodeOfflinePolyline6(points: List<GpsPoint>): String {
    var lastLat = 0L
    var lastLon = 0L
    val output = StringBuilder()
    fun encode(delta: Long) {
        var value = if (delta < 0) ((-delta) shl 1) - 1 else delta shl 1
        while (value >= 0x20) {
            output.append(((0x20 or (value and 0x1fL).toInt()) + 63).toChar())
            value = value shr 5
        }
        output.append((value.toInt() + 63).toChar())
    }
    for (p in points) {
        val lat = (p.lat * 1_000_000).roundToLong()
        val lon = (p.lon * 1_000_000).roundToLong()
        encode(lat-lastLat)
        encode(lon-lastLon)
        lastLat = lat
        lastLon = lon
    }
    return output.toString()
}

private fun roadBearing(a: GpsPoint, b: GpsPoint): Double {
    val phi1 = Math.toRadians(a.lat)
    val phi2 = Math.toRadians(b.lat)
    val dl = Math.toRadians(b.lon - a.lon)
    val y = sin(dl) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/** Basic intersection-based guidance only; signs, lanes and complex exits remain unverified. */
internal fun offlineDrivingDirections(path: OfflineRoadPath): DirectionsRoute {
    require(path.points.size >= 2)
    val points = path.points
    val cuts = mutableListOf(0)
    val maneuvers = mutableListOf("Drive along the road")
    val modifiers = mutableListOf("straight")
    val maxSteps = 140
    for (i in 1 until points.lastIndex) {
        if (cuts.size >= maxSteps - 1) break
        val before = roadBearing(points[i-1], points[i])
        val after = roadBearing(points[i], points[i+1])
        val delta = ((after - before + 540) % 360) - 180
        if (abs(delta) >= 40 && navigationDistance(points[i-1], points[i]) >= 3.0) {
            cuts.add(i)
            maneuvers.add(if (delta < 0) "Turn left" else "Turn right")
            modifiers.add(if (delta < 0) "left" else "right")
        }
    }
    cuts.add(points.lastIndex)
    val steps = JsonArray()
    for (i in 0 until cuts.lastIndex) {
        val segment = points.subList(cuts[i], cuts[i+1]+1)
        val distance = segment.zipWithNext().sumOf { (a,b) -> navigationDistance(a,b) }
        val duration = if (path.meters > 0) path.seconds * distance / path.meters else 0.0
        val start = segment.first()
        val step = JsonObject().apply {
            addProperty("distance", distance)
            addProperty("duration", duration)
            addProperty("weight", duration)
            addProperty("name", "")
            addProperty("mode", "driving")
            addProperty("driving_side", "left")
            addProperty("geometry", encodeOfflinePolyline6(segment))
            add("maneuver", JsonObject().apply {
                addProperty("type", if (i == 0) "depart" else "turn")
                addProperty("modifier", modifiers[i])
                addProperty("instruction", maneuvers[i])
                add("location", JsonArray().apply { add(start.lon); add(start.lat) })
                addProperty("bearing_before", 0)
                addProperty("bearing_after", roadBearing(segment[0], segment[1]).roundToInt())
            })
        }
        steps.add(step)
    }
    val destination = points.last()
    steps.add(JsonObject().apply {
        addProperty("distance", 0)
        addProperty("duration", 0)
        addProperty("weight", 0)
        addProperty("name", "")
        addProperty("mode", "driving")
        addProperty("driving_side", "left")
        addProperty("geometry", encodeOfflinePolyline6(listOf(points[points.size - 2], destination)))
        add("maneuver", JsonObject().apply {
            addProperty("type", "arrive")
            addProperty("instruction", "You have arrived at your destination")
            add("location", JsonArray().apply { add(destination.lon); add(destination.lat) })
            addProperty("bearing_before", 0)
            addProperty("bearing_after", 0)
        })
    })
    val response = JsonObject().apply {
        addProperty("code", "Ok")
        add("routes", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("distance", path.meters)
                addProperty("duration", path.seconds)
                addProperty("weight", path.seconds)
                addProperty("weight_name", "offline_car")
                addProperty("voiceLocale", "en-US")
                addProperty("geometry", encodeOfflinePolyline6(points))
                add("legs", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("distance", path.meters)
                        addProperty("duration", path.seconds)
                        addProperty("weight", path.seconds)
                        add("steps", steps)
                    })
                })
            })
        })
    }
    return parseNavigationRoute(response.toString(), points.first(), destination)
}
