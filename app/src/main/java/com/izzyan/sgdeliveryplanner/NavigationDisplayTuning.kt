package com.izzyan.sgdeliveryplanner

/** Presentation-only tuning; GPS fixes used for routing and arrival remain untouched. */
internal object NavigationDisplayTuning {
    fun zoomForSpeed(speedMetersPerSecond: Float?): Double = when {
        speedMetersPerSecond == null || !speedMetersPerSecond.isFinite() -> 16.7
        speedMetersPerSecond < 4f -> 17.4
        speedMetersPerSecond < 12f -> 16.9
        speedMetersPerSecond < 22f -> 16.3
        else -> 15.7
    }

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
