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
    val crossedThreshold:Boolean=false
)

internal class CameraVoicePlanner865 {
    private val delivered=mutableSetOf<String>() // Only confirmed MediaPlayer.start()
    private val requested=mutableSetOf<String>() // Accepted by controller, not yet audible
    private val startedAt=mutableMapOf<String,Long>()
    private val lastSeen=mutableMapOf<String,Double>()
    private val absentSince=mutableMapOf<String,Long>()
    private var routeGeneration=0L

    fun reset() {
        delivered.clear()
        requested.clear()
        startedAt.clear()
        lastSeen.clear()
        absentSince.clear()
        routeGeneration++
    }

    private fun prefix(cue:CameraVoiceCue865)=
        "$routeGeneration|${cue.cameraId}|${cue.category}|${cue.stage}"
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
        val visible=approaches.map { it.camera.id+"|"+it.camera.category }.toSet()
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
        if(!voiceGuidanceEnabled || !cameraAudioAvailable || !settings.voice ||
            !settings.categoryEnabled(cam.category)) return null
        if(!cam.isRedLight && !settings.coordinateCameraAlerts) return null
        if(approach.routeMetresAhead < 25.0 || !approach.routeMetresAhead.isFinite()) return null
        // Never interrupt an imminent real navigation maneuver.
        if(turnMetres != null && turnMetres.isFinite() && turnMetres < 160.0) return null
        // SPF camera coordinates do not prove average speed-zone entrance and exit geometry.
        if(cam.isAverage) return null

        val metres=approach.routeMetresAhead
        val key=cam.id+"|"+cam.category
        val previous=lastSeen.put(key,metres)
        if(!approach.directionConfirmed && approach.genericSafetyEligible) {
            // Deliberately do not claim a camera is enforcing this direction.
            // These original supplied recordings say only "watch traffic signal"
            // or "follow posted speed limit". One safety cue per approach.
            val token="$routeGeneration|$key|safety"
            if(token in delivered || token in requested) return null
            val safeClip=if(cam.isRedLight) "red_light_camera_near"
                else if(cam.category=="MOBILE_ENFORCEMENT_LOCATION") "mobile_speed_reminder"
                else "speed_limit_reminder"
            return CameraVoiceCue865(cam.id,cam.category,"safety",safeClip,metres,
                VoicePhrase("camera-$routeGeneration-${cam.id}-safety",VoicePriority.CAMERA,
                    listOf(VoicePart(safeClip,"General road-safety reminder; camera direction not verified"))),
                previousMetresAhead=previous,
                warningThresholdMetres=CameraCoordinateCatalog867.PRIMARY_METRES,
                crossedThreshold=previous!=null &&
                    previous>CameraCoordinateCatalog867.PRIMARY_METRES &&
                    metres<=CameraCoordinateCatalog867.PRIMARY_METRES)
        }
        if(!approach.directionConfirmed || !approach.alertAllowed) return null
        val main=if(cam.isRedLight)300.0 else CameraCoordinateCatalog867.PRIMARY_METRES
        val near=if(cam.isRedLight)100.0 else CameraCoordinateCatalog867.REMINDER_METRES
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
        val prior=previous
        val mainToken="$routeGeneration|$key|main"
        val nearToken="$routeGeneration|$key|near"
        // Threshold is inclusive and stateful; 650 -> 470 m triggers without exact 500 m.
        // A deferred attempt is allowed to retry later while still ahead.
        val crossedReminder=prior!=null && prior>near && metres<=near
        // If navigation began within 500 m, prioritize primary audio first; give
        // an optional secondary reminder only after advancing into the near-zone.
        val shortLateReminder=prior!=null && prior>minOf(near*0.4,200.0) &&
            metres<=minOf(near*0.4,200.0)
        val stageAndClip=when {
            metres<=main && mainToken !in delivered && mainToken !in requested -> "main" to mainClip
            settings.coordinateReminder && metres<=near && mainToken in delivered &&
                nearToken !in delivered && nearToken !in requested &&
                (crossedReminder || shortLateReminder) -> "near" to nearClip
            else->return null
        }
        val (stage,clip)=stageAndClip
        val priority=VoicePriority.CAMERA
        val verifiedLimit=if(stage=="main" && !cam.isRedLight)
            CameraSpeedLimitVoice865.phrase(cam)?.parts.orEmpty() else emptyList()
        return CameraVoiceCue865(cam.id,cam.category,stage,clip,metres,
            VoicePhrase("camera-$routeGeneration-${cam.id}-$stage",priority,
                listOf(VoicePart(clip,"Published camera warning"))+verifiedLimit),
            previousMetresAhead=prior,warningThresholdMetres=if(stage=="near")near else main,
            crossedThreshold=prior!=null && prior>(if(stage=="near")near else main) &&
                metres<=(if(stage=="near")near else main))
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
