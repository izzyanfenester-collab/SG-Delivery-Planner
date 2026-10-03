package com.izzyan.sgdeliveryplanner

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDateTime

private val holdReasons = setOf("Customer not home", "No answer", "Reschedule", "Payment issue", "Access issue", "Other")

fun normalizedStatus(status: String): String = when (status) {
    "COMPLETED", "DELIVERED" -> "DELIVERED"
    "ON_HOLD", "ON HOLD" -> "ON_HOLD"
    "SKIPPED" -> "SKIPPED"
    else -> "PENDING"
}

/** A reviewed pending stop still contributes to Pending in the summary. */
fun reviewStop(plan: Plan, action: String, now: String, holdReason: String? = null, holdNote: String? = null): Plan {
    if (plan.current !in plan.stops.indices) return plan
    val status = when (action) {
        "DELIVERED" -> "DELIVERED"
        "ON_HOLD" -> "ON_HOLD"
        "SKIP", "SKIPPED" -> "SKIPPED"
        "NEXT" -> normalizedStatus(plan.stops[plan.current].status)
        else -> throw IllegalArgumentException("Unknown delivery action: $action")
    }
    val original = plan.stops[plan.current]
    val alreadyReviewed = action == "NEXT" && original.reviewedAt != null
    val reason = if (action == "NEXT") original.holdReason else holdReason?.takeIf { status == "ON_HOLD" && it in holdReasons }
    val stops = plan.stops.toMutableList()
    stops[plan.current] = stops[plan.current].copy(
        status = status,
        completedAt = if (action == "NEXT") original.completedAt else now.takeIf { status == "DELIVERED" },
        holdReason = reason,
        holdNote = if (action == "NEXT") original.holdNote else holdNote?.trim()?.take(160)?.takeIf { reason == "Other" && it.isNotEmpty() },
        reviewedAt = if (alreadyReviewed) original.reviewedAt else now
    )
    val next = (plan.current + 1 until stops.size).firstOrNull { stops[it].reviewedAt == null }
        ?: stops.indexOfFirst { it.reviewedAt == null }.takeIf { it >= 0 }
        ?: stops.size
    return plan.copy(
        stops = stops,
        current = next,
        actualCompletion = if (alreadyReviewed) plan.actualCompletion else if (stops.all { normalizedStatus(it.status) == "DELIVERED" }) stops.mapNotNull { it.completedAt }.maxOrNull() ?: now else null,
        reviewedFinishedAt = if (alreadyReviewed) {
            plan.reviewedFinishedAt ?: if (next == stops.size) stops.mapNotNull { it.reviewedAt }.maxOrNull() ?: plan.actualCompletion else null
        } else now.takeIf { next == stops.size },
        summarySavedAt = if (alreadyReviewed) plan.summarySavedAt else null
    )
}

data class DeliverySummary(
    val totalParcel: Int,
    val delivered: Int,
    val onHold: Int,
    val skipped: Int,
    val pending: Int,
    val successRate: Double,
    val start: String,
    val finish: String,
    val totalRouteSeconds: Double,
    val totalKm: Double,
    val returned: String
)

fun deliverySummary(plan: Plan): DeliverySummary {
    val delivered = plan.stops.count { it.status == "DELIVERED" || it.status == "COMPLETED" }
    val onHold = plan.stops.count { it.status == "ON_HOLD" }
    val skipped = plan.stops.count { it.status == "SKIPPED" }
    val total = plan.stops.size
    val finish = plan.reviewedFinishedAt ?: plan.actualCompletion ?: plan.stops.lastOrNull()?.leave ?: plan.start
    val seconds = runCatching {
        Duration.between(LocalDateTime.parse(plan.start), LocalDateTime.parse(finish)).seconds.toDouble().coerceAtLeast(0.0)
    }.getOrDefault(0.0)
    return DeliverySummary(
        totalParcel = total,
        delivered = delivered,
        onHold = onHold,
        skipped = skipped,
        pending = total - delivered - onHold - skipped,
        successRate = if (total == 0) 0.0 else BigDecimal.valueOf(delivered.toLong())
            .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total.toLong()), 1, RoundingMode.HALF_UP).toDouble(),
        start = plan.start,
        finish = finish,
        totalRouteSeconds = seconds,
        totalKm = plan.totalKm,
        returned = plan.returned
    )
}

/** Also accepts incomplete decimal input while a driver edits the field. */
fun validCurrencyInput(text: String): Boolean = text.matches(Regex("[0-9]{0,9}(\\.[0-9]{0,2})?"))

/** Null indicates an incomplete or invalid value, which must not overwrite saved money. */
fun normalizedCurrency(text: String): String? {
    if (!validCurrencyInput(text)) return null
    if (text.isEmpty()) return "0.00"
    return text.toBigDecimalOrNull()?.setScale(2, RoundingMode.UNNECESSARY)?.toPlainString()
}

fun formatCurrency(text: String): String = "SGD ${normalizedCurrency(text) ?: "0.00"}"

/** Room stores route JSON; adding payload fields does not change its version-1 tables. */
object PlanJson {
    private val gson = Gson()

    fun encode(plan: Plan): String = gson.toJsonTree(plan).asJsonObject.apply {
        addProperty("deliveryStateVersion", 2)
    }.toString()

    fun decode(json: String): Plan {
        val root = JsonParser.parseString(json).asJsonObject
        val legacy = (root.optionalString("deliveryStateVersion")?.toIntOrNull() ?: 1) < 2
        // Gson bypasses Kotlin constructors. Supply new non-null defaults before decoding
        // both absent legacy fields and explicit JSON nulls.
        root.addProperty("cashOnHand", normalizedCurrency(root.optionalString("cashOnHand").orEmpty()) ?: "0.00")
        root.addProperty("tax", normalizedCurrency(root.optionalString("tax").orEmpty()) ?: "0.00")
        val fallbackReviewedAt = root.optionalString("created") ?: root.optionalString("start")
        root.getAsJsonArray("stops").forEach { element ->
            val stop = element.asJsonObject
            val status = normalizedStatus(stop.optionalString("status") ?: "PENDING")
            stop.addProperty("status", status)
            if (legacy && status != "PENDING" && stop.optionalString("reviewedAt") == null) {
                stop.addProperty("reviewedAt", stop.optionalString("completedAt") ?: fallbackReviewedAt)
            }
        }
        val decoded = gson.fromJson(root, Plan::class.java)
        val current = decoded.current.coerceIn(0, decoded.stops.size)
        val allReviewed = decoded.stops.isNotEmpty() && decoded.stops.all { it.reviewedAt != null }
        return decoded.copy(
            current = current,
            reviewedFinishedAt = decoded.reviewedFinishedAt ?: if (legacy && allReviewed && current == decoded.stops.size) {
                decoded.actualCompletion ?: decoded.stops.mapNotNull { it.reviewedAt }.maxOrNull() ?: decoded.stops.last().leave
            } else null
        )
    }

    private fun JsonObject.optionalString(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
}
