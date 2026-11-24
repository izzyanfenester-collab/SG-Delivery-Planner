package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import java.util.Locale

/** Building paint only. Geometry, heights, sources, filters, lights and zoom rules stay intact. */
internal object NavigationBuildings886 {
    const val OPACITY = 0.55f
    val day = listOf("#9AB2C2", "#8FB5AE", "#ACB9A0", "#B5ACC4", "#C7BAA6", "#CCBE9E")
    val night = listOf("#8AA2B3", "#7FA79F", "#9AA98E", "#A69DB5", "#B6A992", "#BAAD8E")

    fun isBuilding(id:String, sourceLayer:String?):Boolean {
        val key=id.lowercase(Locale.ROOT)
        return sourceLayer?.lowercase(Locale.ROOT)=="building" || "building" in key ||
            "house" in key || "residential_area" in key
    }

    /** Source feature ID wins. Intrinsic attributes are conservative stable fallbacks;
     * no random/zoom/GPS/time inputs, feature-state, inferred occupancy or new data. */
    fun expressionJson(dark:Boolean):String = JsonArray().apply {
        add("match")
        // MapLibre converts null to zero. Non-numeric missing sentinels let later
        // genuine attributes participate instead of an absent ID pre-empting them.
        add(JsonParser.parseString("""["%",["floor",["abs",["to-number",["coalesce",["id"],"missing"],["coalesce",["get","osm_id"],"missing"],["coalesce",["get","id"],"missing"],["coalesce",["get","render_height"],"missing"],0]]],6]"""))
        (if(dark)night else day).forEachIndexed { index,color -> add(index);add(color) }
        add((if(dark)night else day).last())
    }.toString()

    private val dayExpression by lazy { Expression.raw(expressionJson(false)) }
    private val nightExpression by lazy { Expression.raw(expressionJson(true)) }

    fun apply(layer:FillExtrusionLayer,dark:Boolean) {
        if(!isBuilding(layer.id,layer.sourceLayer))return
        // RGBA colour alpha is ignored by fill-extrusion; use its supported
        // per-layer opacity property. Never change MapView or route opacity.
        layer.setProperties(fillExtrusionColor(if(dark)nightExpression else dayExpression),
            fillExtrusionOpacity(OPACITY))
    }
}
