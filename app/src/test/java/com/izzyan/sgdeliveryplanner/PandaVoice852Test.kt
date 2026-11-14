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
            assertTrue(phrase("turn",side,137.0).parts.isEmpty())
            assertTrue(phrase("turn",side,173.0).parts.isEmpty())
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
        assertEquals("roundabout",phrase("roundabout","right",40.0,7).parts.first().clip)
        assertTrue(phrase("unknown","left",5.0).parts.isEmpty())
        assertTrue(phrase("arrive","straight",5.0).parts.isEmpty())
        assertEquals("arrive_200m",phrase("arrive","left",200.0).parts.first().clip)
    }
    @Test fun dynamicExitAndConsecutiveManeuversUseRealMetadata(){
        val cue=NavigationPanelCue(NavigationSign.EXIT_LEFT,"17")
        val p=PandaVoiceMapper.maneuver("off ramp","left","Take exit 17 onto Tampines Road","Tampines Road",cue,500.0,10f)
        assertEquals("exit_left_500m",p.parts[0].clip)
        assertEquals(1,p.parts.size) // Road and numbered exit stay on screen.
        val q=PandaVoiceMapper.maneuver("turn","left","Turn left",null,NavigationPanelCue(NavigationSign.TURN_LEFT),5.0,10f,
            PandaFollowing("turn","right","Turn right"),30.0)
        assertEquals("then_turn_right",q.parts.last().clip)
        assertEquals("turn_left",phrase("turn","left",5.0,text="Turn left then turn right").parts.first().clip)
    }
    @Test fun everyRecordedManeuverFamilyIsReachable(){
        val ids=mutableSetOf<String>()
        fun add(t:String,m:String,d:Double,exit:Int?=null){phrase(t,m,d,exit).parts.mapNotNull{it.clip}.forEach{ids+=it}}
        for(side in listOf("left","right")){
            for(d in listOf(5,50,100,200,300,500,800,1000,2000))add("turn",side,d.toDouble())
            for(d in listOf(5,200))add("keep",side,d.toDouble())
            add("turn","slight $side",5.0);add("turn","sharp $side",5.0)
            add("merge",side,5.0)
            for(d in listOf(5,200,500,1000))add("off ramp",side,d.toDouble())
            add("arrive",side,5.0)
            val a=PandaVoiceMapper.maneuver("turn","left","Turn left",null,NavigationPanelCue(NavigationSign.TURN_LEFT),5.0,10f,
                PandaFollowing("turn",side,"Turn $side"),30.0)
            ids+=a.parts.last().clip!!
            val k=PandaVoiceMapper.maneuver("turn","left","Turn left",null,NavigationPanelCue(NavigationSign.TURN_LEFT),5.0,10f,
                PandaFollowing("keep",side,"Keep $side"),30.0)
            ids+=k.parts.last().clip!!
        }
        add("continue","straight",5.0);add("continue","straight",1000.0);add("continue","straight",2000.0)
        add("turn","uturn",5.0);add("turn","uturn",200.0);add("merge","straight",5.0)
        add("roundabout","right",40.0)
        for(i in 1..6)add("roundabout","right",40.0,i)
        add("arrive","straight",200.0)
        ids+=listOf("start_navigation","wrong_turn","rerouting","new_route_ready","approaching_destination",
            "arrived","next_delivery","delivery_complete","gps_lost","gps_restored","route_unavailable")
        assertEquals("All 67 manifest instruction IDs have a supported mapping/event",67,ids.size)
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
    @Test fun unmatchedDistanceDoesNotConsumeVoiceGate(){
        val gate=PandaVoiceGate()
        assertFalse(gate.accept(2,phrase("turn","left",137.0),137.0,false,true))
        assertTrue(gate.accept(2,phrase("turn","left",100.0),100.0,false,true))
        assertTrue(gate.accept(2,phrase("turn","left",5.0),5.0,false,true))
    }
    @Test fun thresholdCrossingAndNoLegacyVoice(){
        val cue=NavigationPanelCue(NavigationSign.TURN_RIGHT)
        fun at(d:Double,prev:Double?)=PandaVoiceMapper.maneuver("turn","right","Turn right","Road",cue,d,15f,previousDistance=prev)
        assertTrue(at(373.0,400.0).parts.isEmpty())
        assertEquals("turn_right_300m",at(286.0,315.0).parts.single().clip)
        assertTrue(at(240.0,340.0).parts.isEmpty())
        assertEquals("turn_right_200m",at(184.0,208.0).parts.single().clip)
        assertEquals("turn_right",at(8.0,40.0).parts.single().clip)
        assertEquals(listOf(NavigationVoice.PANDA),NavigationVoice.entries.toList())
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
