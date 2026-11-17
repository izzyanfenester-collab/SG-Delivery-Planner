package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class TrafficCamera865Test {
    private fun camera(limit: VerifiedRoadLimit? = null) = SingaporeTrafficCamera(
        id = "SPF-RLC-001", officialLocation = "Sample junction",
        category = TrafficCameraCategory.RED_LIGHT_CAMERA,
        latitude = 1.35, longitude = 103.82,
        roadName = "Sample road", travelDirection = "Towards junction",
        junction = "Sample junction",
        sourceUrl = "https://www.police.gov.sg/knowledge-hub/traffic/traffic-matters/red-light-camera-locations",
        sourcePublicationDate = "2026-07-01", refreshedAt = null,
        roadSegmentId = "segment-1", verifiedRoadLimit = limit
    )
    private val matched = CameraApproachEvidence(true, true, true, 300.0, 12f)
    @Test fun unknownSpeedLimitMustNeverBeAnnouncedAsFact() {
        val c = camera()
        assertNull(c.verifiedSpeedLimitKmh)
        assertEquals("NOT VERIFIED", c.speedLimitDisplay)
        assertFalse(TrafficCameraSafetyGate.overspeedAllowed(c, matched))
        assertFalse(c.isConfirmedSpeedEnforcement)
    }
    @Test fun verifiedLimitRequiresSourceAndMatchingSegment() {
        val verified = VerifiedRoadLimit(80, "https://www.lta.gov.sg/official", "2026-11-17", "segment-1", "Towards junction")
        assertEquals(80, camera(verified).verifiedSpeedLimitKmh)
        assertTrue(TrafficCameraSafetyGate.overspeedAllowed(camera(verified), matched))
    }
    @Test fun noProximityOnlyOrOppositeDirectionAlerts() {
        assertFalse(TrafficCameraSafetyGate.eligible(matched.copy(matchedTravelDirection = false)))
        assertFalse(TrafficCameraSafetyGate.eligible(matched.copy(matchedRoadSegment = false)))
        assertFalse(TrafficCameraSafetyGate.eligible(matched.copy(onActiveRouteAhead = false)))
        assertFalse(TrafficCameraSafetyGate.eligible(matched.copy(routeDistanceMetres = null)))
        assertFalse(TrafficCameraSafetyGate.eligible(matched.copy(locationAccuracyMetres = 100f)))
        assertTrue(TrafficCameraSafetyGate.eligible(matched))
    }
}
