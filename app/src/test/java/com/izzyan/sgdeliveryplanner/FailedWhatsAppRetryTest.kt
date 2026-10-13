package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class FailedWhatsAppRetryTest {
    private val good = """
        ORDER ALIA
        Total: $55 (COD)
        Nama: Alia
        Alamat:
        Blk 123 Singapore 750512
        WhatsApp: https://wa.me/+6584253239
        Item: Red
    """.trimIndent()

    private val bad = """
        ORDER SHARIPA
        Total: $65 (COD)
        Nama: Sharipa
        Alamat:
        Blk 303B #03-104 Anchorvale Link
        WhatsApp: https://wa.me/+6590150338
        Item: Blue
    """.trimIndent()

    @Test fun correctedFailedOrderAddsCustomerAndUpdatesFailedCount() {
        val initial = parseCustomerOrders(good + "\n\n" + bad)
        assertEquals(1,initial.orders.size)
        assertEquals(1,initial.failed)
        val edited = bad.replace("Anchorvale Link","Anchorvale Link Singapore 543303")
        val result = retryFailedCustomerOrder(initial, 0, edited)
        val updated = requireNotNull(result.updated)
        assertEquals(2,updated.orders.size)
        assertEquals(0,updated.failed)
        assertTrue(updated.failedOrders.isEmpty())
        assertEquals("750512",updated.orders.first().postalCode)
        assertEquals("543303",updated.orders.last().postalCode)
        assertEquals("Sharipa",updated.orders.last().customerName)
        assertEquals(updated.orders.last().orderId,result.addedOrder?.orderId)
        assertTrue(updated.orders.last().originalMessage!!.contains("Item: Blue"))
    }

    @Test fun correctedOrderAtSamePostalIsStillSeparateCustomer() {
        val initial = parseCustomerOrders(good + "\n\n" + bad)
        val edited = bad.replace("Anchorvale Link","Anchorvale Link Singapore 750512")
        val result = requireNotNull(retryFailedCustomerOrder(initial,0,edited).updated)
        assertEquals(2,result.orders.size)
        assertEquals(1,result.orders.map { it.postalCode }.distinct().size)
        assertEquals(2,result.orders.map { it.orderId }.distinct().size)
        assertEquals(0,result.failed)
    }

    @Test fun retryWithoutPostalKeepsFailedEntryAndExistingValidOrders() {
        val initial = parseCustomerOrders(good + "\n\n" + bad)
        val edited = bad.replace("Anchorvale Link","Anchorvale Street")
        val result = retryFailedCustomerOrder(initial,0,edited)
        val updated = requireNotNull(result.updated)
        assertNull(result.addedOrder)
        assertEquals(1,updated.orders.size)
        assertEquals(1,updated.failed)
        assertEquals(1,updated.failedOrders.size)
        assertTrue(updated.failedOrders.single().originalMessage.contains("Anchorvale Street"))
        assertTrue(result.message.startsWith("Still failed"))
    }

    @Test fun retryOfMultipleMessagesCannotSilentlyImportMoreThanOne() {
        val initial = parseCustomerOrders(good + "\n\n" + bad)
        val revised = bad.replace("Anchorvale Link","Anchorvale Link Singapore 543303")
        val result = retryFailedCustomerOrder(initial,0,revised + "\n\n" + good)
        assertNull(result.updated)
        assertNull(result.addedOrder)
        assertEquals(1,initial.orders.size)
        assertEquals(1,initial.failed)
    }

    @Test fun retryDuplicateOfExistingOrderIsNotAddedTwice() {
        val initial = parseCustomerOrders(good + "\n\n" + bad)
        val result = retryFailedCustomerOrder(initial,0,good)
        assertNull(result.updated)
        assertTrue(result.message.contains("already"))
    }
}
