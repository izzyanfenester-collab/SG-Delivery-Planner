package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.nativeCanvas
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
 * Bold, compact Cash on Delivery insignia, inspired by the supplied reference.
 * Vector-style drawing stays sharp at all Android display densities.
 * It replaces the on-screen COD pill on Route, Delivery and History only.
 */
@Composable
private fun CashOnDeliveryLogo() {
    // Watermark-free, compact bitmap of the approved premium COD design.
    // Sized consistently for Route, Delivery and History cards.
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(R.drawable.cod_premium_logo),
        contentDescription = "Cash on Delivery",
        modifier = Modifier.size(width = 100.dp, height = 40.dp),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit
    )
}

/**
 * PayNow wordmark taken from the user-provided reference image.
 * The PNG is transparent; a compact white backing preserves the original
 * brand contrast on SG Delivery's dark navy cards.
 */
@Composable
private fun PayNowLogo() {
    Surface(
        modifier = Modifier
            .size(width = 100.dp, height = 24.dp)
            .clearAndSetSemantics { contentDescription = "PayNow" },
        color = Color.White,
        shape = RoundedCornerShape(5.dp)
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.paynow_logo),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 2.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )
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
        } else if (order?.paymentStatus?.equals("PAYNOW", ignoreCase = true) == true) {
            PayNowLogo()
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
