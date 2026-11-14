package com.izzyan.sgdeliveryplanner

import kotlin.math.abs
import java.util.Locale

internal enum class NavigationVoice(val label:String) { DEFAULT("Default Navigation Voice"), PANDA("Panda Warrior – English") }
internal enum class VoicePriority(val rank:Int) { PREVIEW(0), CHARACTER(1), STATUS(2), ADVANCE(3), CRITICAL(4) }
internal data class VoicePart(val clip:String?,val fallback:String)
internal data class VoicePhrase(val key:String,val priority:VoicePriority,val parts:List<VoicePart>)
internal data class PandaFollowing(val type:String?,val modifier:String?,val text:String?)
internal object PandaVoiceMapper {
    private fun norm(s:String?)=s.orEmpty().lowercase(Locale.ROOT).replace('_',' ').replace('-',' ').replace(Regex("\\s+")," ").trim()
    private val distances= listOf(50 to "50m",100 to "100m",200 to "200m",300 to "300m",500 to "500m",800 to "800m",1000 to "1km",2000 to "2km")
    internal fun base(type:String?,modifier:String?,cue:NavigationPanelCue):String? {
        val t=norm(type);val m=norm(modifier)
        // Unknown types are not inferred from the straight-arrow default.
        if(t.isBlank()) return null
        if("roundabout" in t || "rotary" in t) return cue.roundaboutExit?.let { if(it in 1..6) "roundabout_exit_$it" else null }
            ?: if(cue.roundaboutExit==null && t in setOf("roundabout","rotary","roundabout turn")) "roundabout" else null
        if(t in setOf("arrive","arrival")) return when(m){"left"->"destination_left";"right"->"destination_right";else->"arrived"}
        if("u turn" in m || m=="uturn" || "u turn" in t || t=="uturn") return "u_turn"
        if(t=="merge") return when(m){"left","slight left"->"merge_left";"right","slight right"->"merge_right";else->"merge"}
        if(t in setOf("off ramp","exit","exit ramp","motorway exit")) return when(cue.sign){
            NavigationSign.EXIT_LEFT->"exit_left";NavigationSign.EXIT_RIGHT->"exit_right";else->null}
        if(t in setOf("keep","keep left","keep right","fork")) return when(cue.sign){
            NavigationSign.KEEP_LEFT,NavigationSign.FORK_LEFT->"keep_left"
            NavigationSign.KEEP_RIGHT,NavigationSign.FORK_RIGHT->"keep_right";else->null}
        if(t in setOf("turn","continue","new name","end of road","notification","depart")) return when(m){
            "left"->"turn_left";"right"->"turn_right";"slight left"->"slight_left";"slight right"->"slight_right"
            "sharp left"->"sharp_left";"sharp right"->"sharp_right";"straight"->"continue_straight"
            else->if(t=="depart" || t=="continue") "continue_straight" else null}
        return null
    }
    private fun distanceId(base:String?,meters:Double):String? {
        val allowed=when(base){
            "turn_left","turn_right"->distances
            "exit_left","exit_right"->distances.filter{it.first in listOf(200,500,1000)}
            "keep_left","keep_right","u_turn","destination_left","destination_right","arrived"->distances.filter{it.first==200}
            "continue_straight"->distances.filter{it.first in listOf(1000,2000)}
            else->emptyList()
        }
        val near=allowed.minByOrNull{abs(it.first-meters)}?:return null
        // Narrow tolerance for sampled GPS distances, not nearest-clip rounding.
        if(abs(near.first-meters)>maxOf(5.0,near.first*0.06)) return null
        val prefix=when(base){"destination_left","destination_right","arrived"->"arrive";"continue_straight"->"continue";else->base}
        return "${prefix}_${near.second}"
    }
    fun maneuver(type:String?,modifier:String?,instruction:String,road:String?,cue:NavigationPanelCue,
        meters:Double,speed:Float,following:PandaFollowing?=null,nextLength:Double=Double.MAX_VALUE,
        threshold:Int?=null, live:Boolean=false):VoicePhrase {
        val d=meters.coerceAtLeast(0.0)
        val immediate=d<=maxOf(9.0,(speed*1.5).toDouble()).coerceAtMost(30.0)
        val base=base(type,modifier,cue)
        val complex=Regex("(?i)\\b(?:then|followed by)\\b").containsMatchIn(instruction)
        val exact=if(immediate) base else distanceId(base,threshold?.toDouble() ?: d)
        val raw=if(immediate) instruction else "In ${metricDistance(d)}, $instruction"
        // Roundabout ordinal phrase describes the actual exit, never a guessed one.
        val nearRound=base?.startsWith("roundabout")==true && d<=80
        val clip=if(complex) null else exact ?: if(nearRound) base else null
        // "Arrived" is reserved for confirmed arrival, not the approach.
        val selected=if(clip=="arrived") null else clip
        val priority=if(immediate) VoicePriority.CRITICAL else VoicePriority.ADVANCE
        val parts=mutableListOf(VoicePart(selected,raw))
        if(selected!=null) {
            val extra=buildList {
                cue.expresswayExit?.let{add("Exit $it.")}
                // For live Panda guidance the panel already shows ordinary road names.
                // Speak only essential exit identification / complex unsupported instructions.
                if(!live && !road.isNullOrBlank() && road.length<=120 && instruction.contains(road,ignoreCase=true) &&
                    base !in setOf("destination_left","destination_right","arrived"))
                    add("Road: $road.")
            }.joinToString(" ")
            if(extra.isNotBlank()) parts+=VoicePart(null,extra)
            // Only append a second verified maneuver for a closely spaced actual SDK step.
            if(immediate && following!=null && nextLength in 0.0..60.0) {
                val nextCue=NavigationPanelSigns.cue(following.type,following.modifier,following.text)
                val next=base(following.type,following.modifier,nextCue)
                if(next in setOf("turn_left","turn_right","keep_left","keep_right"))
                    parts+=VoicePart("then_$next","Then ${following.text?:next!!.replace('_',' ')}")
            }
        }
        val bucket=when{d>500->"far";d>100->"mid";else->"near"}
        return VoicePhrase(selected ?: if(immediate) "tts-now" else "tts-advance-$bucket",priority,parts)
    }
}
/** At most two advance cues per maneuver plus one immediate cue. A route
 * replacement/voice change resets the gate. No repeated GPS-tick announcements. */
