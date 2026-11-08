package com.izzyan.sgdeliveryplanner

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Local Shortbread vector-style file. No network fonts/icons/tile URLs. */
internal fun singaporeOfflineMapStyle(context: Context, night: Boolean): String? {
    val path = OfflineRoadPackageStore(context.applicationContext).offlineVectorTilePath()
        ?: return null
    val navy = if (night) "#081B33" else "#102A4C"
    val pale = if (night) "#B3C1D0" else "#C5D2DC"
    fun layer(id: String, type: String, sourceLayer: String? = null,
              paint: JsonObject): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("type", type)
        if (sourceLayer != null) {
            addProperty("source", "sg")
            addProperty("source-layer", sourceLayer)
        }
        add("paint", paint)
    }
    fun paint(key: String, value: String) = JsonObject().apply {
        addProperty(key, value)
    }
    return JsonObject().apply {
        addProperty("version", 8)
        addProperty("name", "SG Delivery offline Singapore streets")
        add("sources", JsonObject().apply {
            add("sg", JsonObject().apply {
                addProperty("type", "vector")
                addProperty("url", "pmtiles://file://$path")
                addProperty("minzoom", 0)
                addProperty("maxzoom", 15)
            })
        })
        add("layers", JsonArray().apply {
            add(layer("sg-background", "background", paint = paint("background-color", navy)))
            add(layer("sg-land", "fill", "land", paint = paint("fill-color", "#152F50")))
            add(layer("sg-ocean", "fill", "ocean", paint = paint("fill-color", "#061326")))
            add(layer("sg-water", "fill", "water_polygons", paint = paint("fill-color", "#092742")))
            add(layer("sg-sites", "fill", "sites", paint = paint("fill-color", "#183B48")))
            add(layer("sg-buildings", "fill", "buildings", paint = paint("fill-color", "#24405B")))
            add(layer("sg-roads-casing", "line", "streets",
                paint = JsonObject().apply {
                    addProperty("line-color", "#29415D")
                    add("line-width", JsonArray().apply {
                        add("interpolate"); add(JsonArray().apply { add("linear") })
                        add(JsonArray().apply { add("zoom") })
                        add(10); add(1.4); add(14); add(3.2); add(18); add(7.0)
                    })
                }))
            add(layer("sg-roads-main", "line", "streets",
                paint = JsonObject().apply {
                    addProperty("line-color", pale)
                    add("line-width", JsonArray().apply {
                        add("interpolate"); add(JsonArray().apply { add("linear") })
                        add(JsonArray().apply { add("zoom") })
                        add(10); add(0.8); add(14); add(1.9); add(18); add(4.0)
                    })
                    addProperty("line-opacity", 0.91)
                }))
        })
    }.toString()
}
