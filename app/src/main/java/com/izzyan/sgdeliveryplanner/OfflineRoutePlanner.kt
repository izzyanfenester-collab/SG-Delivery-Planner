package com.izzyan.sgdeliveryplanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.math.*

/** A multi-start directed open-path/closed-path solver, never claiming global optimality. */
internal object OfflineTourOptimizer {
    fun optimize(costs: Array<DoubleArray>, endIndex: Int?): List<Int> {
        require(costs.size in 2..52 && costs.all { it.size == costs.size })
        val stops = (1 until costs.size).filter { it != endIndex }
        require(stops.isNotEmpty())
        val seeds = stops.sortedBy { costs[0][it] }.take(12)
        var best: List<Int>? = null
        var bestCost = Double.POSITIVE_INFINITY

        for (seed in seeds) {
            val pending = stops.toMutableSet()
            val candidate = mutableListOf<Int>()
            candidate.add(seed)
            pending.remove(seed)
            while (pending.isNotEmpty()) {
                val previous = candidate.last()
                val next = pending.minBy { costs[previous][it] }
                candidate.add(next)
                pending.remove(next)
            }
            var attempt = candidate.toList()
            for (pass in 0 until 15) {
                var improvement = attempt
                var score = Optimizer.cost(attempt, costs, endIndex)
                for (i in attempt.indices) {
                    for (j in i + 1 until attempt.size) {
                        val reverse = attempt.toMutableList().apply { subList(i, j + 1).reverse() }
                        val swap = attempt.toMutableList().apply { val t = this[i]; this[i] = this[j]; this[j] = t }
                        for (option in listOf(reverse, swap)) {
                            val next = Optimizer.cost(option, costs, endIndex)
                            if (next + 0.001 < score) { score = next; improvement = option }
                        }
                    }
                    // Relocation (Or-opt 1): often improves directed tours over 2-opt alone.
                    for (position in attempt.indices) if (position != i) {
                        val moved = attempt.toMutableList()
                        val element = moved.removeAt(i)
                        moved.add(position, element)
                        val next = Optimizer.cost(moved, costs, endIndex)
                        if (next + 0.001 < score) { score = next; improvement = moved }
                    }
                }
                if (improvement === attempt) break
                attempt = improvement
            }
            val score = Optimizer.cost(attempt, costs, endIndex)
            if (score < bestCost) { bestCost = score; best = attempt }
        }
        require(best != null && bestCost.isFinite()) {
            "No connected drivable route through all selected Singapore stops"
        }
        return best
    }
}

/**
 * Single-shot offline optimizer for 1–50 unique locations (up to 50 orders).
 * Starts/ends stay fixed, and expandCustomerOrders preserves repeated postal
 * orders, unique IDs, COD, PayNow and proof references.
 */
internal class OfflineRoutePlanner(
    private val graph: OfflineRoadGraph,
    private val resolve: suspend (String) -> Place
) {
    suspend fun plan(
        postals: List<String>,
        start: LocalDateTime,
        service: Int,
        traffic: String,
        startLocation: StartLocation,
        orders: List<CustomerOrder>,
        selectedEnd: EndLocationSelection,
        progress: (String) -> Unit
    ): Plan {
        require(postals.size in 1..50 && orders.size <= 50) {
            "An offline delivery route supports 1–50 delivery stops"
        }
        require(startLocation.isValid() && selectedEnd.isValid())
        val places = ArrayList<Place>()
        places.add(startLocation.asPlace())
        val missing = ArrayList<PostalLookupFailure>()
        postals.forEachIndexed { index, postal ->
            progress("Resolving offline postal " + (index + 1) + " of " + postals.size)
            try {
                places.add(resolve(postal))
            } catch (failure: Exception) {
                missing.add(PostalLookupFailure(postal, failure.message ?: "No offline coordinate"))
            }
        }
        if (missing.isNotEmpty()) throw PostalResolutionException(missing)
        val chosenEnd = if (selectedEnd.useLastDelivery) null
            else (selectedEnd.location ?: startLocation).asPlace()
        val customEnd = chosenEnd?.takeUnless {
            it.lat == startLocation.latitude && it.lon == startLocation.longitude
        }
        if (customEnd != null) places.add(customEnd)
        val end = if (selectedEnd.useLastDelivery) null else if (customEnd == null) 0 else places.lastIndex
        val snaps = withContext(Dispatchers.Default) {
            places.mapIndexed { i, place ->
                if (i % 10 == 0) progress("Matching delivery roads to the Singapore graph…")
                currentCoroutineContext().ensureActive()
                graph.snap(place).node
            }
        }
        val n = places.size
        val distance = Array(n) { DoubleArray(n) }
        val seconds = Array(n) { DoubleArray(n) }
        val effort = Array(n) { DoubleArray(n) }
        withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            for (i in places.indices) {
                context.ensureActive()
                progress("Offline road distances: " + (i + 1) + " / " + n)
                val row = graph.costs(snaps[i], snaps.toIntArray()) { context.ensureActive() }
                for (j in places.indices) {
                    distance[i][j] = row.metres[j]
                    seconds[i][j] = row.seconds[j]
                    effort[i][j] = row.efforts[j]
                }
            }
        }
        progress("Improving 50-stop-capable route order…")
        val order = withContext(Dispatchers.Default) { OfflineTourOptimizer.optimize(effort, end) }
        val path = listOf(0) + order + listOfNotNull(end)
        val legs = ArrayList<Leg>()
        val geometry = ArrayList<List<Double>>()
        withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            path.zipWithNext().forEachIndexed { index, (from, to) ->
                context.ensureActive()
                progress("Drawing offline road geometry: " + (index + 1) + " / " + (path.size - 1))
                val route = graph.route(snaps[from], snaps[to]) { context.ensureActive() }
                val km = route.metres / 1000.0
                legs.add(Leg(km, route.seconds, trafficBuffer(km, route.seconds, traffic)))
                geometry.addAll(if (index == 0) route.geometry else route.geometry.drop(1))
            }
        }
        if (selectedEnd.useLastDelivery) legs.add(Leg(0.0, 0.0, 0.0))
        val scheduled = schedule(
            order.map { places[it] }, legs, start, service, traffic, geometry, startLocation,
            endLocation = if (selectedEnd.useLastDelivery) null else selectedEnd.location,
            endAtLastDelivery = selectedEnd.useLastDelivery
        )
        val withOrders = if (orders.isEmpty()) scheduled else expandCustomerOrders(scheduled, orders)
        // Only the version of the plan created by this solver is labelled Offline.
        return withOrders.copy(routingSource = "Offline")
    }
}
