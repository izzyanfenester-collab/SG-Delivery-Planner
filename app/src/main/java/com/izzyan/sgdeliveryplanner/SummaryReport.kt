package com.izzyan.sgdeliveryplanner

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val reportDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val reportTimeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** One set of values drives the preview, clipboard and shared report. */
private fun summaryReportFields(plan: Plan): List<Pair<String, String>> {
    val summary = deliverySummary(plan)
    val start = LocalDateTime.parse(summary.start)
    fun reportTime(value: String): String {
        val dateTime = LocalDateTime.parse(value)
        val time = dateTime.format(reportTimeFormat)
        return if (dateTime.toLocalDate() == start.toLocalDate()) time
        else "$time (${dateTime.format(reportDateFormat)})"
    }
    return listOf(
        "Date" to start.format(reportDateFormat),
        "Start Location" to plan.startLocation.reportLabel,
        "End Location" to plan.startLocation.reportLabel,
        "Start" to reportTime(summary.start),
        "Finish" to reportTime(summary.finish),
        "Total Parcel" to summary.totalParcel.toString(),
        "Delivered" to summary.delivered.toString(),
        "On Hold" to summary.onHold.toString(),
        "Skipped" to summary.skipped.toString(),
        "Pending" to summary.pending.toString(),
        "Success Rate" to String.format(Locale.ENGLISH, "%.1f%%", summary.successRate),
        "Total KM" to String.format(Locale.ENGLISH, "%.1f KM", summary.totalKm),
        "Cash on Hand" to formatCurrency(plan.cashOnHand),
        "Tax" to formatTax(plan),
        "Rate" to plan.exchangeRate,
        "Remark" to plan.remark
    )
}

fun buildSummaryReport(plan: Plan): String = buildString {
    append("Runner Route Planning Summary")
    summaryReportFields(plan).forEach { (label, value) ->
        append('\n').append(label).append(": ").append(value)
    }
}

internal fun summaryReportShareIntent(plan: Plan): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_SUBJECT, "Runner Route Planning Summary")
    putExtra(Intent.EXTRA_TEXT, buildSummaryReport(plan))
}

internal fun shareSummaryReport(context: Context, plan: Plan) {
    val chooser = Intent.createChooser(summaryReportShareIntent(plan), "Share Report")
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

internal fun copySummaryReport(context: Context, plan: Plan) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Runner Route Planning Summary", buildSummaryReport(plan)))
    Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
}

/** The owner shows this only after the complete summary has been persisted successfully. */
@Composable
fun DeliverySummaryReportPreview(plan: Plan, onClose: () -> Unit) {
    val context = LocalContext.current
    val fields = remember(plan) { summaryReportFields(plan) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .9f
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.94f).heightIn(max = maxHeight),
            shape = RoundedCornerShape(26.dp),
            color = PremiumNavy,
            contentColor = Color.White,
            border = BorderStroke(1.dp, PremiumGold.copy(alpha = .7f)),
            tonalElevation = 0.dp
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Runner Route Planning Report Preview",
                    color = PremiumGold,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    fields.forEach { (label, value) ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(label, color = Color(0xFFC0CADB), style = MaterialTheme.typography.labelLarge)
                            Text(
                                value,
                                color = when (label) {
                                    "Delivered" -> Color(0xFF64D2A2)
                                    "On Hold" -> Color(0xFFFFC570)
                                    else -> Color.White
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            try {
                                shareSummaryReport(context, plan)
                            } catch (_: android.content.ActivityNotFoundException) {
                                Toast.makeText(context, "No app is available to share the report.", Toast.LENGTH_LONG).show()
                            } catch (_: SecurityException) {
                                Toast.makeText(context, "The report could not be shared. Please try again.", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PremiumGold, contentColor = PremiumNavy),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Share Report") }
                    OutlinedButton(
                        onClick = {
                            try {
                                copySummaryReport(context, plan)
                            } catch (_: SecurityException) {
                                Toast.makeText(context, "The report could not be copied. Please try again.", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        border = BorderStroke(1.dp, PremiumGold),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PremiumGold),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Copy Text") }
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                    ) { Text("Close") }
                }
            }
        }
    }
}
