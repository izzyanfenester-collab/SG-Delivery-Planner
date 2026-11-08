package com.izzyan.sgdeliveryplanner

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Independent offline graph regression using synthetic real binary graph bytes. */
class OfflineRoadGraphTest {
    private fun tinyGraph(): File {
        val file = File.createTempFile("sg-roads-test-", ".bin")
        file.deleteOnExit()
        // Nodes 0..5; alternative shortcut 1->4->3 must be rejected by turn rule.
        // 0->1->2->3 is a valid one-way 300m route.
        val edges = listOf(
            intArrayOf(1, 100, 20, 1), // from node0
            intArrayOf(2, 100, 20, 1), // from node1
            intArrayOf(4, 20, 5, 3),
            intArrayOf(3, 100, 20, 2), // from node2
            intArrayOf(5, 20, 5, 4),  // from node3
            intArrayOf(3, 10, 3, 3),  // from node4
            intArrayOf(3, 20, 5, 4)   // from node5
        )
        val starts = intArrayOf(0, 1, 3, 4, 5, 6)
        val degrees = intArrayOf(1, 2, 1, 1, 1, 1)
        val b = ByteBuffer.allocate(32 + 6 * 16 + edges.size * 16 + 16)
            .order(ByteOrder.LITTLE_ENDIAN)
        b.put("SGR1".toByteArray(Charsets.US_ASCII))
        listOf(2, 6, edges.size, 1, 0, 0, 0).forEach(b::putInt)
        for (n in 0..5) {
            b.putInt((1.3 + n * 0.001).times(1e7).toInt())
            b.putInt((103.8 + n * 0.001).times(1e7).toInt())
            b.putInt(starts[n])
            b.putInt(degrees[n] or Int.MIN_VALUE)
        }
        edges.forEach { edge -> edge.forEach(b::putInt) }
        listOf(1, 1, 3, 0).forEach(b::putInt) // NO turn at node 1 from way 1 to way 3
        file.writeBytes(b.array())
        return file
    }

    @Test fun mapGraphValidatesBinaryAndRespectsOneWaysAndTurnRestrictions() {
        OfflineRoadGraph.open(tinyGraph()).use { roads ->
            assertEquals(6, roads.nodeCount)
            assertEquals(7, roads.edgeCount)
            assertEquals(1, roads.restrictionCount)
            val result = roads.route(0, 3)
            assertEquals(300.0, result.metres, 0.001)
            assertEquals(60.0, result.seconds, 0.001)
            assertEquals(4, result.geometry.size)
            val cost = roads.costs(0, intArrayOf(3, 0))
            assertEquals(300.0, cost.metres[0], 0.001)
            assertEquals(0.0, cost.metres[1], 0.001)
            assertTrue(roads.costs(3, intArrayOf(0)).metres[0].isInfinite())
        }
    }

    @Test fun closestDrivableVertexRejectsInventedAndDistantCoordinates() {
        OfflineRoadGraph.open(tinyGraph()).use { graph ->
            val nearby = Place("550001", 1.3000, 103.8000, "", "", "")
            assertEquals(0, graph.snap(nearby).node)
            val nowhere = nearby.copy(lat = 1.45, lon = 103.95)
            try {
                graph.snap(nowhere)
                fail("Coordinates distant from any drivable road must not be guessed")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun fiftyStopsImproveOverBacktrackingAndPreserveFixedEnd() {
        val nodes = 52 // Start 0, 50 delivery stops, End 51
        val matrix = Array(nodes) { i ->
            DoubleArray(nodes) { j -> if (i == j) 0.0 else kotlin.math.abs(i - j) * 100.0 }
        }
        val order = OfflineTourOptimizer.optimize(matrix, nodes - 1)
        assertEquals(50, order.size)
        assertEquals((1..50).toList(), order)
        val inefficient = (1..50).toList().filter { it % 2 == 0 } +
            (1..50).toList().filter { it % 2 == 1 }
        assertTrue(Optimizer.cost(order, matrix, 51) < Optimizer.cost(inefficient, matrix, 51))
        assertEquals(5100.0, Optimizer.cost(order, matrix, 51), 0.001)
    }

    @Test fun fiftyStopsCanEndAtLastDeliveryWithoutReturningToStart() {
        val nodes = 51
        val costs = Array(nodes) { i ->
            DoubleArray(nodes) { j -> kotlin.math.abs(i - j) * 100.0 }
        }
        val order = OfflineTourOptimizer.optimize(costs, null)
        assertEquals(50, order.size)
        assertEquals(5000.0, Optimizer.cost(order, costs, null), 0.001)
    }
}
