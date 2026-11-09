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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** FX values are independent of Delivery Report Summary.
 * Public daily published market reference data is not the GoogleFinance live feed.
 */
private val SINGAPORE_GST = BigDecimal("0.09")

internal data class HomeTaxRates(val beforeMarkup: BigDecimal) {
    // Add a 0.4 FX-rate markup to the live SGD/MYR rate exactly once; not a RM0.40 fee.
    val afterMarkup: BigDecimal get() = beforeMarkup.add(HOME_TAX_RATE_MARKUP)
}
internal data class HomeTaxResult(val amountSgd: String, val gstSgd: String, val taxMyr: String)

/** HALF_UP at the requested steps:
 * 1) RM / full-precision SGD/MYR -> rounded SGD
 * 2) rounded SGD * 9% -> rounded SGD GST
 * 3) rounded SGD GST * (base + 0.40) -> rounded MYR GST
 */
internal fun calculateHomeTax(invoiceRm: String, rates: HomeTaxRates?): HomeTaxResult? {
    if (rates == null || invoiceRm.isBlank() || !validCurrencyInput(invoiceRm)) return null
    val invoice = invoiceRm.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO } ?: return null
    if (rates.beforeMarkup <= BigDecimal.ZERO) return null
    val amountSgd = invoice.divide(rates.beforeMarkup, 16, RoundingMode.HALF_UP)
        .setScale(2, RoundingMode.HALF_UP)
    val gstSgd = amountSgd.multiply(SINGAPORE_GST).setScale(2, RoundingMode.HALF_UP)
    val taxMyr = gstSgd.multiply(rates.afterMarkup).setScale(2, RoundingMode.HALF_UP)
    return HomeTaxResult(amountSgd.toPlainString(), gstSgd.toPlainString(), taxMyr.toPlainString())
}

/** Only publication time and age are shown; never expose numeric FX or markup. */
internal fun homeTaxRateStatus(
    quote: LiveHomeFxQuote, isCached: Boolean,
    nowEpochSeconds: Long = Instant.now().epochSecond
): String {
    val published = Instant.ofEpochSecond(quote.sourceUpdatedEpochSeconds)
        .atZone(ZoneId.of("Asia/Singapore"))
        .format(DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a", Locale.ENGLISH))
    val hoursOld = ((nowEpochSeconds - quote.sourceUpdatedEpochSeconds)
        .coerceAtLeast(0L) / 3600L)
    val age = if (hoursOld >= 48) (hoursOld / 24).toString() + " days old"
        else if (hoursOld >= 24) "1 day old"
        else hoursOld.toString() + "h old"
    return (if (isCached) "Using cached FX · " else "Latest published FX · ") +
        published + " · " + age
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
    val context = LocalContext.current

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
        HomeTaxCalculatorDialog(onDismiss = { showCalculator = false })
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

/** Each popup opening refreshes the latest published SGD/MYR rate in background.
 * Cached values are visible immediately if available; only the rate is sent over
 * the network, NEVER invoice amounts or payment information.
 */
@Composable
private fun HomeTaxCalculatorDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val repo = remember(context) { HomeTaxLiveFxRepository(context) }
    val scope = rememberCoroutineScope()
    var invoice by rememberSaveable { mutableStateOf("") }
    var quote by remember(repo) { mutableStateOf(repo.cached()) }
    var usingCache by remember(repo) { mutableStateOf(quote != null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf(false) }

    fun refreshRate() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            val fresh = withContext(Dispatchers.IO) { repo.fetchLatest() }
            if (fresh != null) {
                quote = fresh
                usingCache = false
                refreshError = false
            } else {
                quote = repo.cached() ?: quote
                usingCache = quote != null
                refreshError = true
            }
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { refreshRate() }
    val result = calculateHomeTax(invoice, quote?.let { HomeTaxRates(it.beforeMarkup) })
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
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                OutlinedTextField(
                    value = invoice,
                    onValueChange = {
                        if (it.length <= 16 && !it.startsWith("-") && validCurrencyInput(it)) {
                            invoice = it
                        }
                    },
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
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (refreshing) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(13.dp), strokeWidth = 1.7.dp,
                            color = SGColors.Gold
                        )
                    } else if (quote != null && !usingCache) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null,
                            tint = SGColors.Gold, modifier = Modifier.size(14.dp))
                    }
                    val caption = when {
                        quote == null && refreshing -> "Retrieving latest published FX..."
                        quote == null -> "No live or cached FX available. Check internet and Refresh."
                        else -> homeTaxRateStatus(requireNotNull(quote), usingCache)
                    }
                    Text(caption,
                        color = if (quote == null || usingCache) SGColors.Warning else SGColors.Silver,
                        fontSize = 10.sp, lineHeight = 13.sp,
                        modifier = Modifier.weight(1f))
                }
                if (refreshError && quote != null) {
                    Text("Unable to refresh. Results use the cached rate shown above.",
                        color = SGColors.Warning, fontSize = 10.sp, lineHeight = 13.sp)
                }
                HomeTaxResultRow("Amount in SGD",
                    result?.let { "S$ " + it.amountSgd } ?: "—")
                HomeTaxResultRow("Tax Amount (9%) SGD",
                    result?.let { "S$ " + it.gstSgd } ?: "—")
                HomeTaxResultRow("Tax Amount in RM",
                    result?.let { "RM " + it.taxMyr } ?: "—", prominent = true)
                TextButton(
                    onClick = { uriHandler.openUri("https://www.exchangerate-api.com") },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Text("Rates by ExchangeRate-API", color = SGColors.Silver, fontSize = 10.sp)
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { invoice = "" }, modifier = Modifier.weight(1f)) {
                    Text("Clear", color = SGColors.White, fontSize = 12.sp)
                }
                TextButton(onClick = { refreshRate() },
                    enabled = !refreshing, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null,
                        tint = SGColors.Gold, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Refresh", color = SGColors.SelectedGold, fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Close", color = SGColors.SelectedGold, fontSize = 12.sp)
                }
            }
        }
    )
}
