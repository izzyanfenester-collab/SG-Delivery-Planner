package com.izzyan.sgdeliveryplanner

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Keep the persisted payment codes and all payment calculations unchanged. */
data class DeliveryBadgeStyle(val label: String, val backgroundArgb: Int, val foregroundArgb: Int)

fun paymentModeBadge(order: CustomerOrder?): DeliveryBadgeStyle? =
    when (order?.paymentStatus?.uppercase(Locale.ENGLISH)) {
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
 * Shared artwork for Route, Delivery, History and exported Delivery Report.
 * Text is rotated 35 degrees CLOCKWISE, descending from left to right.
 * The double-ring circular boundary stays upright.
 */
internal fun drawSlantedPaymentStamp(
    canvas: AndroidCanvas,
    paymentMode: String,
    sizePx: Float
) {
    val mode = paymentMode.uppercase(Locale.ENGLISH)
    if (mode != "COD" && mode != "PAYNOW") return
    val red = android.graphics.Color.rgb(255, 55, 88)
    val magenta = android.graphics.Color.rgb(227, 75, 228)
    val ink = if (mode == "COD") red else magenta

    val outer = canvas.save()
    canvas.scale(sizePx / 240f, sizePx / 240f)
    val ring = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = ink
        style = AndroidPaint.Style.STROKE
        strokeWidth = 7f
        strokeCap = AndroidPaint.Cap.ROUND
    }
    canvas.drawCircle(120f, 120f, 109f, ring)
    ring.strokeWidth = 2.5f
    ring.alpha = 170
    canvas.drawCircle(120f, 120f, 99f, ring)
    ring.alpha = 255

    val text = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = ink
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
        textAlign = AndroidPaint.Align.CENTER
        isSubpixelText = true
    }
    val textSave = canvas.save()
    canvas.rotate(35f, 120f, 120f)
    if (mode == "COD") {
        text.textSize = 25f
        canvas.drawText("CASH ON", 120f, 90f, text)
        text.textSize = 73f
        canvas.drawText("COD", 120f, 148f, text)
        text.textSize = 24f
        canvas.drawText("DELIVERY", 120f, 180f, text)
    } else {
        text.textSize = 57f
        text.textScaleX = 0.88f
        canvas.drawText("PayNow", 120f, 137f, text)
        ring.strokeWidth = 5f
        canvas.drawLine(47f, 152f, 193f, 152f, ring)
    }
    canvas.restoreToCount(textSave)
    canvas.restoreToCount(outer)
}

/** Preserve the original Build #717 circular COD stamp; PayNow styling is unchanged. */
@Composable
fun PaymentModeStamp(order: CustomerOrder?, modifier: Modifier = Modifier, size: Dp = 82.dp) {
    when (val mode = order?.paymentStatus?.uppercase(Locale.ENGLISH)) {
        "COD" -> Image(
            painter = painterResource(R.drawable.cod_circle_stamp),
            contentDescription = "Cash on Delivery",
            modifier = modifier.size(size),
            contentScale = ContentScale.Fit
        )
        "PAYNOW" -> Canvas(modifier = modifier.size(size)) {
            drawSlantedPaymentStamp(drawContext.canvas.nativeCanvas, mode, this.size.width)
        }
    }
}

@Composable
fun PaymentModeRow(order: CustomerOrder?, useCodLogo: Boolean = true) {
    PaymentModeStamp(order)
}
