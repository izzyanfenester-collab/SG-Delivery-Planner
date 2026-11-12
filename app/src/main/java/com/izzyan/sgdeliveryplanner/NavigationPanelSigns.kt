package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/** Navigation presentation only: the original GPS route and SDK remain unchanged. */
internal enum class NavigationSign(val drawable: Int) {
    STRAIGHT(R.drawable.nav_straight),
    TURN_LEFT(R.drawable.nav_turn_left), SLIGHT_LEFT(R.drawable.nav_slight_left),
    TURN_RIGHT(R.drawable.nav_turn_right), SLIGHT_RIGHT(R.drawable.nav_slight_right),
    UTURN(R.drawable.nav_uturn), ROUNDABOUT(R.drawable.nav_roundabout),
    SHARP_LEFT(R.drawable.nav_sharp_left), SHARP_RIGHT(R.drawable.nav_sharp_right),
    KEEP_LEFT(R.drawable.nav_keep_left), KEEP_RIGHT(R.drawable.nav_keep_right),
    FORK_LEFT(R.drawable.nav_fork_left), FORK_RIGHT(R.drawable.nav_fork_right),
    MERGE_LEFT(R.drawable.nav_merge_left), MERGE_RIGHT(R.drawable.nav_merge_right),
    EXIT_LEFT(R.drawable.nav_exit_left), EXIT_RIGHT(R.drawable.nav_exit_right),
    RAMP_LEFT(R.drawable.nav_ramp_left), RAMP_RIGHT(R.drawable.nav_ramp_right),
    ROUNDABOUT_EXIT(R.drawable.nav_roundabout_exit), ARRIVE(R.drawable.nav_arrive)
}

internal data class NavigationPanelCue(
    val sign: NavigationSign = NavigationSign.STRAIGHT,
    val expresswayExit: String? = null,
    val roundaboutExit: Int? = null
)

/** Preserve the provider's explicit exit fields even if the SDK model omits them. */
internal data class RouteSignMetadata(
    val type: String? = null,
    val modifier: String? = null,
    val instruction: String? = null,
    val exitNumber: String? = null,
    val roundaboutExit: Int? = null,
    val nextBannerExit: String? = null
)

internal object NavigationPanelSigns {
    private fun normalized(value: String?) = value.orEmpty().lowercase(Locale.ROOT)
        .replace('_', ' ').replace('-', ' ').trim().replace(Regex("\\s+"), " ")

    private fun isRoundabout(type: String) = "roundabout" in type || "rotary" in type
    private fun isOffRamp(type: String) = type in setOf("off ramp", "exit", "exit ramp", "motorway exit")
    private fun isOnRamp(type: String) = type in setOf("on ramp", "ramp", "enter motorway", "enter highway")
    private fun isNumberedInterchange(type: String) = isOffRamp(type) || isOnRamp(type) ||
        type in setOf("fork", "merge", "interchange")

    private fun validExit(value: String?): String? = value?.trim()?.uppercase(Locale.ROOT)
        ?.removePrefix("EXIT ")?.takeIf { Regex("[0-9]{1,4}[A-Z]{0,2}").matches(it) }

    private fun explicitExit(instruction: String?): String? {
        // Only an explicit route instruction, never a street name, ref, route number or bearing.
        return Regex("(?i)\\b(?:take|use|leave\\s+(?:the\\s+)?(?:highway|motorway|expressway)\\s+at|at)\\s+(?:the\\s+)?exit\\s+(?:number\\s+|no\\.?\\s*)?([0-9]{1,4}[a-z]{0,2})\\b")
            .find(instruction.orEmpty())?.groupValues?.get(1)?.let(::validExit)
    }

