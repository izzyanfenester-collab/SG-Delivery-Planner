package com.izzyan.sgdeliveryplanner

/** Presentation-only tuning; GPS fixes used for routing and arrival remain untouched. */
internal object NavigationDisplayTuning {
    fun zoomForSpeed(speedMetersPerSecond: Float?): Double = when {
        speedMetersPerSecond == null || !speedMetersPerSecond.isFinite() -> 18.2
        speedMetersPerSecond < 4f -> 18.8
        speedMetersPerSecond < 12f -> 18.4
        speedMetersPerSecond < 22f -> 18.0
        else -> 17.6
    }

    /** Portrait camera: native GPS puck stays around 72% from the top. */
    fun trackingPadding(mapHeightPixels: Int): DoubleArray =
        doubleArrayOf(0.0, mapHeightPixels.coerceAtLeast(0) * 0.44, 0.0, 0.0)

    /** Short landscape has a bottom ETA bar, so move the puck to 60% height. */
    fun trackingPadding(mapWidthPixels: Int, mapHeightPixels: Int): DoubleArray =
        if (mapHeightPixels > 0 && mapWidthPixels > mapHeightPixels &&
            mapHeightPixels < 1300) {
            doubleArrayOf(0.0, mapHeightPixels * 0.20, 0.0, 0.0)
        } else trackingPadding(mapHeightPixels)

    fun adjustedZoom(currentZoom: Double, step: Double): Double =
        (currentZoom + step).coerceIn(15.5, 19.5)

    /** Extract the road and exit for a large, at-a-glance turn banner. */
    fun nextRoadLabel(instruction: String, fallbackStreet: String?): String =
        Regex("""(?i)\bonto\s+(.+)$""").find(instruction)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackStreet?.takeIf { it.isNotBlank() }
            ?: instruction

    fun roundaboutExit(instruction: String): String? =
        Regex("""(?i)\b(\d+)(?:st|nd|rd|th)\s+exit\b""").find(instruction)
            ?.groupValues?.get(1)?.let { "EXIT $it" }

    fun isNightSingapore(hour: Int): Boolean = hour < 7 || hour >= 19

    /** Maintain the last reliable heading while stationary, to avoid spinning at traffic lights. */
    fun smoothBearing(previous: Float?, measured: Float?, speedMetersPerSecond: Float?): Float? {
        if (measured == null || !measured.isFinite() || speedMetersPerSecond == null ||
            !speedMetersPerSecond.isFinite() || speedMetersPerSecond < 2.0f) return previous
        val target = ((measured % 360f) + 360f) % 360f
        if (previous == null) return target
        val delta = ((target - previous + 540f) % 360f) - 180f
        return (((previous + delta * 0.55f) % 360f) + 360f) % 360f
    }

    fun maneuverArrow(instruction: String): String = when {
        instruction.contains("u-turn", ignoreCase = true) -> "↶"
        instruction.contains("keep left", ignoreCase = true) || instruction.contains("turn left", ignoreCase = true) -> "↰"
        instruction.contains("keep right", ignoreCase = true) || instruction.contains("turn right", ignoreCase = true) -> "↱"
        instruction.contains("roundabout", ignoreCase = true) -> "⟳"
        else -> "↑"
    }
}
