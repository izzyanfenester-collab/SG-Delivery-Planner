package com.izzyan.sgdeliveryplanner

import java.time.LocalDateTime

/**
 * True offline stop optimization: road-network directed shortest paths, never
 * latitude/longitude straight-line estimates or previously cached OSRM tables.
 */
internal fun buildOfflineDeliveryPlan(
    graph: OfflineRoadGraph,
    destinations: List<Place>,
    start: LocalDateTime,
    serviceMinutes: Int,
    mode: String,
    startLocation: StartLocation,
    endSelection: EndLocationSelection,
    orders: List<CustomerOrder> = emptyList(),
    progress: (String) -> Unit = {}
): Plan {
    require(destinations.size in 1..30) { "Offline optimization supports 1–30 physical stops." }
    require(startLocation.isValid() && endSelection.isValid())
    val end = if (endSelection.useLastDelivery) null else
        (endSelection.location ?: startLocation).asPlace()
    val differentEnd = end?.takeUnless {
        it.lat == startLocation.latitude && it.lon == startLocation.longitude
    }
    val places = listOf(startLocation.asPlace()) + destinations + listOfNotNull(differentEnd)
    val endIndex: Int? = when {
        endSelection.useLastDelivery -> null
        differentEnd == null -> 0
        else -> places.lastIndex
    }
    progress("Calculating directed driving-road distances offline…")
    val matrix = graph.distanceMatrix(places.map { GpsPoint(it.lat, it.lon) })
    val costs = Array(places.size) { a ->
        DoubleArray(places.size) { b ->
            val leg = matrix[a][b]
            leg.meters + leg.seconds * 1.6 +
                trafficBuffer(leg.meters / 1000, leg.seconds, mode) * 1.6
        }
    }
    progress("Optimizing road-based delivery order without internet…")
    val order = Optimizer.optimize(costs, endIndex)
    val path = listOf(0) + order + listOfNotNull(endIndex)
    val geometry = mutableListOf<List<Double>>()
    val drivingLegs = path.zipWithNext().map { (a, b) ->
        val route = graph.route(
            GpsPoint(places[a].lat, places[a].lon),
            GpsPoint(places[b].lat, places[b].lon)
        )
        for ((i, point) in route.points.withIndex()) {
            if (geometry.isNotEmpty() && i == 0) continue
            geometry.add(listOf(point.lon, point.lat))
        }
        Leg(route.meters / 1000, route.seconds,
            trafficBuffer(route.meters / 1000, route.seconds, mode))
    }
    val legs = if (endSelection.useLastDelivery) drivingLegs + Leg(0.0, 0.0, 0.0)
        else drivingLegs
    val route = schedule(order.map { places[it] }, legs, start, serviceMinutes, mode,
        geometry, startLocation,
        endLocation = if (endSelection.useLastDelivery) null else endSelection.location,
        endAtLastDelivery = endSelection.useLastDelivery
    )
    return if (orders.isEmpty()) route else expandCustomerOrders(route, orders)
}
