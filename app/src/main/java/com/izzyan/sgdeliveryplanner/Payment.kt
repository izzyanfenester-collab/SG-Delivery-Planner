package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Independent of route history: payment records must survive route deletion and app restarts. */
data class PaymentRecord(
    val date: String,
    val quantity: Int,
    val commissionPerParcelRm: String,
    val totalPaymentRm: String,
    val taxRm: String,
    val othersRm: String,
    val grandTotalPaymentRm: String
)

data class PaymentCalculation(
    val totalPaymentRm: String,
    val grandTotalPaymentRm: String
)

data class PaymentRecordRef(val index: Int, val record: PaymentRecord)

/** Create a balanced replacement record from editable fields, without losing past payments. */
fun revisedPaymentRecord(
    dateText: String, quantityText: String, commissionText: String, taxText: String, othersText: String
): PaymentRecord? {
    val date = runCatching { LocalDate.parse(dateText.trim()).toString() }.getOrNull() ?: return null
    val quantity = quantityText.toIntOrNull() ?: return null
    val amounts = calculatePayment(quantityText, commissionText, taxText, othersText) ?: return null
    return PaymentRecord(
        date = date, quantity = quantity,
        commissionPerParcelRm = normalizedCurrency(commissionText) ?: return null,
        totalPaymentRm = amounts.totalPaymentRm,
        taxRm = normalizedCurrency(taxText) ?: return null,
        othersRm = normalizedCurrency(othersText) ?: return null,
        grandTotalPaymentRm = amounts.grandTotalPaymentRm
    )
}

/** All payment maths use decimal amounts, with rounding to exactly two RM decimals. */
fun calculatePayment(
    quantityText: String,
    commissionPerParcelText: String,
    taxRmText: String,
    othersText: String
): PaymentCalculation? {
    val quantity = quantityText.toIntOrNull()?.takeIf { it in 1..999999 } ?: return null
    if (commissionPerParcelText.isBlank()) return null
    val commission = normalizedCurrency(commissionPerParcelText)?.toBigDecimalOrNull() ?: return null
    val tax = normalizedCurrency(taxRmText)?.toBigDecimalOrNull() ?: return null
    val others = normalizedCurrency(othersText)?.toBigDecimalOrNull() ?: return null
    val subtotal = commission.multiply(BigDecimal(quantity)).setScale(2, RoundingMode.HALF_UP)
    val grandTotal = subtotal.add(tax).add(others).setScale(2, RoundingMode.HALF_UP)
    return PaymentCalculation(subtotal.toPlainString(), grandTotal.toPlainString())
}

/** Use the latest SAVED report for the date selected on Home. Un-saved tax is excluded. */
fun savedReportTaxRm(deliveryDate: LocalDate, plans: List<Plan>): String? {
    val report = plans.asSequence()
        .filter { it.deliveryDate == deliveryDate && it.summarySavedAt != null }
        .maxByOrNull { it.summarySavedAt.orEmpty() }
        ?: return null
    return taxMyr(report.tax, report.exchangeRate)
}

/** A manual tax entry overrides the report only on the selected date. Null restores auto-source. */
fun effectivePaymentTaxRm(reportTaxRm: String?, manualTaxRm: String?): String =
    manualTaxRm ?: (reportTaxRm ?: "0.00")

/** Aggregate exact values, never sum rounded floating-point doubles. */
fun monthlyPaymentTotals(records: List<PaymentRecord>, year: Int, month: Int): PaymentCalculation {
    val matching = records.filter { record ->
        val date = runCatching { LocalDate.parse(record.date) }.getOrNull()
        date?.year == year && date.monthValue == month
    }
    fun total(selector: (PaymentRecord) -> String): String = matching
        .fold(BigDecimal.ZERO) { sum, record -> sum.add(BigDecimal(selector(record))) }
        .setScale(2, RoundingMode.HALF_UP).toPlainString()
    return PaymentCalculation(total { it.totalPaymentRm }, total { it.grandTotalPaymentRm })
}

