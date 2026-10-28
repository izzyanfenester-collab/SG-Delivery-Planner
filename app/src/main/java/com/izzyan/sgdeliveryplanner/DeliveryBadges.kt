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
    Canvas(
        modifier = Modifier
            .size(width = 150.dp, height = 43.dp)
            .clearAndSetSemantics { contentDescription = "Cash on Delivery" }
    ) {
        val graphic = drawContext.canvas.nativeCanvas
        val saved = graphic.save()
        graphic.scale(size.width / 300f, size.height / 86f)

        val red = android.graphics.Color.rgb(229, 37, 43)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }
        // White interior with a neat delivery-label frame.
        paint.color = android.graphics.Color.WHITE
        paint.style = android.graphics.Paint.Style.FILL
        graphic.drawRoundRect(2.5f, 2.5f, 297.5f, 83.5f, 11f, 11f, paint)
        paint.color = red
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 4.5f
        graphic.drawRoundRect(2.5f, 2.5f, 297.5f, 83.5f, 11f, 11f, paint)
        paint.style = android.graphics.Paint.Style.FILL

        // Red upper-right parcel-label corner and two white highlights.
        graphic.drawRoundRect(256f, 3f, 296f, 24f, 4f, 4f, paint)
        paint.color = android.graphics.Color.WHITE
        graphic.drawRoundRect(268f, 9f, 277f, 15f, 2f, 2f, paint)
        graphic.drawRoundRect(282f, 9f, 293f, 15f, 2f, 2f, paint)
        paint.color = red
        graphic.drawRoundRect(4f, 76.5f, 39f, 83.5f, 3f, 3f, paint)

        // Speed streaks match the delivery theme without crossing the words.
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 3.5f
        paint.strokeCap = android.graphics.Paint.Cap.ROUND
        graphic.drawLine(18f, 28f, 58f, 28f, paint)
        graphic.drawLine(8f, 39f, 52f, 39f, paint)
        graphic.drawLine(27f, 49f, 55f, 49f, paint)
        paint.style = android.graphics.Paint.Style.FILL

        val boldItalic = android.graphics.Typeface.create(
            "sans-serif-condensed", android.graphics.Typeface.BOLD_ITALIC
        )
        val cashPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = red
            typeface = boldItalic
            textSize = 53f
            isSubpixelText = true
            textSkewX = -0.06f
        }
        // Fill the main line instead of leaving a large blank white box.
        cashPaint.textScaleX = (181f / cashPaint.measureText("CASH")).coerceIn(0.88f, 1.35f)
        graphic.drawText("CASH", 74f, 49f, cashPaint)

        val onPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = red
            typeface = boldItalic
            textSize = 31f
            isSubpixelText = true
        }
        val deliveryPaint = android.graphics.Paint(onPaint).apply {
            color = android.graphics.Color.rgb(18, 18, 18)
            textSize = 31f
        }
        val on = "ON"
        val delivery = "DELIVERY"
        val gap = 7f
        val width = onPaint.measureText(on) + gap + deliveryPaint.measureText(delivery)
        if (width > 245f) {
            val factor = 245f / width
            onPaint.textScaleX = factor
            deliveryPaint.textScaleX = factor
        }
        val totalWidth = onPaint.measureText(on) + gap + deliveryPaint.measureText(delivery)
        val lineStart = (300f - totalWidth) / 2f + 7f
        graphic.drawText(on, lineStart, 75f, onPaint)
        graphic.drawText(delivery, lineStart + onPaint.measureText(on) + gap, 75f, deliveryPaint)
        graphic.restoreToCount(saved)
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
