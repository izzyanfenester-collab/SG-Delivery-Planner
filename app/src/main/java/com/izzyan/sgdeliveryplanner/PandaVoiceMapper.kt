package com.izzyan.sgdeliveryplanner

import java.util.Locale

internal enum class NavigationVoice(val label:String) { PANDA("Panda Warrior V2 – English") }
internal enum class VoicePriority(val rank:Int) { PREVIEW(0), CHARACTER(1), STATUS(2), CAMERA(3), ADVANCE(4), CRITICAL(5) }
internal data class VoicePart(val clip:String,val description:String)
internal data class VoicePhrase(val key:String,val priority:VoicePriority,val parts:List<VoicePart>)
internal data class PandaFollowing(val type:String?,val modifier:String?,val text:String?)
internal object PandaVoiceMapper {
    private fun norm(s:String?)=s.orEmpty().lowercase(Locale.ROOT).replace('_',' ').replace('-',' ').trim()
    private fun base(type:String?,modifier:String?,cue:NavigationPanelCue):String? {
        val t=norm(type);val m=norm(modifier)
        // Unknown types are not inferred from the straight-arrow default.
        // Use explicit panel metadata when the SDK omits the modifier.
        fun fromSign():String?=when(cue.sign){
            NavigationSign.TURN_LEFT->"turn_left";NavigationSign.TURN_RIGHT->"turn_right"
            NavigationSign.SLIGHT_LEFT->"slight_left";NavigationSign.SLIGHT_RIGHT->"slight_right"
            NavigationSign.SHARP_LEFT->"sharp_left";NavigationSign.SHARP_RIGHT->"sharp_right"
            NavigationSign.KEEP_LEFT->"keep_left";NavigationSign.FORK_LEFT->"fork_keep_left"
            NavigationSign.KEEP_RIGHT->"keep_right";NavigationSign.FORK_RIGHT->"fork_keep_right"
            NavigationSign.MERGE_LEFT->"merge_left"
            NavigationSign.RAMP_LEFT->"keep_left"
            NavigationSign.MERGE_RIGHT->"merge_right"
            NavigationSign.RAMP_RIGHT->"keep_right"
            NavigationSign.EXIT_LEFT->"exit_left";NavigationSign.EXIT_RIGHT->"exit_right"
            NavigationSign.UTURN->"u_turn"
            else->null
        }
        if(t.isBlank()) return fromSign()
        if(t in setOf("on ramp","ramp","enter motorway","enter highway")) return fromSign()
        if(m.isBlank() && t in setOf("turn","continue","new name","end of road","notification")) return fromSign()
        if("roundabout" in t || "rotary" in t) return cue.roundaboutExit?.let { if(it in 1..10) "roundabout_exit_$it" else "roundabout" }
            ?: if(cue.roundaboutExit==null && t in setOf("roundabout","rotary","roundabout turn")) "roundabout" else null
        if(t in setOf("arrive","arrival")) return when(m){"left"->"destination_left";"right"->"destination_right";else->"arrived"}
        if("u turn" in m || m=="uturn" || "u turn" in t || t=="uturn") return "u_turn"
        if(t=="merge") return when(m){"left","slight left"->"merge_left";"right","slight right"->"merge_right";else->"merge"}
        if(t in setOf("off ramp","exit","exit ramp","motorway exit")) return when(cue.sign){
            NavigationSign.EXIT_LEFT->"exit_left";NavigationSign.EXIT_RIGHT->"exit_right";else->"take_exit"}
        if(t in setOf("keep","keep left","keep right","fork")) return when(cue.sign){
            NavigationSign.KEEP_LEFT->"keep_left";NavigationSign.FORK_LEFT->"fork_keep_left"
            NavigationSign.KEEP_RIGHT->"keep_right";NavigationSign.FORK_RIGHT->"fork_keep_right";else->null}
        if(t in setOf("turn","continue","new name","end of road","notification","depart")) return when(m){
            "left"->"turn_left";"right"->"turn_right";"slight left"->"slight_left";"slight right"->"slight_right"
            "sharp left"->"sharp_left";"sharp right"->"sharp_right";"straight"->"continue_straight"
            else->if(t=="depart" || t=="continue") "continue_straight" else null}
        return null
    }
    private val standalone = setOf(10,20,30,40,60,70,80,90,150,250,400,600,700,900,1000,2000,3000,4000,5000,6000,7000,8000,9000,10000,15000,20000,25000,30000,40000,50000)
    private fun numeric(n:Int):List<String> = when {
        n in 0..20 || n in setOf(30,40,50,60,70,80,90,100) -> listOf("number_$n")
        n in 21..99 -> numeric(n/10*10)+numeric(n%10)
        else -> emptyList()
    }
    /** Spoken distances are ALWAYS integer km or integer metres. The exact
     * route distance is never rounded or changed. No word_point or decimal
     * kilometre clips are constructed anywhere in navigation.
     */
    private fun distanceParts(m:Int):List<String> {
        if(m<=0) return emptyList()
        if(m>=1000) {
            if(m%1000!=0 || m>100_000) return emptyList()
            if(m in standalone) return listOf("distance_in_${m/1000}km")
            val integerKm=m/1000
            val numbers=numeric(integerKm)
            return if(numbers.isNotEmpty()) listOf("word_in")+numbers+listOf("unit_kilometres")
                else emptyList()
        }
        if(m in standalone) return listOf("distance_in_${m}m")
        // Original number_100 recording says "One hundred", NOT "hundred".
        // Combining "number_8"+"number_100" would wrongly speak
        // "Eight one hundred metres". Only use exact recordings.
        // Other hundred-metre marks are provided by combined direction clips
        // (e.g. turn_right_800m, exit_left_500m) or independent
        // distance_in_*m recordings already present in the original pack.
        if(m==100) return listOf("word_in","number_100","unit_metres")
        if(m in 200..900 && m%100==0) return emptyList()
        val numbers=numeric(m)
        return if(numbers.isNotEmpty() && m!=1) listOf("word_in")+numbers+listOf("unit_metres")
            else emptyList()
    }
    private fun full(base:String,m:Int):String? {
        val suffix=if(m>=1000 && m%1000==0) "${m/1000}km" else "${m}m"
        return when {
            base in setOf("turn_left","turn_right") && m in setOf(50,100,200,300,500,800,1000,2000)->"${base}_$suffix"
            base in setOf("exit_left","exit_right") && m in setOf(200,500,1000)->"${base}_$suffix"
            base in setOf("keep_left","keep_right","u_turn") && m==200->"${base}_200m"
            base=="continue_straight" && m in setOf(1000,2000)->"continue_$suffix"
            else->null
        }
    }
    private fun exitParts(value:String?):List<String> {
        val match=Regex("^([0-9]{1,3})([A-D]?)$").matchEntire(value.orEmpty())?:return emptyList()
        val num=match.groupValues[1].toIntOrNull()?:return emptyList()
        val nums=numeric(num);if(nums.isEmpty())return emptyList()
        return listOf("take_exit_number")+nums+match.groupValues[2].lowercase(Locale.ROOT).takeIf{it.isNotEmpty()}?.let{listOf("letter_$it")}.orEmpty()
    }
    private fun roadClip(road:String?):String? {
        val key=norm(road)
        return mapOf("pie" to "pie","pan island expressway" to "pie","bke" to "bke","bukit timah expressway" to "bke","kje" to "kje","kranji expressway" to "kje","sle" to "sle","seletar expressway" to "sle","tpe" to "tpe","tampines expressway" to "tpe","cte" to "cte","central expressway" to "cte","ecp" to "ecp","east coast parkway" to "ecp","aye" to "aye","ayer rajah expressway" to "aye","kpe" to "kpe","kallang paya lebar expressway" to "kpe","mce" to "mce","marina coastal expressway" to "mce")[key]?.let{"road_$it"}
    }
    fun composeAtDistance(type:String?,modifier:String?,instruction:String,road:String?,cue:NavigationPanelCue,metres:Int):List<String> {
        val t=norm(type)
        val b=base(type,modifier,cue)
        val text=norm(instruction)
        if(b in setOf("arrived","destination_left","destination_right")) return if(metres==200)listOf("arrive_200m")else emptyList()
        val exit=if(t in setOf("off ramp","exit","exit ramp","motorway exit"))exitParts(cue.expresswayExit)else emptyList()
        val direction=if(exit.isNotEmpty())exit else listOfNotNull(b)
        val explicit=when {
            text.startsWith("follow the signs towards woodlands checkpoint")->"follow_woodlands"
            text.startsWith("follow the signs towards tuas checkpoint")->"follow_tuas"
            text.startsWith("proceed towards singapore")->"proceed_singapore"
            text.startsWith("proceed towards johor bahru")->"proceed_johor_bahru"
            text.startsWith("continue onto the expressway")->"continue_onto_expressway"
            text.startsWith("stay on the main road")->"stay_main_road"
            text.startsWith("take the next exit")->"take_next_exit"
            text.startsWith("take the next left")->"take_next_left"
            text.startsWith("take the next right")->"take_next_right"
            text.startsWith("enter the roundabout")->"enter_roundabout"
            text.startsWith("go straight through the roundabout")->"roundabout_straight"
            text in setOf("take the left lane","use the left lane")->"lane_left"
            text in setOf("take the right lane","use the right lane")->"lane_right"
            text=="use the middle lane"->"lane_middle"
            else->null
        }
        val command=if(explicit!=null)listOf(explicit)else direction
        if(command.isEmpty())return emptyList()
        val roadId=roadClip(road)
        val roadTail=if(roadId!=null && text.contains("onto") && b in setOf("turn_left","turn_right","keep_left","keep_right","fork_keep_left","fork_keep_right","continue_straight"))listOf("word_onto",roadId)else emptyList()
        if(metres<=0)return command+roadTail
        if(exit.isEmpty()&&explicit==null&&roadTail.isEmpty()&&b!=null)full(b,metres)?.let{return listOf(it)}
        val prefix=distanceParts(metres)
        if(prefix.isEmpty())return emptyList()
        return prefix+command+roadTail
    }
    fun maneuver(type:String?,modifier:String?,instruction:String,road:String?,cue:NavigationPanelCue,
        meters:Double,speed:Float,following:PandaFollowing?=null,nextLength:Double=Double.MAX_VALUE,previousDistance:Double?=null,
        force:Boolean=false):VoicePhrase {
        val d=meters.coerceAtLeast(0.0)
        val immediate=d<=maxOf(9.0,(speed*1.5).toDouble()).coerceAtMost(30.0)
        val priority=if(immediate)VoicePriority.CRITICAL else VoicePriority.ADVANCE
        val m=if(immediate) 0 else
            WholeDistanceVoiceScheduler.checkpoint(d,speed,previousDistance,force) ?: -1
        val ids=if(m<0)emptyList()else composeAtDistance(type,modifier,instruction,road,cue,m)
        val parts=ids.map{VoicePart(it,instruction)}.toMutableList()
        if(immediate && parts.isNotEmpty() && following!=null && nextLength in 0.0..60.0){
            val next=base(following.type,following.modifier,NavigationPanelSigns.cue(following.type,following.modifier,following.text))
            if(next in setOf("turn_left","turn_right","keep_left","keep_right","exit_left","exit_right","u_turn"))parts+=VoicePart("then_$next","Then ${following.text}")
        }
        return VoicePhrase(if(parts.isEmpty())"await-recorded-cue" else "${ids.joinToString("+")}-$m",priority,parts)
    }

}
/** One far, one medium, one near and one immediate cue per actual maneuver.
 * Reserve a near cue even when the first announcement is many kilometres away. */
