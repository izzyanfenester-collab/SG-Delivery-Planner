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

    val updated = if (text.matches(Regex("[0-9]{6}"))) {
        // Postal-only editing works for WhatsApp imports too. Keep order identity,
        // payment, price and proofs, and only replace the old six-digit code in
        // customer-supplied address/original message. Do not touch other orders.
        val oldPostal = Regex("(?<!\\\\d)${Regex.escape(old.postalCode)}(?!\\\\d)")
        old.copy(
            postalCode = text,
            fullAddress = old.fullAddress?.replace(oldPostal, text),
            originalMessage = old.originalMessage?.replace(oldPostal, text)
        )
    } else if (old.originalMessage.isNullOrBlank()) {
        return RoutePostalEditResult(error = "Enter exactly one six-digit Singapore postal code.")
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
