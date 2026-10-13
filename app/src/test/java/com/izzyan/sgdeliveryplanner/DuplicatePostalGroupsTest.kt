package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class DuplicatePostalGroupsTest {
    @Test fun showsPostalAndEachCustomerWhenTwoOrdersSharePostcode() {
        val orders = listOf(
            CustomerOrder(orderId = "1", postalCode = "730171", customerName = "Salmah"),
            CustomerOrder(orderId = "2", postalCode = "520220", customerName = "Ali"),
            CustomerOrder(orderId = "3", postalCode = "730171", customerName = "Nurijah")
        )
        val groups = duplicatePostalGroups(orders)
        assertEquals(1, groups.size)
        assertEquals("730171", groups.single().postalCode)
        assertEquals(listOf("Salmah", "Nurijah"), groups.single().orders.map { it.customerName })
        assertEquals(1, groups.single().extraParcels)
        assertEquals(3, orders.size)
    }

    @Test fun preservesDistinctDuplicateGroupsAndThreeCustomerCase() {
        val orders = listOf(
            CustomerOrder(postalCode = "732001", customerName = "A"),
            CustomerOrder(postalCode = "520259", customerName = "B"),
            CustomerOrder(postalCode = "732001", customerName = "C"),
            CustomerOrder(postalCode = "520259", customerName = "D"),
            CustomerOrder(postalCode = "732001", customerName = "E")
        )
        val groups = duplicatePostalGroups(orders)
        assertEquals(listOf("732001", "520259"), groups.map { it.postalCode })
        assertEquals(listOf(3, 2), groups.map { it.orders.size })
        assertEquals(3, groups.sumOf { it.extraParcels })
        assertEquals(listOf("A","C","E"), groups.first().orders.map { it.customerName })
        assertEquals(5, orders.size)
    }

    @Test fun noDuplicateGivesNoWarningGroups() {
        val orders = listOf(CustomerOrder(postalCode = "730171"), CustomerOrder(postalCode = "520259"))
        assertTrue(duplicatePostalGroups(orders).isEmpty())
    }

    @Test fun correctedFailedOrderAppearsInDuplicateGroupAutomatically() {
        val original = CustomerOrder(postalCode = "543303", customerName = "Sharipa")
        val imported = OrderImport(listOf(original), failed = 1)
        val correction = CustomerOrder(postalCode = "543303", customerName = "Nadia")
        val updated = imported.copy(orders = imported.orders + correction, failed = 0)
        val groups = duplicatePostalGroups(updated.orders)
        assertEquals(1, groups.size)
        assertEquals(listOf("Sharipa", "Nadia"), groups.single().orders.map { it.customerName })
        assertEquals(0, updated.failed)
        assertEquals(2, updated.orders.size)
    }

    @Test fun full50OrderPreviewStillRetainsAllParcelsAndShowsDuplicates() {
        val orders = (1..50).map { i ->
            CustomerOrder(postalCode = if (i <= 3) "730171" else "%06d".format(700000 + i), customerName = "Runner $i")
        }
        val groups = duplicatePostalGroups(orders)
        assertEquals(1, groups.size)
        assertEquals(3, groups.single().orders.size)
        assertEquals(2, groups.sumOf { it.extraParcels })
        assertEquals(50, orders.size)
        assertEquals(50, orders.map { it.orderId }.distinct().size)
    }
}