internal class PandaVoiceGate {
    private var step=-1
    private val stages=mutableSetOf<Int>()
    private var observed:Double?=null
    fun previousDistance(index:Int,distance:Double):Double? {
        if(index!=step){reset();step=index}
        val old=observed;observed=distance;return old
    }
    fun reset(){step=-1;stages.clear();observed=null}
    private fun stage(phrase:VoicePhrase,distance:Double):Int =
        if(phrase.priority==VoicePriority.CRITICAL)0 else if(distance<=250)1 else if(distance<=1500)2 else 3
    fun eligible(index:Int,phrase:VoicePhrase,distance:Double):Boolean {
        if(index!=step){reset();step=index}
        if(phrase.parts.isEmpty() || distance>50000 || 0 in stages)return false
        val s=stage(phrase,distance)
        return s !in stages && stages.none{it<s}
    }
    fun mark(index:Int,phrase:VoicePhrase,distance:Double){
        if(index!=step){reset();step=index}
        stages+=stage(phrase,distance)
    }
}
internal fun deliveryVoiceEvent(before:Plan,after:Plan,action:String):String? {
    if(action!="DELIVERED" || before.current !in before.stops.indices ||
        normalizedStatus(before.stops[before.current].status)=="DELIVERED") return null
    if(activeDeliveryPlan(after)==null && isRouteCompleted(after)) return "delivery_complete"
    if(activeDeliveryPlan(after)!=null && after.current!=before.current && after.current in after.stops.indices) return "next_delivery"
    return null
}
