package com.izzyan.sgdeliveryplanner

import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDateTime

class CustomerOrdersTest {
    private fun message(name:String="Helen",postal:String="650417",price:String="55",mode:String="COD",phone:String="https://wa.me/+6583836087") = """
        Total: $$price ($mode)
        Nama: $name
        FB: Other Facebook Person
        Alamat:
        After 6pm/Friday
        Blk 417, Bukit Batok West Ave 4 #10-288 S $postal
        No.telefon:
        $phone
        Order:
        Quantity 123456
    """.trimIndent()
    @Test fun extractsNamePhoneAddressNotesPriceAndPaymentWithoutOrderOrFacebookLeak() {
        val order=parseCustomerOrders(message()).orders.single()
        assertEquals("Helen",order.customerName); assertEquals("+6583836087",order.phoneNumber)
        assertEquals("After 6pm/Friday\nBlk 417, Bukit Batok West Ave 4 #10-288 S 650417",order.fullAddress)
        assertEquals("650417",order.postalCode); assertEquals(java.math.BigDecimal("55.00"),order.parcelPrice); assertEquals("COD",order.paymentStatus)
        assertFalse(order.fullAddress!!.contains("123456")); assertFalse(order.customerName!!.contains("Facebook"))
    }
    @Test fun normalizesNamesPhonesPaymentAndPriceVariants() {
        for (phone in listOf("https://wa.me/6583836087","+65 8383 6087","8383 6087")) {
            val order=parseCustomerOrders(message(mode="paynow",phone=phone).replace("Nama:","nAmA:")).orders.single()
            assertEquals("+6583836087",order.phoneNumber); assertEquals("PAYNOW",order.paymentStatus)
            assertEquals("PayNow",orderPayment(order)); assertEquals("$55.00",orderPrice(order))
        }
        for(total in listOf("Total: SGD 65","Total: 65","Total: $65 (PAYNOW)")) {
            assertEquals(java.math.BigDecimal("65.00"),parseCustomerOrders(message().replace("Total: $55 (COD)",total)).orders.single().parcelPrice)
        }
        assertNull(normalizeCustomerPhone("123456"))
    }
    @Test fun supportsAllAddressPostalPrefixesAndNeverUsesPhonePriceOrDatesAsPostal() {
        for (prefix in listOf("S ","SG ","Singapore ","S'pore ","")) {
            val order=parseCustomerOrders(message().replace("S 650417","${prefix}650417")).orders.single()
            assertEquals("650417",order.postalCode)
        }
        assertEquals(1,parseCustomerOrders(message().replace("S 650417","no postcode")).failed)
    }
    @Test fun everyMessageHasAnIndependentIdEvenAtSamePostal() {
        val parsed=parseCustomerOrders("[10/10, 2:17 pm] Sha: INVOICE 03/10/26\n"+message()+"\n\n"+message("Aminah")+"\n\n"+message("Ros","650331"))
        assertEquals(3,parsed.orders.size); assertEquals(3,parsed.orders.map {it.orderId}.distinct().size)
        assertEquals(listOf("Helen","Aminah","Ros"),parsed.orders.map {it.customerName})
        assertEquals(listOf("650417","650417","650331"),parsed.orders.map {it.postalCode})
    }
    @Test fun expansionMapsStableIdsToOptimizedStopsAndPreservesEachServiceTime() {
        val orders=parseCustomerOrders(message()+"\n"+message("Aminah")+"\n"+message("Ros","650331")).orders
        val places=listOf(Place("650331",1.3,103.8,"","","OneMap Ros"),Place("650417",1.4,103.8,"","","OneMap Helen"))
        val plan=schedule(places,List(3){Leg(1.0,120.0,0.0)},LocalDateTime.of(2026,10,10,10,0),8,"Normal",emptyList())
        val expanded=expandCustomerOrders(plan,orders)
        assertEquals(listOf("Ros","Helen","Aminah"),expanded.stops.map {it.order!!.customerName})
        assertEquals(orders[2].orderId,expanded.stops[0].orderId)
        assertEquals(orders[0].orderId,expanded.stops[1].orderId)
        assertEquals(0.0,expanded.stops[2].leg.km,0.0)
        assertEquals(expanded.stops[1].leave,expanded.stops[2].arrival)
        assertEquals(LocalDateTime.parse(plan.returned).plusMinutes(8),LocalDateTime.parse(expanded.returned))
        assertEquals(2,plan.stops.size); assertEquals(plan.geometry,expanded.geometry)
    }
    @Test fun missingTotalBetweenPricedOrdersStillCreatesIndependentCustomer() {
        val text = message()+"\nNama: No Price\nAlamat: Singapore 650417\nNo.telefon: 81984289\n"+message("Ros","650331")
        val result = parseCustomerOrders(text)
        assertEquals(listOf("Helen","No Price","Ros"),result.orders.map {it.customerName})
        assertNull(result.orders[1].parcelPrice)
    }
    @Test fun missingOptionalFieldsRemainUnknownAndFailedOrdersAreReported() {
        val order=parseCustomerOrders("Nama: Customer\nAlamat: Singapore 650417\nNo.telefon: missing").orders.single()
        assertNull(order.parcelPrice); assertNull(order.paymentStatus); assertNull(order.phoneNumber)
        assertEquals("—",orderPrice(order)); assertEquals("—",orderPayment(order))
        assertTrue(parseCustomerOrders("").orders.isEmpty())
        assertEquals(1,parseCustomerOrders("not an order").failed)
    }
}
