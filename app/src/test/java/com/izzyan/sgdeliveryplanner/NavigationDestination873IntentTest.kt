package com.izzyan.sgdeliveryplanner

import android.content.*
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class NavigationDestination873IntentTest {
    private val place=Place("460011",1.33,103.93,"11","Bedok","11 Bedok South Road")
    private fun plan():Plan {
        val p=schedule(listOf(place,place),List(3){Leg(1.0,100.0,10.0)},
            LocalDateTime.of(2026,11,19,10,0),10,"Normal Traffic",emptyList())
        return p.copy(stops=p.stops.mapIndexed { i,s ->
            val id="ORDER-$i"
            s.copy(orderId=id,order=CustomerOrder(orderId=id,postalCode=place.postal,
                fullAddress="Blk 11, ${if(i==0)"#05-123" else "#08-456"}, Singapore 460011"))
        })
    }
    private class RecordingContext:ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        val intents=mutableListOf<Intent>()
        override fun startActivity(intent:Intent){intents+=intent}
    }
    @Test fun actualDeliveryLauncherBindsEachDuplicatePostalToItsOwnOrderAndLeavesPlanUntouched() {
        val context=RecordingContext();val p=plan();val before=PlanJson.encode(p)
        assertTrue(openDeliveryNavigation(context,p,NavigationChoice.BUILT_IN))
        assertTrue(openDeliveryNavigation(context,p.copy(current=1),NavigationChoice.BUILT_IN))
        val a=navigationDestinationSnapshot873(context.intents[0])!!
        val b=navigationDestinationSnapshot873(context.intents[1])!!
        assertEquals("Block 11, #05-123 (460011)",a.heading.full)
        assertEquals("Block 11, #08-456 (460011)",b.heading.full)
        assertEquals("ORDER-0",a.orderId);assertEquals("ORDER-1",b.orderId)
        assertEquals(a.sessionKey,b.sessionKey)
        assertEquals(place.address,a.place.address)
        assertEquals(before,PlanJson.encode(p))
        assertTrue(p.stops.all{it.status=="PENDING" && it.completedAt==null})
        assertTrue(context.intents.all{it.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP!=0})
    }
    @Test fun missingUnitNeverReusesPreviousOrderOrGeocodedPlaceUnit() {
        val context=ApplicationProvider.getApplicationContext<Context>();val p=plan()
        val first=navigationDestinationSnapshot873(builtInNavigationIntent(context,p)!!)!!
        val second=p.stops[1].copy(place=place.copy(address="Blk 11 #09-999"),
            order=p.stops[1].order!!.copy(fullAddress="Blk 11 Singapore 460011"))
        val next=navigationDestinationSnapshot873(builtInNavigationIntent(context,second))!!
        assertEquals("#05-123",first.heading.unit)
        assertNull(next.heading.unit);assertEquals("Block 11 (460011)",next.heading.full)
        val empty=second.copy(order=second.order!!.copy(fullAddress=null))
        assertNull(navigationDestinationSnapshot873(builtInNavigationIntent(context,empty))!!.heading.unit)
    }
    @Test fun legacySelectedStopAddressIsSupportedWithoutWritingNewSavedDataFields() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val selected=plan().stops[0].copy(order=null,place=place.copy(address="Blk 11 #01-02A Singapore 460011"))
        val snapshot=navigationDestinationSnapshot873(builtInNavigationIntent(context,selected))!!
        assertEquals("Block 11, #01-02A (460011)",snapshot.heading.full)
    }
    @Test fun customEndDoesNotBorrowCurrentDeliveryUnitAndAutomaticEndUsesTheLastOrder() {
        val context=RecordingContext();val p=plan().copy(endLocation=StartLocation("CUSTOM",1.39,103.91,
            "Home #01-02","1 Home Road","519001"))
        assertTrue(openEndNavigation(context,p,NavigationChoice.BUILT_IN))
        val custom=navigationDestinationSnapshot873(context.intents.last())!!
        assertNull(custom.deliveryAddress);assertNull(custom.heading.unit)
        assertTrue(openEndNavigation(context,p.copy(endAtLastDelivery=true),NavigationChoice.BUILT_IN))
        val last=navigationDestinationSnapshot873(context.intents.last())!!
        assertEquals("ORDER-1",last.orderId);assertEquals("#08-456",last.heading.unit)
    }
    @Test fun labelOnlyUpdatesKeepTheRouteKeyButRealDestinationChangesGetANewKey() {
        val context=ApplicationProvider.getApplicationContext<Context>();val p=plan()
        val a=navigationDestinationSnapshot873(builtInNavigationIntent(context,p)!!)!!
        val b=navigationDestinationSnapshot873(builtInNavigationIntent(context,p.copy(current=1))!!)!!
        assertEquals(a.sessionKey,b.sessionKey);assertNotEquals(a.heading.full,b.heading.full)
        assertNotEquals(a.sessionKey,b.copy(place=place.copy(postal="460012",lat=1.34)).sessionKey)
        assertNull(builtInNavigationIntent(context,p.copy(current=2)))
    }
    @Test fun coordinateAndCheckpointPayloadsRetainTheirOriginalDestination() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val end=woodlandsStartLocation.asPlace()
        val snapshot=navigationDestinationSnapshot873(builtInNavigationIntent(context,end))!!
        assertEquals(end.postal,snapshot.place.postal)
        assertEquals(end.lat,snapshot.place.lat,0.0);assertEquals(end.lon,snapshot.place.lon,0.0)
        assertNull(snapshot.deliveryAddress);assertNull(snapshot.heading.unit)
        assertEquals(destinationHeading870(end).full,snapshot.heading.full)
    }
}
