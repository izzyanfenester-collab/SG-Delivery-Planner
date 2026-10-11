package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class OrderMessagePrefillTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private fun plan(orders: List<CustomerOrder>): Plan {
        val places=orders.map {it.postalCode}.distinct().map {Place(it,1.3,103.8,"","","Resolved")}
        return expandCustomerOrders(schedule(places,List(places.size+1){Leg(0.0,0.0,0.0)},LocalDateTime.of(2026,10,11,10,0),8,"Normal",emptyList()),orders)
    }
    @Test fun englishMalayEmojiBodiesRetainExactItemsAndExcludeExportHeaders() {
        val source=WhatsAppImportFixture.mixed
        val orders=parseCustomerOrders(source).orders
        assertEquals(3,orders.size)
        assertEquals(WhatsAppImportFixture.english+"\n",orders[0].originalOrderText)
        assertEquals(WhatsAppImportFixture.malay.replace("COD","PayNow")+"\n",orders[1].originalOrderText)
        assertEquals(WhatsAppImportFixture.emoji,orders[2].originalOrderText)
        assertFalse(orders.any {it.originalOrderText!!.contains("Eyra Ramanny")})
        assertTrue(orders[0].originalOrderText!!.contains("1- Light Purple\n2- Maroon"))
        assertTrue(orders[1].originalOrderText!!.contains("1. SOFT YELLOW\n2. PEANUT BROWN"))
        assertTrue(orders[2].originalOrderText!!.contains("🛒 Item:\nBLACK\nOAT"))
        val crlf=WhatsAppImportFixture.emoji.replace("\n","\r\n")
        assertEquals(crlf,parseCustomerOrders("[31/07, 11:23 pm] Sender:\r\n$crlf").orders.single().originalOrderText)
    }
    @Test fun duplicatePostalBodiesRemainBoundToTheirOwnOrderIdsPhonesAndOptimizedStops() {
        val first=WhatsAppImportFixture.english
        val second=first.replace("Norlaili bte Mohd Nor","Other Customer").replace("6584253239","6591525714").replace("Light Purple","BLUE ONLY")
        val orders=parseCustomerOrders("[31/07, 11:21 pm] Runner: $first\n[31/07, 11:22 pm] Runner: $second").orders
        val route=plan(orders)
        assertEquals(2,route.stops.size);assertNotEquals(route.stops[0].orderId,route.stops[1].orderId)
        val a=customerWhatsAppIntent(route.stops[0].order!!.phoneNumber!!,message=customerOrderMessage(route.stops[0]))
        val b=customerWhatsAppIntent(route.stops[1].order!!.phoneNumber!!,message=customerOrderMessage(route.stops[1]))
        assertEquals("6584253239",a.data!!.lastPathSegment);assertEquals("6591525714",b.data!!.lastPathSegment)
        assertEquals(first+"\n",a.data!!.getQueryParameter("text"));assertEquals(second,b.data!!.getQueryParameter("text"))
        assertFalse(a.data!!.getQueryParameter("text")!!.contains("BLUE ONLY"))
        assertFalse(b.data!!.getQueryParameter("text")!!.contains("Light Purple"))
    }
    @Test fun encodedDraftIsViewOnlyAndBlankGreenChatHasNoTextForEitherProvider() {
        val message="🛒 Item:\n2 × BLUE & GOLD + 50%\n#07-04? Price: $55 / COD"
        for(choice in WhatsAppChoice.entries) {
            val intent=customerWhatsAppIntent("+65 9152 5714",choice,message)
            assertEquals(Intent.ACTION_VIEW,intent.action);assertEquals(choice.packageName,intent.`package`)
            assertEquals(message,intent.data!!.getQueryParameter("text"))
            assertTrue(intent.data!!.encodedQuery!!.contains("%0A"));assertTrue(intent.data!!.encodedQuery!!.contains("%26"))
            assertNull(intent.extras) // No send/broadcast action, automation flags or message extras.
            assertNull(customerWhatsAppIntent("91525714",choice).data!!.query)
        }
        val prefs=WhatsAppPreferences(app)
        prefs.updateDefault(WhatsAppChoice.BUSINESS)
        assertEquals(WhatsAppChoice.BUSINESS,WhatsAppPreferences(app).default)
        customerWhatsAppIntent("91525714",WhatsAppChoice.PERSONAL,message) // Just once does not mutate preferences.
        assertEquals(WhatsAppChoice.BUSINESS,prefs.default)
        assertFalse(openCustomerWhatsApp(app,"91525714",prefs.default!!,message))
        assertEquals(message,customerWhatsAppIntent("91525714",prefs.default!!,message).data!!.getQueryParameter("text"))
        assertEquals(WhatsAppChoice.BUSINESS,prefs.default)
        prefs.updateDefault(null);assertNull(WhatsAppPreferences(app).default)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun importedBodiesSurviveDraftRestartOptimizerRoomAndLegacyDecoding() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val models=ViewModelStore()
        try {
            val vm=PlannerViewModel(app).also {models.put("import",it)}
            val orders=parseCustomerOrders(WhatsAppImportFixture.mixed).orders
            vm.importOrders(orders)
            val restarted=PlannerViewModel(app).also {models.put("restart",it)}
            assertEquals(orders,restarted.planningOrders())
            val route=plan(restarted.planningOrders())
            val repo=Repository(app);repo.save(route)
            val reopened=Repository(app)
            val history=reopened.decode(reopened.dao.route(route.id)!!)
            assertEquals(route.stops.map {it.order},history.stops.map {it.order})
            val legacy=com.google.gson.JsonParser.parseString(PlanJson.encode(route)).asJsonObject
            legacy.getAsJsonArray("stops").forEach {it.asJsonObject.getAsJsonObject("order").remove("originalOrderText")}
            val old=PlanJson.decode(legacy.toString())
            assertTrue(old.stops.all {it.order!!.originalOrderText==null})
            assertFalse(customerOrderMessage(old.stops[0]).contains("Light Purple"))
            assertTrue(customerOrderMessage(old.stops[0]).contains("Norlaili bte Mohd Nor"))
            val manual=route.stops[0].copy(order=null)
            assertTrue(customerOrderMessage(manual).contains(manual.place.postal))
        } finally {models.clear();Dispatchers.resetMain()}
    }
}
