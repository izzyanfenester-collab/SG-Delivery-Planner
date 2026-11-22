package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.routeprogress.RouteProgress
import java.util.Locale

/** Provider lane markings, in physical left-to-right order. No vehicle-lane inference. */
internal enum class LaneDirection885 {
    STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT,
    UTURN, MERGE_LEFT, MERGE_RIGHT;
    companion object {
        fun parse(text: String): LaneDirection885? = when (text.lowercase(Locale.ROOT).replace('_', ' ').trim()) {
            "straight", "through" -> STRAIGHT
            "left" -> LEFT
            "right" -> RIGHT
            "slight left" -> SLIGHT_LEFT
            "slight right" -> SLIGHT_RIGHT
            "sharp left" -> SHARP_LEFT
            "sharp right" -> SHARP_RIGHT
            "uturn", "u turn", "reverse" -> UTURN
            "merge to left", "merge left" -> MERGE_LEFT
            "merge to right", "merge right" -> MERGE_RIGHT
            else -> null
        }
    }
}

internal data class GuidanceLane885(
    val directions: List<LaneDirection885>,
    val valid: Boolean,
    val highlighted: Set<LaneDirection885>
)
internal data class LaneStrip885(val maneuverIndex: Int, val lanes: List<GuidanceLane885>)
internal data class ThenDirection885(val maneuverIndex: Int, val cue: NavigationPanelCue)
internal data class VisualGuidanceState885(
    val lanes: LaneStrip885? = null,
    val then: ThenDirection885? = null
)

