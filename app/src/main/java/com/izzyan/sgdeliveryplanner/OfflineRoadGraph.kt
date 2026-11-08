package com.izzyan.sgdeliveryplanner

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.PriorityQueue
import kotlin.math.*

/**
 * Memory-mapped directed road graph, independent of Internet and navigation UI.
 * Header "SGR1", version 1, nodeCount, edgeCount, turnCount, 3 reserved ints.
 * Node: latE7, lonE7, firstEdge, degree (16 bytes).
 * Edge: targetNode, lengthMetres, seconds, compactWayId (16 bytes).
 * Turn: viaNode, incomingWayId, outgoingWayId, mode (0=no; 1=only).
 */
internal class OfflineRoadGraph private constructor(
    private val channel: FileChannel,
    private val bytes: ByteBuffer,
    val nodeCount: Int,
    val edgeCount: Int,
    val restrictionCount: Int
) : Closeable {
    data class Snap(val node: Int, val distanceMetres: Double)
    data class RoadCosts(val metres: DoubleArray, val seconds: DoubleArray, val efforts: DoubleArray)
    data class RoadPath(val metres: Double, val seconds: Double, val geometry: List<List<Double>>)
    private data class QueueEntry(val edge: Int, val effort: Double)
    private class TurnRule {
        val forbidden = HashSet<Int>()
        val allowed = HashSet<Int>()
    }
    private val schemaVersion = bytes.getInt(4)
    private val nodeOffset = 32
    private val edgeOffset = nodeOffset + nodeCount * 16
    private val turnOffset = edgeOffset + edgeCount * 16
    private val edgeOrigin = IntArray(edgeCount)
    private val rules = HashMap<Long, TurnRule>()

    init {
        for (n in 0 until nodeCount) {
            val start = first(n)
            val size = degree(n)
            require(start >= 0 && size >= 0 && start.toLong() + size <= edgeCount) {
                "Invalid offline road index"
            }
            for (e in start until start + size) {
                edgeOrigin[e] = n
                require(to(e) in 0 until nodeCount && length(e) > 0 && seconds(e) > 0) {
                    "Invalid offline road connectivity or cost"
                }
            }
        }
        for (i in 0 until restrictionCount) {
            val off = turnOffset + i * 16
            val via = bytes.getInt(off)
            val from = bytes.getInt(off + 4)
            val to = bytes.getInt(off + 8)
            val type = bytes.getInt(off + 12)
            require(via in 0 until nodeCount && from >= 0 && to >= 0 && type in 0..1)
            val rule = rules.getOrPut(key(via, from)) { TurnRule() }
            if (type == 0) rule.forbidden.add(to) else rule.allowed.add(to)
        }
    }

    private fun no(n: Int) = nodeOffset + n * 16
    private fun eo(e: Int) = edgeOffset + e * 16
    private fun lat(n: Int) = bytes.getInt(no(n)).toDouble() / 1e7
    private fun lon(n: Int) = bytes.getInt(no(n) + 4).toDouble() / 1e7
    private fun first(n: Int) = bytes.getInt(no(n) + 8)
    private fun degree(n: Int) = bytes.getInt(no(n) + 12) and Int.MAX_VALUE
    private fun snapEligible(n: Int): Boolean =
        degree(n) > 0 && (schemaVersion < 2 || bytes.getInt(no(n) + 12) < 0)
    private fun to(e: Int) = bytes.getInt(eo(e))
    private fun length(e: Int) = bytes.getInt(eo(e) + 4)
    private fun seconds(e: Int) = bytes.getInt(eo(e) + 8)
    private fun way(e: Int) = bytes.getInt(eo(e) + 12)
    private fun key(via: Int, fromWay: Int): Long =
        (via.toLong() shl 32) or (fromWay.toLong() and 0xffffffffL)

    fun snap(place: Place, limitMetres: Double = 1200.0): Snap {
        require(place.lat in 1.0..1.7 && place.lon in 103.4..104.3) {
            "Offline routing only supports Singapore-area locations"
        }
        var nearest = -1
        var best = Double.POSITIVE_INFINITY
        val xScale = 111195.0 * cos(Math.toRadians(place.lat))
        for (i in 0 until nodeCount) {
            if (!snapEligible(i)) continue
            val dy = (lat(i) - place.lat) * 111195.0
            if (abs(dy) > limitMetres || abs(dy) >= best) continue
            val dx = (lon(i) - place.lon) * xScale
            val distance = hypot(dx, dy)
            if (distance < best) { best = distance; nearest = i }
        }
        require(nearest >= 0 && best <= limitMetres) {
            "No drivable road near postal " + place.postal + ". Check the offline package."
        }
        return Snap(nearest, best)
    }

    private fun permitted(via: Int, inWay: Int, outWay: Int): Boolean {
        val rule = rules[key(via, inWay)] ?: return true
        return outWay !in rule.forbidden && (rule.allowed.isEmpty() || outWay in rule.allowed)
    }

    private data class Search(
        val length: DoubleArray, val seconds: DoubleArray,
        val effort: DoubleArray, val parent: IntArray, val found: IntArray
    )

    /**
     * Edge-state Dijkstra: obeys one-way edges and simple OSM via-node turn rules.
     * Search terminates once all supplied target vertices have been settled.
     * U-turns incur a 180m penalty, not a physical ban unless OSM forbids them.
     */
    private fun search(source: Int, goals: IntArray, checkCancelled: () -> Unit): Search {
        require(source in 0 until nodeCount && goals.all { it in 0 until nodeCount })
        val effort = DoubleArray(edgeCount) { Double.POSITIVE_INFINITY }
        val metres = DoubleArray(edgeCount)
        val durations = DoubleArray(edgeCount)
        val parent = IntArray(edgeCount) { -1 }
        val found = IntArray(goals.size) { -1 }
        val pending = HashMap<Int, MutableList<Int>>()
        goals.forEachIndexed { index, target ->
            if (target == source) found[index] = -2
            else pending.getOrPut(target) { mutableListOf() }.add(index)
        }
        val heap = PriorityQueue(compareBy<QueueEntry> { it.effort })
        for (e in first(source) until first(source) + degree(source)) {
            effort[e] = length(e).toDouble()
            metres[e] = length(e).toDouble()
            durations[e] = seconds(e).toDouble()
            heap.add(QueueEntry(e, effort[e]))
        }
        var visited = 0
        while (heap.isNotEmpty() && pending.isNotEmpty()) {
            val current = heap.remove()
            val edge = current.edge
            if (current.effort > effort[edge] + 1e-8) continue
            if (++visited % 4096 == 0) checkCancelled()
            val via = to(edge)
            pending.remove(via)?.forEach { found[it] = edge }
            if (pending.isEmpty()) break
            for (next in first(via) until first(via) + degree(via)) {
                if (!permitted(via, way(edge), way(next))) continue
                val uturn = to(next) == edgeOrigin[edge]
                val penalty = if (uturn) 180.0 else if (way(edge) != way(next)) 12.0 else 0.0
                val candidate = current.effort + length(next) + penalty
                if (candidate + 1e-8 < effort[next]) {
                    effort[next] = candidate
                    metres[next] = metres[edge] + length(next)
                    durations[next] = durations[edge] + seconds(next)
                    parent[next] = edge
                    heap.add(QueueEntry(next, candidate))
                }
            }
        }
        return Search(metres, durations, effort, parent, found)
    }

    fun costs(source: Int, goals: IntArray, checkCancelled: () -> Unit = {}): RoadCosts {
        val s = search(source, goals, checkCancelled)
        fun value(i: Int, data: DoubleArray) = when (val edge = s.found[i]) {
            -2 -> 0.0
            -1 -> Double.POSITIVE_INFINITY
            else -> data[edge]
        }
        return RoadCosts(
            DoubleArray(goals.size) { value(it, s.length) },
            DoubleArray(goals.size) { value(it, s.seconds) },
            DoubleArray(goals.size) { value(it, s.effort) }
        )
    }

    fun route(from: Int, to: Int, checkCancelled: () -> Unit = {}): RoadPath {
        if (from == to) return RoadPath(0.0, 0.0, listOf(listOf(lon(from), lat(from))))
        val s = search(from, intArrayOf(to), checkCancelled)
        var edge = s.found[0]
        require(edge >= 0) { "No connected drivable road between the selected locations" }
        val last = edge
        val pathEdges = ArrayList<Int>()
        while (edge >= 0) {
            pathEdges.add(edge)
            edge = s.parent[edge]
            require(pathEdges.size <= edgeCount) { "Invalid road predecessor chain" }
        }
        val coords = ArrayList<List<Double>>(pathEdges.size + 1)
        coords.add(listOf(lon(from), lat(from)))
        for (part in pathEdges.asReversed()) coords.add(listOf(lon(to(part)), lat(to(part))))
        return RoadPath(s.length[last], s.seconds[last], coords)
    }

    override fun close() { channel.close() }

    companion object {
        fun open(file: File): OfflineRoadGraph {
            require(file.isFile && file.length() in 64L..250_000_000L) {
                "Install the verified Singapore offline road package first"
            }
            val raf = RandomAccessFile(file, "r")
            try {
                val channel = raf.channel
                val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    .order(ByteOrder.LITTLE_ENDIAN)
                require(mapped.getInt(0) == 0x31524753 && mapped.getInt(4) in 1..2) {
                    "Unsupported Singapore road graph version"
                }
                val nodes = mapped.getInt(8)
                val edges = mapped.getInt(12)
                val turns = mapped.getInt(16)
                require(nodes in 2..1_500_000 && edges in 1..10_000_000 &&
                    turns in 0..2_000_000) { "Invalid offline graph sizes" }
                require(32L + 16L * (nodes.toLong() + edges.toLong() + turns) == channel.size()) {
                    "Incomplete or damaged road graph"
                }
                return OfflineRoadGraph(channel, mapped, nodes, edges, turns)
            } catch (failure: Throwable) {
                raf.close()
                throw failure
            }
        }
    }
}
