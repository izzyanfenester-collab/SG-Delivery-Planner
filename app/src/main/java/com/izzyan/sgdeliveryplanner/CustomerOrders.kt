package com.izzyan.sgdeliveryplanner

import java.util.UUID
import java.time.LocalDateTime

/** Identity is per parcel/customer, never a postal code. */
data class CustomerOrder(
    val orderId: String = UUID.randomUUID().toString(), val postalCode: String,
    val customerName: String? = null, val phoneNumber: String? = null, val fullAddress: String? = null,
    val parcelPrice: java.math.BigDecimal? = null, val paymentStatus: String? = null,
    val paymentProofFileId: String? = null, val paymentProofUploadedAt: String? = null,
    val proofFileId: String? = null, val proofUploadedAt: String? = null
)
data class OrderImport(val orders: List<CustomerOrder>, val failed: Int)

fun normalizeCustomerPhone(raw: String?): String? {
    val digits = raw?.replace(Regex("[^0-9]"), "") ?: return null
    return when {
        digits.matches(Regex("[89][0-9]{7}")) -> "+65$digits"
        digits.matches(Regex("65[89][0-9]{7}")) -> "+$digits"
        else -> null
    }
}
fun parseCustomerOrders(raw: String): OrderImport {
    val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        .replace(Regex("(?m)^\\[[^\\n]+][ \t]*[^\\n]*?:[ \t]*"), "")
    val markers = Regex("(?im)^[ \t]*(Total|Nama)[ \t]*:").findAll(text).toList()
    val boundaries = mutableListOf<Int>()
    var hasName = false; var hasTotal = false
    for (marker in markers) {
        val name = marker.groupValues[1].equals("Nama",ignoreCase=true)
        val hasAddress = boundaries.lastOrNull()?.let { Regex("(?im)^\\s*Alamat\\s*:").containsMatchIn(text.substring(it,marker.range.first)) } ?: false
        if (boundaries.isEmpty() || (name && (hasName || hasAddress)) || (!name && (hasTotal || hasAddress))) {
            boundaries.add(marker.range.first); hasName=false; hasTotal=false
        }
        if (name) hasName=true else hasTotal=true
    }
    if (boundaries.isEmpty()) return OrderImport(emptyList(), if (text.isBlank()) 0 else 1)
    var failed = 0
    val orders = boundaries.mapIndexedNotNull { index, offset ->
        val block = text.substring(offset, boundaries.getOrNull(index + 1) ?: text.length)
        val addressStart = Regex("(?im)^[ \t]*Alamat[ \t]*:[ \t]*").find(block)
        val address = addressStart?.let { label ->
            val body = block.substring(label.range.last+1)
            val end = Regex("(?im)^[ \t]*No\\.?[ \t]*telefon[ \t]*:").find(body)?.range?.first
                ?: Regex("(?im)^[ \t]*Order[ \t]*:").find(body)?.range?.first ?: body.length
            body.substring(0,end).trim()
        }
        val postal = address?.let {
            Regex("(?i)(?:\\bSingapore|\\bS'pore|\\bSG|\\bS)[ \t]*([0-9]{6})(?![0-9])").find(it)?.groupValues?.get(1)
                ?: Regex("(?<!\\d)\\d{6}(?!\\d)").find(it)?.value
        }
        if (postal == null) { failed++; null }
        else {
            val phoneField = Regex("(?ims)^\\s*No\\.?\\s*telefon\\s*:[ \\t]*\\n?([^\\n]*)").find(block)?.groupValues?.get(1).orEmpty()
            val phone = Regex("(?i)wa\\.me/\\+?([0-9]+)").find(phoneField)?.groupValues?.get(1) ?: phoneField
            val total = Regex("(?im)Total\\s*:[ \\t]*(?:\\$|SGD[ \\t]*)?([0-9]+(?:\\.[0-9]{1,2})?)(?![0-9.])").find(block)?.groupValues?.get(1)
            val mode = Regex("(?i)\\b(COD|PAYNOW)\\b").find(Regex("(?im)^[ \t]*Total[^\\n]*").find(block)?.value.orEmpty())?.value?.uppercase()
            CustomerOrder(postalCode = postal,
                customerName = Regex("(?im)^\\s*Nama\\s*:[ \\t]*([^\\n]*)").find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() },
                phoneNumber = normalizeCustomerPhone(phone), fullAddress = address,
                parcelPrice = total?.let { normalizedCurrency(it)?.toBigDecimal() }, paymentStatus = mode)
        }
    }
    return OrderImport(orders, failed)
}
fun orderPrice(order: CustomerOrder?) = order?.parcelPrice?.let { "$${it.setScale(2).toPlainString()}" } ?: "—"
fun orderPayment(order: CustomerOrder?) = when (order?.paymentStatus) { "COD" -> "COD"; "PAYNOW" -> "PayNow"; else -> "—" }
fun customerAddress(stop: Stop) = stop.order?.fullAddress?.takeIf { it.isNotBlank() } ?: stop.place.address
fun displayCustomerPhone(phone: String?) = phone?.takeIf { it.length == 11 && it.startsWith("+65") }?.let { "+65 ${it.substring(3,7)} ${it.substring(7)}" } ?: phone ?: "—"

/** Expand optimized geographic visits to individual order IDs without changing optimizer costs. */
fun expandCustomerOrders(plan: Plan, orders: List<CustomerOrder>): Plan {
    require(orders.size in 1..50 && orders.map { it.orderId }.distinct().size == orders.size)
    require(plan.stops.map { it.place.postal }.toSet() == orders.map { it.postalCode }.toSet())
    val pairs = plan.stops.flatMap { stop -> orders.filter { it.postalCode == stop.place.postal }.mapIndexed { i, order ->
        Triple(stop.place, if (i == 0) stop.leg else Leg(0.0,0.0,0.0), order)
    } }
    val result = schedule(pairs.map { it.first }, pairs.map { it.second } + plan.returnLeg,
        LocalDateTime.parse(plan.start), plan.serviceMinutes, plan.mode, plan.geometry, plan.startLocation)
    return result.copy(id = plan.id, created = plan.created, stops = result.stops.mapIndexed { i, stop -> stop.copy(orderId = pairs[i].third.orderId, order = pairs[i].third) })
}
