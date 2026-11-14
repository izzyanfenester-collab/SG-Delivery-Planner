package com.izzyan.sgdeliveryplanner
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.navigation.core.routeprogress.RouteProgress
import org.maplibre.spatialk.geojson.Position

class LivePanda853Test {
    private fun eval(s:LivePandaScheduler,i:Int,d:Double,t:Long,side:String="left",force:Boolean=false, speed:Float=12f)=
        s.evaluate(i,"turn",side,"Turn $side",null,NavigationPanelSigns.cue("turn",side,"Turn $side"),d,speed,t,force)
    @Test fun sampledTraceCrossesThresholdsWithoutTtsOrLostSlots(){
        val s=LivePandaScheduler()
        val trace=listOf(550.0,511.0,491.0,420.0,315.0,275.0,211.0,190.0,155.0,111.0,79.0,57.0,43.0,29.0,10.0)
        val played=mutableListOf<String?>()
        trace.forEachIndexed{i,d->
            val t=i*4000L
            eval(s,1,d,t)?.let{played+=it.phrase.parts.first().clip;s.committed(it,t)}
        }
        assertEquals(listOf("turn_left_500m","turn_left_200m","turn_left_50m","turn_left"),played)
    }
    @Test fun rightTurnHighSpeedCrossingAndNoPrematureNow(){
        val s=LivePandaScheduler()
        assertNull(eval(s,2,520.0,0,"right",speed=25f))
        val a=eval(s,2,478.0,1000,"right",speed=25f)!!
        assertEquals("turn_right_500m",a.phrase.parts.first().clip);s.committed(a,1000)
        assertNull(eval(s,2,145.0,6000,"right",speed=25f))
        val b=eval(s,2,44.0,10000,"right",speed=25f)!!
        assertEquals("turn_right_50m",b.phrase.parts.first().clip);s.committed(b,10000)
        assertEquals("turn_right",eval(s,2,18.0,11000,"right",speed=25f)!!.phrase.parts.first().clip)
    }
    @Test fun rejectedAudioDoesNotConsumeThresholdAndNewRouteClearsHistory(){
        val s=LivePandaScheduler()
        val a=eval(s,1,500.0,0)!!
        assertEquals("turn_left_500m",a.phrase.parts.first().clip)
        val retry=eval(s,1,492.0,500)!!
        s.committed(retry,500)
        assertNull(eval(s,1,489.0,1000))
        s.reset()
        val b=eval(s,1,198.0,2000,"right",true)!!
        assertEquals("turn_right_200m",b.phrase.parts.first().clip)
        s.committed(b,2000)
        assertNull(eval(s,1,195.0,2500,"right"))
    }
    @Test fun realValhallaSdkProgressUsesActualManeuverMetadata(){
        val json=requireNotNull(javaClass.getResource("/navigation/valhalla-singapore.json")).readText()
        val route=parseNavigationRoute(json,GpsPoint(1.35,103.8),GpsPoint(1.36,103.81))
        val signs=NavigationPanelSigns.metadata(json)
        val steps=route.legs.single().steps
        val shape=decodeNavigationGeometry(route.geometry).map{Position(it.lon,it.lat)}
        val progress=RouteProgress(directionsRoute=route,legIndex=0,distanceRemaining=route.distance,
            currentStepPoints=shape,upcomingStepPoints=null,stepIndex=0,legDistanceRemaining=route.distance,
            stepDistanceRemaining=493.0,intersections=null,currentIntersection=null,upcomingIntersection=null,
            currentLegAnnotation=null,intersectionDistancesAlongStep=null)
        val next=progress.currentLegProgress.currentStepProgress.nextStep!!
        val type=next.maneuver.type?.toString();val mod=next.maneuver.modifier?.toString()
        val instruction=next.maneuver.instruction.orEmpty()
        val cue=NavigationPanelSigns.cue(type,mod,instruction,signs[1],signs[0])
        val s=LivePandaScheduler()
        val selected=s.evaluate(1,type,mod,instruction,next.name,cue,493.0,15f,0,force=true)!!
        assertTrue("actual next step=$type/$mod",selected.phrase.parts.first().clip?.startsWith("exit_")==true)
        println("Valhalla type=$type modifier=$mod clip=${selected.phrase.parts.first().clip} reason=${selected.reason}")
    }
    @Test fun dynamicRoadNotRepeatedAndUnknownFallbackIsExplicit(){
        val s=LivePandaScheduler()
        val a=s.evaluate(1,"turn","left","Turn left onto Orchard Road","Orchard Road",
            NavigationPanelCue(NavigationSign.TURN_LEFT),198.0,10f,0)!!
        assertEquals(1,a.phrase.parts.size);assertEquals("turn_left_200m",a.phrase.parts.first().clip)
        val b=s.evaluate(2,"unknown","left","Complex instruction",null,NavigationPanelCue(),100.0,10f,1000,force=true)!!
        assertNull(b.phrase.parts.first().clip);assertEquals("unsupported-maneuver",b.reason)
    }
}
