package com.izzyan.sgdeliveryplanner

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** New messages are manually reviewed and sent in WhatsApp; no automatic messaging. */
internal enum class RunnerEtaMessage(val title: String) {
    UPCOMING("Upcoming Delivery"),
    RUNNING_LATE("Running Late"),
    ARRIVED("Arrived"),
    RESCHEDULE("Reschedule Request")
}

internal fun smartEtaMessage(
    stop: Stop,
    type: RunnerEtaMessage,
    now: LocalDateTime = LocalDateTime.now(ZoneId.of("Asia/Singapore"))
): String? {
    val order = stop.order ?: return null
    if (normalizeCustomerPhone(order.phoneNumber) == null) return null
    val name = order.customerName?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
    val eta = (stop.etaArrival ?: stop.arrival).let {
        runCatching { LocalDateTime.parse(it) }.getOrNull()
    }
    val time = eta?.takeUnless { it.isBefore(now) }
        ?.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
    val greeting = "Hi$name,"
    val destination = "delivery to postal code ${stop.place.postal}"
    return buildString {
        appendLine(greeting)
        appendLine()
        when (type) {
            RunnerEtaMessage.UPCOMING -> appendLine(
                if (time == null)
                    "I am preparing your $destination. I will confirm the arrival time shortly."
                else "Your $destination is estimated at around $time (subject to traffic)."
            )
            RunnerEtaMessage.RUNNING_LATE -> appendLine(
                if (time == null)
                    "I am running later than planned for your $destination. I will update the ETA as soon as possible."
                else "I am running later than planned for your $destination. My updated estimated arrival is around $time."
            )
            RunnerEtaMessage.ARRIVED ->
                appendLine("I have arrived for your $destination. Please let me know when you are available.")
            RunnerEtaMessage.RESCHEDULE ->
                appendLine("I could not complete your $destination as planned. Could you confirm a suitable time for redelivery?")
        }
        if (order.paymentStatus.equals("COD", ignoreCase = true)) {
            order.parcelPrice?.let { amount ->
                appendLine()
                appendLine("COD amount: S$" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
            }
        }
        appendLine()
        append("Thank you.")
    }
}
