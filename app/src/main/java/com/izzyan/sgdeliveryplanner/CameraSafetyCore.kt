package com.izzyan.sgdeliveryplanner

import org.json.JSONObject
import java.util.Locale
import kotlin.math.*

/** SPF publishes *location and textual direction*, not camera bearing, live activation
 * or a camera-specific signed posted limit. Missing evidence is represented by null.
 */
internal data class EnforcementCamera(
    val id: String, val category: String, val name: String, val point: GpsPoint,
    val road: String, val directionText: String?, val junction: String?,
    val sourceUrl: String, val sourcePublicationDate: String?,
    val directionBearing: Double?, val directionVerified: Boolean,
    val speedLimitKmh: Int?, val speedLimitSource: String?, val speedLimitVerifiedAt: String?,
    val speedEnforcementStatus: String,
    val zoneId: String?,
    val averageZoneEntryVerified:Boolean=false,
    val averageZoneExitVerified:Boolean=false,
    val zoneEvidenceUrl:String?=null,
    val speedEnforcementEvidenceUrl:String?=null
) {
    val isRedLight get() = category in setOf("RED_LIGHT_CAMERA","RED_LIGHT_SPEED_CAMERA")
    val isMobile get() = category in setOf("MOBILE_ENFORCEMENT_LOCATION", "POLICE_LASER_LOCATION")
    val isAverage get() = category == "AVERAGE_SPEED_ZONE"
    val verifiedZoneBoundary get() = isAverage && !zoneId.isNullOrBlank() &&
        CameraEvidence872.official(zoneEvidenceUrl) &&
        (averageZoneEntryVerified xor averageZoneExitVerified)
    val verifiedDualEnforcement get() = isRedLight &&
        speedEnforcementStatus=="VERIFIED_SPEED_ENFORCEMENT" &&
        CameraEvidence872.official(speedEnforcementEvidenceUrl)
}

internal object CameraEvidence872 {
    fun official(url:String?):Boolean = !url.isNullOrBlank() && runCatching {
        val uri=java.net.URI(url)
        uri.scheme=="https" && uri.host?.lowercase(Locale.ROOT) in setOf(
            "www.police.gov.sg","www.lta.gov.sg","onemotoring.lta.gov.sg")
    }.getOrDefault(false)
}

internal data class CameraSafetySnapshot(
    val version: String, val refreshedAt: String, val cameras: List<EnforcementCamera>
) {
    val redLightCount get() = cameras.count { it.isRedLight }
}

/** Fail closed on stale/partial official source downloads; 241 was the 1-Jul-2026 SPF list.
 * New legitimate official counts require confirmation, not hardcoded truncation to 241.
 */
internal object CameraSafetySnapshotParser {
    val categories = setOf("RED_LIGHT_CAMERA","FIXED_SPEED_CAMERA","LTA_SPEED_CAMERA",
        "MOBILE_ENFORCEMENT_LOCATION","AVERAGE_SPEED_ZONE","POLICE_LASER_LOCATION",
        "RED_LIGHT_SPEED_CAMERA")
    private val allowedSpeedSourceHosts = setOf("www.lta.gov.sg","onemotoring.lta.gov.sg",
        "www.police.gov.sg","sso.agc.gov.sg")

