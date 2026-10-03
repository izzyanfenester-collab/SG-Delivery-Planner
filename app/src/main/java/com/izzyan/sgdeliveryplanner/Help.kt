package com.izzyan.sgdeliveryplanner

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A snapshot prepared only when the driver asks for help. No chat API or credentials are used. */
fun buildHelpContext(plan: Plan?, message: String): String = buildString {
    appendLine("Please help me with this IZZ Delivery route in Singapore. Reply in clear English only.")
    appendLine("The arrival times below are planning estimates. They do not use live traffic or current GPS information.")
    if (plan == null) {
        appendLine("Current stop: No route planned yet.")
        appendLine("Next stop: Not available.")
        appendLine("ETA: Not available until a route is planned.")
        appendLine("Total route progress: 0 / 0 deliveries; 0 / 0 reviewed.")
        appendLine("Delivered: 0; On hold: 0; Skipped: 0; Pending: 0.")
    } else {
        val currentIndex = plan.current.coerceIn(0, plan.stops.size)
        val current = plan.stops.getOrNull(currentIndex)
        val nextIndex = ((currentIndex + 1 until plan.stops.size) + (0 until currentIndex))
            .firstOrNull { plan.stops[it].reviewedAt == null }
        val next = nextIndex?.let { plan.stops[it] }
        val delivered = plan.stops.count { it.status == "DELIVERED" || it.status == "COMPLETED" }
        val onHold = plan.stops.count { it.status == "ON_HOLD" }
        val skipped = plan.stops.count { it.status == "SKIPPED" }
        val pending = plan.stops.size - delivered - onHold - skipped
        val reviewed = plan.stops.count { it.reviewedAt != null }
        val percent = if (plan.stops.isEmpty()) 0 else delivered * 100 / plan.stops.size
        appendLine("Route: Woodlands Checkpoint (${depot.postal}) to ${plan.stops.size} delivery stops, then return to Woodlands Checkpoint.")
        appendLine("Planned start: ${helpTime(plan.start)}; traffic estimate: ${helpTrafficMode(plan.mode)}.")
        if (current != null) {
            appendLine("Current stop: ${currentIndex + 1} / ${plan.stops.size}, postal code ${current.place.postal}, ${current.place.address}.")
            appendLine("Current status: ${helpStatus(current.status)}.")
            current.holdReason?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("Hold reason: $it.") }
            current.holdNote?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("Hold note: $it") }
            appendLine("ETA: ${helpTime(current.arrival)}; planned departure: ${helpTime(current.leave)}.")
        } else {
            appendLine("Current stop: None; you are at the end of the delivery stops.")
            appendLine("ETA: Planned return to Woodlands Checkpoint at ${helpTime(plan.returned)}.")
        }
        if (next != null) {
            appendLine("Next stop: ${nextIndex!! + 1} / ${plan.stops.size}, postal code ${next.place.postal}, ${next.place.address}.")
            appendLine("Next stop ETA: ${helpTime(next.arrival)}.")
        } else {
            appendLine("Next stop: Return to Woodlands Checkpoint, postal code ${depot.postal}.")
        }
        appendLine("Total route progress: $delivered / ${plan.stops.size} delivered ($percent%); $reviewed / ${plan.stops.size} reviewed.")
        appendLine("Delivered: $delivered; On hold: $onHold; Skipped: $skipped; Pending: $pending.")
        appendLine("Planned distance: ${String.format(Locale.US, "%.1f", plan.totalKm)} km; planned return ETA: ${helpTime(plan.returned)}.")
    }
    appendLine("App message or route error: ${message.trim().ifEmpty { "None." }}")
    append("Please explain useful next steps. Ask me for any information this context does not contain.")
}

private fun helpTime(value: String): String = runCatching {
    LocalDateTime.parse(value).format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)) + " SGT"
}.getOrDefault(value.ifBlank { "Not available" })

private fun helpTrafficMode(mode: String): String = when (mode) {
    "Light Traffic" -> "Light traffic"
    "Heavy Traffic" -> "Heavy traffic"
    else -> "Normal traffic"
}

private fun helpStatus(status: String): String = when (status) {
    "DELIVERED", "COMPLETED" -> "Delivered"
    "ON_HOLD" -> "On hold"
    "SKIPPED" -> "Skipped"
    else -> "Pending"
}

@Composable
fun AskChatGptButton(plan: Plan?, message: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var copyContext by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(50.dp)
    val gold = Color(0xFFD8B970)
    Button(
        onClick = {
            val snapshot = buildHelpContext(plan, message)
            val shared = tryStartHelp(
                context,
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage("com.openai.chatgpt")
                    putExtra(Intent.EXTRA_SUBJECT, "IZZ Delivery help")
                    putExtra(Intent.EXTRA_TEXT, snapshot)
                }
            )
            if (!shared) {
                // HTTPS works without depending on an undocumented ChatGPT custom scheme.
                val opened = tryStartHelp(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/?q=${Uri.encode(snapshot)}")))
                copyContext = snapshot
                Toast.makeText(
                    context,
                    if (opened) "Opening ChatGPT. You can also copy your route details." else "ChatGPT could not be opened. Copy your route details and paste them into ChatGPT later.",
                    Toast.LENGTH_LONG
                ).show()
            }
        },
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xFF244EA6), Color(0xFF111E42))), shape),
        shape = shape,
        border = BorderStroke(1.dp, gold),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp, pressedElevation = 1.dp)
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(28.dp)) {
                val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                drawRoundRect(gold, Offset(size.width * .08f, size.height * .08f), Size(size.width * .84f, size.height * .66f), CornerRadius(4.dp.toPx()), style = stroke)
                val tail = Path().apply {
                    moveTo(size.width * .28f, size.height * .75f)
                    lineTo(size.width * .24f, size.height * .93f)
                    lineTo(size.width * .46f, size.height * .76f)
                }
                drawPath(tail, gold, style = stroke)
                listOf(.3f, .5f, .7f).forEach { x -> drawCircle(Color.White, 1.2.dp.toPx(), Offset(size.width * x, size.height * .41f)) }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Ask ChatGPT", style = MaterialTheme.typography.titleMedium)
                Text("Get delivery help", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE6EAF5))
            }
        }
    }
    copyContext?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { copyContext = null },
            title = { Text("Your route details are ready") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("If your route details do not appear in ChatGPT, copy them below and paste them into your chat.")
                    SelectionContainer { Text(snapshot, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("IZZ Delivery route details", snapshot))
                    copyContext = null
                }) { Text("Copy route details") }
            },
            dismissButton = { TextButton(onClick = { copyContext = null }) { Text("Close") } }
        )
    }
}

private fun tryStartHelp(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
