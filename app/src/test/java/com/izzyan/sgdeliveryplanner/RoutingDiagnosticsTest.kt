package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RoutingDiagnosticsTest {
    private val stops=listOf(
        Place("738203",1.4464,103.7696,"","Woodlands","Woodlands Checkpoint"),
        Place("431011",1.32,103.89,"","Area","Blk 11"),
        Place("680573",1.39,103.75,"","Area","Blk 573"),
        Place("823168",1.4,103.92,"","Area","Blk 168")
    )
    private fun json(body:String)=JsonParser.parseString(body).asJsonObject

    @Test fun noSegmentPointsExactlyToFailedPostalAndNotUnrelatedOrders() {
        val result=routingProblem(json("""{"code":"NoSegment","message":"Could not find a matching segment for coordinate 2"}"""),stops,"distance table",400)
        assertTrue(result is RoutingPostalException)
        val failures=(result as RoutingPostalException).failures
        assertEquals(listOf("680573"),failures.map { it.postalCode })
        val display=formattedPostalFailures(failures)
        assertTrue(display.contains("680573"))
        assertFalse(display.contains("431011"))
        assertFalse(display.contains("823168"))
        assertTrue(display.contains("No nearby drivable road"))
    }

    @Test fun errorIndexZeroRefersToDepotRatherThanCustomerPostal() {
        val error=routingProblem(json("""{"code":"NoSegment","message":"Could not find a matching segment for coordinate 0"}"""),stops,"distance table",400)
        assertFalse(error is RoutingPostalException)
        assertTrue(error.message!!.contains("Start / End"))
        assertFalse(error.message!!.contains("680573"))
    }

    @Test fun missingCoordinateIndexDoesNotGuessInvalidPostal() {
        val error=routingProblem(json("""{"code":"NoSegment","message":"Could not find a matching segment"}"""),stops,"distance table",400)
        assertFalse(error is RoutingPostalException)
        assertTrue(error.message!!.contains("did not identify"))
        assertFalse(error.message!!.contains("680573"))
    }

    @Test fun tooBigExplainsServerLimitWithoutAccusingPostal() {
        val error=routingProblem(json("""{"code":"TooBig","message":"Request size not supported"}"""),stops,"distance table",400)
        assertFalse(error is RoutingPostalException)
        assertTrue(error.message!!.contains("TooBig"))
        assertTrue(error.message!!.contains("3 postal codes"))
        assertFalse(error.message!!.contains("680573"))
    }

    @Test fun failedDrivingPairDisplaysBothPostalsButDoesNotDeclareThemInvalid() {
        val error=noDrivingConnection(stops[1],1,stops[2],2)
        assertTrue(error.message!!.contains("431011"))
        assertTrue(error.message!!.contains("680573"))
        assertTrue(error.message!!.contains("not necessarily"))
    }

    @Test fun structuredHttp400IsDecodedForRoutingServer() {
        val body="""{"code":"NoSegment","message":"Could not find a matching segment for coordinate 2"}"""
        val error=HttpException(Response.error<Any>(400,body.toResponseBody("application/json".toMediaType())))
        val result=routingHttpFailure(error,stops,"distance table")
        assertEquals(listOf("680573"),(result as RoutingPostalException).failures.map { it.postalCode })
    }


    @Test fun genericNoRouteRunsRealDiagnosticAndIdentifiesOnlyBlockingPostal680573() = kotlinx.coroutines.runBlocking {
        val depot=stops[0]
        val routePlaces=listOf(depot,stops[1],stops[2],stops[3],depot)
        var probes=0
        val api=object : Api {
            override suspend fun get(url:String):com.google.gson.JsonObject {
                probes++
                // 680573 corresponds to longitude 103.75, latitude 1.39 in this example.
                val blocked=url.contains("103.75,1.39")
                return json(if(blocked) """{"code":"NoRoute"}""" else """{"code":"Ok"}""")
            }
        }
        val original=routingProblem(json("""{"code":"NoRoute","message":"No route found"}"""),
            routePlaces,"route map",400)
        val isolated=isolateNoRouteIfNeeded(original,api,"https://router.example",routePlaces)
        assertTrue(isolated is RoutingPostalException)
        val codes=(isolated as RoutingPostalException).failures.map { it.postalCode }
        assertEquals(listOf("680573"),codes)
        assertTrue(formattedPostalFailures(isolated.failures).contains("Postal code 680573:"))
        assertFalse(formattedPostalFailures(isolated.failures).contains("431011:"))
        assertTrue("Diagnostic should use bounded route calls",probes<=7)
    }

    @Test fun noRouteWithTwoBlockingStopsDoesNotGuessOnePostal() = kotlinx.coroutines.runBlocking {
        val depot=stops[0]
        val routePlaces=listOf(depot,stops[1],stops[2],stops[3],depot)
        val api=object:Api {
            override suspend fun get(url:String)=json(
                if(url.contains("103.75,1.39") || url.contains("103.92,1.4"))
                    """{"code":"NoRoute"}""" else """{"code":"Ok"}"""
            )
        }
        val original=routingProblem(json("""{"code":"NoRoute"}"""),routePlaces,"route map",400)
        val checked=isolateNoRouteIfNeeded(original,api,"https://router.example",routePlaces)
        assertSame("Ambiguous failure should retain safe generic error",original,checked)
    }

    @Test fun globalNoRouteOrServerOutageNeverMislabelsPostal() = kotlinx.coroutines.runBlocking {
        val depot=stops[0]
        val routePlaces=listOf(depot,stops[1],stops[2],stops[3],depot)
        val original=routingProblem(json("""{"code":"NoRoute"}"""),routePlaces,"route map",400)
        val alwaysFail=object:Api {
            override suspend fun get(url:String)=json("""{"code":"NoRoute"}""")
        }
        assertSame(original,isolateNoRouteIfNeeded(original,alwaysFail,"https://router.example",routePlaces))
        val outage=object:Api {
            override suspend fun get(url:String):com.google.gson.JsonObject {
                throw java.io.IOException("Network down")
            }
        }
        assertSame(original,isolateNoRouteIfNeeded(original,outage,"https://router.example",routePlaces))
    }

    @Test fun unstructuredHttp400ReportsServerIssueNotRandomPostal() {
        val error=HttpException(Response.error<Any>(400,"server says no".toResponseBody("text/plain".toMediaType())))
        val problem=routingHttpFailure(error,stops,"route map")
        assertTrue(problem.message!!.contains("HTTP 400"))
        assertTrue(problem.message!!.contains("routing server",ignoreCase=true))
        assertFalse(problem.message!!.contains("680573"))
    }

    @Test fun onemapFailureMessageShowsPostalAndReasonWithoutDuplicatingPrefix() {
        val text=formattedPostalFailures(listOf(
            PostalLookupFailure("680573","Postal code 680573 was not found."),
            PostalLookupFailure("471609","The mapping service took too long to respond.")
        ))
        assertTrue(text.contains("Postal code 680573: Postal code 680573 was not found."))
        assertTrue(text.contains("Postal code 471609:"))
    }
}