    fun parse(json: String): CameraSafetySnapshot {
        require(json.length in 5000..2_000_000) { "Unrecognized camera dataset size" }
        val root=JSONObject(json)
        require(root.optInt("schema_version")==1)
        val version=root.getString("database_version")
        require(version.matches(Regex("spf-[0-9a-f]{16}")))
        val last=root.getString("last_successful_refresh")
        require(Regex("^20[0-9]{2}-[0-9]{2}-[0-9]{2}T").containsMatchIn(last))
        val list=root.getJSONArray("cameras")
        require(list.length()==root.getInt("record_count"))
        require(list.length() in 280..750)
        val cameras=ArrayList<EnforcementCamera>(list.length())
        val ids=HashSet<String>()
        for(i in 0 until list.length()) {
            val c=list.getJSONObject(i)
            val id=c.getString("camera_id")
            val category=c.getString("category")
            val lat=c.getDouble("latitude")
            val lon=c.getDouble("longitude")
            val source=c.getString("source_url")
            require(ids.add(id) && category in categories)
            require(lat in 1.10..1.50 && lon in 103.50..104.20)
            require(source.startsWith("https://www.police.gov.sg/",true))
            val limit=c.optInt("speed_limit_kmh",0).takeIf{it in 20..110}
            val limitSrc=c.optString("speed_limit_source","").takeUnless{it.isBlank() || it=="null"}
            val limitDate=c.optString("speed_limit_verified_at","").takeUnless{it.isBlank() || it=="null"}
            val verifiedLimit= if(limit!=null && limitSrc!=null && limitDate!=null &&
                runCatching{java.net.URI(limitSrc).host?.lowercase(Locale.ROOT) in allowedSpeedSourceHosts}.getOrDefault(false))
                limit else null
            // Numeric bearings cannot be claimed verified without independent source evidence.
            val bearingSource=c.optString("direction_provenance_url","").takeIf{it.startsWith("https://")}
            val bearing=c.optDouble("direction_bearing",Double.NaN)
                .takeIf { it.isFinite() && it in 0.0..360.0 && c.optBoolean("direction_verified") && bearingSource!=null }
            cameras += EnforcementCamera(
                id,category,c.getString("official_name"),GpsPoint(lat,lon),
                c.optString("road_name"),c.optString("direction_text","").takeUnless{it.isBlank()||it=="null"},
                c.optString("junction_name","").takeUnless{it.isBlank()||it=="null"},
                source,c.optString("source_publication_date","").takeUnless{it.isBlank()||it=="null"},
                bearing,bearing!=null,verifiedLimit,
                limitSrc.takeIf{verifiedLimit!=null},limitDate.takeIf{verifiedLimit!=null},
                c.getString("speed_enforcement_status"),
                c.optString("average_speed_zone_id","").takeUnless{it.isBlank()||it=="null"},
                c.optBoolean("average_zone_entry_verified",false),
                c.optBoolean("average_zone_exit_verified",false),
                c.optString("average_zone_evidence_url","").takeIf{CameraEvidence872.official(it)},
                c.optString("speed_enforcement_evidence_url","").takeIf{CameraEvidence872.official(it)})
        }
        require(cameras.count{it.isRedLight}>=200)
        require(cameras.count{it.category=="FIXED_SPEED_CAMERA"}>=18)
        require(cameras.count{it.category=="LTA_SPEED_CAMERA"}>=10)
        require(cameras.count{it.category=="POLICE_LASER_LOCATION"}>=35)
        require(root.optBoolean("current_activation_claimed")==false)
        return CameraSafetySnapshot(version,last,cameras)
    }
}

internal data class CameraRouteProjection(
    val progressedMetres: Double, val lateralMetres: Double,
    val segmentBearing: Double
)

/** Geographic polyline projection in local metre coordinates, not crow-flies distance. */
internal object CameraRouteGeometry {
    fun project(target:GpsPoint,shape:List<GpsPoint>): CameraRouteProjection? {
        if(shape.size<2 || !target.valid())return null
        val latScale=111_195.0
        val lonScale=latScale*cos(Math.toRadians(target.lat))
        var cumulative=0.0
        var closest:CameraRouteProjection?=null
        for(i in 0 until shape.lastIndex){
            val a=shape[i];val b=shape[i+1]
            if(!a.valid()||!b.valid())return null
            val dx=(b.lon-a.lon)*lonScale
            val dy=(b.lat-a.lat)*latScale
            val length=hypot(dx,dy)
            if(length<0.15)continue
            val x=(target.lon-a.lon)*lonScale
            val y=(target.lat-a.lat)*latScale
            val t=((x*dx+y*dy)/(length*length)).coerceIn(0.0,1.0)
            val lateral=hypot(x-t*dx,y-t*dy)
            val heading=(Math.toDegrees(atan2(dx,dy))+360.0)%360.0
            if(closest==null || lateral<closest.lateralMetres)
                closest=CameraRouteProjection(cumulative+t*length,lateral,heading)
            cumulative+=length
        }
        return closest
    }
    fun angularGap(a:Double,b:Double):Double=abs((a-b+540.0)%360.0-180.0)
}

