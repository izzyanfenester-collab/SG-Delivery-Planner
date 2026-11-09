package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
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
    val grandTotalPaymentRm: String,
    // Nullable additions preserve JSON records saved by Official #832.
    val petrolRm: String? = null,
    val touchAndGoRm: String? = null,
    val autopassRm: String? = null,
    val totalDeductionRm: String? = null,
    val nettEarningRm: String? = null,
    val schemaVersion: Int = 1
)

data class PaymentCalculation(
    val totalPaymentRm: String,
    val grandTotalPaymentRm: String
)

data class PaymentRecordRef(val index: Int, val record: PaymentRecord)

/** Calculate deductions once: Tax is added to earnings, then deducted exactly once. */
data class PaymentFinancials(val totalDeductionRm: String, val nettEarningRm: String)

fun calculatePaymentFinancials(
    grandTotalRm: String, taxRm: String, petrolRm: String,
    touchAndGoRm: String, autopassRm: String
): PaymentFinancials? {
    val values = listOf(grandTotalRm, taxRm, petrolRm, touchAndGoRm, autopassRm)
        .map { normalizedCurrency(it)?.toBigDecimalOrNull() ?: return null }
    val deduction = values.drop(1).fold(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP)
    return PaymentFinancials(
        deduction.toPlainString(),
        values[0].subtract(deduction).setScale(2, RoundingMode.HALF_UP).toPlainString()
    )
}

/** Legacy #832 records already include the earned Tax in grand total.
 * Do not add the earned Tax again. Derive the one deduction without changing saved totals.
 */
fun financialsForRecord(record: PaymentRecord): PaymentFinancials =
    requireNotNull(calculatePaymentFinancials(
        record.grandTotalPaymentRm, record.taxRm,
        record.petrolRm ?: "0.00", record.touchAndGoRm ?: "0.00",
        record.autopassRm ?: "0.00"
    ))

