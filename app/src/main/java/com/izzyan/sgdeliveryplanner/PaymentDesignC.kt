package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Default amounts apply only to NEW, unsaved Payment drafts; saved payments retain their own values. */
internal enum class PaymentExpense(val title: String, val defaultRm: String) {
    PETROL("Petrol", "30.00"),
    TOUCH_AND_GO("Touch & Go", "10.00"),
    AUTOPASS("Autopass", "30.00")
}

/** Design C is payment-only. No changes to the calculator, ledger, backup or navigation model. */
internal object PaymentDesignC {
    // Ultra compact design targets; controls grow naturally when text/font scaling needs space.
    val ScreenHorizontal = 8.dp
    val CardGap = 4.dp
    val InsideGap = 3.dp
    val CardPadding = 6.dp
    val InputHeight = 34.dp
    val ControlHeight = 46.dp
    val HistoryRowHeight = 34.dp
    val HistoryHeaderHeight = 33.dp
    val MonthlyMetricHeight = 44.dp
    fun historyRowHeight(fontScale: Float) = if (fontScale >= 1.20f) 44.dp else HistoryRowHeight
    /** Below 400 dp the 2 financial cards stack to prevent truncated fields. */
    fun useSideBySideCards(screenWidthDp: Int): Boolean = screenWidthDp >= 400
    /** Ultra Compact always displays the monthly KPI tiles in 2 × 2. */
    fun useFourAcrossKpis(screenWidthDp: Int): Boolean = false
    fun previousMonth(month: Int): Int = if (month <= 1) 12 else month - 1
    fun nextMonth(month: Int): Int = if (month >= 12) 1 else month + 1
}

@Composable
private fun DesignCPanel(
    modifier: Modifier = Modifier,
    strongBorder: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.border(
            BorderStroke(1.dp, if (strongBorder) SGColors.Gold else SGColors.Divider.copy(alpha = .9f)),
            shape
        ).background(SGColors.Card, shape)
            .padding(PaymentDesignC.CardPadding),
        verticalArrangement = Arrangement.spacedBy(PaymentDesignC.InsideGap),
        content = content
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesignCOverview(date: String, onLongPressDate: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, SGColors.Divider), shape)
            .background(SGColors.Card, shape).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Payments, contentDescription = null, tint = SGColors.Gold, modifier = Modifier.size(21.dp))
        Text("Payment Overview", color = SGColors.SelectedGold, fontSize = 14.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Row(
            Modifier.heightIn(min = PaymentDesignC.ControlHeight)
                .combinedClickable(onClick = {}, onLongClick = onLongPressDate)
                .semantics { contentDescription = "Payment date $date. Long press to edit" }
                .padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = null,
                tint = SGColors.Gold, modifier = Modifier.size(17.dp))
            Text(date, color = SGColors.White, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun DesignCInput(
    value: String, hint: String, keyboard: KeyboardType, onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        textStyle = TextStyle(color = SGColors.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        cursorBrush = SolidColor(SGColors.Gold), modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = PaymentDesignC.InputHeight)
                    .border(BorderStroke(.8.dp, SGColors.Divider), shape)
                    .background(SGColors.Background.copy(alpha = .50f), shape)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isBlank()) Text(hint, color = SGColors.Silver, fontSize = 11.sp)
                inner()
            }
        }
    )
}