/** Only display months with actual payouts in either amount column. */
fun nonZeroMonthlyPaymentRows(records: List<PaymentRecord>, year: Int): List<Pair<Int, PaymentCalculation>> =
    (1..12).map { month -> month to monthlyPaymentTotals(records, year, month) }
        .filter { (_, totals) ->
            BigDecimal(totals.totalPaymentRm).signum() != 0 ||
                BigDecimal(totals.grandTotalPaymentRm).signum() != 0
        }

/** Full January–December annual totals, including any months not shown in the compact table. */
fun yearlyPaymentTotals(records: List<PaymentRecord>, year: Int): PaymentCalculation {
    val totals = (1..12).map { month -> monthlyPaymentTotals(records, year, month) }
    fun sum(select: (PaymentCalculation) -> String): String =
        totals.fold(BigDecimal.ZERO) { acc, month -> acc.add(BigDecimal(select(month))) }
            .setScale(2, RoundingMode.HALF_UP).toPlainString()
    return PaymentCalculation(sum { it.totalPaymentRm }, sum { it.grandTotalPaymentRm })
}

/** A separate preference file avoids changing the version-1 Room route database. */
class PaymentLedger(context: Context) {
    private val prefs = context.getSharedPreferences("payment_ledger", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<PaymentRecord>>() {}.type

    fun read(): List<PaymentRecord> {
        // Never silently interpret corrupted storage as an empty list (that would lose records).
        val rows = gson.fromJson<List<PaymentRecord>>(prefs.getString("records", "[]"), type)
            ?: error("Saved payment data is invalid.")
        require(rows.size <= 10000 && rows.all(::validPaymentRecord)) { "Saved payment data is invalid." }
        return rows
    }

    fun append(record: PaymentRecord): Boolean {
        require(validPaymentRecord(record)) { "Invalid payment values." }
        val current = read()
        require(current.size < 10000) { "Payment history is full. Back up your records." }
        return prefs.edit().putString("records", gson.toJson(current + record)).commit()
    }

    /** Check both position and value: identical payments on the same date remain distinct. */
    fun replaceAt(index: Int, original: PaymentRecord, updated: PaymentRecord): Boolean {
        require(validPaymentRecord(updated)) { "Invalid payment values." }
        val records = read().toMutableList()
        require(index in records.indices && records[index] == original) {
            "Payment record changed. Close and reopen it before editing."
        }
        records[index] = updated
        return prefs.edit().putString("records", gson.toJson(records)).commit()
    }

    fun deleteAt(index: Int, original: PaymentRecord): Boolean {
        val records = read().toMutableList()
        require(index in records.indices && records[index] == original) {
            "Payment record changed. Close and reopen it before deleting."
        }
        records.removeAt(index)
        return prefs.edit().putString("records", gson.toJson(records)).commit()
    }

    /** Merge instead of overwriting; keep real duplicate entries while making repeated restores idempotent. */
    fun mergeRestored(imported: List<PaymentRecord>): Int {
        require(imported.size <= 10000 && imported.all(::validPaymentRecord)) { "Invalid backed-up payments." }
        val current = read()
        val currentCounts = current.groupingBy { it }.eachCount()
        val seen = mutableMapOf<PaymentRecord, Int>()
        val extra = imported.filter { record ->
            val number = (seen[record] ?: 0) + 1
            seen[record] = number
            number > (currentCounts[record] ?: 0)
        }
        if (extra.isEmpty()) return 0
        require(current.size + extra.size <= 10000) { "Too many payment records to restore." }
        check(prefs.edit().putString("records", gson.toJson(current + extra)).commit()) {
            "Payment history could not be saved. Retry restore."
        }
        return extra.size
    }
}

/** Validate imported records so a corrupt ZIP cannot alter saved payment history. */
fun validPaymentRecord(record: PaymentRecord): Boolean = runCatching {
    LocalDate.parse(record.date)
    val recalculated = calculatePayment(
        record.quantity.toString(), record.commissionPerParcelRm, record.taxRm, record.othersRm
    ) ?: return@runCatching false
    val total = normalizedCurrency(record.totalPaymentRm)
    val grand = normalizedCurrency(record.grandTotalPaymentRm)
    total != null && grand != null && total == recalculated.totalPaymentRm &&
        grand == recalculated.grandTotalPaymentRm
}.getOrDefault(false)

private val rowDate = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

private fun displayPaymentDate(value: String): String = runCatching {
    LocalDate.parse(value).format(rowDate)
}.getOrDefault(value)

@Composable
private fun PaymentRow(label: String, strong: Boolean = false, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val cellBackground = if (strong) colors.primaryContainer else colors.surface
    val cellText = if (strong) colors.onPrimaryContainer else colors.onSurface
    val gridLine = colors.outlineVariant
    Row(
        Modifier.fillMaxWidth().border(BorderStroke(0.5.dp, gridLine)).background(cellBackground),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(0.42f).heightIn(min = 54.dp).padding(8.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                label, color = cellText, fontSize = 13.sp,
                lineHeight = 17.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal
            )
        }
        Box(
            Modifier.weight(0.58f).heightIn(min = 54.dp)
                .border(BorderStroke(0.5.dp, gridLine)).padding(8.dp),
            contentAlignment = Alignment.CenterStart
        ) { content() }
    }
}