internal class PandaVoiceGate {
    private var step=-1
    private var advances=0
    private var lastAdvanceDistance=Double.POSITIVE_INFINITY
    private var immediate=false
    private val keys=mutableSetOf<String>()
    fun reset(){step=-1;advances=0;lastAdvanceDistance=Double.POSITIVE_INFINITY;immediate=false;keys.clear()}
    fun accept(index:Int,phrase:VoicePhrase,distance:Double,force:Boolean,sdkDue:Boolean):Boolean {
        if(index!=step){reset();step=index}
        if(phrase.priority==VoicePriority.CRITICAL){
            if(immediate)return false
            immediate=true;return true
        }
        if(immediate || advances>=2 || phrase.key in keys || (advances>0 && distance>lastAdvanceDistance*0.5))return false
        val recorded=phrase.parts.firstOrNull()?.clip!=null
        // SDK timing is used for unsupported distance TTS. Do not speak on every tick.
        if(!force && !recorded && !sdkDue)return false
        if(!force && distance>2000)return false
        keys+=phrase.key;advances++;lastAdvanceDistance=distance;return true
    }
}
internal fun deliveryVoiceEvent(before:Plan,after:Plan,action:String):String? {
    if(action!="DELIVERED" || before.current !in before.stops.indices ||
        normalizedStatus(before.stops[before.current].status)=="DELIVERED") return null
    if(activeDeliveryPlan(after)==null && isRouteCompleted(after)) return "delivery_complete"
    if(activeDeliveryPlan(after)!=null && after.current!=before.current && after.current in after.stops.indices) return "next_delivery"
    return null
}
