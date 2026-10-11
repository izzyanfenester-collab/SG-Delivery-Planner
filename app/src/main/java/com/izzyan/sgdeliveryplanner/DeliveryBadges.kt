package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared visual definitions for on-screen cards and exported report PNGs. */
data class DeliveryBadgeStyle(val label: String, val backgroundArgb: Int, val foregroundArgb: Int)

fun paymentModeBadge(order: CustomerOrder?): DeliveryBadgeStyle? = when (order?.paymentStatus?.uppercase(java.util.Locale.ENGLISH)) {
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

@Composable
fun PaymentModeRow(order: CustomerOrder?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Payment Mode : ", style = MaterialTheme.typography.bodyMedium)
        val badge = paymentModeBadge(order)
        if (badge == null) Text("—", style = MaterialTheme.typography.bodyMedium)
        else Surface(
            color = Color(badge.backgroundArgb),
            contentColor = Color(badge.foregroundArgb),
            shape = RoundedCornerShape(50)
        ) {
            Text(
                badge.label,
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}
