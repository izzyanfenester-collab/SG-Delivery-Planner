package com.izzyan.sgdeliveryplanner

import java.net.SocketTimeoutException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class UiErrorsTest {
    @Test
    fun foreignServiceAndLibraryMessagesNeverReachTheInterface() {
        val foreignMessage = "Perkhidmatan tidak tersedia"
        val fallback = "Could not open this saved route. Please try again."

        assertEquals(fallback, englishError(IllegalStateException(foreignMessage), fallback))
        val response = Response.error<String>(503, foreignMessage.toResponseBody())
        val displayed = englishError(HttpException(response), fallback)
        assertFalse(displayed.contains(foreignMessage))
        assertEquals("The mapping service is temporarily unavailable. Please try again later.", displayed)
    }

    @Test
    fun actionableAppErrorsRetainPostalCodes() {
        val detail = "Postal code 012345 was not found. Check the code and try again."
        assertEquals(detail, englishError(PlannerError(detail), "Could not plan your route."))
    }

    @Test
    fun badRoutingServerHttp400NamesServerAndDoesNotAccuseAnyPostal() {
        val response = Response.error<String>(400, "provider-internal text".toResponseBody())
        val failure = routingFailure(
            "driving-time matrix", listOf("680573", "730521"), HttpException(response)
        )
        assertEquals("driving-time matrix", failure.stage)
        assertEquals(listOf("680573", "730521"), failure.verifiedPostals)
        assertTrue(failure.englishMessage.contains("HTTP 400"))
        assertTrue(failure.englishMessage.contains("OneMap-verified"))
        assertTrue(failure.englishMessage.contains("did not identify a wrong postal code"))
        assertFalse(failure.englishMessage.contains("provider-internal"))
        assertFalse(failure.englishMessage.contains("680573 was not found"))
    }

    @Test
    fun aSpecificOneMapMissingPostalKeepsItsExactPostalCode() {
        val failure = PostalResolutionException(listOf(
            PostalLookupFailure("680573",
                "680573: Postal code 680573 was not found. Check the code and try again.")
        ))
        assertEquals("680573", failure.failures.single().postalCode)
        assertTrue(failure.failures.single().reason.contains("was not found"))
        val road = RoadConnectionException("680573", "730521")
        assertTrue(road.englishMessage.contains("680573"))
        assertTrue(road.englishMessage.contains("730521"))
        assertTrue(road.englishMessage.contains("neither postal code is confirmed wrong"))
    }

    @Test
    fun localizedTimeoutMessageUsesEnglishRetryGuidance() {
        assertEquals("The mapping service took too long to respond. Please try again.",
            englishError(SocketTimeoutException("Masa tamat"), "Could not plan your route."))
    }
}
