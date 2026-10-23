package com.izzyan.sgdeliveryplanner

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeliveryWhatsAppDraftTest {
    private val sampleMessage = """
        TT.Co 💍 INVOICE: 6/10/26

        Total: $75  (COD)

        Nama: Jaliah
        FB: Jaliah Awang

        Alamat:
        Block 573, Chua Chu Kang Street 52, #05-276 S 680572

        No.telefon:
        https://wa.me/+6597298524

        Order:
        BLOUSE POLKADOT: BT
        SIZE 5XL
        1. BLACK
        2. WHITE
        PALAZO: BT
        SIZE 7XL
        1. BLUE
        2. CREAM
    """.trimIndent()

    private fun stop(
        eta: String? = "2026-10-13T19:10:00",
        original: String? = sampleMessage,
        price: BigDecimal? = BigDecimal("75.00"),
        arrival: String = "2026-10-13T19:21:00",
        customer: String = "Jaliah",
    ): Stop = Stop(
        place = Place("680572", 1.39, 103.74, "573", "Chua Chu Kang", "Block 573 Chua Chu Kang Street 52"),
        leg = Leg(2.0, 350.0, 40.0),
        arrival = arrival,
        leave = "2026-10-13T19:29:00",
        cumulativeKm = 2.0,
        etaArrival = eta,
        order = CustomerOrder(
            postalCode = "680572",
            customerName = customer,
            phoneNumber = "+6597298524",
            parcelPrice = price,
            paymentStatus = "COD",
            originalMessage = original
        )
    )

    @Test fun updatedEtaAndActualPriceUseExactRequestedWhatsAppTemplate() {
        val message = deliveryWhatsAppMessageDraft(stop())!!
        assertTrue(message.startsWith("Hi! I’ll be delivering your COD parcel today.\n\n*COD Amount: S$75.00*\n*ETA : 7.10pm - 7.20pm*\n\nOrder Details :\n=================================\n"))
        val expected = buildString {
            appendLine("Hi! I’ll be delivering your COD parcel today.")
            appendLine()
            appendLine("*COD Amount: S$75.00*")
            appendLine("*ETA : 7.10pm - 7.20pm*")
            appendLine()
            appendLine("Order Details :")
            appendLine("=================================")
            appendLine(sampleMessage)
            appendLine("=================================")
            appendLine("To make the delivery process easier and faster, please have the exact cash ready.")
            appendLine()
            append("Thank you for your purchase with us! 🙏 We truly appreciate your support. We hope to serve you again for your next order. 😊")
        }
        assertEquals(expected, message)
        assertTrue(message.contains(sampleMessage))
        assertTrue(message.contains("No.telefon:\nhttps://wa.me/+6597298524"))
        assertTrue(message.contains("\n=================================\nTo make the delivery process easier and faster, please have the exact cash ready.\n\n"))
        assertTrue(message.endsWith("Thank you for your purchase with us! 🙏 We truly appreciate your support. We hope to serve you again for your next order. 😊"))
        assertFalse(message.contains("ETA : 7.21pm"))
        val intent = customerWhatsAppIntent("+6597298524", WhatsAppChoice.PERSONAL, message)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(message, intent.data!!.getQueryParameter("text"))
        assertEquals("6597298524", intent.data!!.lastPathSegment)
    }

    @Test fun plannedEtaFallbackWhenNoUpdatedEtaAndAcrossMidnight() {
        val planned = deliveryWhatsAppMessageDraft(stop(eta = null))!!
        assertTrue(planned.contains("*ETA : 7.21pm - 7.31pm*"))
        val midnight = deliveryWhatsAppMessageDraft(stop(eta = "2026-10-13T23:55:00"))!!
        assertTrue(midnight.contains("*ETA : 11.55pm - 12.05am*"))
    }

    @Test fun missingOriginalOrPriceNeverInventsCustomerOrderOrAmount() {
        assertNull(deliveryWhatsAppMessageDraft(stop(original = null)))
        assertNull(deliveryWhatsAppMessageDraft(stop(price = null)))
        assertNull(deliveryWhatsAppMessageDraft(stop(eta = "bad datetime")))
        assertNull(deliveryWhatsAppMessageDraft(stop().copy(order = null)))
    }

    @Test fun separateCustomersAtSamePostalNeverLeakEachOthersOrder() {
        val first = stop(original = sampleMessage, customer = "Jaliah")
        val second = stop(original = sampleMessage.replace("Jaliah", "Aminah").replace("1. BLACK", "1. GREEN"),
            customer = "Aminah", price = BigDecimal("120"))
        val a = deliveryWhatsAppMessageDraft(first)!!
        val b = deliveryWhatsAppMessageDraft(second)!!
        assertTrue(a.contains("Nama: Jaliah"))
        assertFalse(a.contains("Nama: Aminah"))
        assertTrue(b.contains("Nama: Aminah"))
        assertFalse(b.contains("Nama: Jaliah"))
        assertTrue(b.contains("*COD Amount: S$120.00*"))
        assertFalse(a.contains("1. GREEN"))
    }
}
