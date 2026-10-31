package com.izzyan.sgdeliveryplanner

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val V6Background = Color(0xFF06172A)
private val V6Panel = Color(0xFF0B2949)
private val V6Action = Color(0xFF12365D)
private val V6Outline = Color(0xFF365E84)
private val V6Gold = Color(0xFFFFC76D)
private val V6White = Color(0xFFF1F5FE)
private val V6Secondary = Color(0xFFB5CAE3)
private val V6CodRed = Color(0xFFFF3758)
private val V6PayNowMagenta = Color(0xFFE34BE4)

private fun savedDateLabel(raw: String): String = runCatching {
    LocalDate.parse(raw).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
}.getOrDefault(raw)

/** Modern history-only layout, based on official #717. All content remains read-only. */
@Composable
internal fun HistoryViewV6(
    plan: Plan,
    onClose: () -> Unit,
    onReport: (Int) -> Unit,
    onSummaryReport: () -> Unit
) {
    val summary = remember(plan) { deliverySummary(plan) }
    var detailsOpen by remember(plan.id) { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(V6Background)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 13.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0C2442))
                        .border(1.dp, V6Outline, RoundedCornerShape(12.dp))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Text("‹", fontSize = 35.sp, lineHeight = 36.sp, color = V6Gold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text("SG DELIVERY", color = V6Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("History", color = V6White, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                }
                Column(
                    Modifier.background(Color(0xFF102F51), RoundedCornerShape(13.dp))
                        .border(1.dp, V6Outline, RoundedCornerShape(13.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(savedDateLabel(plan.deliveryDate), color = V6White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Saved route", color = V6Secondary, fontSize = 10.sp)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .background(Color(0xFF0C2442))
                                .border(1.dp, V6Outline, RoundedCornerShape(18.dp))
                                .padding(7.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            V6StatBox("▣", plan.stops.size.toString(), "Total", V6Gold, Modifier.weight(1f))
                            V6StatBox("✓", summary.delivered.toString(), "Delivered", Color(0xFF61E6A5), Modifier.weight(1f))
                            V6StatBox("◷", summary.onHold.toString(), "On Hold", Color(0xFFFFC76D), Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { detailsOpen = !detailsOpen }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                Text(if (detailsOpen) "Hide route details" else "Route details", fontSize = 12.sp, color = V6Secondary)
                            }
                            TextButton(onClick = onSummaryReport, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                                Text("View / Share Summary Report", fontSize = 12.sp, color = V6Gold)
                            }
                        }
                        if (detailsOpen) {
                            Column(
                                Modifier.fillMaxWidth().background(Color(0xFF112E4D), RoundedCornerShape(14.dp))
                                    .border(1.dp, V6Outline, RoundedCornerShape(14.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text("VIEW ONLY · Saved route", color = V6Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Date: " + plan.deliveryDate)
                                Text("Total Parcel: " + summary.totalParcel + " · Delivered: " + summary.delivered)
                                Text("On Hold: " + summary.onHold + " · Skipped: " + summary.skipped + " · Pending: " + summary.pending)
                                Text("Success Rate: " + String.format(Locale.ENGLISH, "%.1f%%", summary.successRate))
                                Text("Cash on Hand: " + formatCurrency(plan.cashOnHand) + " · Tax: " + plan.tax)
                                Text("Remark: " + plan.remark.ifBlank { "—" })
                            }
                        }
                    }
                }
                itemsIndexed(plan.stops, key = { i, stop -> stop.orderId + "-history-" + i }) { index, stop ->
                    V6DeliveryCard(stop, index, onReport = { onReport(index) })
                }
            }
        }
    }
}

@Composable
private fun V6StatBox(symbol: String, count: String, label: String, tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.background(Color(0xFF143454), RoundedCornerShape(13.dp))
            .padding(horizontal = 7.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(symbol, color = tint, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(count, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, color = V6White)
            Text(label, fontSize = 10.sp, maxLines = 1, color = V6Secondary)
        }
    }
}

@Composable
private fun V6DeliveryCard(stop: Stop, index: Int, onReport: () -> Unit) {
    val payment = stop.order?.paymentStatus?.uppercase(Locale.ENGLISH)
    val accent = when (payment) {
        "COD" -> V6CodRed
        "PAYNOW" -> V6PayNowMagenta
        else -> V6Outline
    }
    val status = reportStatusBadge(stop.status)
    val delivered = normalizedStatus(stop.status) == "DELIVERED"
    val onHold = normalizedStatus(stop.status) == "ON_HOLD"
    val statusTint = if (delivered) Color(0xFF5DE8A3) else if (onHold) V6Gold else V6Secondary
    val statusBackground = if (delivered) Color(0xFF063C33) else if (onHold) Color(0xFF49371A) else Color(0xFF1A3754)
    var extraOpen by remember(stop.orderId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).background(V6Panel)
            .border(1.dp, V6Outline, shape)
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(start = 15.dp, end = 11.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.border(1.dp, V6Gold, RoundedCornerShape(50))
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Text("STOP " + (index + 1).toString().padStart(2, '0'),
                                    color = V6Gold, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
                            }
                            Box(
                                Modifier.background(statusBackground, RoundedCornerShape(50))
                                    .border(1.dp, statusTint, RoundedCornerShape(50))
                                    .padding(horizontal = 7.dp, vertical = 5.dp)
                            ) {
                                Text((if (delivered) "✓ " else if (onHold) "◷ " else "") + status.label,
                                    color = statusTint, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                        Text(stop.place.postal, color = V6White, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 28.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("●", color = V6Secondary, fontSize = 13.sp)
                            Text(stop.order?.customerName ?: "—", color = V6White, fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 2)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
                            Text("⌖", color = Color(0xFFA8C5F3), fontSize = 18.sp, lineHeight = 19.sp)
                            Text(stop.order?.fullAddress ?: stop.place.address, color = V6Secondary,
                                fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3)
                        }
                    }
                    Column(Modifier.width(91.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        V6SlantedPaymentSeal(payment, 88.dp)
                        Text("Payment Mode", color = V6Secondary, fontSize = 10.sp, maxLines = 1)
                        TextButton(
                            onClick = { extraOpen = !extraOpen },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text(if (extraOpen) "Hide details ‹" else "Details ›",
                                color = Color(0xFFADCFF8), fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                Text("PARCEL AMOUNT", color = V6Secondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(orderPrice(stop.order), color = V6Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp)
                if (extraOpen) {
                    HorizontalDivider(color = V6Outline, thickness = 1.dp)
                    Text("Phone : " + (stop.order?.phoneNumber ?: "—"), color = V6White, fontSize = 13.sp)
                    Text("Address : " + (stop.order?.fullAddress ?: stop.place.address), color = V6White, fontSize = 13.sp)
                    if (stop.holdReason != null) Text("Hold reasons : " + stop.holdReason, color = V6White, fontSize = 13.sp)
                    if (stop.holdNote != null) Text("Hold notes : " + stop.holdNote, color = V6White, fontSize = 13.sp)
                }
            }
            // Full-height stripe inside this card's upper portion only.
            Box(Modifier.matchParentSize()) {
                Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
            }
        }
        HorizontalDivider(color = V6Outline, thickness = 1.dp)
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp).background(V6Action),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            V6ProofAction(
                id = stop.order?.paymentProofFileId,
                label = "Payment\nProof",
                iconRes = R.drawable.ic_delivery_receipt,
                modifier = Modifier.weight(1f)
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(V6Outline))
            V6ProofAction(
                id = stop.order?.proofFileId,
                label = "Delivery\nProof",
                iconRes = R.drawable.ic_delivery_camera,
                modifier = Modifier.weight(1f)
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(V6Outline))
            Row(
                Modifier.weight(1f).fillMaxHeight().clickable(onClick = onReport).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(painterResource(R.drawable.ic_customs_document), null, tint = V6Gold, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(5.dp))
                Text("Report", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = V6Gold)
            }
        }
    }
}

/**
 * Text in the circular seals goes 35 degrees clockwise: upper left to lower right.
 * The circles themselves are drawn upright. Only History uses these new stamps;
 * Route/Delivery and the shared report retain their existing approved graphics.
 */
@Composable
private fun V6SlantedPaymentSeal(payment: String?, diameter: Dp) {
    if (payment != "COD" && payment != "PAYNOW") {
        Box(Modifier.size(diameter))
        return
    }
    val isCod = payment == "COD"
    Canvas(Modifier.size(diameter)) {
        val native = drawContext.canvas.nativeCanvas
        val saved = native.save()
        native.scale(size.width / 240f, size.height / 240f)
        val ink = if (isCod) android.graphics.Color.rgb(255, 55, 88)
                  else android.graphics.Color.rgb(227, 75, 228)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = 7f
        }
        native.drawCircle(120f, 120f, 109f, stroke)
        stroke.strokeWidth = 2.5f
        stroke.alpha = 150
        native.drawCircle(120f, 120f, 99f, stroke)
        stroke.alpha = 255
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
            textAlign = Paint.Align.CENTER
            isSubpixelText = true
        }
        native.save()
        native.rotate(35f, 120f, 120f) // clockwise is down to the right in Android coordinates
        if (isCod) {
            labelPaint.textSize = 25f
            native.drawText("CASH ON", 120f, 90f, labelPaint)
            labelPaint.textSize = 73f
            native.drawText("COD", 120f, 148f, labelPaint)
            labelPaint.textSize = 24f
            native.drawText("DELIVERY", 120f, 180f, labelPaint)
        } else {
            labelPaint.textSize = 58f
            labelPaint.textScaleX = 0.90f
            native.drawText("PayNow", 120f, 137f, labelPaint)
            stroke.strokeWidth = 5f
            native.drawLine(48f, 151f, 192f, 151f, stroke)
        }
        native.restore()
        native.restoreToCount(saved)
    }
}

/** Compact, read-only proof shortcut. The uploaded image still opens full screen. */
@Composable
private fun V6ProofAction(id: String?, label: String, iconRes: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var enlarged by remember(id) { mutableStateOf(false) }
    val thumbnail by produceState<Bitmap?>(initialValue = null, id) {
        value = withContext(Dispatchers.IO) {
            val store = OrderProofStore(context)
            store.file(id)?.let { store.decode(it, 180) }
        }
    }
    Row(
        modifier = modifier.fillMaxHeight()
            .clickable(enabled = thumbnail != null) { enlarged = true }
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (thumbnail != null) {
            Image(
                thumbnail!!.asImageBitmap(),
                label.replace("\n", " "),
                Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (id == null) Color(0xFF8CA0BA) else V6Secondary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            if (id == null) label.replace("\n", "\n") else label,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            textAlign = TextAlign.Start,
            color = if (id == null) Color(0xFF8CA0BA) else V6White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (enlarged) {
        val fullSize by produceState<Bitmap?>(initialValue = null, id) {
            value = withContext(Dispatchers.IO) {
                val store = OrderProofStore(context)
                store.file(id)?.let { store.decode(it, 2048) }
            }
        }
        Dialog(onDismissRequest = { enlarged = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(PremiumNavy).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label.replace("\n", " "), Modifier.weight(1f), color = PremiumGold)
                    TextButton(onClick = { enlarged = false }) { Text("Close") }
                }
                fullSize?.let {
                    Image(it.asImageBitmap(), label.replace("\n", " "), Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit)
                } ?: Text("Unable to load image.", color = Color.White)
            }
        }
    }
}