internal data class CameraApproach(
    val camera: EnforcementCamera, val routeMetresAhead: Double,
    val directionConfirmed: Boolean, val alertAllowed: Boolean,
    // Conservative nearby *general safety reminder*, never a claim of directional enforcement.
    val genericSafetyEligible: Boolean = false,
    val coordinateFallbackUsed: Boolean = false,
    val matchedRoad:String?=null,
    val matchReason:String="route-direction-confirmed",
    val voiceGroupId:String?=null,
    val voiceGroupCategory:String?=null
)

/** Conservative approach classification: road, route shape AND published travel direction.
 * If no reliable bearing and no corroborating upcoming road, show a map icon only.
 */
internal object CameraApproachLogic {
    private fun normalized(s:String)=s.lowercase(Locale.ROOT)
        .replace(Regex("\\(.*?\\)"),"")
        .replace(Regex("\\b(rd|road)\\b"),"road")
        .replace(Regex("\\b(ave|avenue)\\b"),"avenue")
        .replace(Regex("\\b(expwy|expressway)\\b"),"expressway")
        .replace(Regex("\\b(jln|jalan)\\b"),"jalan")
        .replace(Regex("\\b(lor|lorong)\\b"),"lorong")
        .replace(Regex("\\b(upp|upper)\\b"),"upper")
        .replace(Regex("\\b(st|street)\\b"),"street")
        .replace(Regex("\\s+")," ").trim()
    private val namedExpressways=mapOf(
        "pie" to "pan island expressway",
        "aye" to "ayer rajah expressway",
        "bke" to "bukit timah expressway",
        "cte" to "central expressway",
        "ecp" to "east coast parkway",
        "kpe" to "kallang paya lebar expressway",
        "mce" to "marina coastal expressway",
        "sle" to "seletar expressway",
        "tpe" to "tampines expressway",
        "kje" to "kranji expressway"
    )
    private fun roadKey(s:String):String {
        // SPF KPE/MCE data has a kilometre marker; it is not the road name.
        // Mobile enforcement names may additionally describe a lamp post/exit.
        val value=normalized(s)
            .replace(Regex("\\s+\\d+(?:\\.\\d+)?\\s*km\\b"),"")
            .substringBefore(" at ")
            .substringBefore(" between ")
            .replace("-", " ")
            .replace(Regex("\\s+")," ").trim()
        return namedExpressways[value]?:value
    }
    private fun nearPublishedBidirectionalSite(
        camera:EnforcementCamera, records:List<EnforcementCamera>
    ):Boolean {
        // Paired SPF entries with different stated directions document BOTH travel
        // directions at one physical site; do NOT manufacture a numeric bearing.
        if(camera.category !in setOf("FIXED_SPEED_CAMERA","POLICE_LASER_LOCATION"))
            return false
        val direction=camera.directionText ?: return false
        val road=roadKey(camera.road)
        return records.any { other ->
            other.id!=camera.id && other.category==camera.category &&
                roadKey(other.road)==road && !other.directionText.isNullOrBlank() &&
                roadKey(other.directionText)!=roadKey(direction) &&
                abs(other.point.lat-camera.point.lat)<0.00065 &&
                abs(other.point.lon-camera.point.lon)<0.00065 &&
                hypot((other.point.lat-camera.point.lat)*111_195.0,
                    (other.point.lon-camera.point.lon)*111_195.0*
                    cos(Math.toRadians(camera.point.lat)))<=65.0
        }
    }
    private fun roadMatches(current:String?,camera:String):Boolean {
        if(current.isNullOrBlank()) return false
        val a=roadKey(current)
        val b=roadKey(camera)
        return a==b || (a.length>=6 && b.length>=6 && (a.startsWith(b+" ")||b.startsWith(a+" ")))
    }
    private fun publishedDestinationBearing(
        camera:EnforcementCamera, records:List<EnforcementCamera>
    ):Double? {
        // Use two independently published SPF coordinates *on the same road*.
        // A corroborating camera specifically names the destination as its junction.
        // This is a geometry-based route corroboration, never official camera bearing.
        val destination=camera.directionText ?: return null
        if(roadKey(destination).length<5) return null
        val matches=records.filter { other ->
            other.id!=camera.id && roadKey(other.road)==roadKey(camera.road) &&
                other.junction!=null && roadKey(other.junction)==roadKey(destination)
        }.map { other ->
            val dx=(other.point.lon-camera.point.lon)*111_195.0*
                cos(Math.toRadians(camera.point.lat))
            val dy=(other.point.lat-camera.point.lat)*111_195.0
            val length=hypot(dx,dy)
            length to ((Math.toDegrees(atan2(dx,dy))+360)%360)
        }.filter { (distance,_) -> distance in 120.0..1500.0 }
            .sortedBy{it.first}
        if(matches.isEmpty()) return null
        // Reject conflicting evidence pointing to separate directions.
        val bearing=matches.first().second
        if(matches.any { CameraRouteGeometry.angularGap(it.second,bearing)>30.0 }) return null
        return bearing
    }
    private fun directionMatches(
        camera:EnforcementCamera,routeBearing:Double,upcomingRoads:List<String>,
        records:List<EnforcementCamera>
    ):Boolean {
        if(camera.directionVerified && camera.directionBearing!=null)
            return CameraRouteGeometry.angularGap(camera.directionBearing,routeBearing)<=28.0
        val destination=camera.directionText ?: return false
        // Match SPF "twds Farrer Rd" against actual downstream road names first.
        if(roadKey(destination).length>=4 &&
            upcomingRoads.any { roadMatches(it,destination) }) return true
        val independentlyCorroborated=publishedDestinationBearing(camera,records)
            ?: return false
        return CameraRouteGeometry.angularGap(independentlyCorroborated,routeBearing)<=25.0
    }
    fun find(
        current:GpsPoint, shape:List<GpsPoint>, currentRoad:String?,
        upcomingRoads:List<String>, accuracyMetres:Float,
        records:List<EnforcementCamera>,
        speedMps:Float?,
        vehicleBearingDegrees:Double?=null,
        bearingAccuracyDegrees:Float?=null,
        roadCorridors:List<CameraRoadCorridor872> = emptyList()
    ):List<CameraApproach> {
        if(!current.valid()||shape.size<2||!accuracyMetres.isFinite()||accuracyMetres>25) return emptyList()
        val vehicle=CameraRouteGeometry.project(current,shape) ?: return emptyList()
        if(vehicle.lateralMetres>25.0) return emptyList()
        if(vehicleBearingDegrees?.isFinite()==true && (speedMps?:0f)>=3f &&
            CameraRouteGeometry.angularGap(vehicleBearingDegrees,vehicle.segmentBearing)>55.0)
            return emptyList()
        val results=ArrayList<CameraApproach>()
        for(camera in records) {
            // Bound the expensive polyline projection with a cheap spherical box.
            if(abs(camera.point.lat-current.lat)>0.045 || abs(camera.point.lon-current.lon)>0.045)continue
            val projected=CameraRouteGeometry.project(camera.point,shape) ?: continue
            if(projected.lateralMetres>16.0)continue
            // A camera may be on a later named road, before the runner joins it.
            // Match that exact route section, not every name anywhere in the route.
            val cameraRoad=roadCorridors.firstOrNull {
                projected.progressedMetres>=it.startMetres && projected.progressedMetres<=it.endMetres
            }?.road ?: currentRoad
            if(!roadMatches(cameraRoad,camera.road))continue
            val ahead=projected.progressedMetres-vehicle.progressedMetres
            // No post-camera warning: never convert a behind-camera (-30m) into 0m ahead.
            if((!camera.verifiedZoneBoundary && ahead<=0.0) || ahead < -80.0 || ahead > 3_200.0)continue
            val routeHeading=projected.segmentBearing
            val downstream=if(roadCorridors.isEmpty())upcomingRoads else
                roadCorridors.filter{it.startMetres>projected.progressedMetres}.map{it.road}
            val directionalTextMatches=directionMatches(camera,routeHeading,downstream,records)
            val explicitOpposite=camera.directionVerified && camera.directionBearing!=null &&
                CameraRouteGeometry.angularGap(camera.directionBearing,routeHeading)>28.0
            val bothDirectionsDocumented=!explicitOpposite && nearPublishedBidirectionalSite(camera,records) &&
                projected.lateralMetres<=10.0 && vehicle.lateralMetres<=12.0 &&
                accuracyMetres<=12f && (speedMps?:0f)>=3f &&
                vehicleBearingDegrees?.isFinite()==true &&
                CameraRouteGeometry.angularGap(vehicleBearingDegrees,vehicle.segmentBearing)<=20.0
            val direction=directionalTextMatches || bothDirectionsDocumented
            val required=CameraCoordinateCatalog867.PRIMARY_METRES
            // If SPF orientation cannot be corroborated, NEVER claim a speed or
            // red-light enforcement camera is operating in this direction.
            // An optional *generic* Panda safety reminder is allowed only with
            // very accurate heading, close route geometry and known same road.
            val headingReliable=vehicleBearingDegrees?.isFinite()==true &&
                vehicleBearingDegrees in 0.0..360.0 &&
                CameraRouteGeometry.angularGap(vehicleBearingDegrees,projected.segmentBearing)<=15.0 &&
                ((bearingAccuracyDegrees?.isFinite()==true && bearingAccuracyDegrees<=20f) ||
                    (bearingAccuracyDegrees==null && (speedMps?:0f)>=7f &&
                        accuracyMetres<=8f))
            val safetyReminder=!direction && !explicitOpposite && !camera.isAverage &&
                accuracyMetres<=12f && (speedMps?:0f)>=3f &&
                vehicle.lateralMetres<=12.0 && projected.lateralMetres<=8.0 &&
                ahead in 35.0..CameraCoordinateCatalog867.PRIMARY_METRES && headingReliable
            results += CameraApproach(camera,ahead,direction,
                direction && ahead<=required && (!camera.isAverage || camera.verifiedZoneBoundary),
                genericSafetyEligible=safetyReminder,matchedRoad=cameraRoad,
                matchReason=when {
                    camera.isAverage && !camera.verifiedZoneBoundary -> "zone-boundary-evidence-missing"
                    explicitOpposite -> "opposite-camera-direction"
                    !direction -> "published-direction-not-corroborated"
                    else -> "route-road-and-direction-confirmed"
                })
        }
        return results.sortedBy{it.routeMetresAhead}
    }
}

/** Deterministic one-alert-per-approach gate. Reset on reroute, voice mute or route end. */
internal class CameraAlertGate {
    private val warned=mutableSetOf<String>()
    fun reset(){warned.clear()}
    fun shouldPlay(approach:CameraApproach,enabled:Boolean):Boolean {
        if(!enabled || !approach.alertAllowed) return false
        val bucket=approach.camera.id+":"+approach.camera.category
        if(bucket in warned)return false
        warned.add(bucket)
        return true
    }
}
