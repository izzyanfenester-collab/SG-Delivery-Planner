package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class OfflineRoadRoutingTest {
    private val a = GpsPoint(1.3000,103.8000)
    private val b = GpsPoint(1.3000,103.8010)
    private val c = GpsPoint(1.3000,103.8020)
    private val d = GpsPoint(1.3010,103.8010)
    private fun graph(restrict: Boolean): OfflineRoadGraph {
        val links = Array(4) { mutableListOf<OfflineRoadArc>() }
        fun edge(from:Int,to:Int,way:Long) { links[from].add(OfflineRoadArc(to,110.0,12.0,way)) }
        edge(0,1,101)
        edge(1,0,101)
        edge(1,2,202)
        edge(2,1,202)
        edge(1,3,303)
        edge(3,1,303)
        edge(3,2,404)
        edge(2,3,404)
        val restrictions = if(restrict) listOf(OfflineTurnRestriction(1,101,202,false)) else emptyList()
        return OfflineRoadGraph(listOf(a,b,c,d),Array(4) { links[it].toList() },restrictions)
    }

    @Test fun respectsLegalTurnRestrictionsAndUsesRealRoadSegments() {
        val normal=graph(false).route(a,c)
        assertEquals(listOf(a,b,c), normal.points)
        assertEquals(220.0,normal.meters,.01)
        val restricted=graph(true).route(a,c)
        assertEquals(listOf(a,b,d,c), restricted.points)
        assertEquals(330.0,restricted.meters,.01)
        assertTrue(restricted.meters > normal.meters)
    }

    @Test fun carRoadCostsAreDirectedAndUnreachableDestinationsReject() {
        val graph=OfflineRoadGraph(listOf(a,b),
            arrayOf(listOf(OfflineRoadArc(1,110.0,12.0,5)),emptyList()))
        assertEquals(110.0, graph.route(a,b).meters,.01)
        try {graph.route(b,a);fail("Should reject illegal reverse direction")}
        catch(e:IllegalStateException){assertTrue(e.message!!.contains("No legal"))}
    }

    @Test fun offlinePlanPreservesStopsAndLastDeliveryEndWithoutOnlineApi() {
        val graph=graph(false)
        val start=StartLocation("CUSTOM",a.lat,a.lon,"Start")
        val stops=listOf(
            Place("120001",c.lat,c.lon,"1","Area","C"),
            Place("120002",d.lat,d.lon,"2","Area","D")
        )
        val p=buildOfflineDeliveryPlan(graph,stops,LocalDateTime.of(2026,11,8,8,0),
            10,"Normal",start,EndLocationSelection(true,null))
        assertEquals(2,p.stops.size)
        assertTrue(p.endAtLastDelivery)
        assertEquals(setOf("120001","120002"),p.stops.map {it.place.postal}.toSet())
        assertTrue(p.geometry.size>2)
        assertEquals(0.0,p.returnLeg.km,.0001)
        assertEquals(10,p.serviceMinutes)
    }

    @Test fun offlineDrivingInstructionsConvertToExistingSdkRouteWithoutHttp() {
        val route=offlineDrivingDirections(graph(false).route(a,c))
        assertEquals(1,route.legs.size)
        assertTrue(route.distance>100)
        assertTrue(route.legs.single().steps.size>=2)
        assertEquals(a,decodeNavigationGeometry(route.geometry).first())
        assertEquals(c,decodeNavigationGeometry(route.geometry).last())
    }

    @Test fun rejectsUnverifiedPackageManifestAndNonGithubDownloads() {
        val good="""{"schema":1,"country":"SG","version":"2026-11-08T00:00:00Z","package_url":"https://github.com/izzyanfenester-collab/SG-Delivery-Planner/releases/download/offline-sg-current/sg-road-graph.zip","sha256":"${"a".repeat(64)}","size_bytes":1024000}"""
        assertEquals(1024000L,parseOfflineRoadManifest(good).sizeBytes)
        try { parseOfflineRoadManifest(good.replace("https://github.com","https://evil.example"))
            fail("Untrusted host accepted")
        } catch (_:IllegalArgumentException) {}
    }
}
