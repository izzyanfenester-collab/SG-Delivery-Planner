package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class PandaVoice852Test {
    private fun phrase(t:String,m:String,d:Double,exit:Int?=null,road:String?=null,text:String="Turn $m")=
        PandaVoiceMapper.maneuver(t,m,text,road,NavigationPanelSigns.cue(t,m,text,
            RouteSignMetadata(t,m,text,roundaboutExit=exit)),d,10f)
    @Test fun exactDistanceClipsForBothTurnsAndNoIncorrectDistance(){
        for(side in listOf("left","right")){
            for((meters,suffix) in listOf(50 to "50m",100 to "100m",200 to "200m",300 to "300m",500 to "500m",800 to "800m",1000 to "1km",2000 to "2km"))
                assertEquals("turn_${side}_$suffix",phrase("turn",side,meters.toDouble()).parts.first().clip)
            assertNull(phrase("turn",side,137.0).parts.first().clip)
            assertEquals("turn_$side",phrase("turn",side,5.0).parts.first().clip)
        }
    }
    @Test fun structuredManeuversAndRoundaboutOrdinals(){
        for(side in listOf("left","right")){
            assertEquals("keep_$side",phrase("fork",side,5.0).parts.first().clip)
            assertEquals("merge_$side",phrase("merge",side,5.0).parts.first().clip)
            assertEquals("exit_${side}_500m",phrase("off ramp",side,500.0).parts.first().clip)
            assertEquals("slight_$side",phrase("turn","slight $side",5.0).parts.first().clip)
            assertEquals("sharp_$side",phrase("turn","sharp $side",5.0).parts.first().clip)
            assertEquals("destination_$side",phrase("arrive",side,5.0).parts.first().clip)
        }
        for(exit in 1..6) assertEquals("roundabout_exit_$exit",phrase("roundabout","right",40.0,exit).parts.first().clip)
        assertNull(phrase("roundabout","right",40.0,7).parts.first().clip)
        assertNull(phrase("unknown","left",5.0).parts.first().clip)
        assertNull(phrase("arrive","straight",5.0).parts.first().clip)
        assertEquals("arrive_200m",phrase("arrive","left",200.0).parts.first().clip)
    }
    @Test fun dynamicExitAndConsecutiveManeuversUseRealMetadata(){
        val cue=NavigationPanelCue(NavigationSign.EXIT_LEFT,"17")
        val p=PandaVoiceMapper.maneuver("off ramp","left","Take exit 17 onto Tampines Road","Tampines Road",cue,500.0,10f)
        assertEquals("exit_left_500m",p.parts[0].clip)
        assertTrue(p.parts[1].fallback.contains("Exit 17"))
        assertTrue(p.parts[1].fallback.contains("Tampines Road"))
        val q=PandaVoiceMapper.maneuver("turn","left","Turn left",null,NavigationPanelCue(NavigationSign.TURN_LEFT),5.0,10f,
            PandaFollowing("turn","right","Turn right"),30.0)
        assertEquals("then_turn_right",q.parts.last().clip)
        assertNull(phrase("turn","left",5.0,text="Turn left then turn right").parts.first().clip)
    }
    @Test fun gateLimitsAdvanceAndResetsOnReroute(){
        val gate=PandaVoiceGate()
        assertTrue(gate.accept(1,phrase("turn","left",800.0),800.0,false,true))
        assertFalse(gate.accept(1,phrase("turn","left",500.0),500.0,false,true))
        assertTrue(gate.accept(1,phrase("turn","left",200.0),200.0,false,true))
        assertFalse(gate.accept(1,phrase("turn","left",100.0),100.0,false,true))
        assertTrue(gate.accept(1,phrase("turn","left",5.0),5.0,false,true))
        assertFalse(gate.accept(1,phrase("turn","left",5.0),5.0,false,true))
        gate.reset()
        assertTrue(gate.accept(1,phrase("turn","right",200.0),200.0,true,true))
    }
    @Test fun deliveryAnnouncementsOnlyAfterActualDeliveredTransition(){
        val plan=schedule(listOf(depot.copy(postal="001234"),depot.copy(postal="001235")),
            List(3){Leg(1.0,100.0,0.0)},LocalDateTime.of(2026,11,14,9,0),10,"Normal",emptyList())
        val first=reviewStop(plan,"DELIVERED","2026-11-14T09:10:00")
        assertEquals("next_delivery",deliveryVoiceEvent(plan,first,"DELIVERED"))
        val second=reviewStop(first,"DELIVERED","2026-11-14T09:20:00")
        assertEquals("delivery_complete",deliveryVoiceEvent(first,second,"DELIVERED"))
        val held=reviewStop(first,"ON_HOLD","2026-11-14T09:20:00","Other","test")
        assertNull(deliveryVoiceEvent(first,held,"ON_HOLD"))
    }
}
