package com.izzyan.sgdeliveryplanner

/** Route statuses are derived from each persisted Plan, not from the Home date,
 * active route preference, postal codes, or the most recently optimized route.
 * This adds no database columns and remains compatible with existing backups.
 */
internal enum class SavedDeliveryStatus(val label: String) {
    NOT_STARTED("NOT STARTED"),
    IN_PROGRESS("IN PROGRESS"),
    COMPLETED("COMPLETED")
}

internal fun savedDeliveryStatus(plan: Plan): SavedDeliveryStatus {
    if (plan.reviewedFinishedAt != null || isRouteCompleted(plan)) {
        return SavedDeliveryStatus.COMPLETED
    }
    // ON_HOLD, SKIPPED, proof uploads and reviewed pending deliveries also count
    // as an update even if none was successfully delivered yet.
    val updated = plan.stops.any { stop ->
        stop.reviewedAt != null || stop.completedAt != null ||
            normalizedStatus(stop.status) != "PENDING" ||
            stop.order?.proofFileId != null || stop.order?.paymentProofFileId != null
    }
    return if (updated) SavedDeliveryStatus.IN_PROGRESS else SavedDeliveryStatus.NOT_STARTED
}

/** Look up the route from Room by its stable ID, rather than using a stale
 * History card snapshot or matching delivery dates/postal codes.
 *
 * This function only reads data. It never reoptimizes or rewrites any record.
 * An invalid/missing saved route cannot replace the currently active route.
 */
internal suspend fun loadPersistedRouteForDelivery(dao: PlannerDao, routeId: String): Plan {
    require(routeId.isNotBlank()) { "Select a saved route before continuing." }
    val saved = dao.route(routeId)
        ?: throw IllegalStateException("This saved route was not found. Existing delivery progress is unchanged.")
    require(saved.id == routeId) { "Saved route identifier mismatch." }
    val selected = PlanJson.decode(saved.json)
    require(selected.id == routeId) { "Saved delivery details belong to another route." }
    require(selected.stops.isNotEmpty()) { "The selected route has no delivery stops." }
    check(savedDeliveryStatus(selected) != SavedDeliveryStatus.COMPLETED) {
        "Completed routes can be viewed in History but cannot be restarted automatically."
    }
    return selected
}
