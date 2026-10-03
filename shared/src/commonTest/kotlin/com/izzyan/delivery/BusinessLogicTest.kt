package com.izzyan.delivery

import kotlin.test.*

class BusinessLogicTest {
    private val p = Place("012345",1.3,103.8,"1","Road","1 Road")
    private fun route(count: Int = 4): Route = schedule(ScheduleInput("test",0,1_700_006_350,8,"Normal Traffic",Tour((1..count).toList(),List(count) { p.copy(postal=it.toString().padStart(6,'0')) },List(count+1) { Leg(1.0,120.0,30.1) }),emptyList(),StartLocation()))
    @Test fun postalLeadingZerosDuplicatesAndInvalids() {
        assertEquals(PostalInput(listOf("012345","123456"),listOf("123","abcdef"),1),parsePostalCodes("012345,123456;012345\n123 abcdef"))
        assertEquals(emptyList(),parsePostalCodes(" ").valid)
    }
    @Test fun directedTourEvaluatesInternalReversalsAndReturn() {
        val costs = arrayOf(doubleArrayOf(0.0,3.0,7.0,8.0), doubleArrayOf(10.0,0.0,2.0,1.0), doubleArrayOf(1.0,12.0,0.0,4.0), doubleArrayOf(3.0,6.0,2.0,0.0))
        val order = Optimizer.optimize(costs)
        assertEquals(setOf(1,2,3),order.toSet())
        assertEquals(listOf(1,3,2),order)
        assertEquals(7.0,Optimizer.cost(order,costs))
        assertFailsWith<IllegalArgumentException> { Optimizer.optimize(arrayOf(doubleArrayOf(0.0,Double.NaN),doubleArrayOf(1.0,0.0))) }
    }
    @Test fun roundTripOneStopAndTrafficBands() {
        assertEquals(listOf(1),Optimizer.optimize(arrayOf(doubleArrayOf(0.0,2.0),doubleArrayOf(3.0,0.0))))
        assertEquals(0.0,trafficBuffer(5.0,0.0,"Heavy Traffic"))
        assertEquals(960.0,trafficBuffer(10.0,3600.0,"Normal Traffic"))
        assertEquals(trafficBuffer(2.0,120.0,"Normal Traffic")*1.8,trafficBuffer(2.0,120.0,"Heavy Traffic"),0.00001)
        val r = route(1)
        assertEquals(151L,r.stops[0].arrival-r.start)
        assertEquals(480L,r.stops[0].leave-r.stops[0].arrival)
        assertEquals(151L,r.returned-r.stops[0].leave)
        assertEquals(2.0,summary(r).totalKm)
    }
    @Test fun pendingReviewAndHoldDoNotImplyDelivery() {
        var r = route()
        r = reviewStop(ReviewInput(r,"DELIVERED",r.start+60))
        r = reviewStop(ReviewInput(r,"ON_HOLD",r.start+120,"Other","x".repeat(170)))
        assertEquals(160,r.stops[1].holdNote?.length)
        r = reviewStop(ReviewInput(r,"SKIPPED",r.start+180))
        r = reviewStop(ReviewInput(r,"NEXT",r.start+240))
        val s = summary(r)
        assertEquals(listOf(1,1,1,1),listOf(s.delivered,s.onHold,s.skipped,s.pending))
        assertEquals(25.0,s.successRate); assertNull(r.actualCompletion)
        assertEquals(r.start+240,r.reviewedFinishedAt); assertEquals(4,r.current)
        val saved = saveMoney(MoneyInput(r,"123.4","2",r.start+250))
        assertEquals("123.40",saved.cashOnHand.amount); assertEquals("2.00",saved.tax.amount)
        val next = reviewStop(ReviewInput(saved.copy(current=0),"NEXT",r.start+300))
        assertEquals(saved.summarySavedAt,next.summarySavedAt)
        val changed = reviewStop(ReviewInput(saved.copy(current=0),"PENDING",r.start+301))
        assertNull(changed.summarySavedAt); assertNull(changed.stops[0].completedAt)
    }
    @Test fun completedOnlyWhenEveryParcelDeliveredAndRateRoundsHalfUp() {
        var r = route(3)
        repeat(3) { r = reviewStop(ReviewInput(r,"DELIVERED",r.start+60*(it+1))) }
        assertEquals(r.start+180,r.actualCompletion); assertEquals(100.0,summary(r).successRate)
        assertEquals(33.3,summary(r.copy(stops=r.stops.mapIndexed { i,stop -> if(i==0) stop else stop.copy(status=DeliveryStatus.PENDING) })).successRate)
    }
    @Test fun currencyIsIndependentExactAndBounded() {
        assertEquals("0.00",normalizedCurrency("")); assertEquals("0.50",normalizedCurrency(".5")); assertEquals("2.00",normalizedCurrency("0002."))
        for(value in listOf(".","-1","1.234","1e2","1000000000","abc")) assertNull(normalizedCurrency(value))
        val r = saveMoney(MoneyInput(route(),"999999999.99","0",1))
        assertEquals("999999999.99",r.cashOnHand.amount); assertEquals("0.00",r.tax.amount)
    }
    @Test fun jsonFacadeRoundTripsAndReportsErrors() {
        val engine = DeliveryEngine()
        assertTrue(engine.parsePostalCodes("012345").contains("012345"))
        assertTrue(engine.optimize("{}").contains("error"))
    }
}
