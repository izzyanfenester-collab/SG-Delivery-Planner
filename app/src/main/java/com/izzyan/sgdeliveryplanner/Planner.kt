package com.izzyan.sgdeliveryplanner

import kotlin.math.*
import java.time.LocalDateTime

 data class Place(val postal: String, val lat: Double, val lon: Double, val block: String, val area: String, val address: String)
val depot = Place("738203",1.4464,103.7696,"Woodlands Checkpoint","Woodlands","21 Woodlands Crossing")
data class Input(val valid: List<String>, val invalid: List<String>, val duplicates: Int)
fun parseInput(raw: String): Input {
 val tokens = raw.trim().split(Regex("[\\s,;]+" )).filter { it.isNotEmpty() }
 val valid = tokens.filter { it.matches(Regex("[0-9]{6}")) }
 return Input(valid.distinct(),tokens.filterNot { it.matches(Regex("[0-9]{6}")) }.distinct(),valid.size-valid.distinct().size)
}
data class Leg(val km: Double, val baseSeconds: Double, val bufferSeconds: Double) { val plannedSeconds get() = baseSeconds + bufferSeconds }
data class Stop(val place: Place, val leg: Leg, val arrival: String, val leave: String, val cumulativeKm: Double, val status: String = "PENDING", val completedAt: String? = null)
data class Plan(val id: String, val created: String, val start: String, val serviceMinutes: Int, val mode: String, val stops: List<Stop>, val returnLeg: Leg, val returned: String, val geometry: List<List<Double>>, val current: Int = 0, val actualCompletion: String? = null) {
 val totalKm get() = stops.sumOf { it.leg.km } + returnLeg.km
 val baseSeconds get() = stops.sumOf { it.leg.baseSeconds } + returnLeg.baseSeconds
 val bufferSeconds get() = stops.sumOf { it.leg.bufferSeconds } + returnLeg.bufferSeconds
}
fun trafficBuffer(km: Double, seconds: Double, mode: String): Double {
 // Slow roads receive a larger junction allowance than faster expressway legs.
 val speed = if (seconds > 0) km / (seconds / 3600) else 0.0
 val fraction = if (speed < 30) .24 else if (speed < 50) .16 else .10
 val factor = when(mode) { "Light Traffic" -> .6; "Heavy Traffic" -> 1.8; else -> 1.0 }
 return if (seconds <= 0) 0.0 else (seconds * fraction + min(km, 8.0) * 12) * factor
}
object Optimizer {
 fun cost(order: List<Int>, costs: Array<DoubleArray>): Double = (listOf(0)+order+0).zipWithNext().sumOf { (a,b) -> costs[a][b] }
 fun optimize(costs: Array<DoubleArray>): List<Int> {
  require(costs.size >= 2 && costs.all { it.size == costs.size && it.all { v -> v.isFinite() && v >= 0 } })
  val remaining = (1 until costs.size).toMutableSet(); val order = mutableListOf<Int>(); var last = 0
  while(remaining.isNotEmpty()) { val next = remaining.minBy { costs[last][it] }; order.add(next); remaining.remove(next); last = next }
  // Evaluate the entire directed tour: reversing a section changes internal directed legs too.
  repeat(12) {
   var best = cost(order,costs); var improved: List<Int>? = null
   for (i in order.indices) for (j in i+1 until order.size) {
    val reversed = order.toMutableList().apply { subList(i,j+1).reverse() }
    val swapped = order.toMutableList().apply { val t=this[i]; this[i]=this[j]; this[j]=t }
    for (candidate in listOf(reversed,swapped)) { val c=cost(candidate,costs); if(c < best-.01) { best=c; improved=candidate } }
   }
   if(improved == null) return order
   order.clear(); order.addAll(improved!!)
  }
  return order
 }
}
fun schedule(places: List<Place>, legs: List<Leg>, start: LocalDateTime, service: Int, mode: String, geometry: List<List<Double>>): Plan {
 require(legs.size == places.size+1 && places.isNotEmpty())
 var clock=start; var distance=0.0
 val stops=places.mapIndexed { i,p ->
  val leg=legs[i]; clock=clock.plusSeconds(ceil(leg.plannedSeconds).toLong()); val arrival=clock
  clock=clock.plusMinutes(service.toLong()); distance+=leg.km
  Stop(p,leg,arrival.toString(),clock.toString(),distance)
 }
 return Plan(java.util.UUID.randomUUID().toString(),LocalDateTime.now(java.time.ZoneId.of("Asia/Singapore")).toString(),start.toString(),service,mode,stops,legs.last(),clock.plusSeconds(ceil(legs.last().plannedSeconds).toLong()).toString(),geometry)
}
