package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Kept for report metadata; payment codes and calculations remain unchanged. */
data class DeliveryBadgeStyle(val label: String, val backgroundArgb: Int, val foregroundArgb: Int)

fun paymentModeBadge(order: CustomerOrder?): DeliveryBadgeStyle? = when (order?.paymentStatus?.uppercase(Locale.ENGLISH)) {
    "PAYNOW" -> DeliveryBadgeStyle("PayNow", 0xFF9900CC.toInt(), 0xFFFFFFFF.toInt())
    "COD" -> DeliveryBadgeStyle("Cash On Delivery", 0xFFFFC000.toInt(), 0xFF151515.toInt())
    else -> null
}

fun reportStatusBadge(status: String): DeliveryBadgeStyle = when (normalizedStatus(status)) {
    "DELIVERED" -> DeliveryBadgeStyle("Delivered", 0xFF4EA72E.toInt(), 0xFFFFFFFF.toInt())
    "ON_HOLD" -> DeliveryBadgeStyle("On Hold", 0xFFE97132.toInt(), 0xFFFFFFFF.toInt())
    "SKIPPED" -> DeliveryBadgeStyle("Skipped", 0xFF4F5E73.toInt(), 0xFFFFFFFF.toInt())
    else -> DeliveryBadgeStyle("Pending", 0xFF183C78.toInt(), 0xFFFFFFFF.toInt())
}

/** Show the uploaded transparent circular logo with no Payment Mode label. */
@Composable
fun PaymentModeStamp(order: CustomerOrder?, modifier: Modifier = Modifier, size: Dp = 82.dp) {
    val image = when (order?.paymentStatus?.uppercase(Locale.ENGLISH)) {
        "COD" -> R.drawable.cod_circle_stamp
        "PAYNOW" -> R.drawable.paynow_circle_stamp
        else -> null
    }
    if (image != null) {
        Image(
            painter = painterResource(image),
            contentDescription = if (order?.paymentStatus?.uppercase(Locale.ENGLISH) == "COD")
                "Cash on Delivery" else "PayNow",
            modifier = modifier.then(Modifier.size(size)),
            contentScale = ContentScale.Fit
        )
    }
}

/** Compatibility for any existing callers: icon only. */
@Composable
fun PaymentModeRow(order: CustomerOrder?, useCodLogo: Boolean = true) {
    PaymentModeStamp(order)
}
