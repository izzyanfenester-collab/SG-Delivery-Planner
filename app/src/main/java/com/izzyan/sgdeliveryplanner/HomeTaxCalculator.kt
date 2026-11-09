package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/** The existing Summary only stores the final rate after the 0.40 markup.
 * The original raw reference rate and fractional precision are NOT stored.
 * Derive a conservative pre-markup rate from the saved final amount and never
 * add another markup or use a network rate here.
 */
private val SAVED_REPORT_MARKUP = BigDecimal("0.40")
private val SINGAPORE_GST = BigDecimal("0.09")

internal data class HomeTaxRates(
    val beforeMarkup: BigDecimal,
    val afterMarkup: BigDecimal
)

internal data class HomeTaxResult(
    val amountSgd: String,
    val gstSgd: String,
    val taxMyr: String
)

/** Select the most recently saved report, not the current Home date or an unsaved plan.
 * If its final stored rate is unusable, do not invent or fall back to a different rate.
 */
internal fun newestSavedHomeTaxRates(plans: List<Plan>): HomeTaxRates? {
    val latest = plans.asSequence()
        .mapNotNull { plan ->
            val timestamp = plan.summarySavedAt?.let {
                runCatching { LocalDateTime.parse(it) }.getOrNull()
            } ?: return@mapNotNull null
            timestamp to plan
        }.maxByOrNull { it.first }?.second ?: return null

    val storedFinal = latest.exchangeRate.toBigDecimalOrNull()
        ?.takeIf { it > SAVED_REPORT_MARKUP && it <= BigDecimal("100") }
        ?: return null
    val derivedBase = storedFinal.subtract(SAVED_REPORT_MARKUP)
    return HomeTaxRates(derivedBase, storedFinal)
}

/** Exact decimal arithmetic. Tax uses unrounded SGD conversion, then rounds
 * GST once to two decimals before converting that rounded Tax into MYR.
 */
internal fun calculateHomeTax(
    invoiceRm: String,
    rates: HomeTaxRates?
): HomeTaxResult? {
    if (rates == null || invoiceRm.isBlank() || !validCurrencyInput(invoiceRm)) return null
    val invoice = invoiceRm.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO } ?: return null
    if (rates.beforeMarkup <= BigDecimal.ZERO || rates.afterMarkup <= BigDecimal.ZERO) return null
    val unroundedSgd = invoice.divide(rates.beforeMarkup, 16, RoundingMode.HALF_UP)
    val taxSgd = unroundedSgd.multiply(SINGAPORE_GST)
        .setScale(2, RoundingMode.HALF_UP)
    return HomeTaxResult(
        amountSgd = unroundedSgd.setScale(2, RoundingMode.HALF_UP).toPlainString(),
        gstSgd = taxSgd.toPlainString(),
        taxMyr = taxSgd.multiply(rates.afterMarkup)
            .setScale(2, RoundingMode.HALF_UP).toPlainString()
    )
}

@Composable
private fun TaxHomeButton(
    label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 60.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SGColors.Gold),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = PremiumNavy.copy(alpha = 0.85f),
            contentColor = SGColors.SelectedGold
        ),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 3.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            icon()
            Text(label, fontSize = 11.sp, lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2,
                softWrap = true, textAlign = TextAlign.Center)
        }
    }
}

/** Only replaces the existing two-button row on Home; preserves both actions. */
@Composable
internal fun HomeTaxActionRow(vm: PlannerViewModel) {
    var showCalculator by rememberSaveable { mutableStateOf(false) }
    val savedRoutes by vm.history.collectAsState(initial = emptyList())
    val reports = remember(vm.route, savedRoutes) {
        listOfNotNull(vm.route) + savedRoutes.mapNotNull { saved ->
            runCatching { vm.repo.decode(saved) }.getOrNull()
        }
    }
    val savedRates = remember(reports) { newestSavedHomeTaxRates(reports) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TaxHomeButton(
            label = "Tax\nDeclare", modifier = Modifier.weight(1f), enabled = !vm.busy,
            onClick = { openTaxDeclare(context) }
        ) {
            Icon(painterResource(R.drawable.ic_customs_document),
                contentDescription = null, modifier = Modifier.size(17.dp))
        }
        TaxHomeButton(
            label = "Tax\nCalculation", modifier = Modifier.weight(1f), enabled = !vm.busy,
            onClick = { showCalculator = true }
        ) {
            Icon(Icons.Outlined.Calculate,
                contentDescription = null, modifier = Modifier.size(18.dp))
        }
        TaxHomeButton(
            label = "Clear Postal\nCode", modifier = Modifier.weight(1f), enabled = !vm.busy,
            onClick = vm::clearPostalCodes
        ) {
            Icon(Icons.Outlined.DeleteSweep,
                contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
    if (showCalculator) {
        HomeTaxCalculatorDialog(
            rates = savedRates,
            onDismiss = { showCalculator = false }
        )
    }
}

@Composable
private fun HomeTaxResultRow(label: String, display: String, prominent: Boolean = false) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = Modifier.fillMaxWidth()
            .border(BorderStroke(if (prominent) 1.dp else .7.dp,
                if (prominent) SGColors.Gold else SGColors.Divider), shape)
            .background(if (prominent) SGColors.Card else Color.Transparent, shape)
            .padding(horizontal = 10.dp, vertical = if (prominent) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f),
            color = if (prominent) SGColors.SelectedGold else SGColors.White,
            fontSize = if (prominent) 14.sp else 12.sp,
            lineHeight = if (prominent) 17.sp else 15.sp,
            fontWeight = if (prominent) FontWeight.Bold else FontWeight.Medium)
        Text(display, color = if (prominent) SGColors.SelectedGold else SGColors.White,
            fontSize = if (prominent) 19.sp else 16.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

@Composable
private fun HomeTaxCalculatorDialog(rates: HomeTaxRates?, onDismiss: () -> Unit) {
    var invoice by rememberSaveable { mutableStateOf("") }
    val result = calculateHomeTax(invoice, rates)
    val invalidInvoice = invoice.isNotBlank() &&
        (!validCurrencyInput(invoice) || invoice.toBigDecimalOrNull() == null)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SGColors.SolidCard,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Calculate, contentDescription = null,
                    tint = SGColors.Gold, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tax Calculation", color = SGColors.SelectedGold,
                    fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OutlinedTextField(
                    value = invoice,
                    onValueChange = { if (validCurrencyInput(it)) invoice = it },
                    label = { Text("Invoice Price (RM)") },
                    prefix = { Text("RM ") },
                    placeholder = { Text("Enter amount") },
                    isError = invalidInvoice,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SGColors.Gold,
                        unfocusedBorderColor = SGColors.Divider,
                        focusedTextColor = SGColors.White,
                        unfocusedTextColor = SGColors.White
                    )
                )

                if (rates == null) {
                    Text("No valid exchange rate from a saved Delivery Report Summary. Save a report with a valid rate before calculating.",
                        color = SGColors.Warning, fontSize = 12.sp, lineHeight = 17.sp)
                } else {
                    HomeTaxResultRow("Amount in SGD",
                        result?.let { "S$ " + it.amountSgd } ?: "—")
                    HomeTaxResultRow("Tax Amount (9%) SGD",
                        result?.let { "S$ " + it.gstSgd } ?: "—")
                    HomeTaxResultRow("Tax Amount in RM",
                        result?.let { "RM " + it.taxMyr } ?: "—", prominent = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = SGColors.SelectedGold) }
        },
        dismissButton = {
            TextButton(onClick = { invoice = "" }) { Text("Clear", color = SGColors.White) }
        }
    )
}