/** Revisions are constructed atomically, with two-decimal amounts for all new fields. */
fun revisedPaymentRecord(
    dateText: String, quantityText: String, commissionText: String,
    taxText: String, othersText: String,
    petrolText: String = "0.00", touchAndGoText: String = "0.00",
    autopassText: String = "0.00"
): PaymentRecord? {
    val date = runCatching { LocalDate.parse(dateText.trim()).toString() }.getOrNull() ?: return null
    val quantity = quantityText.toIntOrNull() ?: return null
    val amounts = calculatePayment(quantityText, commissionText, taxText, othersText) ?: return null
    val tax = normalizedCurrency(taxText) ?: return null
    val petrol = normalizedCurrency(petrolText) ?: return null
    val touchAndGo = normalizedCurrency(touchAndGoText) ?: return null
    val autopass = normalizedCurrency(autopassText) ?: return null
    val net = calculatePaymentFinancials(amounts.grandTotalPaymentRm, tax, petrol, touchAndGo, autopass)
        ?: return null
    return PaymentRecord(
        date = date, quantity = quantity,
        commissionPerParcelRm = normalizedCurrency(commissionText) ?: return null,
        totalPaymentRm = amounts.totalPaymentRm,
        taxRm = tax, othersRm = normalizedCurrency(othersText) ?: return null,
        grandTotalPaymentRm = amounts.grandTotalPaymentRm,
        petrolRm = petrol, touchAndGoRm = touchAndGo, autopassRm = autopass,
        totalDeductionRm = net.totalDeductionRm,
        nettEarningRm = net.nettEarningRm, schemaVersion = 2
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

/** Home date is default; manual override is local to the Payment Calculator. */
fun paymentDateForCalculator(homeDate: LocalDate, manualDate: String?): LocalDate =
    manualDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: homeDate

/** Use latest SAVED report for the selected Payment date. Unsaved tax is excluded. */
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

/** New four-column summaries; the two-field legacy helpers remain for existing records/tests. */
data class PaymentNetTotals(
    val grandTotalPaymentRm: String,
    val totalDeductionRm: String,
    val nettEarningRm: String
)

fun monthlyPaymentNetTotals(records: List<PaymentRecord>, year: Int, month: Int): PaymentNetTotals {
    val recordsInMonth = records.filter {
        runCatching { LocalDate.parse(it.date) }.getOrNull()?.let { date ->
            date.year == year && date.monthValue == month
        } == true
    }
    fun sum(values: (PaymentRecord) -> String): String =
        recordsInMonth.fold(BigDecimal.ZERO) { result, record ->
            result.add(BigDecimal(values(record)))
        }.setScale(2, RoundingMode.HALF_UP).toPlainString()
    return PaymentNetTotals(
        sum { it.grandTotalPaymentRm },
        sum { financialsForRecord(it).totalDeductionRm },
        sum { financialsForRecord(it).nettEarningRm }
    )
}

fun nonZeroMonthlyPaymentNetRows(records: List<PaymentRecord>, year: Int): List<Pair<Int, PaymentNetTotals>> =
    (1..12).map { it to monthlyPaymentNetTotals(records, year, it) }
        .filter { (_, totals) ->
            listOf(totals.grandTotalPaymentRm, totals.totalDeductionRm, totals.nettEarningRm)
                .any { BigDecimal(it).signum() != 0 }
        }

fun yearlyPaymentNetTotals(records: List<PaymentRecord>, year: Int): PaymentNetTotals {
    val months = (1..12).map { monthlyPaymentNetTotals(records, year, it) }
    fun sum(value: (PaymentNetTotals) -> String): String =
        months.fold(BigDecimal.ZERO) { amount, month -> amount.add(BigDecimal(value(month))) }
            .setScale(2, RoundingMode.HALF_UP).toPlainString()
    return PaymentNetTotals(
        sum { it.grandTotalPaymentRm }, sum { it.totalDeductionRm }, sum { it.nettEarningRm }
    )
}

/** Only display months with actual payouts in either amount column. */
fun nonZeroMonthlyPaymentRows(records: List<PaymentRecord>, year: Int): List<Pair<Int, PaymentCalculation>> =
    (1..12).map { month -> month to monthlyPaymentTotals(records, year, month) }
        .filter { (_, totals) ->
            BigDecimal(totals.totalPaymentRm).signum() != 0 ||
                BigDecimal(totals.grandTotalPaymentRm).signum() != 0
        }

/** Full January–December legacy annual totals. */
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

/** Reject corrupted backup and ensure new records' stored totals match all source amounts. */
fun validPaymentRecord(record: PaymentRecord): Boolean = runCatching {
    LocalDate.parse(record.date)
    val calculated = calculatePayment(
        record.quantity.toString(), record.commissionPerParcelRm, record.taxRm, record.othersRm
    ) ?: return@runCatching false
    val total = normalizedCurrency(record.totalPaymentRm)
    val grand = normalizedCurrency(record.grandTotalPaymentRm) ?: return@runCatching false
    if (total != calculated.totalPaymentRm || grand != calculated.grandTotalPaymentRm) {
        return@runCatching false
    }
    if (record.schemaVersion <= 1) return@runCatching true
    if (record.schemaVersion != 2) return@runCatching false
    val petrol = record.petrolRm ?: return@runCatching false
    val touch = record.touchAndGoRm ?: return@runCatching false
    val autopass = record.autopassRm ?: return@runCatching false
    val financials = calculatePaymentFinancials(grand, record.taxRm, petrol, touch, autopass)
        ?: return@runCatching false
    normalizedCurrency(record.totalDeductionRm ?: "") == financials.totalDeductionRm &&
        record.nettEarningRm == financials.nettEarningRm
}.getOrDefault(false)

private val rowDate = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

/** Payment-only dimensions: responsive minimum heights, never fixed-size text containers. */
internal object PaymentCompactLayout {
    val ScreenHorizontal = 12.dp
    val ScreenVertical = 6.dp
    val SectionGap = 10.dp
    val IntraCardGap = 6.dp
    val CardPadding = 10.dp
    val TableCardPadding = 8.dp
    val OverviewMinHeight = 56.dp
    val CalculatorTitleMinHeight = 40.dp
    val FieldMinHeight = 48.dp
    val InputMinHeight = 42.dp
    val GrandTotalMinHeight = 56.dp
    val TableRowMinHeight = 48.dp
    val HistoryRowMinHeight = 48.dp
    const val HistoryVisibleRows = 5
}

// Older entries remain scrollable within a maximum of five visible rows.
private const val VISIBLE_PAYMENT_HISTORY_ROWS = PaymentCompactLayout.HistoryVisibleRows
private val PAYMENT_HISTORY_ROW_HEIGHT = PaymentDesignC.HistoryRowHeight

private fun displayPaymentDate(value: String): String = runCatching {
    LocalDate.parse(value).format(rowDate)
}.getOrDefault(value)


/** Compact surfaces are local to Payment and do not change any other screen. */
@Composable
private fun PaymentSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier = modifier, color = SGColors.Gold, fontSize = 16.sp,
        lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun PaymentGlassPanel(compact: Boolean = false, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(SGDimensions.RadiusSmall)
    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(1.dp, SGColors.Divider.copy(alpha = 0.75f)), shape)
            .background(SGColors.Card, shape)
            .padding(if (compact) PaymentCompactLayout.CardPadding else PaymentDesignC.CardPadding),
        verticalArrangement = Arrangement.spacedBy(if (compact) PaymentCompactLayout.IntraCardGap else 2.dp),
        content = { content() }
    )
}

