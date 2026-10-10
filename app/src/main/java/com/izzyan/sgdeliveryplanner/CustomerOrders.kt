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
private enum class OrderField { NAME, ADDRESS, PHONE, PRICE, END }
private data class OrderLabel(val field: OrderField, val value: String)
private val orderLabel = Regex("^([^:]+):[ \t]*(.*)$")
private fun label(line: String): OrderLabel? {
    // Only matching labels is normalized: customer/address content stays untouched.
    val clean=line.trimStart().replace(Regex("^[^\\p{L}\\p{N}]+"), "")
    val match=orderLabel.matchEntire(clean) ?: return null
    val key=match.groupValues[1].trim().lowercase(java.util.Locale.ENGLISH)
        .replace(".", "").replace(Regex("[ \t]+"), " ")
    val field=when(key) {
        "nama", "name", "customer", "customer name" -> OrderField.NAME
        "alamat", "address" -> OrderField.ADDRESS
        "no telefon", "notelefon", "phone", "phone no", "telephone", "whatsapp", "whatsapp no" -> OrderField.PHONE
        "total", "amount" -> OrderField.PRICE
        "order", "item", "tarikh order", "date", "fb", "page" -> OrderField.END
        else -> return null
    }
    return OrderLabel(field,match.groupValues[2])
}
private val chatHeader=Regex("^[ \t]*\\[[0-9]{1,4}[/.-][0-9]{1,2}[^]\\n]*][ \t]*[^:\\n]+:[ \t]*(.*)$")
private val plainChatHeader=Regex("^[ \t]*[0-9]{1,4}[/.-][0-9]{1,2}(?:[/.-][0-9]{2,4})?,[ \t]*[0-9]{1,2}:[0-9]{2}[^\\n]*? - [^:\\n]+:[ \t]*(.*)$")
private fun orderHeading(line: String): Boolean {
    if(label(line)!=null) return false
    val clean=line.trimStart().replace(Regex("^[^\\p{L}\\p{N}]+"), "")
    return Regex("(?i)^ORDER\\b|\\bINVOICE[ \t]*:").containsMatchIn(clean)
}
fun parseCustomerOrders(raw: String): OrderImport {
    if(raw.isBlank()) return OrderImport(emptyList(),0)
    val blocks=mutableListOf<List<String>>()
    var lines=mutableListOf<String>()
    var seen=mutableSetOf<OrderField>()
    fun flush() {
        if(lines.any {it.isNotBlank()}) blocks.add(lines.toList())
        lines=mutableListOf();seen=mutableSetOf()
    }
    raw.replace("\r\n","\n").replace('\r','\n').lineSequence().forEach { original ->
        val header=chatHeader.matchEntire(original) ?: plainChatHeader.matchEntire(original)
        if(header!=null) flush()
        val line=header?.groupValues?.get(1) ?: original
        val field=label(line)?.field
        val primary=field in listOf(OrderField.NAME,OrderField.PRICE,OrderField.ADDRESS)
        if((orderHeading(line) && seen.isNotEmpty()) ||
            (primary && (field in seen || (OrderField.ADDRESS in seen && field != OrderField.ADDRESS)))) flush()
        lines.add(line)
        field?.let {seen.add(it)}
    }
    flush()
    var failed=0
    val orders=blocks.mapNotNull { block ->
        val fields=mutableMapOf<OrderField,String>()
        var current: OrderField?=null
        val value=StringBuilder()
        fun save() {current?.let {fields.putIfAbsent(it,value.toString().trim())};value.clear()}
        block.forEach { line ->
            val found=label(line)
            if(found!=null) {save();current=found.field;value.append(found.value)}
            else if(orderHeading(line)) {save();current=null}
            else if(current!=null) {if(value.isNotEmpty()) value.append('\n');value.append(line)}
        }
        save()
        val address=fields[OrderField.ADDRESS]?.takeIf {it.isNotBlank()}
        val searchable=address?.replace(Regex("https?://\\S+",RegexOption.IGNORE_CASE)," ")
            ?.replace(Regex("\\b[0-9]{1,4}[/.-][0-9]{1,2}[/.-][0-9]{2,4}\\b")," ")
        val postal=searchable?.let {
            Regex("(?i)(?:\\bSingapore|\\bS['’]pore|\\bSG|\\bS)[ \t]*([0-9]{6})(?![0-9])").find(it)?.groupValues?.get(1)
                ?: Regex("(?<!\\d)[0-9]{6}(?!\\d)").find(it)?.value
        }
        if(postal==null) {failed++;null}
        else {
            val phoneField=fields[OrderField.PHONE].orEmpty()
            val phone=Regex("(?i)wa\\.me/\\+?([0-9]+)").find(phoneField)?.groupValues?.get(1)
                ?: phoneField.lines().firstOrNull {it.isNotBlank()}
            val total=fields[OrderField.PRICE].orEmpty()
            val amount=Regex("(?i)^(?:\\$[ \t]*|SGD[ \t]*)?([0-9]+(?:\\.[0-9]{1,2})?)(?![0-9.])").find(total)?.groupValues?.get(1)
            CustomerOrder(postalCode=postal,customerName=fields[OrderField.NAME]?.lines()?.firstOrNull {it.isNotBlank()},
                phoneNumber=normalizeCustomerPhone(phone),fullAddress=address,
                parcelPrice=amount?.let {normalizedCurrency(it)?.toBigDecimal()},
                paymentStatus=Regex("(?i)\\b(COD|PAYNOW)\\b").find(total)?.value?.uppercase(java.util.Locale.ENGLISH))
        }
    }
    return OrderImport(orders,failed)
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
