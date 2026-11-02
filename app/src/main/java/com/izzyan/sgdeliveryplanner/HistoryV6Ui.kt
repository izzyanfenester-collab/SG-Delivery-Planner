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

private fun savedDateLabel(raw: LocalDate): String = runCatching {
    raw.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
}.getOrDefault(raw.toString())

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
        MarinaBayBackdrop(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(V6Background.copy(alpha = .12f))) {
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
                    Text("SG DELIVERY", color = V6Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("History", color = V6White, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
                }
                Column(
                    Modifier.background(Color(0xFF102F51), RoundedCornerShape(13.dp))
                        .border(1.dp, V6Outline, RoundedCornerShape(13.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(savedDateLabel(plan.deliveryDate), color = V6White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Saved route", color = V6Secondary, fontSize = 12.sp)
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
                                Text(if (detailsOpen) "Hide route details" else "Route details", fontSize = 13.sp, color = V6Secondary)
                            }
                            TextButton(onClick = onSummaryReport, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                                Text("View / Share Summary Report", fontSize = 13.sp, color = V6Gold)
                            }
                        }
                        if (detailsOpen) {
                            Column(
                                Modifier.fillMaxWidth().background(Color(0xFF112E4D), RoundedCornerShape(14.dp))
                                    .border(1.dp, V6Outline, RoundedCornerShape(14.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text("VIEW ONLY · Saved route", color = V6Gold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
}

@Composable
private fun V6StatBox(symbol: String, count: String, label: String, tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.background(Color(0xFF143454), RoundedCornerShape(13.dp))
            .padding(horizontal = 7.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(symbol, color = tint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(count, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp, color = V6White)
            Text(label, fontSize = 12.sp, maxLines = 1, color = V6Secondary)
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
    val statusBackground = if (delivered) Color(0xFF063C33)
        else if (onHold) Color(0xFF49371A) else Color(0xFF1A3754)
    var extraOpen by remember(stop.orderId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(17.dp)

    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).background(V6Panel)
            .border(1.dp, V6Outline.copy(alpha = .8f), shape)
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = 16.dp, end = 13.dp, top = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.border(1.dp, V6Gold.copy(alpha = .85f), RoundedCornerShape(50))
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text("STOP " + (index + 1).toString().padStart(2, '0'),
                            color = V6Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Box(
                        Modifier.background(statusBackground, RoundedCornerShape(50))
                            .border(1.dp, statusTint.copy(alpha = .8f), RoundedCornerShape(50))
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text((if (delivered) "✓ " else if (onHold) "◷ " else "") + status.label,
                            color = statusTint, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(stop.place.postal, color = V6White, fontWeight = FontWeight.Bold,
                            fontSize = 27.sp, lineHeight = 30.sp)
                        Text(stop.order?.customerName ?: "—", color = V6White,
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stop.order?.fullAddress ?: stop.place.address,
                            color = V6Secondary, fontSize = 13.sp, lineHeight = 18.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Column(
                        Modifier.width(80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Original COD / PayNow stamp keeps the 35-degree right tilt.
                        PaymentModeStamp(stop.order, size = 72.dp)
                        Text("Payment Mode", color = V6Secondary, fontSize = 10.sp,
                            maxLines = 1)
                    }
                }

                Row(
                    Modifier.fillMaxWidth()
                        .background(Color(0xFF123657), RoundedCornerShape(11.dp))
                        .padding(start = 11.dp, end = 5.dp, top = 7.dp, bottom = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("PARCEL AMOUNT", color = V6Secondary, fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold)
                        Text(orderPrice(stop.order), color = V6Gold,
                            fontSize = 24.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = { extraOpen = !extraOpen },
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp)
                    ) {
                        Text(if (extraOpen) "Hide details ⌃" else "Details ›",
                            color = Color(0xFFC1DCFC), fontSize = 13.sp, maxLines = 1)
                    }
                }

                if (extraOpen) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Color(0xFF102C4C), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Phone: " + (stop.order?.phoneNumber ?: "—"),
                            color = V6White, fontSize = 13.sp)
                        Text("Address: " + (stop.order?.fullAddress ?: stop.place.address),
                            color = V6White, fontSize = 13.sp)
                        if (stop.holdReason != null) Text("Hold reasons: " + stop.holdReason,
                            color = V6White, fontSize = 13.sp)
                        if (stop.holdNote != null) Text("Hold notes: " + stop.holdNote,
                            color = V6White, fontSize = 13.sp)
                    }
                }
            }
            Box(Modifier.matchParentSize()) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            }
        }

        HorizontalDivider(color = V6Outline.copy(alpha = .65f), thickness = 1.dp)
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF103050))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            V6InlineProof(
                id = stop.order?.paymentProofFileId,
                label = "Payment Proof",
                modifier = Modifier.weight(1f)
            )
            V6InlineProof(
                id = stop.order?.proofFileId,
                label = "Delivery Proof",
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = V6Outline.copy(alpha = .65f), thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth().height(46.dp)
                .background(Color(0xFF15395C))
                .clickable(onClick = onReport),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(R.drawable.ic_customs_document), null,
                tint = V6Gold, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(8.dp))
            Text("Report", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = V6Gold)
        }
    }
}

/** Always-visible, read-only proof images with a clean shared gallery layout. */
@Composable
private fun V6InlineProof(id: String?, label: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, id) {
        value = withContext(Dispatchers.IO) {
            val store = OrderProofStore(context)
            store.file(id)?.let { store.decode(it, 900) }
        }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = V6White, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
        Box(
            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF071C32))
                .border(1.dp, V6Outline.copy(alpha = .75f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(
                    thumbnail!!.asImageBitmap(),
                    label,
                    Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    if (id == null) "No photo" else "Photo unavailable",
                    color = V6Secondary, fontSize = 12.sp, textAlign = TextAlign.Center
                )
            }
        }
    }
}
