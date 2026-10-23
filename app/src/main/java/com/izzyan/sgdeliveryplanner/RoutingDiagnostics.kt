package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import retrofit2.HttpException

/** The routing server must explicitly identify a coordinate before we accuse a postal code. */
class RoutingPostalException(val failures: List<PostalLookupFailure>, cause: Throwable? = null) :
    PlannerError("Routing cannot match: ${failures.joinToString { it.postalCode }}", cause)

private fun JsonObject.safeString(field: String): String? =
    get(field)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

internal fun routingLocationLabel(place: Place, index: Int): String =
    if (index == 0) "Start / End location" else "Postal code ${place.postal}"

internal fun formattedPostalFailures(failures: List<PostalLookupFailure>): String {
    if (failures.isEmpty()) return "No postal code was identified."
    val rows=failures.distinctBy { it.postalCode }.take(12)
        .map { "Postal code ${it.postalCode}: ${it.reason.removePrefix("${it.postalCode}: ").trim()}" }
    val additional=failures.map { it.postalCode }.distinct().size - rows.size
    return (listOf("Please check these postal codes:") + rows +
        (if (additional>0) listOf("...and $additional more (open Review Failed Orders).") else emptyList()))
        .joinToString("\n")
}

/** Decode OSRM's structured codes; never guess which postal failed from a generic HTTP 400. */
internal fun routingProblem(body: JsonObject?, places: List<Place>, stage: String, status: Int? = null): PlannerError {
    val code=body?.safeString("code")
    val detail=body?.safeString("message").orEmpty()
    if (code == "NoSegment") {
        // OSRM uses zero-based indices: "Could not find a matching segment for coordinate 3".
        val indices=Regex("""(?:coordinate|waypoint|point)\s*(?:#|:)?\s*(\d+)""",RegexOption.IGNORE_CASE)
            .findAll(detail).mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in places.indices }.distinct().toList()
        val invalid=indices.filter { it>0 }
        if(invalid.isNotEmpty()) return RoutingPostalException(invalid.map { index ->
            PostalLookupFailure(places[index].postal,
                "No nearby drivable road found by routing server. Check the address or routing server.")
        })
        if(indices.contains(0)) return PlannerError(
            "The routing server could not locate a drivable road near your Start / End location. Check that location in Settings.")
        return PlannerError(
            "The routing server could not match one of the ${places.size} locations to a road (NoSegment), " +
                "but did not identify which postal code. Try again or change the routing server.")
    }
    val suffix=if(status!=null)" (HTTP $status)" else ""
    return when(code) {
        "TooBig" -> PlannerError("Routing server limit (TooBig$suffix): too many locations in the $stage request " +
            "(${places.size-1} postal codes). Split the route or select a server with a higher limit. " +
            "No specific postal code was found invalid.")
        "NoRoute","NoTable" -> PlannerError("The routing server could not find a driving connection for this $stage " +
            "request ($code$suffix). No single postal code has been confirmed invalid.")
        "InvalidOptions","InvalidQuery","InvalidValue","InvalidUrl","InvalidService","InvalidVersion" ->
            PlannerError("Routing server rejected the $stage request ($code$suffix). " +
                "Check the routing server URL in Settings. No postal code has been confirmed invalid.")
        "DisabledDataset" -> PlannerError("This routing server does not support requested driving data " +
            "(DisabledDataset$suffix). Use a compatible server.")
        else -> PlannerError("Routing server could not process the $stage request" +
            (if(code!=null)" ($code$suffix)" else suffix) +
            ". Please retry or check the server in Settings. No postal code has been confirmed invalid.")
    }
}

internal fun routingHttpFailure(error: HttpException, places: List<Place>, stage: String): PlannerError {
    val json=runCatching {
        error.response()?.errorBody()?.string()?.let { JsonParser.parseString(it).asJsonObject }
    }.getOrNull()
    return routingProblem(json,places,stage,error.code())
}

internal fun noDrivingConnection(from: Place, fromIndex: Int, to: Place, toIndex: Int): PlannerError =
    PlannerError("No drivable route found between ${routingLocationLabel(from,fromIndex)} and " +
        "${routingLocationLabel(to,toIndex)}. Check the road locations or another routing server. " +
        "This does not necessarily mean either postal code is invalid.")
