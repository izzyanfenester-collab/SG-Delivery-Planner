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

/** Original saved-route History card layout with the existing V9 full-size proof viewer. */
@Composable
private fun V6DeliveryCard(stop: Stop, index: Int, onReport: () -> Unit) {
    val badge = reportStatusBadge(stop.status)
    val paymentProofId = stop.order?.paymentProofFileId
    val deliveryProofId = stop.order?.proofFileId
    var expandedProofIndex by remember(stop.orderId) { mutableStateOf<Int?>(null) }
    val shape = RoundedCornerShape(20.dp)

    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Color(0xFF102844))
            .border(1.dp, V6Outline.copy(alpha = .48f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Payment logo retains the existing 35-degree tilt and sits next to Delivered.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Stop ${index + 1} · ${stop.place.postal}",
                Modifier.weight(1f), color = V6Gold,
                fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Box(
                Modifier.background(Color(badge.backgroundArgb), RoundedCornerShape(30.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text(badge.label, color = Color(badge.foregroundArgb),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (stop.order?.paymentStatus?.uppercase(Locale.ENGLISH) in listOf("COD", "PAYNOW")) {
                PaymentModeStamp(stop.order, size = 54.dp)
            }
        }
        Text("Customer : " + (stop.order?.customerName ?: "—"),
            color = V6White, fontSize = 14.sp)
        Text("Phone : " + (stop.order?.phoneNumber ?: "—"),
            color = V6White, fontSize = 14.sp)
        Text("Address : " + (stop.order?.fullAddress ?: stop.place.address),
            color = V6White, fontSize = 14.sp, lineHeight = 19.sp)
        Text("Parcel Price : " + orderPrice(stop.order),
            color = V6White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        // Both proof thumbnails sit directly below Parcel Price.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top) {
            V9ProofTile(
                id = paymentProofId, label = "Proof of Payment",
                modifier = Modifier.weight(1f), onExpand = { expandedProofIndex = 0 }
            )
            V9ProofTile(
                id = deliveryProofId, label = "Proof of Delivery",
                modifier = Modifier.weight(1f), onExpand = { expandedProofIndex = 1 }
            )
        }
        if (stop.holdReason != null)
            Text("Hold reasons : " + stop.holdReason, color = V6White, fontSize = 13.sp)
        if (stop.holdNote != null)
            Text("Hold notes : " + stop.holdNote, color = V6White, fontSize = 13.sp)

        OutlinedButton(
            onClick = onReport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, V6Outline)
        ) {
            Text("View / Share Delivery Report", color = V6White, fontSize = 13.sp)
        }
    }

    expandedProofIndex?.let { current ->
        V9ProofViewer(
            paymentProofId = paymentProofId,
            deliveryProofId = deliveryProofId,
            selectedIndex = current,
            onSelect = { expandedProofIndex = it },
            onMinimize = { expandedProofIndex = null }
        )
    }
}

/** Both proof images remain visible; tapping a present image opens the larger viewer. */
@Composable
private fun V9ProofTile(
    id: String?, label: String, modifier: Modifier = Modifier,
    onExpand: () -> Unit
) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, id) {
        value = withContext(Dispatchers.IO) {
            val store = OrderProofStore(context)
            store.file(id)?.let { store.decode(it, 750) }
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = V6White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1)
        Box(
            Modifier.fillMaxWidth().height(125.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF06192C))
                .border(1.dp, V6Outline.copy(alpha = .7f), RoundedCornerShape(10.dp))
                .clickable(enabled = thumbnail != null, onClick = onExpand),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(thumbnail!!.asImageBitmap(), label,
                    Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .background(Color(0xD908223E), RoundedCornerShape(50))
                        .border(1.dp, V6Outline, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) { Text("⤢", color = V6White, fontSize = 16.sp) }
            } else {
                Text(if (id == null) "No photo" else "Photo unavailable",
                    color = V6Secondary, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
        Text(if (thumbnail != null) "View Photo" else "No proof yet",
            color = V6Secondary, fontSize = 12.sp)
    }
}

/** Read-only proof lightbox: arrows switch images; Minimize restores History position. */
@Composable
private fun V9ProofViewer(
    paymentProofId: String?,
    deliveryProofId: String?,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onMinimize: () -> Unit
) {
    val ids = listOf(paymentProofId, deliveryProofId)
    val titles = listOf("Payment Proof", "Delivery Proof")
    val context = LocalContext.current
    val currentId = ids[selectedIndex]
    val fullImage by produceState<Bitmap?>(initialValue = null, currentId) {
        value = withContext(Dispatchers.IO) {
            val store = OrderProofStore(context)
            store.file(currentId)?.let { store.decode(it, 2048) }
        }
    }
    Dialog(
        onDismissRequest = onMinimize,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier.fillMaxSize().background(Color(0xF0061527))
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(titles[selectedIndex], color = V6White, fontSize = 19.sp,
                        fontWeight = FontWeight.Bold)
                    Text("Saved delivery photo", color = V6Secondary, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onMinimize,
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(13.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text("⌄  Minimize", color = V6Gold, fontSize = 13.sp)
                }
            }
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .background(Color(0xFF071B30), RoundedCornerShape(15.dp))
                    .border(1.dp, V6Outline, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (fullImage != null) {
                    Image(fullImage!!.asImageBitmap(), titles[selectedIndex],
                        Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                } else {
                    Text(if (currentId == null) "No photo" else "Loading photo…",
                        color = V6Secondary, fontSize = 14.sp)
                }
                if (ids[1 - selectedIndex] != null) {
                    TextButton(
                        onClick = { onSelect(1 - selectedIndex) },
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Text("‹", color = V6White, fontSize = 38.sp)
                    }
                    TextButton(
                        onClick = { onSelect(1 - selectedIndex) },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Text("›", color = V6White, fontSize = 38.sp)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text((selectedIndex + 1).toString() + " / 2",
                    color = V6White, fontSize = 13.sp)
            }
        }
    }
}
