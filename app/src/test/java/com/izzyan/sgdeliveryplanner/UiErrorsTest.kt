package com.izzyan.sgdeliveryplanner

import java.net.SocketTimeoutException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun localizedTimeoutMessageUsesEnglishRetryGuidance() {
        assertEquals("The mapping service took too long to respond. Please try again.",
            englishError(SocketTimeoutException("Masa tamat"), "Could not plan your route."))
    }
}
