package com.izzyan.delivery

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Stable JSON bridge avoids exposing Kotlin collections, enums and nullable primitives to Swift. */
class DeliveryEngine {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private inline fun run(block: () -> String): String = try { block() } catch(e: IllegalArgumentException) { json.encodeToString(mapOf("error" to (e.message ?: "Invalid delivery data."))) } catch(e: IllegalStateException) { json.encodeToString(mapOf("error" to (e.message ?: "Could not process delivery data."))) }
    fun parsePostalCodes(raw: String): String = json.encodeToString(com.izzyan.delivery.parsePostalCodes(raw))
    fun optimize(matrixJson: String): String = run { json.encodeToString(optimizeTour(json.decodeFromString<RoadMatrix>(matrixJson))) }
    fun createSchedule(inputJson: String): String = run { json.encodeToString(schedule(json.decodeFromString<ScheduleInput>(inputJson))) }
    fun review(inputJson: String): String = run { json.encodeToString(reviewStop(json.decodeFromString<ReviewInput>(inputJson))) }
    fun deliverySummary(routeJson: String): String = run { json.encodeToString(summary(json.decodeFromString<Route>(routeJson))) }
    fun saveSummary(inputJson: String): String = run { json.encodeToString(saveMoney(json.decodeFromString<MoneyInput>(inputJson))) }
}
