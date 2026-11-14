package com.izzyan.sgdeliveryplanner

import kotlin.math.abs

/** Same scheduler is used by live SDK progress and the first maneuver of a replacement route.
 * Distance windows account for sampled GPS, not arbitrary nearest-distance rounding.
 * A large jump skips expired instructions. The actual junction still controls immediate cues. */
internal data class LivePandaCue(val phrase:VoicePhrase,val reason:String,val token:String)
internal class LivePandaScheduler {
    private var step = -1
    private var previous:Double?=null
    private val emitted=mutableSetOf<String>()
    private var lastAt=Long.MIN_VALUE
    private var immediateSent=false
    fun reset(){step=-1;previous=null;emitted.clear();lastAt=Long.MIN_VALUE;immediateSent=false}
    fun committed(cue:LivePandaCue,now:Long){
        emitted+=cue.token;lastAt=now
        if(cue.phrase.priority==VoicePriority.CRITICAL) immediateSent=true
    }
    fun evaluate(index:Int,type:String?,modifier:String?,text:String,road:String?,cue:NavigationPanelCue,
        distance:Double,speed:Float,now:Long,force:Boolean=false,sdkDue:Boolean=true,
        following:PandaFollowing?=null,nextLength:Double=Double.MAX_VALUE):LivePandaCue? {
        if(!distance.isFinite() || distance<0)return null
        if(index!=step){reset();step=index}
        val old=previous; previous=distance
        if(immediateSent)return null
        val safeSpeed=speed.takeIf{it.isFinite() && it>=0}?:0f
        val immediate=distance<=maxOf(9.0,safeSpeed*1.5).coerceAtMost(30.0)
        val base=PandaVoiceMapper.base(type,modifier,cue)
        val thresholds=when(base){
            "turn_left","turn_right"->listOf(2000,1000,800,500,300,200,100,50)
            "exit_left","exit_right"->listOf(1000,500,200)
            "keep_left","keep_right","u_turn","destination_left","destination_right","arrived"->listOf(200)
            "continue_straight"->listOf(2000,1000)
            else->emptyList()
        }
        val complex=Regex("(?i)\\b(?:then|followed by)\\b").containsMatchIn(text)
        // Allow the sampled fix to be just after threshold, proportional to speed
        // (up to about 1.5 seconds), capped at 12% of the spoken distance + 8m.
        fun tolerance(t:Int)=maxOf(6.0,safeSpeed*1.5).coerceAtMost(t*0.12+8.0)
        fun eligible(t:Int):Boolean = "distance-$t" !in emitted && when(t) {
            // Optional intermediate clips are useful if the earlier anchor was
            // skipped, not simply because GPS has reached another round number.
            800 -> "distance-1000" !in emitted
            300 -> "distance-500" !in emitted
            100 -> "distance-200" !in emitted
            else -> true
        }
        val crossed=thresholds.filter{t->
            eligible(t) &&
                distance<=t && distance>=t-tolerance(t) &&
                (old==null || old>t || old>=t-tolerance(t))
        }.minOrNull()
        // A newly calculated route may need an immediate accurate advance cue.
        // Ordinary progress waits until the threshold is reached.
        val starting=if(force) thresholds.filter{t->
            eligible(t) && abs(distance-t)<=tolerance(t)
        }.minByOrNull{abs(distance-it)} else null
        val threshold=crossed?:starting
        val round=base?.startsWith("roundabout")==true && distance<=80
        val token=when{
            immediate->"now"
            threshold!=null && !complex->"distance-$threshold"
            round && !complex->"roundabout-ahead"
            // Supported distance families wait for the next usable recording,
            // rather than spending a voice slot on incidental SDK TTS.
            thresholds.isNotEmpty() && !complex && !force->return null
            base!=null && thresholds.isEmpty() && !complex && !force->{
                // No advance clip exists. Give an accurate advance warning only
                // where needed; the actual immediate maneuver remains Panda.
                if(!sdkDue || distance>120 || distance<35 || "advance-essential" in emitted)return null
                "advance-essential"
            }
            !force && (!sdkDue || distance>500)->return null
            else->"advance-essential"
        }
        if(token in emitted)return null
        // Avoid announcing every distance in rapid succession; immediate cues
        // and a fresh route are never throttled. Retain 500/200/50 anchors.
        if(!immediate && !force && lastAt!=Long.MIN_VALUE && now-lastAt<3500)return null
        val phrase=PandaVoiceMapper.maneuver(type,modifier,text,road,cue,distance,safeSpeed,
            following,nextLength,threshold=threshold,live=true)
        val reason=when{
            phrase.parts.first().clip!=null && immediate->"immediate-recording"
            phrase.parts.first().clip!=null && threshold!=null->"threshold-crossed-$threshold"
            phrase.parts.first().clip!=null->"roundabout-recording"
            complex->"complex-maneuver"
            base==null->"unsupported-maneuver"
            force->"new-route-essential-distance"
            else->"essential-advance-no-recording"
        }
        return LivePandaCue(phrase,reason,token)
    }
}
