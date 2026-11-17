package com.izzyan.sgdeliveryplanner

import kotlin.math.max

/**
 * Build 865: deterministic camera warnings from already validated, route-aware approaches.
 * Never infer activation, light state, camera bearing, speed limits, or average-zone edges.
 */
internal data class CameraVoiceCue865(
    val cameraId: String,
    val category: String,
    val stage: String,
    val clipId: String,
    val metresAhead: Double,
    val phrase: VoicePhrase
)

internal class CameraVoicePlanner865 {
    private val delivered=mutableSetOf<String>()
    private val lastSeen=mutableMapOf<String,Double>()
    private var routeGeneration=0L

    fun reset() {
        delivered.clear()
        lastSeen.clear()
        routeGeneration++
    }

    fun due(
        approach: CameraApproach,
        settings: CameraAlertPreferences,
        voiceGuidanceEnabled: Boolean,
        cameraAudioAvailable: Boolean,
        turnMetres: Double?,
        speedMps: Float?
    ): CameraVoiceCue865? {
        val cam=approach.camera
        if(!voiceGuidanceEnabled || !cameraAudioAvailable || !settings.voice ||
            !settings.categoryEnabled(cam.category) || !approach.directionConfirmed ||
            !approach.alertAllowed) return null
        if(approach.routeMetresAhead < 0.0 || !approach.routeMetresAhead.isFinite()) return null
        // Never interrupt an imminent real navigation maneuver.
        if(turnMetres != null && turnMetres.isFinite() && turnMetres < 160.0) return null
        // SPF camera coordinates do not prove average speed-zone entrance and exit geometry.
        if(cam.isAverage) return null

        val metres=approach.routeMetresAhead
        val key=cam.id+"|"+cam.category
        val main=when(cam.category) {
            "RED_LIGHT_CAMERA"->300.0
            "MOBILE_ENFORCEMENT_LOCATION","POLICE_LASER_LOCATION"->500.0
            else->if((speedMps?:0f)>=20f)1000.0 else 500.0
        }
        val near=if(cam.isRedLight)100.0 else if(main>=1000)300.0 else 200.0
        // First eligible observation is the primary cue even when started within the threshold.
        // Never synthesize exact 'in 500m / in 300m': those distance-only assets
        // are absent from this user's 185 original recordings.
        val mainClip=when(cam.category) {
            "RED_LIGHT_CAMERA"->"red_light_camera_ahead"
            "MOBILE_ENFORCEMENT_LOCATION"->"mobile_speed_camera"
            "POLICE_LASER_LOCATION"->"police_speed_laser"
            else->"speed_camera_ahead"
        }
        val nearClip=when(cam.category) {
            "RED_LIGHT_CAMERA"->"red_light_camera_near"
            "MOBILE_ENFORCEMENT_LOCATION"->"mobile_speed_reminder"
            "POLICE_LASER_LOCATION"->"speed_limit_reminder"
            else->"speed_camera_near"
        }
        val prior=lastSeen[key]
        lastSeen[key]=metres
        val mainToken="$routeGeneration|$key|main"
        val nearToken="$routeGeneration|$key|near"
        val stageAndClip=when {
            metres<=main && mainToken !in delivered -> "main" to mainClip
            metres<=near && mainToken in delivered && nearToken !in delivered &&
                prior!=null && prior>near -> "near" to nearClip
            else->return null
        }
        val (stage,clip)=stageAndClip
        val priority=VoicePriority.CAMERA
        return CameraVoiceCue865(cam.id,cam.category,stage,clip,metres,
            VoicePhrase("camera-$routeGeneration-${cam.id}-$stage",priority,
                listOf(VoicePart(clip,"Published camera warning"))))
    }

    fun markScheduled(cue:CameraVoiceCue865) {
        delivered.add("$routeGeneration|${cue.cameraId}|${cue.category}|${cue.stage}")
    }

    fun stateReason(cameraId:String,category:String):String {
        val key="$routeGeneration|$cameraId|$category|main"
        return if(key in delivered) "already-announced-primary" else "awaiting-verified-approach"
    }
}

/** Compose only verified limits and actually recorded original number clips. */
internal object CameraSpeedLimitVoice865 {
    fun phrase(camera:EnforcementCamera):VoicePhrase? {
        val kmh=camera.speedLimitKmh ?: return null
        if(camera.speedLimitSource.isNullOrBlank() || camera.speedLimitVerifiedAt.isNullOrBlank())
            return null
        // Additional segment/direction evidence required: SPF snapshots currently have none.
        if(!camera.directionVerified) return null
        val number = when(kmh) {
            in 10..20, 30,40,50,60,70,80,90,100 -> listOf("number_$kmh")
            in 21..99 -> listOf("number_${kmh/10*10}","number_${kmh%10}")
            else -> return null
        }
        return VoicePhrase("verified-limit-${camera.id}-$kmh",VoicePriority.CAMERA,
            (listOf("speed_limit_prefix")+number+listOf("kilometres_per_hour"))
                .map{VoicePart(it,"Verified speed limit $kmh kilometres per hour")})
    }
}
