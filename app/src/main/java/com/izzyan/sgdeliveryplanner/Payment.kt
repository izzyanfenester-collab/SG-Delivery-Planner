package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
    ) ?: return false
    val total = normalizedCurrency(record.totalPaymentRm)
    val grand = normalizedCurrency(record.grandTotalPaymentRm)
    total != null && grand != null && total == recalculated.totalPaymentRm &&
        grand == recalculated.grandTotalPaymentRm
}.getOrDefault(false)

private val tableBlack = Color(0xFF242424)
private val rowDate = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

private fun displayPaymentDate(value: String): String = runCatching {
    LocalDate.parse(value).format(rowDate)
}.getOrDefault(value)

@Composable
private fun PaymentRow(label: String, strong: Boolean = false, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().border(BorderStroke(0.5.dp, tableBlack)).background(Color.White),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(0.42f).heightIn(min = 54.dp).padding(8.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                label, color = tableBlack, fontSize = 13.sp,
                lineHeight = 17.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal
            )
        }
        Box(
            Modifier.weight(0.58f).heightIn(min = 54.dp)
                .border(BorderStroke(0.5.dp, tableBlack)).padding(8.dp),
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
        textStyle = TextStyle(color = tableBlack, fontSize = 14.sp),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { input ->
            Box {
                if (value.isEmpty()) Text(hint, color = Color(0xFF707070), fontSize = 13.sp)
                input()
            }
        }
    )
}

@Composable
private fun PaymentTableRow(
    dateOrMonth: String, totalPayment: String, grandTotal: String, isHeader: Boolean = false
) {
    Row(Modifier.fillMaxWidth().border(BorderStroke(0.5.dp, tableBlack)).background(Color.White)) {
        listOf(dateOrMonth, totalPayment, grandTotal).forEachIndexed { index, value ->
            Box(
                Modifier.weight(if (index == 0) 0.88f else 1.16f)
                    .heightIn(min = if (isHeader) 54.dp else 30.dp)
                    .border(BorderStroke(0.5.dp, tableBlack)).padding(5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    value, color = tableBlack, fontSize = if (isHeader) 11.sp else 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal
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
    val taxRm = reportTax ?: "0.00"

    var quantity by rememberSaveable(vm.deliveryDate.toString()) { mutableStateOf("") }
    var commission by rememberSaveable(vm.deliveryDate.toString()) { mutableStateOf("") }
    var others by rememberSaveable(vm.deliveryDate.toString()) { mutableStateOf("") }
    val amounts = calculatePayment(quantity, commission, taxRm, others)

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Payment", style = MaterialTheme.typography.headlineMedium)
        Column {
            PaymentRow("Date") {
                Text(displayPaymentDate(vm.deliveryDate.toString()), color = tableBlack, fontSize = 14.sp)
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
                Text(amounts?.totalPaymentRm ?: "—", color = tableBlack, fontSize = 14.sp)
            }
            PaymentRow("Tax (RM)") {
                Text(taxRm, color = tableBlack, fontSize = 14.sp)
            }
            PaymentRow("Others (RM)") {
                PaymentInput(others, "Enter RM (optional)", KeyboardType.Decimal) {
                    if (validCurrencyInput(it)) others = it
                }
            }
            PaymentRow("Grand total payment", strong = true) {
                Text(
                    if (amounts == null) "—" else "RM " + amounts.grandTotalPaymentRm,
                    color = tableBlack, fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { quantity = ""; commission = ""; others = "" },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, tableBlack),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = tableBlack)
                ) { Text("Reset", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = {
                        val value = amounts ?: return@OutlinedButton
                        val record = PaymentRecord(
                            date = vm.deliveryDate.toString(),
                            quantity = quantity.toInt(),
                            commissionPerParcelRm = normalizedCurrency(commission) ?: "0.00",
                            totalPaymentRm = value.totalPaymentRm,
                            taxRm = taxRm,
                            othersRm = normalizedCurrency(others) ?: "0.00",
                            grandTotalPaymentRm = value.grandTotalPaymentRm
                        )
                        if (vm.savePayment(record)) {
                            quantity = ""; commission = ""; others = ""
                        }
                    },
                    enabled = amounts != null && !vm.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, tableBlack),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = tableBlack)
                ) { Text("Save", fontWeight = FontWeight.Bold) }
            }
        }
        if (reportTax == null) {
            Text(
                "Tax (RM) is 0.00 until the Delivery Report Summary for this date is saved.",
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
            repeat((10 - entries.size).coerceAtLeast(0)) { PaymentTableRow("", "", "") }
        }
        Text("Summary payment in month", style = MaterialTheme.typography.titleLarge)
        Text(
            "Monthly totals are calculated from saved payments. Years are shown separately.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        val years = (vm.paymentRecords.mapNotNull { runCatching { LocalDate.parse(it.date).year }.getOrNull() }
            .toSet() + vm.deliveryDate.year + (vm.deliveryDate.year - 1)).sorted()
        years.forEach { year ->
            Text(year.toString(), style = MaterialTheme.typography.titleMedium)
            Column {
                PaymentTableRow("Month", "Total payment (RM)", "Grand total payment (RM)", isHeader = true)
                (1..12).forEach { month ->
                    val totals = monthlyPaymentTotals(vm.paymentRecords, year, month)
                    PaymentTableRow(
                        java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH),
                        totals.totalPaymentRm, totals.grandTotalPaymentRm
                    )
                }
            }
        }
    }
}
