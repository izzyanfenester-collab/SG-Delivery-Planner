package com.izzyan.sgdeliveryplanner

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
    val phrase: VoicePhrase,
    val previousMetresAhead:Double?=null,
    val warningThresholdMetres:Double?=null,
    val crossedThreshold:Boolean=false,
    val approachId:String=cameraId,
    val zoneId:String?=null,
    val stateCategory:String=category
)

internal class CameraVoicePlanner865 {
    private val delivered=mutableSetOf<String>() // Only confirmed MediaPlayer.start()
    private val requested=mutableSetOf<String>() // Accepted by controller, not yet audible
    private val startedAt=mutableMapOf<String,Long>()
    private val lastSeen=mutableMapOf<String,Double>()
    private val previousSeen=mutableMapOf<String,Double?>()
    private val absentSince=mutableMapOf<String,Long>()
    private var routeGeneration=0L
    private val activeZones=mutableSetOf<String>()

    fun reset() {
        delivered.clear()
        requested.clear()
        startedAt.clear()
        lastSeen.clear()
        previousSeen.clear()
        absentSince.clear()
        activeZones.clear()
        routeGeneration++
    }

    private fun prefix(cue:CameraVoiceCue865)=
        "$routeGeneration|${cue.approachId}|${cue.stateCategory}|${cue.stage}"
    private fun isCurrent(cue:CameraVoiceCue865)=
        cue.phrase.key.startsWith("camera-$routeGeneration-")

    /** Request must not be committed until the actual MediaPlayer.start callback fires. */
    fun markRequested(cue:CameraVoiceCue865) {
        if(isCurrent(cue))requested.add(prefix(cue))
    }

    fun playbackEvent(cue:CameraVoiceCue865,event:PandaPlaybackEvent866) {
        if(!isCurrent(cue) || event.phraseKey!=cue.phrase.key) return
        val token=prefix(cue)
        when(event.state) {
            PandaPlaybackState866.STARTED -> {
                requested.remove(token)
                startedAt[token]=event.elapsedMs
                delivered.add(token)
                if(cue.stage=="exit")cue.zoneId?.let{activeZones.remove(it)}
            }
            PandaPlaybackState866.COMPLETED -> {
                requested.remove(token)
                startedAt.remove(token)
            }
            PandaPlaybackState866.INTERRUPTED,
            PandaPlaybackState866.CANCELLED,
            PandaPlaybackState866.FAILED -> {
                requested.remove(token)
                val started=startedAt.remove(token)
                // An instruction that cuts the first syllable does not count as heard.
                if(started==null || event.elapsedMs-started<850L) delivered.remove(token)
            }
        }
    }