@Composable
private fun PaymentOverviewCard(date: String) {
    val shape = RoundedCornerShape(SGDimensions.RadiusSmall)
    Row(
        Modifier.fillMaxWidth().heightIn(min = PaymentCompactLayout.OverviewMinHeight)
            .border(BorderStroke(1.dp, SGColors.Divider), shape)
            .background(SGColors.Card, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Payment Overview", modifier = Modifier.weight(1f),
            color = SGColors.Gold, fontSize = 17.sp, lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold)
        Text(date, color = SGColors.White, fontSize = 14.sp, lineHeight = 19.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

/** Minimum row height adapts to long translated labels and Android font scaling. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PaymentRow(
    label: String, strong: Boolean = false, onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(SGDimensions.RadiusSmall)
    Row(
        Modifier.fillMaxWidth()
            .then(if (onLongClick != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick) else Modifier)
            .heightIn(min = if (strong) PaymentCompactLayout.GrandTotalMinHeight
                else PaymentCompactLayout.FieldMinHeight)
            .border(BorderStroke(if (strong) 1.dp else 0.6.dp,
                if (strong) SGColors.Gold else SGColors.Divider), shape)
            .background(
                // Rows are nested in a 30%-opaque PaymentGlassPanel; no second fill.
                Color.Transparent, shape)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, Modifier.weight(if (strong) 0.47f else 0.52f),
            color = SGColors.White, fontSize = if (strong) 15.sp else 14.sp,
            lineHeight = 19.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium)
        Box(Modifier.weight(if (strong) 0.53f else 0.48f),
            contentAlignment = Alignment.CenterEnd) { content() }
    }
}

@Composable
private fun PaymentInput(
    value: String, hint: String, keyboardType: KeyboardType, onChange: (String) -> Unit
) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        cursorBrush = SolidColor(SGColors.Gold),
        textStyle = TextStyle(color = SGColors.White, fontSize = 16.sp,
            fontWeight = FontWeight.Medium),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { input ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = PaymentCompactLayout.InputMinHeight)
                    .border(BorderStroke(0.8.dp, SGColors.Divider), RoundedCornerShape(8.dp))
                    .background(SGColors.Background.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) Text(hint, color = SGColors.Silver, fontSize = 12.sp,
                    lineHeight = 17.sp, maxLines = 2)
                input()
            }
        }
    )
}

/** Header values use intentional line breaks rather than relying on unstable device wrapping. */
internal fun paymentTableHeaderValue(label: String): String = when (label) {
    "Total payment (RM)" -> "Total\npayment (RM)"
    "Grand total payment (RM)" -> "Grand total\npayment (RM)"
    "Deduction (RM)" -> "Deduction\n(RM)"
    "Nett Earning (RM)" -> "Nett Earning\n(RM)"
    else -> label
}

internal fun paymentTableAlignment(columnIndex: Int): TextAlign =
    if (columnIndex == 0) TextAlign.Start else TextAlign.Center

/** Shared compact four-column Payment History and Monthly Summary layout. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PaymentTableRow(
    dateOrMonth: String, totalPayment: String, grandTotal: String, nettEarning: String,
    isHeader: Boolean = false, isTotal: Boolean = false,
    onLongClick: (() -> Unit)? = null, fixedRowHeight: Dp? = null
) {
    val emphasized = isHeader || isTotal
    val textColor = if (emphasized) SGColors.SelectedGold else SGColors.White
    val gesture = if (onLongClick != null) Modifier.combinedClickable(
        onClick = {}, onLongClick = onLongClick) else Modifier
    Row(
        Modifier.fillMaxWidth().padding(vertical = 0.5.dp)
            .then(gesture)
            .heightIn(min = fixedRowHeight ?: if (isHeader) PaymentDesignC.HistoryHeaderHeight
                else PaymentDesignC.HistoryRowHeight)
            .border(BorderStroke(0.6.dp, SGColors.Divider.copy(alpha = 0.65f)),
                RoundedCornerShape(7.dp))
            .background(Color.Transparent, RoundedCornerShape(7.dp))
            .padding(horizontal = 3.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(dateOrMonth, totalPayment, grandTotal, nettEarning).forEachIndexed { index, value ->
            Text(
                if (isHeader) paymentTableHeaderValue(value) else value,
                modifier = Modifier.weight(if (index == 0) 1.07f else if (index == 1) 1.07f else 1f),
                color = if (!isHeader && index == 3) SGColors.SelectedGold else textColor,
                fontSize = if (isHeader) 10.5.sp else 12.5.sp,
                lineHeight = if (isHeader) 13.sp else 16.sp,
                fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
                softWrap = true,
                maxLines = if (isHeader) 2 else Int.MAX_VALUE,
                textAlign = paymentTableAlignment(index)
            )
        }
    }
}

/** Edit one payment and its distinct deductions; matching date uses its saved Tax. */
@Composable
private fun PaymentEditDialog(
    target: PaymentRecordRef,
    reports: List<Plan>,
    taxOverrides: Map<String, String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (PaymentRecord) -> Unit
) {
    val context = LocalContext.current
    var date by remember(target) { mutableStateOf(target.record.date) }
    var quantity by remember(target) { mutableStateOf(target.record.quantity.toString()) }
    var commission by remember(target) { mutableStateOf(target.record.commissionPerParcelRm) }
    var others by remember(target) { mutableStateOf(target.record.othersRm) }
    var petrol by remember(target) { mutableStateOf(target.record.petrolRm ?: "0.00") }
    var touchAndGo by remember(target) { mutableStateOf(target.record.touchAndGoRm ?: "0.00") }
    var autopass by remember(target) { mutableStateOf(target.record.autopassRm ?: "0.00") }
    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
    val tax = if (date == target.record.date) target.record.taxRm
        else effectivePaymentTaxRm(
            parsedDate?.let { savedReportTaxRm(it, reports) },
            taxOverrides[date]
        )
    val updated = revisedPaymentRecord(
        date, quantity, commission, tax, others, petrol, touchAndGo, autopass
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit saved payment") },
        text = {
            Column(
                Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Original: ${displayPaymentDate(target.record.date)} · RM ${target.record.grandTotalPaymentRm}",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = {
                        deliveryDatePicker(context, parsedDate ?: LocalDate.now()) {
                            date = it.toString()
                        }.show()
                    }, modifier = Modifier.fillMaxWidth()
                ) { Text("Date: ${displayPaymentDate(date)} · Change") }
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) quantity = it },
                    label = { Text("Qty parcel") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = commission,
                    onValueChange = { if (validCurrencyInput(it)) commission = it },
                    label = { Text("Commission per parcel (RM)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text("Tax (RM): ${normalizedCurrency(tax) ?: tax} · Delivery Report Summary",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = others, onValueChange = { if (validCurrencyInput(it)) others = it },
                    label = { Text("Others (RM)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                listOf(
                    Triple("Petrol (RM)", petrol, { value: String -> petrol = value }),
                    Triple("Touch & Go (RM)", touchAndGo, { value: String -> touchAndGo = value }),
                    Triple("Autopass (RM)", autopass, { value: String -> autopass = value })
                ).forEach { (label, value, onChange) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = { if (validCurrencyInput(it)) onChange(it) },
                        label = { Text(label) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    if (updated == null) "Enter valid quantities and RM amounts."
                    else "Grand total RM ${updated.grandTotalPaymentRm} · Deduction RM ${updated.totalDeductionRm} · Nett RM ${updated.nettEarningRm}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { updated?.let(onSave) }, enabled = !busy && updated != null) {
                Text("Save changes")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun PaymentScreen(vm: PlannerViewModel) {
    val savedRoutes by vm.history.collectAsState(initial = emptyList())
    val matchingReports = remember(vm.route, savedRoutes) {
        val active = listOfNotNull(vm.route)
        val historical = savedRoutes.mapNotNull { runCatching { vm.repo.decode(it) }.getOrNull() }
        active + historical
    }
    val context = LocalContext.current
    val taxOverrideLedger = remember(context) { PaymentTaxOverrideLedger(context) }
    var taxOverrides by remember { mutableStateOf(taxOverrideLedger.read()) }
    var showEditTax by remember { mutableStateOf(false) }
    var taxDraft by rememberSaveable { mutableStateOf("") }
    // Manual payment-date choice is independent from the selected Home delivery date.
    var manualPaymentDate by rememberSaveable { mutableStateOf<String?>(null) }
    val paymentDate = paymentDateForCalculator(vm.deliveryDate, manualPaymentDate)
    val reportTax = savedReportTaxRm(paymentDate, matchingReports)
    val taxIsManual = taxOverrides.containsKey(paymentDate.toString())
    val taxRm = effectivePaymentTaxRm(reportTax, taxOverrides[paymentDate.toString()])
    val editTax = {
        taxDraft = taxRm
        showEditTax = true
    }
    var quantity by rememberSaveable { mutableStateOf("") }
    var commission by rememberSaveable { mutableStateOf("") }
    var others by rememberSaveable { mutableStateOf("") }
    var petrol by rememberSaveable { mutableStateOf("") }
    var touchAndGo by rememberSaveable { mutableStateOf("") }
    var autopass by rememberSaveable { mutableStateOf("") }
    val recordToSave = revisedPaymentRecord(
        paymentDate.toString(), quantity, commission, taxRm, others, petrol, touchAndGo, autopass
    )
    val amounts = calculatePayment(quantity, commission, taxRm, others)
    val financials = recordToSave?.let { financialsForRecord(it) }
    val currentYear = LocalDate.now(java.time.ZoneId.of("Asia/Singapore")).year

    var selectedRecord by remember { mutableStateOf<PaymentRecordRef?>(null) }
    var editingRecord by remember { mutableStateOf<PaymentRecordRef?>(null) }
    var deletionPending by remember { mutableStateOf<PaymentRecordRef?>(null) }
    var selectedMonth by remember { mutableStateOf<Int?>(null) }
    var dashboardMonth by rememberSaveable { mutableStateOf(vm.deliveryDate.monthValue) }
    var previewReport by remember { mutableStateOf<Plan?>(null) }
    val paymentHistoryScrollState = rememberScrollState()
    val historyRowHeight = PaymentDesignC.historyRowHeight(
        androidx.compose.ui.platform.LocalConfiguration.current.fontScale
    )

    val taxSource = when {
        taxIsManual -> "Manual Override"
        reportTax != null -> "From Delivery Report Summary"
        else -> "No saved report"
    }
    val chosenMonthly = monthlyPaymentNetTotals(vm.paymentRecords, currentYear, dashboardMonth)
    val chosenTotal = monthlyPaymentTotals(vm.paymentRecords, currentYear, dashboardMonth)
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = PaymentDesignC.ScreenHorizontal, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(PaymentDesignC.CardGap)
    ) {
        DesignCOverview(displayPaymentDate(paymentDate.toString())) {
            deliveryDatePicker(context, paymentDate) { selected ->
                manualPaymentDate = selected.toString()
            }.show()
        }

        DesignCCalculator(
            quantity = quantity,
            onQuantity = { if (it.length <= 6 && it.all(Char::isDigit)) quantity = it },
            commission = commission,
            onCommission = { if (validCurrencyInput(it)) commission = it },
            total = amounts?.totalPaymentRm ?: "—",
            tax = "RM $taxRm",
            taxSource = taxSource,
            onTaxLongPress = editTax,
            others = others,
            onOthers = { if (validCurrencyInput(it)) others = it },
            grandTotal = amounts?.grandTotalPaymentRm?.let { "RM $it" } ?: "—"
        )

        DesignCFinancialCards(
            petrol = petrol,
            onPetrol = { if (validCurrencyInput(it)) petrol = it },
            touch = touchAndGo,
            onTouch = { if (validCurrencyInput(it)) touchAndGo = it },
            autopass = autopass,
            onAutopass = { if (validCurrencyInput(it)) autopass = it },
            tax = taxRm,
            grand = amounts?.grandTotalPaymentRm ?: "—",
            deduction = financials?.totalDeductionRm ?: "—",
            net = financials?.nettEarningRm ?: "—"
        )

        DesignCActions(
            onReset = {
                quantity = ""; commission = ""; others = ""
                petrol = ""; touchAndGo = ""; autopass = ""
                // The per-date Tax override is deliberately preserved.
                manualPaymentDate = null
            },
            onSave = {
                val record = recordToSave
                if (record != null && vm.savePayment(record)) {
                    quantity = ""; commission = ""; others = ""
                    petrol = ""; touchAndGo = ""; autopass = ""
                    manualPaymentDate = null
                }
            },
            onViewReport = {
                val matched = matchingReports.asSequence()
                    .filter { it.deliveryDate == paymentDate && it.summarySavedAt != null }
                    .maxByOrNull { it.summarySavedAt.orEmpty() }
                if (matched != null) previewReport = matched
                else Toast.makeText(context, "No saved Delivery Report Summary for this payment date.", Toast.LENGTH_LONG).show()
            },
            saveEnabled = recordToSave != null && !vm.busy
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PaymentSectionTitle("Payment History (RM)", Modifier.weight(1f))
            Text("Long-press to edit/delete", color = SGColors.Silver,
                fontSize = 11.sp, lineHeight = 15.sp)
        }
        PaymentGlassPanel {
            PaymentTableRow("Date", "Total\nPayment", "Grand\nTotal",
                "Nett\nEarning", isHeader = true)
            Column(
                Modifier.heightIn(max = historyRowHeight * VISIBLE_PAYMENT_HISTORY_ROWS)
                    .verticalScroll(paymentHistoryScrollState)
            ) {
                vm.paymentRecords.indices.reversed().forEach { index ->
                    val record = vm.paymentRecords[index]
                    PaymentTableRow(
                        displayPaymentDate(record.date), record.totalPaymentRm,
                        record.grandTotalPaymentRm, financialsForRecord(record).nettEarningRm,
                        onLongClick = { selectedRecord = PaymentRecordRef(index, record) },
                        fixedRowHeight = historyRowHeight
                    )
                }
            }
        }

        DesignCMonthlySummary(
            monthName = java.time.Month.of(dashboardMonth)
                .getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH),
            year = currentYear,
            total = chosenTotal.totalPaymentRm,
            grand = chosenMonthly.grandTotalPaymentRm,
            deduction = chosenMonthly.totalDeductionRm,
            nett = chosenMonthly.nettEarningRm,
            onPrevious = { dashboardMonth = PaymentDesignC.previousMonth(dashboardMonth) },
            onNext = { dashboardMonth = PaymentDesignC.nextMonth(dashboardMonth) },
            onManageMonth = { selectedMonth = dashboardMonth }
        )
        Text("Hold month to manage · arrows: Jan–Dec",
            color = SGColors.Silver, fontSize = 10.5.sp, lineHeight = 13.sp)
    }

    previewReport?.let { report ->
        DeliverySummaryReportPreview(report, onClose = { previewReport = null })
    }

    if (showEditTax) {
        val normalized = normalizedCurrency(taxDraft)
        AlertDialog(
            onDismissRequest = { showEditTax = false },
            title = { Text("Edit Tax (RM)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Payment date: ${displayPaymentDate(paymentDate.toString())}",
                        style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (taxIsManual) "Source: Manual Override"
                        else if (reportTax != null) "Source: Delivery Summary Report"
                        else "Source: No saved report (RM 0.00)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = taxDraft,
                        onValueChange = { if (validCurrencyInput(it)) taxDraft = it },
                        label = { Text("Current Tax Amount (RM)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (normalized == null) {
                        Text("Enter a valid amount with up to two decimal places.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val amount = normalized ?: return@TextButton
                        if (taxOverrideLedger.save(paymentDate, amount)) {
                            taxOverrides = taxOverrideLedger.read()
                            showEditTax = false
                        } else Toast.makeText(context, "Tax could not be saved.", Toast.LENGTH_LONG).show()
                    },
                    enabled = normalized != null
                ) { Text("Save") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = {
                        if (taxOverrideLedger.resetToReport(paymentDate)) {
                            taxOverrides = taxOverrideLedger.read()
                            showEditTax = false
                        } else Toast.makeText(context, "Could not reset Tax.", Toast.LENGTH_LONG).show()
                    }) { Text("Reset to Report Tax") }
                    TextButton(onClick = { showEditTax = false }) { Text("Cancel") }
                }
            }
        )
    }

    selectedMonth?.let { month ->
        val recordsInMonth = vm.paymentRecords.withIndex().filter { (_, record) ->
            runCatching { LocalDate.parse(record.date) }.getOrNull()?.let {
                it.year == currentYear && it.monthValue == month
            } == true
        }.asReversed()
        AlertDialog(
            onDismissRequest = { selectedMonth = null },
            title = {
                Text(java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) +
                    " $currentYear")
            },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select a saved date to edit or delete that individual payment.",
                        style = MaterialTheme.typography.bodySmall)
                    recordsInMonth.forEach { (index, record) ->
                        OutlinedButton(
                            onClick = {
                                selectedMonth = null
                                selectedRecord = PaymentRecordRef(index, record)
                            },
                            enabled = !vm.busy, modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${displayPaymentDate(record.date)} · RM ${record.grandTotalPaymentRm}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedMonth = null }) { Text("Close") } }
        )
    }
    selectedRecord?.let { target ->
        AlertDialog(
            onDismissRequest = { selectedRecord = null },
            title = { Text("Saved payment: ${displayPaymentDate(target.record.date)}") },
            text = {
                Text("Total payment RM ${target.record.totalPaymentRm}\n" +
                    "Grand total RM ${target.record.grandTotalPaymentRm}\n" +
                    "Deduction RM ${financialsForRecord(target.record).totalDeductionRm}\n" +
                    "Nett earning RM ${financialsForRecord(target.record).nettEarningRm}\n" +
                    "Edit this payment or request deletion?")
            },
            confirmButton = {
                TextButton(onClick = { selectedRecord = null; editingRecord = target }, enabled = !vm.busy) {
                    Text("Edit")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { selectedRecord = null; deletionPending = target }, enabled = !vm.busy) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { selectedRecord = null }) { Text("Cancel") }
                }
            }
        )
    }
    editingRecord?.let { target ->
        PaymentEditDialog(
            target = target,
            reports = matchingReports,
            taxOverrides = taxOverrides,
            busy = vm.busy,
            onDismiss = { editingRecord = null },
            onSave = { updated ->
                if (vm.revisePayment(target.index, target.record, updated)) editingRecord = null
            }
        )
    }
    deletionPending?.let { target ->
        AlertDialog(
            onDismissRequest = { deletionPending = null },
            title = { Text("Delete this payment?") },
            text = {
                Text("${displayPaymentDate(target.record.date)} · RM ${target.record.grandTotalPaymentRm}\n" +
                    "This permanently deletes only the selected payment and updates monthly totals. " +
                    "This cannot be undone unless it exists in an earlier backup.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (vm.deletePayment(target.index, target.record)) deletionPending = null
                    },
                    enabled = !vm.busy
                ) { Text("Confirm Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletionPending = null }) { Text("Cancel") } }
        )
    }
}