/** Immutable route-scoped metadata; JSON is decoded once, never on a GPS frame. */
internal class VisualGuidanceRoute885 private constructor(
    private val route: DirectionsRoute,
    internal val laneStrips: Map<Int, LaneStrip885>,
    internal val missingLaneReasons: Map<Int, String>
) {
    fun state(activeRoute: DirectionsRoute?, primaryIndex: Int, metresToPrimary: Double?,
        offRoute: Boolean = false, arrived: Boolean = false): VisualGuidanceState885 {
        if (activeRoute != route || offRoute || arrived || metresToPrimary == null ||
            !metresToPrimary.isFinite() || metresToPrimary < 0 || primaryIndex !in route.legs.single().steps.indices)
            return VisualGuidanceState885()
        val steps = route.legs.single().steps
        if (normalized(steps[primaryIndex].maneuver.type?.toString()) in setOf("depart", "arrive"))
            return VisualGuidanceState885()
        val lanes = laneStrips[primaryIndex].takeIf { metresToPrimary <= LANE_APPROACH_METRES }
        var then: ThenDirection885? = null
        if (metresToPrimary <= THEN_PRIMARY_APPROACH_METRES) {
            var afterPrimary = 0.0
            for (index in primaryIndex + 1 until steps.size) {
                val precedingLength = steps[index - 1].distance
                if (!precedingLength.isFinite() || precedingLength < 0) break
                afterPrimary += precedingLength
                if (afterPrimary > THEN_FOLLOWING_METRES) break
                val step = steps[index]
                val type = normalized(step.maneuver.type?.toString())
                if (type == "arrive") break
                val modifier = normalized(step.maneuver.modifier?.toString())
                if (!actionable(type, modifier)) continue
                val cue = NavigationPanelSigns.cue(step.maneuver.type?.toString(),
                    step.maneuver.modifier?.toString(), step.maneuver.instruction)
                // Missing modifiers must not turn an unknown turn into a fabricated straight arrow.
                if (cue.sign == NavigationSign.STRAIGHT && modifier != "straight") continue
                then = ThenDirection885(index, cue)
                break
            }
        }
        return VisualGuidanceState885(lanes, then)
    }

    fun state(progress: RouteProgress): VisualGuidanceState885 {
        val next = progress.stepIndex + if (progress.currentLegProgress.currentStepProgress.nextStep != null) 1 else 0
        return state(progress.directionsRoute, next, progress.stepDistanceRemaining)
    }

    companion object {
        const val LANE_APPROACH_METRES = 1_000.0
        const val THEN_PRIMARY_APPROACH_METRES = 750.0
        const val THEN_FOLLOWING_METRES = 450.0
        private fun normalized(text: String?) = text.orEmpty().lowercase(Locale.ROOT)
            .replace('_', ' ').replace('-', ' ').trim()
        private fun actionable(type: String, modifier: String): Boolean = when (type) {
            "turn", "end of road", "fork", "merge", "off ramp", "on ramp", "ramp",
            "exit", "exit ramp", "motorway exit", "keep", "keep left", "keep right",
            "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary",
            "use lane" -> true
            "continue" -> modifier.isNotEmpty() && modifier != "straight"
            else -> false
        }
        private fun JsonElement.obj() = takeIf { isJsonObject }?.asJsonObject
        private fun JsonObject.array(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
        private fun JsonObject.text(name: String): String? = get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        private fun JsonObject.bool(name: String): Boolean? = get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        private fun point(array: JsonArray?): GpsPoint? = runCatching {
            if (array?.size() != 2) return null
            GpsPoint(array[1].asDouble, array[0].asDouble).takeIf { it.valid() }
        }.getOrNull()
        private fun parseLanes(array: JsonArray): List<GuidanceLane885>? = runCatching {
            // All-or-nothing: never remove a malformed lane and shift the physical lane order.
            if (array.size() !in 1..16) return null
            val lanes = array.map { item ->
                val lane = item.obj() ?: return null
                val indications = lane.array("indications") ?: return null
                if (indications.size() !in 1..4) return null
                val directions = indications.map { value ->
                    val text = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                        ?: return null
                    LaneDirection885.parse(text) ?: return null
                }.distinct()
                val valid = lane.bool("valid") ?: return null
                val explicitBranch = lane.text("valid_indication")?.let {
                    LaneDirection885.parse(it) ?: return null
                }
                if (explicitBranch != null && explicitBranch !in directions) return null
                val highlighted = if (!valid) emptySet() else
                    explicitBranch?.let { setOf(it) } ?: directions.toSet()
                GuidanceLane885(directions, valid, highlighted)
            }
            lanes.takeIf { it.any { lane -> lane.valid } }
        }.getOrNull()

        fun parse(json: String, route: DirectionsRoute): VisualGuidanceRoute885 {
            val strips = mutableMapOf<Int, LaneStrip885>()
            val missing = mutableMapOf<Int, String>()
            val rawSteps = runCatching {
                JsonParser.parseString(json).asJsonObject.array("routes")?.firstOrNull()?.obj()
                    ?.array("legs")?.singleOrNull()?.obj()?.array("steps")
            }.getOrNull()
            val steps = route.legs.single().steps
            for (index in steps.indices) {
                val raw = rawSteps?.takeIf { index < it.size() }?.get(index)?.obj()
                if (rawSteps?.size() != steps.size || raw == null) {
                    missing[index] = "Route metadata step count mismatch"; continue
                }
                val maneuverPoint = point(raw.get("maneuver")?.obj()?.array("location"))
                // OSRM intersections[0] is THIS step's maneuver. Later intersections
                // belong to other junctions and must never be used as its approach lanes.
                val intersection = raw.array("intersections")?.firstOrNull()?.obj()
                val intersectionPoint = point(intersection?.array("location"))
                if (maneuverPoint == null || intersectionPoint == null ||
                    navigationDistance(maneuverPoint, intersectionPoint) > 3.0) {
                    missing[index] = "No lane intersection matched to this maneuver"; continue
                }
                val rawLanes = intersection?.array("lanes")
                if (rawLanes == null) {
                    missing[index] = "Provider has no lane metadata at this maneuver"; continue
                }
                val lanes = parseLanes(rawLanes)
                if (lanes == null) missing[index] = "Incomplete or unsupported lane indications/validity"
                else strips[index] = LaneStrip885(index, lanes)
            }
            return VisualGuidanceRoute885(route, strips, missing)
        }
    }
}
