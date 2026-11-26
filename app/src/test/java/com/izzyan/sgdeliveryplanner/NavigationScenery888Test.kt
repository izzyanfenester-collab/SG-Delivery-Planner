package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

/** Checks stable source identity and conservative unknown-data handling without a renderer. */
class NavigationScenery888Test {
    @Test fun vegetationClassificationPreservesStreetsAndNonVegetation() {
        for (id in listOf("road_street", "bridge_street", "street-name", "landcover_ice", "landcover_sand", "poi", "water"))
            assertNull(id, NavigationScenery888.vegetationColour(id, false))
        for (id in listOf("tree-canopy", "trees", "landcover_wood", "landcover_grass", "park", "garden")) {
            for (dark in listOf(false, true)) {
                val rgb = requireNotNull(NavigationScenery888.vegetationColour(id, dark)).drop(1).chunked(2).map { it.toInt(16) }
                assertTrue("Natural green $id", rgb[1] > rgb[0] && rgb[1] > rgb[2])
            }
        }
        assertNotEquals(NavigationScenery888.vegetationColour("tree-canopy", false), NavigationScenery888.vegetationColour("tree-trunk", false))
    }
    private fun colour(id:Any?,properties:Map<String,Any?> = emptyMap(),dark:Boolean=false):String {
        fun evaluate(value:JsonElement):Any? {
            if(value.isJsonNull)return null
            if(!value.isJsonArray)return if(value.asJsonPrimitive.isNumber)value.asDouble else value.asString
            val a=value.asJsonArray
            return when(a[0].asString) {
                "id"->id
                "get"->properties[a[1].asString]
                "coalesce"->(1 until a.size()).firstNotNullOfOrNull { i->evaluate(a[i]) }
                "to-number"->(1 until a.size()).firstNotNullOf { i->
                    when(val input=evaluate(a[i])) {
                        null,false->0.0
                        true->1.0
                        else->input.toString().trim().let { if(it.isEmpty())0.0 else it.toDoubleOrNull() }
                    }
                }
                "abs"->abs(evaluate(a[1]) as Double)
                "floor"->floor(evaluate(a[1]) as Double)
                "%"->(evaluate(a[1]) as Double)%(evaluate(a[2]) as Double)
                "match"->{
                    val key=evaluate(a[1]);var result=evaluate(a[a.size()-1])
                    for(i in 2 until a.size()-1 step 2)if(evaluate(a[i])==key)result=evaluate(a[i+1])
                    result
                }
                else->error("Unexpected paint operator ${a[0]}")
            }
        }
        return evaluate(JsonParser.parseString(NavigationScenery888.buildingExpressionJson(dark))) as String
    }

    @Test fun sourceIdentityWinsOverChangingTileAttributes() {
        for(id in listOf(11676816420L,1542496230L,-1234L)) {
            val initial=colour(id)
            assertEquals(initial,colour(id,mapOf("osm_id" to 99,"id" to 3,"render_height" to 120)))
            assertEquals(initial,colour(id.toString(),mapOf("render_height" to 4)))
        }
    }
    @Test fun missingOrNonNumericFeatureIdUsesActualIntrinsicAttributes() {
        assertEquals(colour(7),colour(null,mapOf("osm_id" to 7)))
        assertEquals(colour(8),colour("way/no-id",mapOf("osm_id" to "8")))
        assertEquals(colour(9),colour(null,mapOf("id" to 9)))
        assertEquals(colour(19),colour(null,mapOf("render_height" to 19)))
        assertEquals(colour(0),colour(null))
    }
    @Test fun everyPaletteEntryRemainsReachableAndDayNightKeepTheSameGrouping() {
        assertEquals(6,(0..5).map { colour(it) }.toSet().size)
        for(i in 0..5) {
            assertEquals(NavigationScenery888.day[i],colour(i))
            assertEquals(NavigationScenery888.night[i],colour(i,dark=true))
        }
    }
    @Test fun unrelatedExtrusionsAndNavigationOverlaysAreExcluded() {
        assertTrue(NavigationScenery888.isBuilding("building-3d",null))
        assertTrue(NavigationScenery888.isBuilding("extrusion","building"))
        assertTrue(NavigationScenery888.isBuilding("Buildings-3D","custom"))
        for(id in listOf("terrain-3d","bridge-deck","water","sg-navigation-erp-876","mapbox-location-foreground-layer"))
            assertFalse(id,NavigationScenery888.isBuilding(id,"transportation"))
    }
    @Test fun colourExpressionHasNoDynamicCameraOrGpsInputsAndPreservesRouteBlueDistinction() {
        val expression=NavigationScenery888.buildingExpressionJson(false)
        for(operator in listOf("zoom","random","feature-state","global-state","heatmap-density","line-progress"))
            assertFalse(expression.contains("\"$operator\""))
        for(hex in NavigationScenery888.day+NavigationScenery888.night) {
            val rgb=hex.drop(1).chunked(2).map { it.toInt(16) }
            assertTrue("Restrained colour spread $hex",rgb.max()-rgb.min()<=100)
            assertNotEquals("#0084FF",hex.uppercase())
        }
        assertTrue(NavigationScenery888.BUILDING_OPACITY in 0.5f..0.65f)
    }
}