@Composable
private fun DesignCInputTile(
    label: String, value: String, onChange: (String) -> Unit,
    keyboard: KeyboardType, modifier: Modifier = Modifier, placeholder: String = "Enter RM"
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = SGColors.Silver, fontSize = 12.sp, lineHeight = 14.sp,
            minLines = 1, maxLines = 2)
        DesignCInput(value, placeholder, keyboard, onChange)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesignCValueTile(
    label: String, value: String, modifier: Modifier = Modifier,
    source: String? = null, onLongPress: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.heightIn(min = 52.dp)
            .then(if (onLongPress != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress) else Modifier)
            .border(BorderStroke(.7.dp, SGColors.Divider), shape)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(label, color = SGColors.Silver, fontSize = 11.sp, lineHeight = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = SGColors.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End)
            if (onLongPress != null) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.Edit, contentDescription = "Hold to edit Tax",
                    tint = SGColors.Gold, modifier = Modifier.size(15.dp))
            }
        }
        source?.let {
            Text(it, color = SGColors.Silver, fontSize = 9.sp, lineHeight = 11.sp,
                maxLines = 2, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun DesignCCalculator(
    quantity: String, onQuantity: (String) -> Unit,
    commission: String, onCommission: (String) -> Unit,
    total: String, tax: String, taxSource: String, onTaxLongPress: () -> Unit,
    others: String, onOthers: (String) -> Unit,
    grandTotal: String
) {
    DesignCPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(Icons.Outlined.ReceiptLong, contentDescription = null,
                tint = SGColors.Gold, modifier = Modifier.size(20.dp))
            Text("Payment Calculator", color = SGColors.SelectedGold,
                fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DesignCInputTile("Qty Parcel", quantity, onQuantity, KeyboardType.Number,
                Modifier.weight(1f), placeholder = "Enter Qty")
            DesignCInputTile("Commission / Parcel (RM)", commission, onCommission,
                KeyboardType.Decimal, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DesignCValueTile("Total Payment (RM)", total, Modifier.weight(1f))
            DesignCValueTile("Tax (RM)", tax, Modifier.weight(1f),
                source = taxSource, onLongPress = onTaxLongPress)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Others (RM)", color = SGColors.Silver, fontSize = 12.sp,
                modifier = Modifier.weight(.70f))
            Box(Modifier.weight(1.30f)) {
                DesignCInput(others, "Optional amount", KeyboardType.Decimal, onOthers)
            }
        }
        val shape = RoundedCornerShape(9.dp)
        Row(
            Modifier.fillMaxWidth().border(BorderStroke(1.dp, SGColors.Gold), shape)
                .padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = 35.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("GRAND TOTAL PAYMENT", color = SGColors.SelectedGold,
                fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(grandTotal, color = SGColors.SelectedGold,
                fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, textAlign = TextAlign.End)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DesignCExpenseRow(
    label: String, icon: ImageVector, value: String, onLongPress: () -> Unit
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, contentDescription = null, tint = SGColors.Gold, modifier = Modifier.size(15.dp))
        Text(label, color = SGColors.White, fontSize = 11.sp, lineHeight = 13.sp,
            modifier = Modifier.weight(1f))
        // Identical size to the previous input, but read-only on short tap.
        // Long-press respects the Android accessibility long-press duration.
        val shape = RoundedCornerShape(8.dp)
        Row(
            Modifier.width(70.dp).heightIn(min = PaymentDesignC.InputHeight)
                .border(BorderStroke(.8.dp, SGColors.Divider), shape)
                .background(SGColors.Background.copy(alpha = .50f), shape)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .semantics { contentDescription = "$label RM $value, long press to edit" }
                .padding(horizontal = 3.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(value, color = SGColors.White, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
                maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Outlined.Edit, contentDescription = null,
                tint = SGColors.Gold, modifier = Modifier.size(11.dp))
        }
    }
}

@Composable
private fun DesignCReadOnlyRow(label: String, value: String, gold: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = if (gold) SGColors.SelectedGold else SGColors.White,
            fontSize = 11.sp, lineHeight = 14.sp, fontWeight = if (gold) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.weight(1f))
        Text(value, color = if (gold) SGColors.SelectedGold else SGColors.White,
            fontSize = 12.sp, fontWeight = if (gold) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.End)
    }
}

@Composable
private fun DesignCDeductions(
    petrol: String, onPetrolLongPress: () -> Unit,
    touch: String, onTouchLongPress: () -> Unit,
    autopass: String, onAutopassLongPress: () -> Unit,
    tax: String, deduction: String, modifier: Modifier = Modifier
) {
    DesignCPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CreditCard, contentDescription = null, tint = SGColors.Gold,
                modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(4.dp))
            Text("DEDUCTIONS (RM)", color = SGColors.SelectedGold,
                fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        DesignCExpenseRow("Petrol", Icons.Outlined.LocalGasStation, petrol, onPetrolLongPress)
        DesignCExpenseRow("Touch & Go", Icons.Outlined.CreditCard, touch, onTouchLongPress)
        DesignCExpenseRow("Autopass", Icons.Outlined.DirectionsCar, autopass, onAutopassLongPress)
        DesignCReadOnlyRow("Tax", tax)
        HorizontalDivider(color = SGColors.Divider.copy(alpha = .7f))
        DesignCReadOnlyRow("TOTAL DEDUCTIONS", deduction, gold = true)
    }
}

@Composable
private fun DesignCEarnings(
    grand: String, deduction: String, net: String, modifier: Modifier = Modifier,
    balancedHeight: Boolean = false
) {
    DesignCPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Payments, contentDescription = null, tint = SGColors.Gold,
                modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(4.dp))
            Text("EARNINGS SUMMARY", color = SGColors.SelectedGold,
                fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 15.sp)
        }
        DesignCReadOnlyRow("Grand Total", grand)
        DesignCReadOnlyRow("Deductions", deduction)
        // Fill shared height above the Nett highlight, never below it.
        if (balancedHeight) Spacer(Modifier.weight(1f))
        else Spacer(Modifier.height(1.dp))
        val shape = RoundedCornerShape(9.dp)
        Column(
            Modifier.fillMaxWidth().border(BorderStroke(1.dp, SGColors.Gold), shape)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("NETT EARNING (RM)", color = SGColors.SelectedGold,
                fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp)
            Text(net, color = if (net.startsWith("-")) SGColors.Error else SGColors.SelectedGold,
                fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun DesignCFinancialCards(
    petrol: String, onPetrolLongPress: () -> Unit,
    touch: String, onTouchLongPress: () -> Unit,
    autopass: String, onAutopassLongPress: () -> Unit,
    tax: String, grand: String, deduction: String, net: String
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp
    if (PaymentDesignC.useSideBySideCards(screenWidth)) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DesignCDeductions(petrol, onPetrolLongPress, touch, onTouchLongPress, autopass, onAutopassLongPress,
                tax, deduction, Modifier.weight(1f).fillMaxHeight())
            DesignCEarnings(grand, deduction, net, Modifier.weight(1f).fillMaxHeight(), balancedHeight = true)
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DesignCDeductions(petrol, onPetrolLongPress, touch, onTouchLongPress, autopass, onAutopassLongPress,
                tax, deduction, Modifier.fillMaxWidth())
            DesignCEarnings(grand, deduction, net, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DesignCAction(
    label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean,
    primary: Boolean, modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = PaymentDesignC.ControlHeight),
        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 5.dp),
        border = BorderStroke(1.dp, SGColors.Gold),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (primary) SGColors.Gold else SGColors.Card,
            contentColor = if (primary) SGColors.Background else SGColors.White,
            disabledContainerColor = SGColors.Card,
            disabledContentColor = SGColors.Disabled
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, lineHeight = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun DesignCActions(
    onReset: () -> Unit, onSave: () -> Unit,
    onViewReport: () -> Unit, saveEnabled: Boolean
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DesignCAction("RESET", Icons.Outlined.RestartAlt, onReset, true, false, Modifier.weight(1f))
        DesignCAction("SAVE", Icons.Outlined.Save, onSave, saveEnabled, true, Modifier.weight(1f))
        DesignCAction("VIEW REPORT", Icons.Outlined.Description,
            onViewReport, true, false, Modifier.weight(1.25f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesignCMonthMetric(
    label: String, amount: String, highlighted: Boolean, modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.heightIn(min = PaymentDesignC.MonthlyMetricHeight)
            .border(BorderStroke(if (highlighted) 1.dp else .7.dp,
                if (highlighted) SGColors.Gold else SGColors.Divider), shape)
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(label, color = if (highlighted) SGColors.SelectedGold else SGColors.Silver,
            fontSize = 10.5.sp, lineHeight = 12.sp, maxLines = 2)
        Text(amount, color = if (highlighted) SGColors.SelectedGold else SGColors.White,
            fontWeight = FontWeight.Bold, fontSize = if (highlighted) 14.sp else 13.sp,
            textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesignCMonthlySummary(
    monthName: String, year: Int,
    total: String, grand: String, deduction: String, nett: String,
    onPrevious: () -> Unit, onNext: () -> Unit,
    onManageMonth: () -> Unit
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp
    DesignCPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous month",
                    tint = SGColors.Gold)
            }
            Column(
                Modifier.weight(1f).heightIn(min = 38.dp)
                    .combinedClickable(onClick = {}, onLongClick = onManageMonth)
                    .semantics { contentDescription = "Monthly summary $monthName $year. Long press to manage payments" },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("MONTHLY SUMMARY", color = SGColors.SelectedGold,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("$monthName $year · Hold to manage",
                    color = SGColors.White, fontSize = 11.sp)
            }
            IconButton(onClick = onNext, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = "Next month",
                    tint = SGColors.Gold)
            }
        }
        // Always keep the requested 2 × 2 dashboard layout, even on tablets.
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DesignCMonthMetric("Total Payment (RM)", total, false, Modifier.weight(1f))
                DesignCMonthMetric("Grand Total (RM)", grand, false, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DesignCMonthMetric("Deductions (RM)", deduction, false, Modifier.weight(1f))
                DesignCMonthMetric("Nett Earning (RM)", nett, true, Modifier.weight(1f))
            }
        }
    }
}
