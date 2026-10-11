package com.izzyan.sgdeliveryplanner

import android.content.Intent
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WhatsAppMessageDraftTest {
    @Test fun importedMessageRetainsActualItemsAndRemovesOnlyChatHeader() {
        val raw = "[31/07, 11:21 pm] Eyra Ramanny: ${WhatsAppImportFixture.english}"
        val parsed = parseCustomerOrders(raw)
        assertEquals(0, parsed.failed)
        val order = parsed.orders.single()
        val draft = orderMessageDraft(order)!!
        assertTrue(draft.startsWith("🌸ORDER SHAKIRA🌸"))
        assertTrue(draft.contains("Total: $ 80 (COD)"))
        assertTrue(draft.contains("Name: Norlaili bte Mohd Nor"))
        assertTrue(draft.contains("1- Light Purple"))
        assertTrue(draft.contains("2- Maroon"))
        assertFalse(draft.contains("Eyra Ramanny:"))
        assertEquals("750512", order.postalCode)
        assertEquals("+6584253239", order.phoneNumber)
    }

    @Test fun independentSamePostalOrdersNeverShareDraftText() {
        val first = WhatsAppImportFixture.english
        val second = first.replace("Name: Norlaili bte Mohd Nor", "Name: Customer B")
            .replace("2- Maroon", "2- Emerald")
        val parsed = parseCustomerOrders("[31/07, 11:21 pm] Sender: $first\n[31/07, 11:22 pm] Sender: $second")
        assertEquals(2, parsed.orders.size)
        val a = parsed.orders[0]
        val b = parsed.orders[1]
        assertEquals(a.postalCode, b.postalCode)
        assertNotEquals(a.orderId, b.orderId)
        assertTrue(orderMessageDraft(a)!!.contains("2- Maroon"))
        assertFalse(orderMessageDraft(a)!!.contains("2- Emerald"))
        assertTrue(orderMessageDraft(b)!!.contains("2- Emerald"))

        val base = schedule(
            listOf(Place("750512", 1.4, 103.8, "512", "Wellington", "Wellington Circle")),
            List(2) { Leg(0.0, 0.0, 0.0) },
            LocalDateTime.of(2026, 10, 11, 10, 0), 8, "Normal", emptyList()
        )
        val expanded = expandCustomerOrders(base, parsed.orders)
        assertEquals(listOf(a.orderId, b.orderId), expanded.stops.map { it.orderId })
        assertEquals(orderMessageDraft(a), orderMessageDraft(expanded.stops[0].order!!))
        assertEquals(orderMessageDraft(b), orderMessageDraft(expanded.stops[1].order!!))
    }

    @Test fun draftSurvivesGsonPersistenceAndOldOrdersRequireReImportForFullItems() {
        val parsed = parseCustomerOrders(WhatsAppImportFixture.emoji).orders.single()
        val restored = Gson().fromJson(Gson().toJson(parsed), CustomerOrder::class.java)
        assertEquals(orderMessageDraft(parsed), orderMessageDraft(restored))
        assertTrue(orderMessageDraft(restored)!!.contains("BLACK\nOAT"))
        assertNull(orderMessageDraft(CustomerOrder(postalCode = "823233", phoneNumber = "+6580143418")))
    }

    @Test fun whatsappLinkTargetsCorrectCustomerWithEditableUnicodeDraftButDoesNotSend() {
        val order = parseCustomerOrders(WhatsAppImportFixture.emoji).orders.single()
        for (choice in WhatsAppChoice.entries) {
            val intent = customerWhatsAppIntent(order.phoneNumber!!, choice, orderMessageDraft(order))
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(choice.packageName, intent.`package`)
            assertEquals("https", intent.data!!.scheme)
            assertEquals("wa.me", intent.data!!.authority)
            assertEquals(listOf("6580143418"), intent.data!!.pathSegments)
            assertEquals(orderMessageDraft(order), intent.data!!.getQueryParameter("text"))
            assertNull(intent.extras)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }

    @Test fun existingCustomerWhatsappIconStillOpensBlankChat() {
        val intent = customerWhatsAppIntent("+65 8425 3239")
        assertEquals("6584253239", intent.data!!.lastPathSegment)
        assertNull(intent.data!!.getQueryParameter("text"))
        assertEquals("com.whatsapp", intent.`package`)
    }
}
