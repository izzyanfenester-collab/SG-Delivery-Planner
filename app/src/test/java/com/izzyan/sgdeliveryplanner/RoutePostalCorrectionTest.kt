package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class RoutePostalCorrectionTest {
    private val badText = """
        🔥 ORDER TELEKUNG 🔥
        Total: $65 (COD)
        Nama: Sharipa
        Alamat:
        Blk 303B #03-104 Anchorvale Link S'pore 731301
        WhatsApp: https://wa.me/+6590150338
        Item:
        SKY BLUE
    """.trimIndent()

    private fun imported() = requireNotNull(parseCustomerOrders(badText).orders.singleOrNull())

    @Test fun fixingOneFailedOrderOf50PreservesAllOther49OrdersAndIdentity() {
        val original = imported()
        val all = (1..49).map {
            CustomerOrder(postalCode="520220", customerName="Recipient $it", originalMessage="customer $it")
        } + original
        val correction = correctRoutePostalOrder(all, original.orderId,
            badText.replace("731301", "542303"))
        val updated = requireNotNull(correction.updatedOrders)
        assertNull(correction.error)
        assertEquals(50, updated.size)
        assertEquals(all.take(49), updated.take(49))
        assertEquals(all.map { it.orderId }, updated.map { it.orderId })
        assertEquals("542303", updated.last().postalCode)
        assertEquals("Sharipa", updated.last().customerName)
        assertEquals("+6590150338", updated.last().phoneNumber)
        assertEquals("COD", updated.last().paymentStatus)
        assertEquals("65.00", updated.last().parcelPrice?.setScale(2)?.toPlainString())
        assertTrue(updated.last().originalMessage!!.contains("SKY BLUE"))
        assertEquals(2, updated.map { it.postalCode }.distinct().size)
    }

    @Test fun correctingOneOfTwoSamePostalLeavesOtherCustomerAndPostalUntouched() {
        val a = imported()
        val b = a.copy(orderId="other-customer", customerName="Different recipient")
        val response = correctRoutePostalOrder(listOf(a,b),a.orderId,
            badText.replace("731301","542303"))
        val updated = requireNotNull(response.updatedOrders)
        assertEquals(listOf("542303","731301"),updated.map {it.postalCode})
        assertEquals(b,updated.last())
        assertEquals(a.orderId,updated.first().orderId)
    }

    @Test fun manualPostalOnlyEntryCanBeEditedWithoutWhatsAppMessage() {
        val manual = CustomerOrder(postalCode="731301")
        val updated = requireNotNull(correctRoutePostalOrder(listOf(manual),manual.orderId,"730541").updatedOrders)
        assertEquals("730541",updated.single().postalCode)
        assertEquals(manual.orderId,updated.single().orderId)
        assertNull(updated.single().originalMessage)
    }

    @Test fun invalidManualPostalLeavesDraftUntouched() {
        val manual = CustomerOrder(postalCode="731301")
        val response = correctRoutePostalOrder(listOf(manual),manual.orderId,"wrong 73130")
        assertNull(response.updatedOrders)
        assertTrue(response.error!!.contains("six-digit"))
        assertEquals("731301",manual.postalCode)
    }

    @Test fun wrongOrMultipleImportedTextsAreRejectedWithoutLosingOriginal() {
        val original = imported()
        for (bad in listOf("Name: Sharipa\nAddress: no postal",
            badText + "\n\n" + badText.replace("Sharipa","Someone else"))) {
            val response = correctRoutePostalOrder(listOf(original),original.orderId,bad)
            assertNull(response.updatedOrders)
            assertNotNull(response.error)
            assertEquals("731301",original.postalCode)
        }
    }

    @Test fun proofFileIdsAndOrderIdSurvivePostalAndWhatsAppCorrection() {
        val original = imported().copy(
            paymentProofFileId="payment-photo",
            proofFileId="doorstep-photo",
            proofUploadedAt="2026-10-13T10:19:00")
        val corrected = requireNotNull(correctRoutePostalOrder(
            listOf(original),original.orderId,badText.replace("731301","730541")).changedOrder)
        assertEquals("payment-photo",corrected.paymentProofFileId)
        assertEquals("doorstep-photo",corrected.proofFileId)
        assertEquals(original.proofUploadedAt,corrected.proofUploadedAt)
        assertEquals(original.orderId,corrected.orderId)
    }

    @Test fun importedWhatsAppCustomerCanCorrectOnlyPostalWhileKeepingAllFieldsAndProofs() {
        val original = imported().copy(
            proofFileId = "proof-id",
            paymentProofFileId = "payment-id",
            paymentProofUploadedAt = "2026-10-12T12:00:00"
        )
        val second = original.copy(orderId = "other", customerName = "Other")
        val edited = requireNotNull(correctRoutePostalOrder(
            listOf(original, second), original.orderId, "680573"
        ).updatedOrders)
        val fixed = edited.first()
        assertEquals("680573", fixed.postalCode)
        assertTrue(fixed.fullAddress.orEmpty().contains("680573"))
        assertFalse(fixed.fullAddress.orEmpty().contains("731301"))
        assertTrue(fixed.originalMessage.orEmpty().contains("680573"))
        assertFalse(fixed.originalMessage.orEmpty().contains("731301"))
        assertEquals(original.customerName, fixed.customerName)
        assertEquals(original.phoneNumber, fixed.phoneNumber)
        assertEquals(original.parcelPrice, fixed.parcelPrice)
        assertEquals(original.paymentStatus, fixed.paymentStatus)
        assertEquals(original.orderId, fixed.orderId)
        assertEquals(original.proofFileId, fixed.proofFileId)
        assertEquals(original.paymentProofFileId, fixed.paymentProofFileId)
        assertEquals(original.paymentProofUploadedAt, fixed.paymentProofUploadedAt)
        assertEquals(second, edited.last())
    }

    @Test fun staleOrderIdIsRejected() {
        val original = imported()
        val result = correctRoutePostalOrder(listOf(original),"wrong",badText)
        assertNull(result.updatedOrders)
        assertNotNull(result.error)
    }

    @Test fun structuredFailuresRetainPostalAndReasonWithoutInferringMissingDigit() {
        val exception = PostalResolutionException(listOf(
            PostalLookupFailure("731301","Postal code 731301 was not found. Check the code and try again."),
            PostalLookupFailure("520220","Mapping service timeout")
        ))
        assertEquals(2,exception.failures.size)
        assertEquals("731301",exception.failures[0].postalCode)
        assertEquals("520220",exception.failures[1].postalCode)
        assertTrue(exception.englishMessage.contains("2 postal code(s)"))
    }
}
