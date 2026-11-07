package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertTrue
import org.junit.Test

class Real3DVehicleMeshTest {
    @Test fun modelIsRealThreeDimensionalMeshNotBitmap() {
        val vertices = VehicleMesh.create()
        assertTrue("3D mesh contains many triangle faces", vertices.size > 1200)
        assertTrue("each vertex has XYZ position and RGB color", vertices.size % 18 == 0)
        val xs = vertices.indices.step(6).map { vertices[it] }
        val ys = vertices.indices.step(6).map { vertices[it + 1] }
        val zs = vertices.indices.step(6).map { vertices[it + 2] }
        assertTrue(xs.maxOrNull()!! - xs.minOrNull()!! > 1.0f)
        assertTrue(ys.maxOrNull()!! - ys.minOrNull()!! > 1.0f)
        assertTrue(zs.maxOrNull()!! - zs.minOrNull()!! > .75f)
        assertTrue("all mesh components finite", vertices.all { it.isFinite() })
    }

    @Test fun vehicleMeshesAreReproducible() {
        val a = VehicleMesh.create()
        val b = VehicleMesh.create()
        assertTrue(a.contentEquals(b))
    }
}
