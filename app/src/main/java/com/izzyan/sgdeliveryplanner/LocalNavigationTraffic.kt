package com.izzyan.sgdeliveryplanner

import kotlin.math.floor

/** Local GPS-derived estimate, not third-party or independently verified live traffic. */
internal enum class LocalTrafficLevel { UNKNOWN, SMOOTH, SLOW, JAMMED }
internal data class LocalTrafficBand(val fromIndex: Int, val toIndex: Int, val level: LocalTrafficLevel)
internal data class LocalTrafficState(val bands: List<LocalTrafficBand>, val ahead: LocalTrafficLevel) {
    companion object { val EMPTY = LocalTrafficState(emptyList(), LocalTrafficLevel.UNKNOWN) }
}

/**
 * Conservative, privacy-preserving on-device observations. This contains NO network calls,
 * does not track other runners, and never claims island-wide coverage.
 *
 * A usable observation needs reliable GPS, a compatible route match and actual driving.
 * Parked runners, proof-of-delivery activity, stale readings and walking speeds are excluded.
 */
internal class LocalNavigationTraffic {
    private data class Sample(val ms: Long, val speedMps: Double)
    private val routeSamples = mutableMapOf<Int, ArrayDeque<Sample>>()
    private var segmentGroups = IntArray(0)
    private var groupRanges = emptyMap<Int, IntRange>()
    private var routeNormalSpeed = 0.0
    private var lastSampleMs = Long.MIN_VALUE
    private var lastSnapshotMs = Long.MIN_VALUE
    private var lastState = LocalTrafficState.EMPTY

    fun reset(shape: List<GpsPoint>, routeDistanceM: Double, routeSeconds: Double) {
        routeSamples.clear()
        lastSampleMs = Long.MIN_VALUE
        lastSnapshotMs = Long.MIN_VALUE
        lastState = LocalTrafficState.EMPTY
        routeNormalSpeed = if (routeSeconds.isFinite() && routeSeconds > 1 &&
            routeDistanceM.isFinite() && routeDistanceM > 0)
            (routeDistanceM / routeSeconds).coerceIn(3.0, 26.0) else 0.0
        segmentGroups = IntArray((shape.size - 1).coerceAtLeast(0))
        val ranges = mutableMapOf<Int, IntRange>()
        var progressMeters = 0.0
        for (i in segmentGroups.indices) {
            val group = floor(progressMeters / 220.0).toInt()
            segmentGroups[i] = group
            val old = ranges[group]
            ranges[group] = if (old == null) i..(i + 1) else old.first..(i + 1)
            progressMeters += navigationDistance(shape[i], shape[i + 1])
        }
        groupRanges = ranges
    }

    fun observe(
        matchingSegmentIndex: Int?,
        speedMetersPerSecond: Float?,
        accuracyMeters: Float,
        nowElapsedMs: Long,
        distanceToDestinationMeters: Double?,
        driving: Boolean,
        deliveryActivity: Boolean = false
    ) {
        if (!driving || deliveryActivity || matchingSegmentIndex == null ||
            matchingSegmentIndex !in segmentGroups.indices ||
            speedMetersPerSecond == null || !speedMetersPerSecond.isFinite() ||
            speedMetersPerSecond !in 2f..45f || !accuracyMeters.isFinite() ||
            accuracyMeters !in 0f..18f ||
            (distanceToDestinationMeters != null && distanceToDestinationMeters < 120.0) ||
            (lastSampleMs != Long.MIN_VALUE && nowElapsedMs - lastSampleMs < 2_500L)) return

        val group = segmentGroups[matchingSegmentIndex]
        val samples = routeSamples.getOrPut(group) { ArrayDeque() }
        samples.addLast(Sample(nowElapsedMs, speedMetersPerSecond.toDouble()))
        while (samples.isNotEmpty() && nowElapsedMs - samples.first().ms > 15 * 60_000L) samples.removeFirst()
        lastSampleMs = nowElapsedMs
    }

    /**
     * Refresh no faster than once per 30 seconds; data expires after 15 minutes.
     * A provisional color needs 5 independent spaced samples covering >= 14 seconds.
     * Without credible measurements / normal-speed estimate, retain UNKNOWN.
     */
    fun snapshot(nowElapsedMs: Long, currentSegmentIndex: Int?): LocalTrafficState {
        if (lastSnapshotMs != Long.MIN_VALUE && nowElapsedMs - lastSnapshotMs < 30_000L)
            return lastState
        lastSnapshotMs = nowElapsedMs
        if (routeNormalSpeed <= 0.0) return LocalTrafficState.EMPTY
        val bands = buildList {
            for ((group, range) in groupRanges) {
                val samples = routeSamples[group] ?: continue
                while (samples.isNotEmpty() &&
                    nowElapsedMs - samples.first().ms > 15 * 60_000L) samples.removeFirst()
                if (samples.size < 5 || samples.last().ms - samples.first().ms < 14_000L) continue
                val sorted = samples.map { it.speedMps }.sorted()
                val median = sorted[sorted.size / 2]
                val ratio = median / routeNormalSpeed
                val level = when {
                    ratio >= .70 -> LocalTrafficLevel.SMOOTH
                    ratio >= .35 -> LocalTrafficLevel.SLOW
                    else -> LocalTrafficLevel.JAMMED
                }
                add(LocalTrafficBand(range.first, range.last, level))
            }
        }
        val currentGroup = currentSegmentIndex?.takeIf { it in segmentGroups.indices }
            ?.let { segmentGroups[it] }
        val currentLevel = bands.firstOrNull { band ->
            val groupRange = currentGroup?.let { groupRanges[it] }
            groupRange != null && band.fromIndex == groupRange.first
        }?.level ?: LocalTrafficLevel.UNKNOWN
        lastState = LocalTrafficState(bands, currentLevel)
        return lastState
    }
}