    /** Only a truly new approach, not GPS jitter, can reset a previous camera cue. */
    fun observeVisible(approaches:List<CameraApproach>,nowMs:Long) {
        val visible=approaches.map { (it.voiceGroupId?:it.camera.id)+"|"+(it.voiceGroupCategory?:it.camera.category) }.toSet()
        for(a in approaches) {
            val key=(a.voiceGroupId?:a.camera.id)+"|"+(a.voiceGroupCategory?:a.camera.category)
            val departed=absentSince[key]
            // Passing close, departing for >=15s and returning from >=300m is
            // a new approach. A brief GPS/road-match dropout is not.
            if(departed!=null && nowMs-departed>=15_000L &&
                (lastSeen[key]?:Double.MAX_VALUE)<=120 && a.routeMetresAhead>=300 && a.directionConfirmed) {
                delivered.removeAll{it.startsWith("$routeGeneration|$key|")}
                requested.removeAll{it.startsWith("$routeGeneration|$key|")}
                startedAt.keys.removeAll{it.startsWith("$routeGeneration|$key|")}
                lastSeen.remove(key)
            }
        }
        val tracked=(lastSeen.keys + delivered.map { it.substringAfter("|").substringBeforeLast("|") } +
            requested.map { it.substringAfter("|").substringBeforeLast("|") }).toSet()
        for(key in tracked) {
            if(key in visible) {
                absentSince.remove(key)
                continue
            }
            val whenAbsent=absentSince.getOrPut(key){nowMs}
            if(nowMs-whenAbsent>=90_000L) {
                lastSeen.remove(key)
                delivered.removeAll { it.startsWith("$routeGeneration|$key|") }
                requested.removeAll { it.startsWith("$routeGeneration|$key|") }
                startedAt.keys.removeAll { it.startsWith("$routeGeneration|$key|") }
                absentSince.remove(key)
            }
        }
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
        val metres=approach.routeMetresAhead
        val identity=approach.voiceGroupId?:cam.id
        val stateCategory=approach.voiceGroupCategory?:cam.category
        val key=identity+"|"+stateCategory
        // Observe every ahead fix, including 3KM/2.1KM and critical-turn deferrals.
        // Event generation is independent of the audio priority gate in the session.
        val previous=if(metres.isFinite())lastSeen.put(key,metres) else null
        previousSeen[key]=previous
        if(!voiceGuidanceEnabled || !cameraAudioAvailable || !settings.voice ||
            !settings.categoryEnabled(cam.category) || !settings.coordinateCameraAlerts) return null
        if(!metres.isFinite())return null
        if(cam.isAverage && !cam.verifiedZoneBoundary)return null
        if(!cam.isAverage && metres<=0.0)return null
        if(!approach.directionConfirmed && !approach.genericSafetyEligible)return null
        // A known opposite-direction camera never becomes a generic camera claim.
        if(!approach.alertAllowed && approach.directionConfirmed && metres<=2_000.0)return null
        val main=CameraCoordinateCatalog867.PRIMARY_METRES
        val near=CameraCoordinateCatalog867.REMINDER_METRES
        fun available(stage:String):Boolean {
            val token="$routeGeneration|$key|$stage"
            return token !in delivered && token !in requested
        }
        var stage:String
        var clips:List<String>
        var threshold=main
        if(cam.isAverage) {
            if(!approach.directionConfirmed)return null
            val zone=cam.zoneId!!
            if(cam.averageZoneEntryVerified && previous!=null && previous>0 && metres<=0)
                activeZones.add(zone)
            when {
                cam.averageZoneEntryVerified && metres>0.0 && metres<=main && available("main") -> {
                    stage="main";clips=listOf("average_speed_zone_ahead")
                }
                cam.averageZoneEntryVerified && metres in -80.0..0.0 && zone in activeZones && available("enter") -> {
                    stage="enter";clips=listOf("average_speed_zone_enter");threshold=0.0
                }
                cam.averageZoneExitVerified && metres in -80.0..0.0 && zone in activeZones && available("exit") -> {
                    stage="exit";clips=listOf("average_speed_zone_exit");threshold=0.0
                }
                else -> return null
            }
        } else if(!approach.directionConfirmed) {
            if(!(metres>0.0 && metres<=main) || !available("safety"))return null
            stage="safety"
            clips=listOf(if(cam.isRedLight)"red_light_camera_near"
                else if(cam.category=="MOBILE_ENFORCEMENT_LOCATION")"mobile_speed_reminder"
                else "speed_limit_reminder")
        } else {
            val mainClips=when {
                cam.verifiedDualEnforcement -> listOf("red_light_possible_speed")
                cam.isRedLight -> listOf("red_light_camera_ahead")
                cam.category=="MOBILE_ENFORCEMENT_LOCATION" -> listOf("mobile_speed_camera")
                cam.category=="POLICE_LASER_LOCATION" -> listOf("police_speed_laser")
                else -> listOf("speed_camera_ahead")
            }
            val nearClip=when {
                cam.isRedLight -> "red_light_camera_near"
                cam.category=="MOBILE_ENFORCEMENT_LOCATION" -> "mobile_speed_reminder"
                cam.category=="POLICE_LASER_LOCATION" -> "speed_limit_reminder"
                else -> "speed_camera_near"
            }
            val mainToken="$routeGeneration|$key|main"
            val crossedReminder=previous!=null && previous>near && metres<=near
            val lateReminder=previous!=null && previous>200 && metres<=200
            when {
                metres<=main && available("main") -> {stage="main";clips=mainClips}
                settings.coordinateReminder && metres<=near && mainToken in delivered &&
                    available("near") && (crossedReminder || lateReminder) -> {
                    stage="near";clips=listOf(nearClip);threshold=near
                }
                else -> return null
            }
        }
        val verifiedLimit=if(stage=="main" && !cam.isRedLight && !cam.isAverage)
            CameraSpeedLimitVoice865.phrase(cam)?.parts.orEmpty() else emptyList()
        return CameraVoiceCue865(cam.id,cam.category,stage,clips.first(),metres,
            VoicePhrase("camera-$routeGeneration-$identity-$stage",VoicePriority.CAMERA,
                clips.map{VoicePart(it,"Published camera warning")}+verifiedLimit),
            previousMetresAhead=previous,warningThresholdMetres=threshold,
            crossedThreshold=previous!=null && previous>threshold && metres<=threshold,
            approachId=identity,zoneId=cam.zoneId,stateCategory=stateCategory)
    }

    /** Kept for the original B865 fixture; production uses markRequested/playbackEvent. */
    fun markScheduled(cue:CameraVoiceCue865) {
        if(isCurrent(cue)) delivered.add(prefix(cue))
    }
    fun isRequested(cue:CameraVoiceCue865)=isCurrent(cue) && prefix(cue) in requested
    fun isStarted(cue:CameraVoiceCue865)=isCurrent(cue) && prefix(cue) in delivered

    fun stateReason(cameraId:String,category:String):String {
        val key="$routeGeneration|$cameraId|$category|main"
        return if(key in delivered) "already-announced-primary" else "awaiting-verified-approach"
    }
    fun previousDistance(approach:CameraApproach)=
        previousSeen[(approach.voiceGroupId?:approach.camera.id)+"|"+(approach.voiceGroupCategory?:approach.camera.category)]
    fun reason(approach:CameraApproach,prefs:CameraAlertPreferences):String = when {
        !prefs.categoryEnabled(approach.camera.category) -> "category-or-master-disabled"
        !prefs.coordinateCameraAlerts -> "coordinate-camera-alerts-disabled"
        !prefs.voice -> "camera-voice-disabled"
        approach.camera.isAverage && !approach.camera.verifiedZoneBoundary -> "verified-zone-entrance-exit-evidence-missing"
        !approach.directionConfirmed && !approach.genericSafetyEligible -> approach.matchReason
        approach.routeMetresAhead>CameraCoordinateCatalog867.PRIMARY_METRES -> "above-2000m-threshold"
        approach.routeMetresAhead<=0 && !approach.camera.isAverage -> "camera-passed-or-too-close"
        requested.any{it.startsWith("$routeGeneration|${approach.voiceGroupId?:approach.camera.id}|${approach.voiceGroupCategory?:approach.camera.category}|")} -> "awaiting-android-playback-start"
        else -> "primary-already-played-or-secondary-not-due"
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
