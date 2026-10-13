package com.izzyan.sgdeliveryplanner

/**
 * Update one parcel after a failed postal lookup. Identity and proof/history
 * fields are kept; the 50-parcel list and its order are never rebuilt from a set.
 */
data class RoutePostalEditResult(
    val updatedOrders: List<CustomerOrder>? = null,
    val changedOrder: CustomerOrder? = null,
    val error: String? = null
)

fun correctRoutePostalOrder(
    existing: List<CustomerOrder>, orderId: String, editedText: String
): RoutePostalEditResult {
    val index = existing.indexOfFirst { it.orderId == orderId }
    if (index < 0) return RoutePostalEditResult(error = "This order is no longer in the draft.")
    val old = existing[index]
    val text = editedText.trim()
    if (text.isEmpty()) return RoutePostalEditResult(error = "Enter the corrected order or postal code.")

    val updated = if (old.originalMessage.isNullOrBlank()) {
        if (!text.matches(Regex("[0-9]{6}"))) {
            return RoutePostalEditResult(error = "Enter exactly one six-digit Singapore postal code.")
        }
        old.copy(postalCode = text)
    } else {
        val retry = parseCustomerOrders(text)
        if (retry.failed != 0 || retry.orders.size != 1) {
            val reason = retry.failedOrders.firstOrNull()?.reason
            return RoutePostalEditResult(error = reason
                ?: "Keep exactly one complete customer order, including its Address field and six-digit postal code.")
        }
        val fixed = retry.orders.single()
        // Avoid changing immutable order ID, delivery proof fields or unrelated parcels.
        old.copy(
            postalCode = fixed.postalCode,
            customerName = fixed.customerName,
            phoneNumber = fixed.phoneNumber,
            fullAddress = fixed.fullAddress,
            parcelPrice = fixed.parcelPrice,
            paymentStatus = fixed.paymentStatus,
            originalMessage = fixed.originalMessage
        )
    }

    return RoutePostalEditResult(
        updatedOrders = existing.toMutableList().apply { this[index] = updated },
        changedOrder = updated
    )
}
