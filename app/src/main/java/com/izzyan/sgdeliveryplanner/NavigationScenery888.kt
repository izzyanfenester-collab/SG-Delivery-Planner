package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import java.util.Locale

/** Paint existing scenery only. Source geometry, native lighting and navigation stay intact. */
internal object NavigationScenery888 {
    const val BUILDING_OPACITY = 0.60f
    val day = listOf("#8EB6D2", "#86BEB2", "#A6BC92", "#B5A4CD", "#CEB292", "#D5BF86")
    val night = listOf("#7C9DB8", "#75A69A", "#91A57D", "#9B8CB3", "#B59C7E", "#B9A571")

    private fun tokens(id: String) = id.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+"))
    fun isBuilding(id: String, sourceLayer: String?): Boolean =
        sourceLayer?.lowercase(Locale.ROOT) == "building" ||
            tokens(id).any { it in setOf("building", "buildings", "house", "houses") }

    /** Stable native feature identity; never tied to the camera, GPS, clock or random values. */
    fun buildingExpressionJson(dark: Boolean): String = JsonArray().apply {
        add("match")
        // Explicit sentinels avoid native to-number(null) swallowing later valid attributes.
        add(JsonParser.parseString("""["%",["floor",["abs",["to-number",["coalesce",["id"],"missing"],["coalesce",["get","osm_id"],"missing"],["coalesce",["get","id"],"missing"],["coalesce",["get","render_height"],"missing"],0]]],6]"""))
        (if (dark) night else day).forEachIndexed { index, colour -> add(index); add(colour) }
        add((if (dark) night else day).last())
    }.toString()

    private val dayExpression by lazy { Expression.raw(buildingExpressionJson(false)) }
    private val nightExpression by lazy { Expression.raw(buildingExpressionJson(true)) }

    /** Match whole tokens: "street" is a road, never a "tree". Mixed POI layers stay intact. */
    fun vegetationColour(id: String, dark: Boolean): String? {
        val names = tokens(id)
        return when {
            names.any { it in setOf("trunk", "trunks") } &&
                names.any { it in setOf("tree", "trees") } -> if (dark) "#7D6550" else "#927455"
            names.any { it in setOf("wood", "woods", "forest", "trees", "tree", "canopy", "orchard") } ->
                if (dark) "#35664A" else "#88B67E"
            names.any { it in setOf("grass", "grassland", "meadow", "scrub", "heath") } ->
                if (dark) "#437854" else "#B1D593"
            names.any { it in setOf("park", "parks", "garden", "gardens", "vegetation") } ->
                if (dark) "#3B7050" else "#A1CB91"
            else -> null
        }
    }

    /** Return true only when the requested scenery paint was applied. */
    fun apply(layer: Layer, dark: Boolean): Boolean {
        if (NavigationMapPalette.isNavigationAnnotation(layer.id)) return false
        val building = when (layer) {
            is FillExtrusionLayer -> isBuilding(layer.id, layer.sourceLayer)
            is FillLayer -> isBuilding(layer.id, layer.sourceLayer)
            else -> false
        }
        if (building) {
            val colour = if (dark) nightExpression else dayExpression
            when (layer) {
                is FillExtrusionLayer -> layer.setProperties(
                    fillExtrusionColor(colour), fillExtrusionOpacity(BUILDING_OPACITY))
                is FillLayer -> layer.setProperties(fillColor(colour))
            }
            return true
        }
        val colour = vegetationColour(layer.id, dark) ?: return false
        when (layer) {
            is FillLayer -> layer.setProperties(fillColor(colour))
            is FillExtrusionLayer -> layer.setProperties(fillExtrusionColor(colour))
            is CircleLayer -> layer.setProperties(circleColor(colour))
            is LineLayer -> layer.setProperties(lineColor(colour))
            is SymbolLayer -> {
                // Native icon-color colours SDF tree symbols. Existing full-colour sprites
                // keep their original artwork; no sprite replacement or fictitious trees.
                layer.setProperties(iconColor(colour))
                return false // Continue the baseline text/halo handling for any labels.
            }
            else -> return false
        }
        return true
    }
}
