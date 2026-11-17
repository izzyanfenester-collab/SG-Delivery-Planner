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
    val zoneId: String?
) {
    val isRedLight get() = category == "RED_LIGHT_CAMERA"
    val isMobile get() = category in setOf("MOBILE_ENFORCEMENT_LOCATION", "POLICE_LASER_LOCATION")
    val isAverage get() = category == "AVERAGE_SPEED_ZONE"
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
        "MOBILE_ENFORCEMENT_LOCATION","AVERAGE_SPEED_ZONE","POLICE_LASER_LOCATION")
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
                c.optString("average_speed_zone_id","").takeUnless{it.isBlank()||it=="null"})
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
    val directionConfirmed: Boolean, val alertAllowed: Boolean
)

/** Conservative approach classification: road, route shape AND published travel direction.
 * If no reliable bearing and no corroborating upcoming road, show a map icon only.
 */
internal object CameraApproachLogic {
    private fun normalized(s:String)=s.lowercase(Locale.ROOT)
        .replace(Regex("\\b(rd|road)\\b"),"road")
        .replace(Regex("\\b(ave|avenue)\\b"),"avenue")
        .replace(Regex("\\b(hwy|h'way|highway)\\b"),"highway")
        .replace(Regex("\\s+")," ").trim()
    private fun roadMatches(current:String?,camera:String):Boolean {
        if(current.isNullOrBlank())return false
        val a=normalized(current)
        val b=normalized(camera).replace(Regex("\\s*\\(.*?\\)"),"").trim()
        return a==b || (a.length>=5 && b.length>=5 && (a.startsWith(b)||b.startsWith(a)))
    }
    private fun directionMatches(camera:EnforcementCamera,routeBearing:Double,upcomingRoads:List<String>):Boolean {
        if(camera.directionVerified && camera.directionBearing!=null)
            return CameraRouteGeometry.angularGap(camera.directionBearing,routeBearing)<=28.0
        val declared=camera.directionText ?: return false
        val target=normalized(declared)
        // Independently corroborate a *textual* destination with upcoming route road names.
        // Do not guess compass bearings based on the SPF location string.
        return target.length>=4 && upcomingRoads.any { road ->
            val named=normalized(road)
            named==target || (named.length>5 && target.length>5 && named.contains(target))
        }
    }
    fun find(
        current:GpsPoint, shape:List<GpsPoint>, currentRoad:String?,
        upcomingRoads:List<String>, accuracyMetres:Float,
        records:List<EnforcementCamera>,
        speedMps:Float?
    ):List<CameraApproach> {
        if(!current.valid()||shape.size<2||!accuracyMetres.isFinite()||accuracyMetres>25) return emptyList()
        val vehicle=CameraRouteGeometry.project(current,shape) ?: return emptyList()
        if(vehicle.lateralMetres>25.0) return emptyList()
        val results=ArrayList<CameraApproach>()
        for(camera in records) {
            if(!roadMatches(currentRoad,camera.road))continue
            // Bound the expensive polyline projection with a cheap spherical box.
            if(abs(camera.point.lat-current.lat)>0.03 || abs(camera.point.lon-current.lon)>0.035)continue
            val projected=CameraRouteGeometry.project(camera.point,shape) ?: continue
            if(projected.lateralMetres>16.0)continue
            val ahead=projected.progressedMetres-vehicle.progressedMetres
            if(ahead < -30.0 || ahead > 1600.0)continue
            val direction=directionMatches(camera,projected.segmentBearing,upcomingRoads)
            val required=if(camera.isRedLight)300.0 else if(camera.isMobile)500.0
                else if(camera.isAverage)600.0 else if((speedMps?:0f)>=20f)1000.0 else 500.0
            results += CameraApproach(camera,ahead.coerceAtLeast(0.0),direction,
                direction && ahead<=required && !camera.isAverage)
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
