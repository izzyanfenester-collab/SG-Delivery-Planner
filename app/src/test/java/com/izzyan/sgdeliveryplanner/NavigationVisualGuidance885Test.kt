package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NavigationVisualGuidance885Test {
    private fun fixture() = requireNotNull(javaClass.getResource("/navigation/valhalla-singapore.json")).readText()
    private fun steps(root: JsonObject) = root["routes"].asJsonArray[0].asJsonObject["legs"].asJsonArray[0]
        .asJsonObject["steps"].asJsonArray
    private fun mutated(change: (JsonObject) -> Unit): String = JsonParser.parseString(fixture()).asJsonObject
        .apply(change).toString()
    private fun model(json: String = fixture()) = parseNavigationRoute(json, GpsPoint(1.35,103.80),
        GpsPoint(1.36,103.81)).let { it to VisualGuidanceRoute885.parse(json,it) }
    private fun lanes(root: JsonObject, index: Int = 1) = steps(root)[index].asJsonObject["intersections"]
        .asJsonArray[0].asJsonObject["lanes"].asJsonArray

    @Test fun actualSingaporeExitHighlightsBothLegalLanesIncludingNonPreferredLane() {
        val (route, model) = model()
        val state = model.state(route,1,180.0)
        val lanes = requireNotNull(state.lanes).lanes
        assertEquals(6,lanes.size)
        assertEquals(listOf(true,true,false,false,false,false),lanes.map { it.valid })
        assertEquals(setOf(LaneDirection885.LEFT),lanes[1].highlighted)
        assertEquals(2,state.then?.maneuverIndex)
        assertEquals(NavigationSign.FORK_LEFT,state.then?.cue?.sign)
    }
    @Test fun sharedLaneHighlightsOnlyExplicitBranchAndNeverReversesLaneOrder() {
        val (route, model) = model()
        val lanes = requireNotNull(model.state(route,2,150.0).lanes).lanes
        assertEquals(listOf(LaneDirection885.LEFT,LaneDirection885.RIGHT),lanes[1].directions)
        assertEquals(setOf(LaneDirection885.LEFT),lanes[1].highlighted)
        assertTrue(lanes.all { it.valid })
    }
    @Test fun newlyRecordedCityRouteSuppliesRealRightLeftAndStraightLaneBranches() {
        val json=requireNotNull(javaClass.getResource("/navigation/valhalla-city-885.json")).readText()
        val (route,model)=model(json)
        val right=model.state(route,2,60.0)
        assertEquals(listOf(false,true),right.lanes?.lanes?.map { it.valid })
        assertEquals(setOf(LaneDirection885.RIGHT),right.lanes?.lanes?.get(1)?.highlighted)
        assertEquals(NavigationSign.TURN_LEFT,right.then?.cue?.sign)
        assertEquals(listOf(false,false,false,true,true),model.state(route,6,100.0).lanes?.lanes?.map { it.valid })
        // At this fork Valhalla explicitly marks the straight branch for the right fork.
        // Trust those actual markings, rather than manufacturing a right arrow from the turn.
        assertEquals(setOf(LaneDirection885.STRAIGHT),model.state(route,8,100.0).lanes?.lanes?.get(1)?.highlighted)
    }
    @Test fun laterIntersectionsCannotSupplyLanesForAnUnmappedManeuver() {
        val (route, model) = model()
        assertNull(model.state(route,4,100.0).lanes)
        assertNull(model.state(route,6,100.0).lanes)
        assertTrue(model.missingLaneReasons.containsKey(4))
    }
    @Test fun unknownMarkingHidesEntireStripWithoutDroppingAnyPhysicalLane() {
        val json = mutated { lanes(it)[2].asJsonObject.add("indications",JsonParser.parseString("[\"bus-only\"]")) }
        val (route,model) = model(json)
        assertNull(model.state(route,1,100.0).lanes)
        assertNotNull(model.state(route,1,100.0).then)
    }
    @Test fun missingOrStringValidityCannotBeInterpretedAsPermission() {
        for (stringValue in listOf(true,false)) {
            val json = mutated {
                val lane = lanes(it)[0].asJsonObject
                if (stringValue) lane.addProperty("valid","true") else lane.remove("valid")
            }
            val (route, model) = model(json)
            assertNull(model.state(route,1,100.0).lanes)
        }
    }
    @Test fun activeFlagNeverOverridesExplicitlyInvalidLane() {
        val json = mutated { lanes(it)[2].asJsonObject.addProperty("active",true) }
        val (route,model) = model(json)
        val lane = requireNotNull(model.state(route,1,100.0).lanes).lanes[2]
        assertFalse(lane.valid);assertTrue(lane.highlighted.isEmpty())
    }
    @Test fun mismatchedJunctionCoordinatesAndImpossibleBranchAreRejected() {
        val mismatch = mutated { steps(it)[1].asJsonObject["intersections"].asJsonArray[0].asJsonObject
            .add("location",JsonParser.parseString("[103.9,1.3]")) }
        val wrongBranch = mutated { lanes(it)[0].asJsonObject.addProperty("valid_indication","right") }
        for (json in listOf(mismatch,wrongBranch)) {
            val (route,model) = model(json)
            assertNull(model.state(route,1,100.0).lanes)
        }
    }
    @Test fun thenUsesFollowingStepAndDisappearsForDistantOrMissingFollowingTurn() {
        val (route,model) = model()
        assertEquals(2,model.state(route,1,100.0).then?.maneuverIndex)
        assertNull(model.state(route,2,100.0).then)
        assertNull(model.state(route,6,100.0).then)
        assertNull(model.state(route,7,0.0).then)
        assertNull(model.state(route,1,900.0).then)
        assertNull(model.state(route,1,1500.0).lanes)
    }
    @Test fun thenSkipsNotificationsAndRoadNameChangesAndAccumulatesTheirDistances() {
        val json = mutated {
            steps(it)[1].asJsonObject.addProperty("distance",100)
            steps(it)[2].asJsonObject.addProperty("distance",100)
            steps(it)[2].asJsonObject["maneuver"].asJsonObject.addProperty("type","new name")
        }
        val (route,model) = model(json)
        assertEquals(3,model.state(route,1,100.0).then?.maneuverIndex)
        assertEquals(NavigationSign.FORK_RIGHT,model.state(route,1,100.0).then?.cue?.sign)
        val longJson = mutated {
            steps(it)[1].asJsonObject.addProperty("distance",300)
            steps(it)[2].asJsonObject.addProperty("distance",300)
            steps(it)[2].asJsonObject["maneuver"].asJsonObject.addProperty("type","notification")
        }
        val (longRoute,longModel) = model(longJson)
        assertNull(longModel.state(longRoute,1,100.0).then)
    }
    @Test fun thenCanShowDifferentConsecutiveJunctionsWithTheSameDirection() {
        val json = mutated {
            steps(it)[1].asJsonObject.addProperty("distance",80)
            steps(it)[2].asJsonObject["maneuver"].asJsonObject.apply {
                addProperty("type","turn");addProperty("modifier","left")
            }
        }
        val (route,model) = model(json)
        assertEquals(2,model.state(route,1,100.0).then?.maneuverIndex)
        assertEquals(NavigationSign.TURN_LEFT,model.state(route,1,100.0).then?.cue?.sign)
    }
    @Test fun arrivalOffRouteRouteReplacementAndInvalidDistanceClearBothDisplays() {
        val (route,model) = model()
        val empty = VisualGuidanceState885()
        assertEquals(empty,model.state(route,1,100.0,offRoute=true))
        assertEquals(empty,model.state(route,1,100.0,arrived=true))
        assertEquals(empty,model.state(route.copy(distance=route.distance+1.0),1,100.0))
        assertNotNull("SDK may copy an otherwise identical route",model.state(route.copy(),1,100.0).lanes)
        assertEquals(empty,model.state(null,1,100.0))
        for (distance in listOf(null,Double.NaN,-1.0,Double.POSITIVE_INFINITY))
            assertEquals(empty,model.state(route,1,distance))
        assertEquals(empty,model.state(route,999,100.0))
    }
    @Test fun progressAdvancesToItsOwnJunctionAndSharedLaneMetadata() {
        val (route,model) = model()
        val first = model.state(route,1,100.0)
        val next = model.state(route,2,200.0)
        assertEquals(1,first.lanes?.maneuverIndex);assertEquals(2,next.lanes?.maneuverIndex)
        assertEquals(6,first.lanes?.lanes?.size);assertEquals(2,next.lanes?.lanes?.size)
        assertNull(next.then)
        assertNull(model.state(route,4,100.0).lanes)
    }
    @Test fun malformedVisualMetadataNeverBreaksNormalRouteOrInventsLanes() {
        val (route,_) = model()
        val malformed = VisualGuidanceRoute885.parse("{broken",route)
        assertNull(malformed.state(route,1,100.0).lanes)
        assertNotNull(malformed.state(route,1,100.0).then)
        assertEquals(10180.688,route.distance,.001)
    }
    @Test fun requestIncludesLanesAndLoadedMetadataWorksOfflineWithoutMoreRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture()))
            val result = ValhallaClient(server.url("/route").toString())
                .routeResult(GpsPoint(1.35,103.80),GpsPoint(1.36,103.81))
            val request = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertTrue(request["turn_lanes"].asBoolean)
            assertEquals(2,request["locations"].asJsonArray.size())
            repeat(100) { assertNotNull(result.visualGuidance.state(result.route,1,180.0).lanes) }
            assertEquals(1,server.requestCount)
            assertEquals(10180.688,result.route.distance,.001)
        }
    }
}