@Composable
private fun PaymentInput(
    value: String,
    hint: String,
    keyboardType: KeyboardType,
    onChange: (String) -> Unit
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { input ->
            Box {
                if (value.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                input()
            }
        }
    )
}

@Composable
private fun PaymentTableRow(
    dateOrMonth: String, totalPayment: String, grandTotal: String,
    isHeader: Boolean = false, isTotal: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val emphasized = isHeader || isTotal
    val cellBackground = if (emphasized) colors.primaryContainer else colors.surface
    val cellText = if (emphasized) colors.onPrimaryContainer else colors.onSurface
    val gridLine = colors.outlineVariant
    Row(Modifier.fillMaxWidth().border(BorderStroke(0.5.dp, gridLine)).background(cellBackground)) {
        listOf(dateOrMonth, totalPayment, grandTotal).forEachIndexed { index, value ->
            Box(
                Modifier.weight(if (index == 0) 0.88f else 1.16f)
                    .heightIn(min = if (isHeader) 54.dp else 30.dp)
                    .border(BorderStroke(0.5.dp, gridLine)).padding(5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    value, color = cellText, fontSize = if (isHeader) 11.sp else 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun PaymentScreen(vm: PlannerViewModel) {
    val savedRoutes by vm.history.collectAsState(initial = emptyList())
    val matchingReports = remember(vm.route, savedRoutes) {
        val active = listOfNotNull(vm.route)
        val historical = savedRoutes.mapNotNull { runCatching { vm.repo.decode(it) }.getOrNull() }
        active + historical
    }
    val reportTax = savedReportTaxRm(vm.deliveryDate, matchingReports)

    var quantity by rememberSaveable(vm.deliveryDate.toString()) { mutableStateOf("") }
    var commission by rememberSaveable(vm.deliveryDate.toString()) { mutableStateOf("") }
    var others by rememberSaveable(vm.deliveryDate.toString()) { mutableStateOf("") }
    // Null means use the newest saved report value. Manual edits take priority until Reset.
    // Keeping an override separate avoids overwriting an in-progress edit when reports refresh.
    var manualTaxRm by rememberSaveable(vm.deliveryDate.toString()) { mutableStateOf<String?>(null) }
    val taxRm = effectivePaymentTaxRm(reportTax, manualTaxRm)
    val amounts = calculatePayment(quantity, commission, taxRm, others)

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Payment", style = MaterialTheme.typography.headlineMedium)
        Column {
            PaymentRow("Date") {
                Text(displayPaymentDate(vm.deliveryDate.toString()), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
            PaymentRow("Qty parcel") {
                PaymentInput(quantity, "Enter quantity", KeyboardType.Number) {
                    if (it.length <= 6 && it.all(Char::isDigit)) quantity = it
                }
            }
            PaymentRow("Commission per parcel (RM)") {
                PaymentInput(commission, "Enter RM", KeyboardType.Decimal) {
                    if (validCurrencyInput(it)) commission = it
                }
            }
            PaymentRow("Total payment (RM)") {
                Text(amounts?.totalPaymentRm ?: "—", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
            PaymentRow("Tax (RM)") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PaymentInput(taxRm, "Enter tax (RM)", KeyboardType.Decimal) {
                        if (validCurrencyInput(it)) manualTaxRm = it
                    }
                    if (reportTax != null && manualTaxRm != null) {
                        TextButton(onClick = { manualTaxRm = null }) {
                            Text("Use report tax: RM $reportTax", fontSize = 12.sp)
                        }
                    } else if (reportTax != null) {
                        Text(
                            "From Delivery Report Summary",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            PaymentRow("Others (RM)") {
                PaymentInput(others, "Enter RM (optional)", KeyboardType.Decimal) {
                    if (validCurrencyInput(it)) others = it
                }
            }
            PaymentRow("Grand total payment", strong = true) {
                Text(
                    if (amounts == null) "—" else "RM " + amounts.grandTotalPaymentRm,
                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { quantity = ""; commission = ""; others = ""; manualTaxRm = null },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) { Text("Reset", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = {
                        val value = amounts ?: return@OutlinedButton
                        val record = PaymentRecord(
                            date = vm.deliveryDate.toString(),
                            quantity = quantity.toInt(),
                            commissionPerParcelRm = normalizedCurrency(commission) ?: "0.00",
                            totalPaymentRm = value.totalPaymentRm,
                            taxRm = normalizedCurrency(taxRm) ?: "0.00",
                            othersRm = normalizedCurrency(others) ?: "0.00",
                            grandTotalPaymentRm = value.grandTotalPaymentRm
                        )
                        if (vm.savePayment(record)) {
                            quantity = ""; commission = ""; others = ""
                        }
                    },
                    enabled = amounts != null && !vm.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text("Save", fontWeight = FontWeight.Bold) }
            }
        }
        if (reportTax == null) {
            Text(
                "No saved Delivery Report Summary for this date. Tax defaults to RM 0.00; you can edit it manually.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text("Payment history", style = MaterialTheme.typography.titleLarge)
        Column {
            PaymentTableRow("Date", "Total payment (RM)", "Grand total payment (RM)", isHeader = true)
            val entries = vm.paymentRecords.asReversed()
            entries.forEach { record ->
                PaymentTableRow(displayPaymentDate(record.date), record.totalPaymentRm, record.grandTotalPaymentRm)
            }
        }
        Text("Summary payment in month", style = MaterialTheme.typography.titleLarge)
        Text(
            "Totals for the current calendar year, calculated from saved payment records.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        val currentYear = LocalDate.now(java.time.ZoneId.of("Asia/Singapore")).year
        Text(currentYear.toString(), style = MaterialTheme.typography.titleMedium)
        val monthsWithPayment = nonZeroMonthlyPaymentRows(vm.paymentRecords, currentYear)
        val yearTotal = yearlyPaymentTotals(vm.paymentRecords, currentYear)
        Column {
            PaymentTableRow("Month", "Total payment (RM)", "Grand total payment (RM)", isHeader = true)
            monthsWithPayment.forEach { (month, totals) ->
                PaymentTableRow(
                    java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH),
                    totals.totalPaymentRm, totals.grandTotalPaymentRm
                )
            }
            if (monthsWithPayment.isEmpty()) {
                Text(
                    "No payment records for $currentYear.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
            PaymentTableRow("Total", yearTotal.totalPaymentRm, yearTotal.grandTotalPaymentRm, isTotal = true)
        }
    }
}
