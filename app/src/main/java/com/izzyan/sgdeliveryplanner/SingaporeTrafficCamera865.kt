package com.izzyan.sgdeliveryplanner

/**
 * B865 TESTING camera data model. Publication does not prove live deployment.
 * Never substitute the general Singapore speed limit for a verified segment limit.
 */
internal enum class TrafficCameraCategory {
    FIXED_SPEED_CAMERA, LTA_SPEED_CAMERA, MOBILE_ENFORCEMENT_LOCATION,
    AVERAGE_SPEED_ZONE, POLICE_LASER_LOCATION, RED_LIGHT_CAMERA
}
internal enum class SpeedEnforcementCapability { CONFIRMED, POSSIBLE, UNVERIFIED }
internal data class VerifiedRoadLimit(
    val kmh: Int,
    val sourceUrl: String,
    val verifiedAt: String,
    val roadSegmentId: String,
    val travelDirection: String
) {
    init {
        require(kmh in 10..130)
        require(sourceUrl.startsWith("https://"))
        require(verifiedAt.isNotBlank() && roadSegmentId.isNotBlank() && travelDirection.isNotBlank())
    }
}
internal data class SingaporeTrafficCamera(
    val id: String,
    val officialLocation: String,
    val category: TrafficCameraCategory,
    val latitude: Double,
    val longitude: Double,
    val roadName: String?,
    val travelDirection: String?,
    val junction: String?,
    val sourceUrl: String,
    val sourcePublicationDate: String?,
    val refreshedAt: String?,
    val roadSegmentId: String? = null,
    val verifiedBearingDegrees: Double? = null,
    val speedCapability: SpeedEnforcementCapability = SpeedEnforcementCapability.UNVERIFIED,
    val verifiedRoadLimit: VerifiedRoadLimit? = null,
    val averageSpeedZoneId: String? = null
) {
    init {
        require(id.isNotBlank() && officialLocation.isNotBlank())
        require(latitude in 1.1..1.6 && longitude in 103.5..104.2)
        require(sourceUrl.startsWith("https://www.police.gov.sg/"))
        require(verifiedBearingDegrees == null || verifiedBearingDegrees in 0.0..360.0)
        if (verifiedRoadLimit != null) {
            require(roadSegmentId == verifiedRoadLimit.roadSegmentId)
            require(travelDirection == verifiedRoadLimit.travelDirection)
        }
    }
    val verifiedSpeedLimitKmh: Int? get() = verifiedRoadLimit?.kmh
    val speedLimitDisplay: String get() = verifiedSpeedLimitKmh?.let { "$it KM/H" } ?: "NOT VERIFIED"
    val isConfirmedSpeedEnforcement: Boolean get() =
        category != TrafficCameraCategory.RED_LIGHT_CAMERA ||
            speedCapability == SpeedEnforcementCapability.CONFIRMED
}

/** Never alert from proximity alone: road, travel direction and forward route match are mandatory. */
internal data class CameraApproachEvidence(
    val matchedRoadSegment: Boolean,
    val matchedTravelDirection: Boolean,
    val onActiveRouteAhead: Boolean,
    val routeDistanceMetres: Double?,
    val locationAccuracyMetres: Float
)
internal object TrafficCameraSafetyGate {
    fun eligible(evidence: CameraApproachEvidence): Boolean =
        evidence.matchedRoadSegment && evidence.matchedTravelDirection &&
        evidence.onActiveRouteAhead && evidence.locationAccuracyMetres <= 60f &&
        evidence.routeDistanceMetres != null &&
        evidence.routeDistanceMetres.isFinite() &&
        evidence.routeDistanceMetres >= 0.0

    fun overspeedAllowed(camera: SingaporeTrafficCamera, evidence: CameraApproachEvidence): Boolean =
        eligible(evidence) && camera.verifiedRoadLimit != null

    fun cameraWarningLabel(camera: SingaporeTrafficCamera): String = when (camera.category) {
        TrafficCameraCategory.RED_LIGHT_CAMERA -> "Red-light camera ahead"
        TrafficCameraCategory.MOBILE_ENFORCEMENT_LOCATION -> "Possible mobile enforcement location ahead"
        TrafficCameraCategory.POLICE_LASER_LOCATION -> "Published police speed laser location ahead"
        TrafficCameraCategory.AVERAGE_SPEED_ZONE -> "Published average speed enforcement zone"
        else -> "Speed camera ahead"
    }
}
