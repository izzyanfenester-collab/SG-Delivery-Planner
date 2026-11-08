package com.izzyan.sgdeliveryplanner

import java.io.File
import java.util.PriorityQueue
import java.util.zip.ZipFile
import kotlin.math.*

/** A directed, car-accessible road graph built from OSM nodes/ways and supported turn restrictions. */
internal data class OfflineRoadArc(
    val to: Int, val meters: Double, val seconds: Double, val wayId: Long
)
internal data class OfflineTurnRestriction(
    val viaNode: Int, val fromWay: Long, val toWay: Long, val only: Boolean
)
internal data class OfflineRoadPath(
    val points: List<GpsPoint>, val meters: Double, val seconds: Double
)
internal data class OfflineRoadCost(val meters: Double, val seconds: Double)

internal class OfflineRoadGraph(
    val nodes: List<GpsPoint>,
    val adjacency: Array<List<OfflineRoadArc>>,
    restrictions: List<OfflineTurnRestriction> = emptyList()
) {
    init {
        require(nodes.isNotEmpty() && nodes.size <= 1_000_000)
        require(adjacency.size == nodes.size)
        require(nodes.all { it.valid() && it.lat in 1.1..1.6 && it.lon in 103.5..104.2 })
        require(adjacency.all { row -> row.all {
            it.to in nodes.indices && it.meters.isFinite() && it.meters > 0 &&
                it.seconds.isFinite() && it.seconds > 0
        } })
        require(restrictions.all { it.viaNode in nodes.indices })
    }

    private val turns = restrictions.groupBy { it.viaNode }
    private val snapBuckets: Map<Long, List<Int>> = nodes.indices.groupBy { bucket(nodes[it]) }
    private fun cell(value: Double) = floor(value * 2000).toInt()
    private fun bucket(point: GpsPoint): Long =
        (cell(point.lat).toLong() shl 32) or (cell(point.lon).toLong() and 0xFFFF_FFFFL)
    private fun bucket(x: Int, y: Int): Long =
        (x.toLong() shl 32) or (y.toLong() and 0xFFFF_FFFFL)

    /** Never invent a coordinate or snap across an unbounded distance. */
    fun nearestNode(point: GpsPoint, maximumMeters: Double = 300.0): Int? {
        if (!point.valid()) return null
        val cx = cell(point.lat); val cy = cell(point.lon)
        var nearest: Int? = null
        var distance = maximumMeters
        // 0.0005 degrees is about 55m latitude; 7 cells span >=300m.
        for (x in cx-7..cx+7) for (y in cy-7..cy+7)
            for (i in snapBuckets[bucket(x, y)].orEmpty()) {
                val d = navigationDistance(point, nodes[i])
                if (d < distance) { nearest = i; distance = d }
            }
        return nearest
    }

    private data class Key(val at: Int, val incomingWay: Long)
    private data class Visit(val key: Key, val score: Double)
    private data class Cost(val meters: Double, val seconds: Double, val score: Double)
    private data class SearchResult(
        val origins: Key, val distances: Map<Key, Cost>,
        val previous: Map<Key, Key>, val reached: Map<Int, Key>
    )
    private fun legal(at: Int, incoming: Long, candidate: Long): Boolean {
        if (incoming < 0L) return true
        val applicable = turns[at].orEmpty().filter { it.fromWay == incoming }
        if (applicable.isEmpty()) return true
        val only = applicable.filter { it.only }
        return (only.isEmpty() || only.any { it.toWay == candidate }) &&
            applicable.none { !it.only && it.toWay == candidate }
    }

    /** Dijkstra on (node,incoming-way) preserves via-node turn restrictions. */
    private fun search(from: Int, targets: Set<Int>): SearchResult {
        val initial = Key(from, -1L)
        val distances = HashMap<Key, Cost>()
        val previous = HashMap<Key, Key>()
        val reached = HashMap<Int, Key>()
        val queue = PriorityQueue<Visit>(compareBy { it.score })
        distances[initial] = Cost(0.0, 0.0, 0.0)
        queue.add(Visit(initial, 0.0))
        while (queue.isNotEmpty() && reached.size < targets.size) {
            val next = queue.remove()
            val current = distances[next.key] ?: continue
            if (next.score > current.score + 1e-7) continue
            if (next.key.at in targets && next.key.at !in reached) {
                reached[next.key.at] = next.key
            }
            for (arc in adjacency[next.key.at]) {
                if (!legal(next.key.at, next.key.incomingWay, arc.wayId)) continue
                val candidate = Key(arc.to, arc.wayId)
                val meters = current.meters + arc.meters
                val seconds = current.seconds + arc.seconds
                // Primary distance and secondary travel time. No straight-line tour costs.
                val score = meters + seconds * 1.6
                if (score + 1e-7 < (distances[candidate]?.score ?: Double.POSITIVE_INFINITY)) {
                    distances[candidate] = Cost(meters, seconds, score)
                    previous[candidate] = next.key
                    queue.add(Visit(candidate, score))
                }
            }
        }
        return SearchResult(initial, distances, previous, reached)
    }

    fun distanceMatrix(points: List<GpsPoint>): Array<Array<OfflineRoadCost>> {
        require(points.size in 2..32) { "Offline route supports up to 30 stops and fixed endpoints." }
        val indices = points.mapIndexed { i, p ->
            nearestNode(p) ?: error("Location ${i+1} cannot be matched to an installed driving road.")
        }
        return Array(indices.size) { from ->
            val search = search(indices[from], indices.toSet())
            Array(indices.size) { to ->
                if (from == to || indices[from] == indices[to]) OfflineRoadCost(0.0, 0.0)
                else search.reached[indices[to]]?.let { key ->
                    search.distances.getValue(key).let { OfflineRoadCost(it.meters, it.seconds) }
                } ?: error("No legal offline driving connection from stop ${from+1} to ${to+1}.")
            }
        }
    }

    fun route(from: GpsPoint, to: GpsPoint): OfflineRoadPath {
        val a = nearestNode(from) ?: error("Current location is outside the offline driving graph.")
        val b = nearestNode(to) ?: error("Destination is outside the offline driving graph.")
        if (a == b) return OfflineRoadPath(listOf(nodes[a], nodes[b]), 0.0, 0.0)
        val result = search(a, setOf(b))
        var step = result.reached[b] ?: error("No legal offline road route to this destination.")
        val cost = result.distances.getValue(step)
        val reversed = mutableListOf(nodes[step.at])
        var count = 0
        while (step != result.origins) {
            step = result.previous[step] ?: error("Broken routing predecessor chain.")
            reversed.add(nodes[step.at])
            if (++count > 1_000_000) error("Offline road path is unreasonably long.")
        }
        return OfflineRoadPath(reversed.asReversed(), cost.meters, cost.seconds)
    }

    companion object {
        /** A deterministic archive: nodes.tsv, arcs.tsv, restrictions.tsv, metadata.json. */
        fun readZip(file: File): OfflineRoadGraph {
            ZipFile(file).use { zip ->
                fun readLines(name: String, maxLines: Int, handle: (List<String>) -> Unit) {
                    val entry = zip.getEntry(name) ?: error("Road package missing $name.")
                    require(entry.size in 0..250_000_000L) { "Road package entry is too large." }
                    zip.getInputStream(entry).bufferedReader().useLines { seq ->
                        var count = 0
                        for (line in seq) {
                            if (++count > maxLines) error("Road package has too many $name rows.")
                            if (line.isBlank() || line.startsWith("#")) continue
                            handle(line.split('\t'))
                        }
                    }
                }
                val nodes = ArrayList<GpsPoint>()
                readLines("nodes.tsv", 1_000_000) { cells ->
                    require(cells.size == 2)
                    nodes.add(GpsPoint(cells[0].toDouble(), cells[1].toDouble()))
                }
                require(nodes.isNotEmpty()) { "Road package contains no driving nodes." }
                val adjacency = Array(nodes.size) { mutableListOf<OfflineRoadArc>() }
                readLines("arcs.tsv", 4_000_000) { cells ->
                    require(cells.size == 5)
                    val from = cells[0].toInt()
                    require(from in nodes.indices)
                    adjacency[from].add(OfflineRoadArc(cells[1].toInt(),
                        cells[3].toDouble(), cells[4].toDouble(), cells[2].toLong()))
                }
                val restrictions = ArrayList<OfflineTurnRestriction>()
                readLines("restrictions.tsv", 150_000) { cells ->
                    require(cells.size == 4)
                    restrictions.add(OfflineTurnRestriction(
                        cells[0].toInt(), cells[1].toLong(), cells[2].toLong(), cells[3] == "only"
                    ))
                }
                return OfflineRoadGraph(nodes, Array(adjacency.size) { adjacency[it].toList() }, restrictions)
            }
        }
    }
}
