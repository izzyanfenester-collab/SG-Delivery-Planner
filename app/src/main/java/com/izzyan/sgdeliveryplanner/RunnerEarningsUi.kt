package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * Additional panel within the existing Payment tab.
 * Never treats customer COD collections as runner income.
 */
@Composable
internal fun RunnerEarningsPanel(vm: PlannerViewModel) {
    val day = vm.deliveryDate
    val report = monthlyRunnerEarnings(vm.paymentRecords, vm.runnerExpenses,
        day.year, day.monthValue)
    val monthExpenses = vm.runnerExpenses.filter {
        runCatching { LocalDate.parse(it.date) }.getOrNull()?.let { d ->
            d.year == day.year && d.monthValue == day.monthValue
        } == true
    }
    var addOpen by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<RunnerExpense?>(null) }

    Surface(
        color = PremiumNavy.copy(alpha = .90f),
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, PremiumGold.copy(alpha = .58f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Runner Earnings", color = PremiumGold,
                    fontWeight = FontWeight.Bold, fontSize = 19.sp,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { addOpen = true }, enabled = !vm.busy) {
                    Text("+ Expense", color = PremiumGold)
                }
            }
            Text(
                "${day.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${day.year} · amounts in RM",
                color = Color(0xFFD6DEE8), fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EarningsValueTile("Saved payout", "RM ${report.grossRm}", Modifier.weight(1f))
                EarningsValueTile("Expenses", "RM ${report.expensesRm}", Modifier.weight(1f))
            }
            HorizontalDivider(color = PremiumGold.copy(alpha = .4f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Estimated net", color = Color.White, modifier = Modifier.weight(1f))
                Text("RM ${report.estimatedNetRm}", fontWeight = FontWeight.Bold,
                    color = PremiumGold, fontSize = 22.sp)
            }
            Text("Derived from saved Payment records minus logged expenses. Excludes COD collected on behalf of customers.",
                color = Color(0xFFD6DEE8), fontSize = 11.sp, lineHeight = 15.sp)
            if (monthExpenses.isNotEmpty()) {
                Text("${report.expenseCount} recorded expense(s)", color = Color.White,
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                monthExpenses.asReversed().take(6).forEach { e ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${e.date} · ${e.category}", color = Color.White, fontSize = 12.sp)
                            Text("${e.currency} ${e.amount} · RM ${e.rmAmount}",
                                color = Color(0xFFD6DEE8), fontSize = 11.sp)
                        }
                        TextButton(onClick = { deleteCandidate = e }, enabled = !vm.busy) {
                            Text("Remove", color = Color(0xFFFFAFAF), fontSize = 11.sp)
                        }
                    }
                }
                if (monthExpenses.size > 6)
                    Text("+${monthExpenses.size - 6} additional expenses in monthly totals",
                        color = Color(0xFFD6DEE8), fontSize = 11.sp)
            }
        }
    }

    if (addOpen) {
        var category by remember { mutableStateOf(runnerExpenseCategories.first()) }
        var currency by remember { mutableStateOf("MYR") }
        var amount by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        var rate by remember { mutableStateOf(vm.latestExchangeRate) }
        var error by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text("Add runner expense") },
            text = {
                Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Date: $day", style = MaterialTheme.typography.bodySmall)
                    Text("Category", fontWeight = FontWeight.Medium)
                    runnerExpenseCategories.forEach { option ->
                        FilterChip(selected = category == option,
                            onClick = { category = option }, label = { Text(option) },
                            modifier = Modifier.heightIn(min = 36.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("MYR", "SGD").forEach { option ->
                            FilterChip(selected = currency == option,
                                onClick = { currency = option },
                                label = { Text(option) })
                        }
                    }
                    OutlinedTextField(value = amount,
                        onValueChange = { amount = it.take(16) },
                        label = { Text("Expense amount ($currency)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true)
                    if (currency == "SGD") {
                        OutlinedTextField(value = rate,
                            onValueChange = { rate = it.take(10) },
                            label = { Text("SGD to MYR rate (snapshot)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    OutlinedTextField(value = note, onValueChange = { note = it.take(160) },
                        label = { Text("Remark (optional)") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3)
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val item = createRunnerExpense(day.toString(), category, amount,
                        currency, if (currency == "SGD") rate else "1.00", note)
                    if (item == null) error = "Enter a valid positive amount and rate."
                    else if (vm.addRunnerExpense(item)) addOpen = false
                    else error = "Could not save expense. Retry."
                }, enabled = !vm.busy) { Text("Save Expense") }
            },
            dismissButton = { TextButton(onClick = { addOpen = false }) { Text("Cancel") } }
        )
    }
    deleteCandidate?.let { expense ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Remove this expense?") },
            text = { Text("${expense.date} · ${expense.category} · ${expense.currency} ${expense.amount}") },
            confirmButton = {
                TextButton(onClick = {
                    if (vm.deleteRunnerExpense(expense.id)) deleteCandidate = null
                }, enabled = !vm.busy) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun EarningsValueTile(title: String, value: String, modifier: Modifier) {
    Surface(modifier, color = Color(0xFF152F50), shape = RoundedCornerShape(11.dp)) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color(0xFFD6DEE8), fontSize = 11.sp)
            Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}
