package com.izzyan.sgdeliveryplanner
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDateTime
class PlannerTest {
 @Test fun validationPreservesLeadingZerosAndReportsErrors() {
  val p=parseInput("012345,730120;730120\nBAD 12345 １２３４５６")
  assertEquals(listOf("012345","730120"),p.valid)
  assertEquals(listOf("BAD","12345","１２３４５６"),p.invalid)
  assertEquals(1,p.duplicates)
 }
 @Test fun emptyInput() { assertTrue(parseInput(" ; , \n").valid.isEmpty()) }
 @Test fun directedTourImprovesGreedyAndVisitsEveryStop() {
  val c=arrayOf(doubleArrayOf(0.0,1.0,2.0,3.0),doubleArrayOf(2.0,0.0,1.0,2.0),doubleArrayOf(1.0,8.0,0.0,100.0),doubleArrayOf(100.0,2.0,1.0,0.0))
  val result=Optimizer.optimize(c)
  assertEquals(setOf(1,2,3),result.toSet()); assertEquals(3,result.size)
  assertTrue(Optimizer.cost(result,c)<Optimizer.cost(listOf(1,2,3),c))
 }
 @Test fun oneStopIncludesReturnLeg() {
  val c=arrayOf(doubleArrayOf(0.0,5.0),doubleArrayOf(7.0,0.0))
  assertEquals(listOf(1),Optimizer.optimize(c)); assertEquals(12.0,Optimizer.cost(listOf(1),c),.001)
 }
 @Test fun timingSeparatesServiceAndIncludesMidnightReturn() {
  val start=LocalDateTime.of(2026,10,3,23,50)
  val p=schedule(listOf(depot.copy(postal="730120"),depot.copy(postal="760270")),listOf(Leg(5.0,600.0,120.0),Leg(2.0,300.0,60.0),Leg(8.0,900.0,180.0)),start,8,"Normal",emptyList())
  assertEquals("2026-10-04T00:02",p.stops[0].arrival)
  assertEquals("2026-10-04T00:10",p.stops[0].leave)
  assertEquals("2026-10-04T00:16",p.stops[1].arrival)
  assertEquals("2026-10-04T00:42",p.returned)
  assertEquals(1800.0,p.baseSeconds,.001); assertEquals(15.0,p.totalKm,.001)
 }
 @Test fun buffersVaryByRoadSpeedDistanceAndTrafficMode() {
  assertTrue(trafficBuffer(2.0,300.0,"Heavy Traffic")>trafficBuffer(2.0,300.0,"Normal"))
  assertTrue(trafficBuffer(2.0,300.0,"Normal")>trafficBuffer(2.0,300.0,"Light Traffic"))
  assertNotEquals(trafficBuffer(1.0,120.0,"Normal"),trafficBuffer(10.0,600.0,"Normal"),.01)
  assertEquals(0.0,trafficBuffer(0.0,0.0,"Normal"),.001)
 }
 @Test fun openRouteStopsAtFinalDeliveryWithoutReturnCost() {
  val costs=arrayOf(
   doubleArrayOf(0.0,2.0,5.0),
   doubleArrayOf(2.0,0.0,1.0),
   doubleArrayOf(5.0,1.0,0.0))
  val route=Optimizer.optimize(costs,endIndex=null)
  assertEquals(listOf(1,2),route)
  assertEquals(3.0,Optimizer.cost(route,costs,endIndex=null),.001)
  assertEquals(8.0,Optimizer.cost(route,costs,endIndex=0),.001)
 }

 @Test fun fixedEndIsExcludedFromDeliveryOrderButIncludedInRouteCost() {
  val costs=arrayOf(
   doubleArrayOf(0.0,1.0,7.0,10.0),
   doubleArrayOf(3.0,0.0,1.0,5.0),
   doubleArrayOf(4.0,2.0,0.0,2.0),
   doubleArrayOf(10.0,5.0,2.0,0.0))
  val route=Optimizer.optimize(costs,endIndex=3)
  assertEquals(listOf(1,2),route)
  assertEquals(4.0,Optimizer.cost(route,costs,endIndex=3),.001)
  assertFalse(route.contains(3))
 }

 @Test fun scheduleTracksLastDeliveryAndFixedEndSeparately() {
  val delivery=depot.copy(postal="730120",lat=1.35,lon=103.8)
  val start=LocalDateTime.of(2026,11,3,10,0)
  val open=schedule(listOf(delivery),listOf(Leg(4.0,600.0,0.0),Leg(0.0,0.0,0.0)),start,8,"Normal",emptyList(),
      endAtLastDelivery=true)
  assertTrue(open.endAtLastDelivery)
  assertEquals(delivery,open.endPlace)
  assertEquals(open.stops.last().leave,open.returned)
  assertEquals(4.0,open.totalKm,.001)

  val home=StartLocation("HOME",1.30,103.80,"Home","Home address","005003")
  val fixed=schedule(listOf(delivery),listOf(Leg(4.0,600.0,0.0),Leg(7.0,900.0,0.0)),start,8,"Normal",emptyList(),
      endLocation=home)
  assertEquals(home.asPlace(),fixed.endPlace)
  assertEquals(11.0,fixed.totalKm,.001)
  assertEquals("Home",fixed.endDisplayName)
 }
 @Test fun duplicateCustomerOrdersKeepOpenEndAndCorrectEndEta() {
  val customerPlace=depot.copy(postal="730120",lat=1.35,lon=103.8)
  val start=LocalDateTime.of(2026,11,3,10,0)
  val base=schedule(listOf(customerPlace),
      listOf(Leg(4.0,600.0,0.0),Leg(0.0,0.0,0.0)),
      start,8,"Normal",emptyList(),endAtLastDelivery=true)
  val expanded=expandCustomerOrders(base,listOf(
      CustomerOrder(postalCode="730120",orderId="A"),
      CustomerOrder(postalCode="730120",orderId="B")))
  assertTrue(expanded.endAtLastDelivery)
  assertEquals(customerPlace,expanded.endPlace)
  assertEquals(2,expanded.stops.size)
  assertEquals("2026-11-03T10:26",expanded.returned)
  assertEquals(expanded.stops.last().leave,expanded.returned)
 }

 @Test(expected=IllegalArgumentException::class) fun rejectsUnreachableMatrix() { Optimizer.optimize(arrayOf(doubleArrayOf(0.0,Double.POSITIVE_INFINITY),doubleArrayOf(1.0,0.0))) }
}
