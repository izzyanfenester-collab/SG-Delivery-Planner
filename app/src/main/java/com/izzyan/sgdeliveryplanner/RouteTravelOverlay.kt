package com.izzyan.sgdeliveryplanner

/**
 * Draw-only progress model: the blue polyline represents the remaining route while the
 * already-traveled road remains gray. Raw GPS still controls off-route detection and arrival.
 * Movement is accepted only when the native GPS puck has a reliable route-segment match.
 */
internal data class RouteTravelSplit(
    val traveled: List<GpsPoint>,
    val remaining: List<GpsPoint>,
    val traveledMeters: Double
)

internal class RouteTravelTracker {
    private var shape: List<GpsPoint> = emptyList()
    private var prefixMeters = DoubleArray(0)
    private var meters = 0.0
    private var split = RouteTravelSplit(emptyList(), emptyList(), 0.0)

    fun reset(route: List<GpsPoint>): RouteTravelSplit {
        shape = route.toList()
        prefixMeters = DoubleArray(shape.size)
        for (i in 1 until shape.size) {
            prefixMeters[i] = prefixMeters[i - 1] + navigationDistance(shape[i - 1], shape[i])
        }
        meters = 0.0
        split = RouteTravelSplit(emptyList(), shape, meters)
        return split
    }

    fun current() = split

    /**
     * The passed position is the very same map-matched geographic point used for the GPS
     * arrow. Never advance from an unmatched raw GPS fix or while the vehicle is stationary.
     */
    fun advance(segmentIndex: Int?, snappedPosition: GpsPoint): RouteTravelSplit? {
        val i = segmentIndex ?: return null
        if (i !in 0 until shape.lastIndex || !snappedPosition.valid()) return null
        val a = shape[i]
        val b = shape[i + 1]
        val length = navigationDistance(a, b)
        if (length < 0.05) return null

        // The route matcher already produced a projected geographic point. Re-project
        // once onto its selected segment to calculate cumulative distance and a stable cut.
        val latScale = 111_195.0
        val lonScale = latScale * kotlin.math.cos(Math.toRadians(a.lat))
        val bx = (b.lon - a.lon) * lonScale
        val by = (b.lat - a.lat) * latScale
        val px = (snappedPosition.lon - a.lon) * lonScale
        val py = (snappedPosition.lat - a.lat) * latScale
        val t = ((px * bx + py * by) / (bx * bx + by * by)).coerceIn(0.0, 1.0)
        val progress = prefixMeters[i] + t * length

        // At high speed, allow ordinary movement. Reject a teleportation or a match to a
        // distant crossing/loop that could prematurely gray out the entire remaining route.
        if (!progress.isFinite() || progress < meters + 2.0 || progress > meters + 350.0) return null
        meters = progress
        val cut = GpsPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        val oldRoad = shape.take(i + 1) + cut
        val nextRoad = listOf(cut) + shape.drop(i + 1)
        split = RouteTravelSplit(oldRoad, nextRoad, meters)
        return split
    }
}
