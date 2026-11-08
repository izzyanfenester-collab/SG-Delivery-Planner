package com.izzyan.sgdeliveryplanner

import android.content.Context
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
private val PAYMENT_HISTORY_ROW_HEIGHT = PaymentCompactLayout.HistoryRowMinHeight

private fun displayPaymentDate(value: String): String = runCatching {
    LocalDate.parse(value).format(rowDate)
}.getOrDefault(value)


/** Compact surfaces are local to Payment and do not change any other screen. */
@Composable
private fun PaymentSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier = modifier, color = SGColors.Gold, fontSize = 18.sp,
        lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun PaymentGlassPanel(compact: Boolean = false, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(SGDimensions.RadiusSmall)
    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(1.dp, SGColors.Divider.copy(alpha = 0.75f)), shape)
            .background(SGColors.Card.copy(alpha = 0.84f), shape)
            .padding(if (compact) PaymentCompactLayout.CardPadding else PaymentCompactLayout.TableCardPadding),
        verticalArrangement = Arrangement.spacedBy(if (compact) PaymentCompactLayout.IntraCardGap else 4.dp),
        content = { content() }
    )
}

@Composable
private fun PaymentOverviewCard(date: String) {
    val shape = RoundedCornerShape(SGDimensions.RadiusSmall)
    Row(
        Modifier.fillMaxWidth().heightIn(min = PaymentCompactLayout.OverviewMinHeight)
            .border(BorderStroke(1.dp, SGColors.Divider), shape)
            .background(SGColors.SecondaryBackground.copy(alpha = 0.72f), shape)
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
private fun PaymentRow(label: String, strong: Boolean = false, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(SGDimensions.RadiusSmall)
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = if (strong) PaymentCompactLayout.GrandTotalMinHeight
                else PaymentCompactLayout.FieldMinHeight)
            .border(BorderStroke(if (strong) 1.dp else 0.6.dp,
                if (strong) SGColors.Gold else SGColors.Divider), shape)
            .background(
                if (strong) SGColors.SecondaryBackground.copy(alpha = 0.94f)
                else SGColors.SecondaryBackground.copy(alpha = 0.53f), shape)
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

/**
 * The date/month column stays left aligned while both money columns are centered.
 * Use explicit two-line headings so narrow Android screens do not truncate them.
 */
internal fun paymentCompactTableHeading(label: String): String = when (label) {
    "Total payment (RM)" -> "Total\npayment (RM)"
    "Grand total payment (RM)" -> "Grand total\npayment (RM)"
    else -> label
}

/** Payment History and Monthly Summary use the same column alignment. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PaymentTableRow(
    dateOrMonth: String, totalPayment: String, grandTotal: String,
    isHeader: Boolean = false, isTotal: Boolean = false,
    onLongClick: (() -> Unit)? = null, fixedRowHeight: Dp? = null
) {
    val emphasized = isHeader || isTotal
    val fill = when {
        isHeader -> SGColors.SecondaryBackground.copy(alpha = 0.95f)
        isTotal -> SGColors.SecondaryBackground.copy(alpha = 0.90f)
        else -> SGColors.Background.copy(alpha = 0.42f)
    }
    val textColor = if (emphasized) SGColors.SelectedGold else SGColors.White
    val rowMinHeight = if (isHeader) 56.dp
        else (fixedRowHeight ?: PaymentCompactLayout.TableRowMinHeight)
    val gesture = if (onLongClick != null) Modifier.combinedClickable(
        onClick = {}, onLongClick = onLongClick) else Modifier
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp)
            .then(gesture)
            .heightIn(min = rowMinHeight)
            .border(BorderStroke(0.6.dp, SGColors.Divider.copy(alpha = 0.65f)),
                RoundedCornerShape(7.dp))
            .background(fill, RoundedCornerShape(7.dp))
            .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(dateOrMonth, totalPayment, grandTotal).forEachIndexed { index, value ->
            Text(
                text = if (isHeader) paymentCompactTableHeading(value) else value,
                modifier = Modifier.weight(if (index == 0) 1.04f else 1.12f)
                    .padding(horizontal = 2.dp),
                color = textColor,
                fontSize = if (isHeader) 12.sp else 14.sp,
                lineHeight = if (isHeader) 16.sp else 19.sp,
                fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
                textAlign = if (index == 0) TextAlign.Start else TextAlign.Center,
                softWrap = true,
                maxLines = if (isHeader) 3 else Int.MAX_VALUE
            )
        }
    }
}


/** Edit one entry rather than an aggregate; calculated totals stay consistent with the fields. */
@Composable
private fun PaymentEditDialog(
    target: PaymentRecordRef,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (PaymentRecord) -> Unit
) {
    var date by remember(target) { mutableStateOf(target.record.date) }
    var quantity by remember(target) { mutableStateOf(target.record.quantity.toString()) }
    var commission by remember(target) { mutableStateOf(target.record.commissionPerParcelRm) }
    var tax by remember(target) { mutableStateOf(target.record.taxRm) }
    var others by remember(target) { mutableStateOf(target.record.othersRm) }
    val updated = revisedPaymentRecord(date, quantity, commission, tax, others)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit saved payment") },
        text = {
            Column(
                Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Original: ${displayPaymentDate(target.record.date)} · RM ${target.record.grandTotalPaymentRm}",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = date,
                    onValueChange = { if (it.length <= 10) date = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
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
                OutlinedTextField(
                    value = tax,
                    onValueChange = { if (validCurrencyInput(it)) tax = it },
                    label = { Text("Tax (RM)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = others,
                    onValueChange = { if (validCurrencyInput(it)) others = it },
                    label = { Text("Others (RM)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (updated != null) "New total: RM ${updated.totalPaymentRm} · Grand total: RM ${updated.grandTotalPaymentRm}"
                    else "Enter a valid date, quantity and RM amounts.",
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
    val reportTax = savedReportTaxRm(vm.deliveryDate, matchingReports)

    var quantity by rememberSaveable(vm.deliveryDate.toString()) { mutableStateOf("") }
    var commission by rememberSaveable(vm.deliveryDate.toString()) { mutableStateOf("") }
    var others by rememberSaveable(vm.deliveryDate.toString()) { mutableStateOf("") }
    // Null means use the newest saved report value. Manual edits take priority until Reset.
    // Keeping an override separate avoids overwriting an in-progress edit when reports refresh.
    var manualTaxRm by rememberSaveable(vm.deliveryDate.toString()) { mutableStateOf<String?>(null) }
    val taxRm = effectivePaymentTaxRm(reportTax, manualTaxRm)
    val amounts = calculatePayment(quantity, commission, taxRm, others)
    val currentYear = LocalDate.now(java.time.ZoneId.of("Asia/Singapore")).year

    var selectedRecord by remember { mutableStateOf<PaymentRecordRef?>(null) }
    var editingRecord by remember { mutableStateOf<PaymentRecordRef?>(null) }
    var deletionPending by remember { mutableStateOf<PaymentRecordRef?>(null) }
    var selectedMonth by remember { mutableStateOf<Int?>(null) }
    val paymentHistoryScrollState = rememberScrollState()

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = PaymentCompactLayout.ScreenHorizontal,
                vertical = PaymentCompactLayout.ScreenVertical),
        verticalArrangement = Arrangement.spacedBy(PaymentCompactLayout.SectionGap)
    ) {
        PaymentOverviewCard(displayPaymentDate(vm.deliveryDate.toString()))
        PaymentGlassPanel(compact = true) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = PaymentCompactLayout.CalculatorTitleMinHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier.border(BorderStroke(1.dp, PremiumGold), RoundedCornerShape(SGDimensions.RadiusSmall))
                        .background(PremiumGold.copy(alpha = 0.18f), RoundedCornerShape(SGDimensions.RadiusSmall))
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.ReceiptLong, contentDescription = null, tint = PremiumGold, modifier = Modifier.size(19.dp))
                }
                Text("Payment calculator", color = SGColors.SelectedGold,
                    style = MaterialTheme.typography.titleMedium, fontSize = 18.sp,
                    fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold)
            }
            PaymentRow("Date") {
                Text(displayPaymentDate(vm.deliveryDate.toString()), color = SGColors.Silver,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
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
                Text(amounts?.totalPaymentRm ?: "—", color = SGColors.White, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
            }
            PaymentRow("Tax (RM)") {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    PaymentInput(taxRm, "Enter tax (RM)", KeyboardType.Decimal) {
                        if (validCurrencyInput(it)) manualTaxRm = it
                    }
                    if (reportTax != null && manualTaxRm != null) {
                        TextButton(onClick = { manualTaxRm = null }) {
                            Text("Use report tax: RM $reportTax", fontSize = 12.sp,
                                fontWeight = FontWeight.Medium)
                        }
                    } else if (reportTax != null) {
                        Text(
                            "From Delivery Report Summary",
                            fontSize = 12.sp, lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    color = SGColors.SelectedGold, fontWeight = FontWeight.Bold,
                    fontSize = 18.sp, textAlign = TextAlign.End
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { quantity = ""; commission = ""; others = ""; manualTaxRm = null },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(SGDimensions.RadiusSmall),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    border = BorderStroke(1.dp, SGColors.Gold),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = PremiumNavy.copy(alpha = 0.45f),
                        contentColor = SGColors.White
                    )
                ) { Text("Reset", style = MaterialTheme.typography.labelLarge) }
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
                    shape = RoundedCornerShape(SGDimensions.RadiusSmall),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    border = BorderStroke(1.dp, PremiumGold),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = PremiumGold,
                        contentColor = PremiumNavy,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text("Save", style = MaterialTheme.typography.labelLarge) }
            }
        }
        if (reportTax == null) {
            Text(
                "No saved Delivery Report Summary for this date. Tax defaults to RM 0.00; you can edit it manually.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp, lineHeight = 16.sp
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PaymentSectionTitle("Payment history", Modifier.weight(1f))
            Text("Long-press to edit/delete", color = SGColors.Silver,
                fontSize = 11.sp, lineHeight = 15.sp)
        }
        PaymentGlassPanel {
            // Header stays visible while the records scroll in a five-row-high viewport.
            PaymentTableRow("Date", "Total payment (RM)", "Grand total payment (RM)", isHeader = true)
            Column(
                Modifier.heightIn(max = PAYMENT_HISTORY_ROW_HEIGHT * VISIBLE_PAYMENT_HISTORY_ROWS)
                    .verticalScroll(paymentHistoryScrollState)
            ) {
                vm.paymentRecords.indices.reversed().forEach { index ->
                    val record = vm.paymentRecords[index]
                    PaymentTableRow(
                        displayPaymentDate(record.date), record.totalPaymentRm, record.grandTotalPaymentRm,
                        onLongClick = { selectedRecord = PaymentRecordRef(index, record) },
                        fixedRowHeight = PAYMENT_HISTORY_ROW_HEIGHT
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PaymentSectionTitle("Monthly Summary", Modifier.weight(1f))
            Text(currentYear.toString(), color = SGColors.Silver,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        val monthsWithPayment = nonZeroMonthlyPaymentRows(vm.paymentRecords, currentYear)
        val yearTotal = yearlyPaymentTotals(vm.paymentRecords, currentYear)
        PaymentGlassPanel {
            PaymentTableRow("Month", "Total payment (RM)", "Grand total payment (RM)", isHeader = true)
            monthsWithPayment.forEach { (month, totals) ->
                PaymentTableRow(
                    java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH),
                    totals.totalPaymentRm, totals.grandTotalPaymentRm,
                    onLongClick = { selectedMonth = month }
                )
            }
            if (monthsWithPayment.isEmpty()) {
                Text(
                    "No payment records for $currentYear.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            PaymentTableRow("Total", yearTotal.totalPaymentRm, yearTotal.grandTotalPaymentRm, isTotal = true)
        }
        Text("Long-press a month to manage saved records.",
            color = SGColors.Silver, fontSize = 11.sp, lineHeight = 15.sp
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
