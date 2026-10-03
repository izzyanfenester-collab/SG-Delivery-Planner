package com.izzyan.delivery

import kotlin.math.*

fun parsePostalCodes(raw: String): PostalInput {
    val tokens = raw.trim().split(Regex("[\\s,;]+" )).filter { it.isNotEmpty() }
    val valid = tokens.filter { it.matches(Regex("[0-9]{6}")) }
    return PostalInput(valid.distinct(), tokens.filterNot { it.matches(Regex("[0-9]{6}")) }.distinct(), valid.size - valid.distinct().size)
}
fun trafficBuffer(km: Double, seconds: Double, mode: String): Double {
    require(km.isFinite() && km >= 0 && seconds.isFinite() && seconds >= 0)
    val speed = if (seconds > 0) km / (seconds / 3600) else 0.0
    val fraction = if (speed < 30) .24 else if (speed < 50) .16 else .10
    val factor = when(mode) { "Light Traffic" -> .6; "Heavy Traffic" -> 1.8; else -> 1.0 }
    return if (seconds <= 0) 0.0 else (seconds * fraction + min(km, 8.0) * 12) * factor
}
/** Same directed-tour heuristic as Android: nearest neighbor, then 12 full-tour improvement passes. */
object Optimizer {
    fun cost(order: List<Int>, costs: Array<DoubleArray>): Double = (listOf(0)+order+0).zipWithNext().sumOf { (a,b) -> costs[a][b] }
    fun optimize(costs: Array<DoubleArray>): List<Int> {
        require(costs.size in 2..51 && costs.all { it.size == costs.size && it.all { v -> v.isFinite() && v >= 0 } }) { "A valid directed road matrix for 1–50 deliveries is required." }
        val remaining = (1 until costs.size).toMutableSet(); val order = mutableListOf<Int>(); var last = 0
        while(remaining.isNotEmpty()) { val next = remaining.minBy { costs[last][it] }; order.add(next); remaining.remove(next); last = next }
        repeat(12) {
            var best = cost(order,costs); var improved: List<Int>? = null
            for (i in order.indices) for (j in i+1 until order.size) {
                val reversed = order.toMutableList().apply { subList(i,j+1).reverse() }
                val swapped = order.toMutableList().apply { val t=this[i]; this[i]=this[j]; this[j]=t }
                for (candidate in listOf(reversed,swapped)) { val c=cost(candidate,costs); if(c < best-.01) { best=c; improved=candidate } }
            }
            if(improved == null) return order
            order.clear(); order.addAll(requireNotNull(improved))
        }
        return order
    }
}
fun optimizeTour(matrix: RoadMatrix): Tour {
    val n = matrix.places.size
    require(n in 2..51 && matrix.meters.size == n && matrix.seconds.size == n)
    require(matrix.meters.all { it.size == n && it.all { v -> v.isFinite() && v >= 0 } })
    require(matrix.seconds.all { it.size == n && it.all { v -> v.isFinite() && v >= 0 } })
    val costs = Array(n) { i -> DoubleArray(n) { j -> matrix.seconds[i][j] + trafficBuffer(matrix.meters[i][j]/1000, matrix.seconds[i][j], matrix.mode) + matrix.meters[i][j]/1000*15 } }
    val order = Optimizer.optimize(costs)
    val legs = (listOf(0)+order+0).zipWithNext().map { (a,b) -> Leg(matrix.meters[a][b]/1000, matrix.seconds[a][b], trafficBuffer(matrix.meters[a][b]/1000,matrix.seconds[a][b],matrix.mode)) }
    return Tour(order, order.map { matrix.places[it] }, legs)
}
fun schedule(input: ScheduleInput): Route {
    val places = input.tour.places; val legs = input.tour.legs
    require(places.size in 1..50 && legs.size == places.size+1 && input.serviceMinutes in 0..120 && input.startLocation.valid())
    require(legs.all { it.km.isFinite() && it.km >= 0 && it.baseSeconds.isFinite() && it.baseSeconds >= 0 && it.bufferSeconds.isFinite() && it.bufferSeconds >= 0 })
    var clock = input.start; var distance = 0.0
    val stops = places.mapIndexed { i,p ->
        val leg = legs[i]; clock += ceil(leg.plannedSeconds).toLong(); val arrival = clock
        clock += input.serviceMinutes*60L; distance += leg.km
        Stop(p,leg,arrival,clock,distance)
    }
    return Route(input.id,input.created,input.start,input.serviceMinutes,input.mode,stops,legs.last(),clock+ceil(legs.last().plannedSeconds).toLong(),input.geometry,input.startLocation)
}
val holdReasons = listOf("Customer not home", "No answer", "Reschedule", "Payment issue", "Access issue", "Other")
fun reviewStop(input: ReviewInput): Route {
    val route = input.route
    require(route.current in route.stops.indices) { "Select a delivery first." }
    val original = route.stops[route.current]
    val status = when(input.action) {
        "DELIVERED" -> DeliveryStatus.DELIVERED
        "ON_HOLD" -> DeliveryStatus.ON_HOLD
        "SKIP", "SKIPPED" -> DeliveryStatus.SKIPPED
        "PENDING" -> DeliveryStatus.PENDING
        "NEXT" -> original.status
        else -> error("Unknown delivery action.")
    }
    val alreadyReviewed = input.action == "NEXT" && original.reviewedAt != null
    val reason = if(input.action == "NEXT") original.holdReason else input.holdReason?.takeIf { status == DeliveryStatus.ON_HOLD && it in holdReasons }
    val stops = route.stops.toMutableList()
    stops[route.current] = original.copy(status=status,
        completedAt=if(input.action == "NEXT") original.completedAt else input.now.takeIf { status == DeliveryStatus.DELIVERED },
        holdReason=reason,
        holdNote=if(input.action == "NEXT") original.holdNote else input.holdNote?.trim()?.take(160)?.takeIf { reason == "Other" && it.isNotEmpty() },
        reviewedAt=if(alreadyReviewed) original.reviewedAt else input.now)
    val next = (route.current+1 until stops.size).firstOrNull { stops[it].reviewedAt == null }
        ?: stops.indexOfFirst { it.reviewedAt == null }.takeIf { it >= 0 } ?: stops.size
    return route.copy(stops=stops,current=next,
        actualCompletion=if(alreadyReviewed) route.actualCompletion else if(stops.all { it.status == DeliveryStatus.DELIVERED }) stops.mapNotNull { it.completedAt }.maxOrNull() ?: input.now else null,
        reviewedFinishedAt=if(alreadyReviewed) route.reviewedFinishedAt ?: stops.mapNotNull { it.reviewedAt }.maxOrNull().takeIf { next == stops.size } else input.now.takeIf { next == stops.size },
        summarySavedAt=if(alreadyReviewed) route.summarySavedAt else null)
}
fun summary(route: Route): DeliverySummary {
    val total = route.stops.size
    val delivered = route.stops.count { it.status == DeliveryStatus.DELIVERED }
    val hold = route.stops.count { it.status == DeliveryStatus.ON_HOLD }
    val skipped = route.stops.count { it.status == DeliveryStatus.SKIPPED }
    val finish = route.reviewedFinishedAt ?: route.actualCompletion ?: route.stops.lastOrNull()?.leave ?: route.start
    return DeliverySummary(total,delivered,hold,skipped,total-delivered-hold-skipped,
        if(total == 0) 0.0 else floor(delivered*1000.0/total+.5)/10,
        route.start,finish,(finish-route.start).coerceAtLeast(0),route.stops.sumOf { it.leg.km }+route.returnLeg.km,route.returned,route.cashOnHand,route.tax)
}
/** String arithmetic preserves cents on all platforms. */
fun normalizedCurrency(text: String): String? {
    if(!text.matches(Regex("[0-9]{0,9}(\\.[0-9]{0,2})?"))) return null
    if(text.isEmpty()) return "0.00"
    if(text == ".") return null
    val parts = text.split('.')
    val whole = parts[0].trimStart('0').ifEmpty { "0" }
    return "$whole.${parts.getOrElse(1) { "" }.padEnd(2,'0')}"
}
fun saveMoney(input: MoneyInput): Route = input.route.copy(
    cashOnHand=CashOnHand(requireNotNull(normalizedCurrency(input.cashOnHand)) { "Enter a non-negative Cash on Hand amount with up to two decimal places." }),
    tax=Tax(requireNotNull(normalizedCurrency(input.tax)) { "Enter a non-negative Tax amount with up to two decimal places." }), summarySavedAt=input.now)
