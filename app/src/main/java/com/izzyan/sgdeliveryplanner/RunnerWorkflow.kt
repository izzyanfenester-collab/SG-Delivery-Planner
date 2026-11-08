package com.izzyan.sgdeliveryplanner

/**
 * A visit is a *location*, not a customer or order. Keeping individual orderId
 * is essential: two customers at the same postcode may have different payments,
 * proof images, completion outcomes and contact numbers.
 */
internal data class LocationParcelProgress(
    val postal: String,
    val indices: List<Int>,
    val delivered: Int,
    val onHold: Int,
    val skipped: Int,
    val pending: Int
) {
    val total: Int get() = indices.size
    val unresolved: Int get() = total - delivered
}

internal fun locationParcelProgress(plan: Plan, stopIndex: Int): LocationParcelProgress? {
    val target = plan.stops.getOrNull(stopIndex) ?: return null
    val indices = plan.stops.indices.filter { i ->
        val item = plan.stops[i]
        item.place.postal == target.place.postal &&
            kotlin.math.abs(item.place.lat - target.place.lat) < 0.00001 &&
            kotlin.math.abs(item.place.lon - target.place.lon) < 0.00001
    }
    val stops = indices.map { plan.stops[it] }
    return LocationParcelProgress(
        target.place.postal,
        indices,
        delivered = stops.count { normalizedStatus(it.status) == "DELIVERED" },
        onHold = stops.count { normalizedStatus(it.status) == "ON_HOLD" },
        skipped = stops.count { normalizedStatus(it.status) == "SKIPPED" },
        pending = stops.count { normalizedStatus(it.status) == "PENDING" }
    )
}

/**
 * Preserve original per-parcel completion rules: proof uploads are optional in
 * the existing official build. Missing proof is disclosed, never silently
 * invented or made mandatory by a screen redesign.
 */
internal data class CompletionReadiness(
    val orderId: String,
    val hasDeliveryProof: Boolean,
    val hasPaymentProof: Boolean,
    val isPayOnDelivery: Boolean,
    val alreadyDelivered: Boolean
) {
    val shouldReviewMissingEvidence: Boolean
        get() = !alreadyDelivered && (!hasDeliveryProof ||
            (isPayOnDelivery && !hasPaymentProof))
}

internal fun completionReadiness(stop: Stop): CompletionReadiness {
    val order = stop.order
    return CompletionReadiness(
        orderId = stop.orderId,
        hasDeliveryProof = order?.proofFileId != null,
        hasPaymentProof = order?.paymentProofFileId != null,
        isPayOnDelivery = order?.paymentStatus?.equals("COD", ignoreCase = true) == true,
        alreadyDelivered = normalizedStatus(stop.status) == "DELIVERED"
    )
}

/** Payment data can be displayed, but never inferred to be 'collected'. */
internal fun paymentReviewLabel(stop: Stop): String =
    when (stop.order?.paymentStatus?.uppercase()) {
        "COD" -> "COD · confirm cash collected before completion"
        "PAYNOW" -> "PayNow · confirm payment independently"
        else -> "Payment method not specified"
    }