    private fun ordinalExit(text: String?): Int? =
        Regex("(?i)\\b([0-9]{1,2})(?:st|nd|rd|th)\\s+exit\\b").find(text.orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
            ?: Regex("(?i)\\bexit\\s+([0-9]{1,2})\\b").find(text.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    fun cue(type: String?, modifier: String?, instruction: String?,
        metadata: RouteSignMetadata? = null, current: RouteSignMetadata? = null): NavigationPanelCue {
        val t = normalized(metadata?.type ?: type)
        val m = normalized(metadata?.modifier ?: modifier)
        val words = normalized(metadata?.instruction ?: instruction)
        val left = "left" in m || (m.isBlank() && "left" in words)
        val right = "right" in m || (m.isBlank() && "right" in words)
        val round = isRoundabout(t) || (t.isBlank() && ("roundabout" in words || "rotary" in words))
        fun sided(l: NavigationSign, r: NavigationSign) = when {
            left -> l
            right -> r
            else -> NavigationSign.STRAIGHT
        }
        val sign = when {
            t == "arrive" || t == "arrival" || (t.isBlank() && "destination" in words && "arriv" in words) -> NavigationSign.ARRIVE
            m in setOf("uturn", "u turn") || t in setOf("uturn", "u turn") || "u turn" in words -> NavigationSign.UTURN
            round && ("exit" in t || t == "roundabout turn") -> NavigationSign.ROUNDABOUT_EXIT
            round -> NavigationSign.ROUNDABOUT
            isOffRamp(t) -> sided(NavigationSign.EXIT_LEFT, NavigationSign.EXIT_RIGHT)
            isOnRamp(t) -> sided(NavigationSign.RAMP_LEFT, NavigationSign.RAMP_RIGHT)
            t == "merge" -> sided(NavigationSign.MERGE_LEFT, NavigationSign.MERGE_RIGHT)
            t == "fork" -> sided(NavigationSign.FORK_LEFT, NavigationSign.FORK_RIGHT)
            t == "keep" || t == "keep left" || t == "keep right" || words.startsWith("keep ") ->
                sided(NavigationSign.KEEP_LEFT, NavigationSign.KEEP_RIGHT)
            m == "sharp left" -> NavigationSign.SHARP_LEFT
            m == "sharp right" -> NavigationSign.SHARP_RIGHT
            m == "slight left" -> NavigationSign.SLIGHT_LEFT
            m == "slight right" -> NavigationSign.SLIGHT_RIGHT
            left -> NavigationSign.TURN_LEFT
            right -> NavigationSign.TURN_RIGHT
            else -> NavigationSign.STRAIGHT
        }
        val exit = if (!round && isNumberedInterchange(t)) {
            validExit(metadata?.exitNumber) ?: validExit(current?.nextBannerExit)
                ?: explicitExit(metadata?.instruction ?: instruction)
        } else null
        val roundExit = if (round) {
            metadata?.roundaboutExit?.takeIf { it > 0 }
                ?: ordinalExit(metadata?.instruction ?: instruction)
                ?: current?.takeIf { isRoundabout(normalized(it.type)) }?.roundaboutExit
        } else null
        return NavigationPanelCue(sign, exit, roundExit)
    }

    private fun JsonObject.text(key: String): String? =
        runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()
    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    /** Valhalla's OSRM response has steps[].exits and current-step banner
     * primary.components[type=exit-number] for the next maneuver. The array
     * follows the SDK's current leg step order; never look at an unrelated leg.
     */
    fun metadata(json: String): List<RouteSignMetadata> = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        val steps = root.array("routes")?.firstOrNull()?.asJsonObject
            ?.array("legs")?.firstOrNull()?.asJsonObject?.array("steps")
            ?: return emptyList()
        steps.map { element ->
            val step = element.asJsonObject
            val maneuver = step.obj("maneuver") ?: JsonObject()
            val type = maneuver.text("type")
            val instruction = maneuver.text("instruction")
            val t = normalized(type)
            val explicitNumber = if (isNumberedInterchange(t)) {
                validExit(step.text("exits")) ?: validExit(step.text("exit_number"))
                    ?: validExit(maneuver.text("exit_number"))
                    ?: explicitExit(instruction)
            } else null
            val roundNumber = if (isRoundabout(t)) {
                (maneuver.text("exit") ?: maneuver.text("roundabout_exit_count")
                    ?: step.text("roundabout_exit_count"))?.toIntOrNull()?.takeIf { it > 0 }
                    ?: ordinalExit(instruction)
            } else null
            val banners = step.array("bannerInstructions") ?: step.array("banner_instructions")
            val bannerExit = banners?.asSequence()?.mapNotNull { item ->
                val primary = item.asJsonObject.obj("primary") ?: return@mapNotNull null
                if (!isNumberedInterchange(normalized(primary.text("type")))) return@mapNotNull null
                primary.array("components")?.asSequence()?.mapNotNull { c ->
                    val component = c.asJsonObject
                    if (normalized(component.text("type")) == "exit number") validExit(component.text("text"))
                    else null
                }?.firstOrNull()
            }?.firstOrNull()
            RouteSignMetadata(type, maneuver.text("modifier"), instruction,
                explicitNumber, roundNumber, bannerExit)
        }
    }.getOrDefault(emptyList())
}
