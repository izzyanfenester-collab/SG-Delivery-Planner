package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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

/**
 * Watermark-free COD logo styled after the supplied red/white/black reference.
 * Only the on-screen Route and Delivery cards use it. Payment data, reports
 * and other payment methods stay as before.
 */
@Composable
private fun CashOnDeliveryLogo() {
    val red = Color(0xFFE6272C)
    Surface(
        modifier = Modifier
            .size(width = 152.dp, height = 49.dp)
            .clearAndSetSemantics { contentDescription = "Cash on Delivery" },
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(2.dp, red),
        color = Color.White
    ) {
        Box(Modifier.fillMaxSize()) {
            // A small red corner panel echoes the COD delivery-frame icon.
            Box(
                Modifier.align(Alignment.TopEnd)
                    .size(width = 27.dp, height = 5.dp)
                    .background(red, RoundedCornerShape(3.dp))
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "CASH",
                    color = red,
                    fontSize = 21.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
                    letterSpacing = (-0.6f).sp,
                    maxLines = 1
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("ON", color = red, fontSize = 12.sp, lineHeight = 14.sp,
                        fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, maxLines = 1)
                    Text("DELIVERY", color = Color.Black, fontSize = 14.sp, lineHeight = 15.sp,
                        fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
                        letterSpacing = (-0.4f).sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun PaymentModeRow(order: CustomerOrder?, useCodLogo: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Payment Mode : ", style = MaterialTheme.typography.bodyMedium)
        val badge = paymentModeBadge(order)
        if (badge == null) {
            Text("—", style = MaterialTheme.typography.bodyMedium)
        } else if (useCodLogo && order?.paymentStatus?.equals("COD", ignoreCase = true) == true) {
            CashOnDeliveryLogo()
        } else {
            Surface(
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
}
