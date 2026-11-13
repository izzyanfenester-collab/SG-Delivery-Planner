package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import com.google.gson.JsonParser

class InstantReroute851Test {
    private fun p(x:Double,y:Double)=GpsPoint(1.35+y/111195.0,103.8+x/111165.0)
    private fun fix(x:Double,y:Double,t:Long,b:Float=0f,s:Float=10f,a:Float=4f)=RerouteFix(p(x,y),a,s,b,t)
    @Test fun missedLeftAndRightReplayTriggersAtConfirmingFixWithoutTenSecondWait(){
        for(side in listOf(-1.0,1.0)){
            val shape=listOf(p(0.0,-100.0),p(0.0,0.0),p(side*100,0.0))
            val detector=InstantRerouteDetector()
            assertNull(detector.observe(fix(0.0,-15.0,0),shape))
            assertNull(detector.observe(fix(0.0,-4.0,500),shape))
            val start=System.nanoTime()
            val decision=detector.observe(fix(0.0,10.0,1000),shape)
            val ms=(System.nanoTime()-start)/1_000_000
            assertEquals("missed-junction",decision?.reason)
            assertEquals(1000L,decision?.confirmingFixMs)
            assertTrue("detector took ${ms}ms",ms<500)
            println("missed turn side=$side confirmingFix=1000 detectionComputeMs=$ms")
        }
    }
    @Test fun correctTurnStationaryDriftAndParallelRoadDoNotTrigger(){
        val shape=listOf(p(0.0,-100.0),p(0.0,0.0),p(-100.0,0.0))
        val d=InstantRerouteDetector()
        assertNull(d.observe(fix(0.0,-10.0,0),shape))
        assertNull(d.observe(fix(-12.0,0.0,500,270f),shape))
        assertNull(d.observe(fix(-24.0,0.0,1000,270f),shape))
        repeat(10){ assertNull(d.observe(fix(40.0,it.toDouble(),2000L+it*500,s=0f),shape)) }
        val straight=listOf(p(0.0,-100.0),p(0.0,300.0))
        d.reset()
        repeat(10){ assertNull(d.observe(fix(14.0,it*10.0,it*500L),straight)) }
        assertNull(d.observe(fix(70.0,100.0,6000,a=40f),straight))
    }
    @Test fun wrongExitAndWrongRoundaboutDepartureReplay(){
        // Approximate exit fork: route bends east; runner continues north.
        val exit=listOf(p(0.0,-100.0),p(0.0,0.0),p(35.0,35.0),p(90.0,50.0))
        val d=InstantRerouteDetector()
        assertNull(d.observe(fix(0.0,-10.0,0),exit))
        assertNull(d.observe(fix(0.0,5.0,500),exit))
        assertNotNull(d.observe(fix(0.0,20.0,1000),exit))
        val round=listOf(p(0.0,-40.0),p(0.0,0.0),p(14.0,14.0),p(28.0,0.0),p(28.0,-40.0))
        d.reset()
        assertNull(d.observe(fix(0.0,-10.0,0),round))
        assertNull(d.observe(fix(0.0,5.0,500),round))
        assertNotNull(d.observe(fix(0.0,20.0,1000),round))
    }
    @Test fun generationAndFreshRouteGuardRejectObsoleteResponses(){
        val gate=RerouteRequestGate()
        val a=gate.begin(fix(0.0,0.0,0))
        assertFalse(gate.shouldSupersede(fix(0.0,30.0,500)))
        assertTrue(gate.shouldSupersede(fix(20.0,0.0,1000,90f)))
        val b=gate.begin(fix(20.0,0.0,1000,90f))
        assertFalse(gate.isCurrent(a)); assertTrue(gate.isCurrent(b))
        assertFalse(routeFitsLatestFix(listOf(p(0.0,0.0),p(0.0,100.0)),fix(40.0,0.0,1500,90f)))
        assertTrue(routeFitsLatestFix(listOf(p(0.0,0.0),p(100.0,0.0)),fix(30.0,0.0,1500,90f)))
        gate.clear();assertFalse(gate.isCurrent(b))
    }
    @Test fun realHttpFixtureRerouteLatencyAndHeadingPreserveDestination()=runBlocking{
        MockWebServer().use{ server ->
            val fixture=requireNotNull(javaClass.getResource("/navigation/valhalla-singapore.json")).readText()
            server.enqueue(MockResponse().setBody(fixture))
            val start=System.nanoTime()
            val result=ValhallaClient(server.url("/route").toString()).routeResult(p(0.0,0.0),p(1000.0,1000.0),90f)
            val ms=(System.nanoTime()-start)/1_000_000
            assertTrue(result.shape.size>2);assertTrue(result.route.legs.single().steps.isNotEmpty())
            val request=server.takeRequest(1,TimeUnit.SECONDS)!!
            val loc=JsonParser.parseString(request.body.readUtf8()).asJsonObject["locations"].asJsonArray
            assertEquals(2,loc.size());assertEquals(90,loc[0].asJsonObject["heading"].asInt)
            assertEquals(p(1000.0,1000.0).lat,loc[1].asJsonObject["lat"].asDouble,0.0)
            println("local HTTP fixture route parse elapsedMs=$ms (not public server or device render)")
            assertEquals(1,server.requestCount)
        }
    }
}
