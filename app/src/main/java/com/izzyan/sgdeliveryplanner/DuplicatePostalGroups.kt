package com.izzyan.sgdeliveryplanner

/** Several separate parcels may share one Singapore postal code. */
data class DuplicatePostalGroup(val postalCode: String, val orders: List<CustomerOrder>) {
    val extraParcels: Int get() = orders.size - 1
}

/** Never deduplicate customers: group only for display in import preview. */
fun duplicatePostalGroups(orders: List<CustomerOrder>): List<DuplicatePostalGroup> =
    orders.groupBy { it.postalCode }
        .filterValues { it.size > 1 }
        .map { (postal, customers) -> DuplicatePostalGroup(postal, customers) }
