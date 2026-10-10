package com.izzyan.sgdeliveryplanner

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CustomerDraftTest {
    private val store=ViewModelStore()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() {store.clear();Dispatchers.resetMain()}
    private fun model(key:String) = PlannerViewModel(app).also {store.put(key,it)}
    @Test fun importedDuplicateCustomersSurviveRestartAndClearDoesNotTouchSavedRoute() {
        val vm=model("one")
        val orders=listOf(CustomerOrder(postalCode="650417",customerName="Helen"),CustomerOrder(postalCode="650417",customerName="Aminah"))
        vm.importOrders(orders)
        assertEquals("650417\n650417",vm.input)
        assertEquals(orders,vm.planningOrders())
        val reloaded=model("two")
        assertEquals(orders,reloaded.planningOrders())
        val route=schedule(listOf(Place("650417",1.3,103.8,"","","Address")),List(2){Leg(0.0,0.0,0.0)},LocalDateTime.of(2026,10,10,10,0),8,"Normal",emptyList())
        reloaded.route=route
        reloaded.clearPostalCodes()
        assertEquals("",reloaded.input);assertTrue(reloaded.draftOrders.isEmpty());assertSame(route,reloaded.route)
        assertNull(app.getSharedPreferences("settings",0).getString("draftCustomerOrders",null))
    }
    @Test fun manualPostalEntryAndFiftyOrderCapacityRemainSupported() {
        val vm=model("one")
        vm.input="650417\n650417\n650331"
        assertEquals(listOf("650417","650331"),vm.planningOrders().map {it.postalCode})
        vm.clearPostalCodes()
        vm.importOrders((1..50).map {CustomerOrder(postalCode="650417",customerName="Name $it")})
        assertEquals(50,vm.planningOrders().size)
        vm.importOrders(listOf(CustomerOrder(postalCode="650331")))
        assertEquals(50,vm.planningOrders().size);assertTrue(vm.message.contains("50"))
    }
}
