package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class ErpLayerOrder878Test {
    private val sign=ErpMapLayer876.LAYER
    @Test fun nativeRouteAnnotationGoesBelowErpAndVehicleGoesAbove() {
        val layers=listOf(
            "background" to false,
            "mapbox-android-line-layer" to true,
            "mapbox-android-symbol-layer" to false,
            "mapbox-location-foreground-layer" to false
        )
        assertEquals(ErpLayerSlot878("mapbox-location-foreground-layer",true),ErpLayerOrder878.slot(layers))
        assertTrue(ErpLayerOrder878.correct(layers.toMutableList().apply{add(3,sign to false)},sign))
        assertFalse(ErpLayerOrder878.correct(layers.toMutableList().apply{add(1,sign to false)},sign))
    }

    @Test fun reorderingFallsBackToAbovePolylineWhenNoVehicleForeground() {
        val layers=listOf("background" to false,"mapbox-android-line-layer" to true,"camera-marker" to false)
        assertEquals(ErpLayerSlot878("mapbox-android-line-layer",false),ErpLayerOrder878.slot(layers))
    }

    @Test fun genericBaseMapLinesAreNotMistakenForNavigationRoute() {
        val layers=listOf("road-centerline" to true,"maplibre-location-foreground-layer" to false)
        assertEquals(ErpLayerSlot878("maplibre-location-foreground-layer",true),ErpLayerOrder878.slot(layers))
        assertTrue(ErpLayerOrder878.correct(listOf(layers[0],sign to false,layers[1]),sign))
    }

    @Test fun annotationOrderingIsIdempotentOnRepeatedSyncChecks() {
        val layers=listOf("background" to false,"mapbox-android-line-layer" to true,
            sign to false,"mapbox-location-foreground-layer" to false)
        assertTrue(ErpLayerOrder878.correct(layers,sign))
        assertEquals(ErpLayerSlot878("mapbox-location-foreground-layer",true),
            ErpLayerOrder878.slot(layers.filterNot{it.first==sign}))
    }
}
