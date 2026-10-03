package com.izzyan.delivery

import kotlinx.serialization.Serializable

@Serializable data class PostalInput(val valid: List<String>, val invalid: List<String>, val duplicates: Int)
@Serializable data class Place(val postal: String, val lat: Double, val lon: Double, val block: String, val area: String, val address: String)
@Serializable data class StartLocation(val type: String = "WOODLANDS", val lat: Double = 1.4464, val lon: Double = 103.7696, val label: String = "Woodlands Checkpoint", val address: String = "21 Woodlands Crossing", val postal: String = "738203") {
    fun asPlace() = Place(postal, lat, lon, label, "", address)
    fun valid() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && label.isNotBlank()
}
@Serializable data class Leg(val km: Double, val baseSeconds: Double, val bufferSeconds: Double) {
    val plannedSeconds get() = baseSeconds + bufferSeconds
}
@Serializable enum class DeliveryStatus { DELIVERED, ON_HOLD, SKIPPED, PENDING }
@Serializable data class Stop(val place: Place, val leg: Leg, val arrival: Long, val leave: Long, val cumulativeKm: Double, val status: DeliveryStatus = DeliveryStatus.PENDING, val completedAt: Long? = null, val holdReason: String? = null, val holdNote: String? = null, val reviewedAt: Long? = null)
/** Manual independent SGD amounts. No inferred tax, deductions or exchange rate. */
@Serializable data class CashOnHand(val amount: String = "0.00")
@Serializable data class Tax(val amount: String = "0.00")
@Serializable data class Route(val id: String, val created: Long, val start: Long, val serviceMinutes: Int, val mode: String, val stops: List<Stop>, val returnLeg: Leg, val returned: Long, val geometry: List<List<Double>>, val startLocation: StartLocation = StartLocation(), val current: Int = 0, val actualCompletion: Long? = null, val reviewedFinishedAt: Long? = null, val cashOnHand: CashOnHand = CashOnHand(), val tax: Tax = Tax(), val summarySavedAt: Long? = null)
@Serializable data class DeliverySummary(val totalParcel: Int, val delivered: Int, val onHold: Int, val skipped: Int, val pending: Int, val successRate: Double, val start: Long, val finish: Long, val totalRouteSeconds: Long, val totalKm: Double, val returned: Long, val cashOnHand: CashOnHand, val tax: Tax)
@Serializable data class RoadMatrix(val places: List<Place>, val meters: List<List<Double>>, val seconds: List<List<Double>>, val mode: String)
@Serializable data class Tour(val order: List<Int>, val places: List<Place>, val legs: List<Leg>)
@Serializable data class ScheduleInput(val id: String, val created: Long, val start: Long, val serviceMinutes: Int, val mode: String, val tour: Tour, val geometry: List<List<Double>>, val startLocation: StartLocation)
@Serializable data class ReviewInput(val route: Route, val action: String, val now: Long, val holdReason: String? = null, val holdNote: String? = null)
@Serializable data class MoneyInput(val route: Route, val cashOnHand: String, val tax: String, val now: Long)
