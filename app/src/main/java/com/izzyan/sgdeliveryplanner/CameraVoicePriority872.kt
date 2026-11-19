package com.izzyan.sgdeliveryplanner

/** Detection always generates its event at 2KM; audio alone waits for a safe gap. */
internal object CameraVoicePriority872 {
    fun deferredReason(turnMetres:Double?,active:VoicePriority?,maneuver:String?=null):String? {
        if(active!=null && active.rank>=VoicePriority.CAMERA.rank)
            return "audio-busy-${active.name}"
        val straight=maneuver?.lowercase() in setOf("continue","new name","new_name","notification")
        if(!straight && turnMetres!=null && turnMetres.isFinite() && turnMetres<160)
            return "immediate-maneuver-priority"
        return null
    }
}
