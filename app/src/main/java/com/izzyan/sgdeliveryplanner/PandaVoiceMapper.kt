package com.izzyan.sgdeliveryplanner

import kotlin.math.abs
import java.util.Locale

internal enum class NavigationVoice(val label:String) { PANDA("Panda Warrior V2 – English") }
internal enum class VoicePriority(val rank:Int) { PREVIEW(0), CHARACTER(1), STATUS(2), ADVANCE(3), CRITICAL(4) }
internal data class VoicePart(val clip:String,val description:String)
internal data class VoicePhrase(val key:String,val priority:VoicePriority,val parts:List<VoicePart>)
internal data class PandaFollowing(val type:String?,val modifier:String?,val text:String?)
internal object PandaVoiceMapper {
    private fun norm(s:String?)=s.orEmpty().lowercase(Locale.ROOT).replace('_',' ').replace('-',' ').trim()
    private val distances= listOf(50 to "50m",100 to "100m",200 to "200m",300 to "300m",500 to "500m",800 to "800m",1000 to "1km",2000 to "2km")
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
    private fun distanceParts(m:Int):List<String> {
        if(m in standalone) return listOf("distance_in_"+if(m>=1000) "${m/1000}km" else "${m}m")
        if(m>=1000) {
            val whole=m/1000;val tenth=(m%1000)/100
            if(whole>100 || m%100!=0) return emptyList()
            val nums=numeric(whole);if(nums.isEmpty())return emptyList()
            return listOf("word_in")+nums+(if(tenth>0)listOf("word_point","number_$tenth")else emptyList())+listOf("unit_kilometres")
        }
        if(m>=100 && m%10==0 && m!=100) {
            val digits=m.toString().padStart(3,'0').trimEnd('0').map { "number_$it" }
            return listOf("word_in","number_0","word_point")+digits+listOf("unit_kilometres")
        }
        val nums=numeric(m);return if(nums.isNotEmpty() && m!=1) listOf("word_in")+nums+listOf("unit_metres") else emptyList()
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
    private fun threshold(d:Double,speed:Float,previous:Double?):Int? {
        val marks=if(speed>=20f) listOf(50000,40000,30000,25000,20000,15000,10000,9000,8000,7000,6000,5000,4000,3000,2000,1000,500,200)
                  else listOf(3000,2000,1000,500,300,200,100,50)
        val crossed=marks.firstOrNull { previous!=null && previous>=it && d<=it && it-d<=maxOf(8.0,speed.toDouble()*1.7, it*0.08) }
        if(crossed!=null)return crossed
        return marks.minByOrNull{abs(it-d)}?.takeIf{abs(it-d)<=maxOf(5.0,minOf(25.0,it*0.05))}
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
        val m=if(immediate)0 else threshold(d,speed,previousDistance) ?: if(force || previousDistance==null) {
            if(d>=1000) (kotlin.math.round(d/100)*100).toInt() else (kotlin.math.round(d/10)*10).toInt()
        } else -1
        val ids=if(m<0)emptyList()else composeAtDistance(type,modifier,instruction,road,cue,m)
        val parts=ids.map{VoicePart(it,instruction)}.toMutableList()
        if(immediate && parts.isNotEmpty() && following!=null && nextLength in 0.0..60.0){
            val next=base(following.type,following.modifier,NavigationPanelSigns.cue(following.type,following.modifier,following.text))
            if(next in setOf("turn_left","turn_right","keep_left","keep_right","exit_left","exit_right","u_turn"))parts+=VoicePart("then_$next","Then ${following.text}")
        }
        return VoicePhrase(if(parts.isEmpty())"await-recorded-cue" else "${ids.joinToString("+")}-$m",priority,parts)
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
    private var observed:Double?=null
    fun previousDistance(index:Int,distance:Double):Double? {
        if(index!=step){reset();step=index}
        val old=observed;observed=distance;return old
    }
    fun reset(){step=-1;advances=0;lastAdvanceDistance=Double.POSITIVE_INFINITY;immediate=false;keys.clear();observed=null}
    fun accept(index:Int,phrase:VoicePhrase,distance:Double,force:Boolean,sdkDue:Boolean):Boolean {
        if(index!=step){reset();step=index}
        if(phrase.parts.isEmpty())return false
        if(phrase.priority==VoicePriority.CRITICAL){
            if(immediate)return false
            immediate=true;return true
        }
        if(immediate || advances>=3 || phrase.key in keys || (advances>0 && distance>lastAdvanceDistance*0.65))return false
        if(!force && distance>50000)return false
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
