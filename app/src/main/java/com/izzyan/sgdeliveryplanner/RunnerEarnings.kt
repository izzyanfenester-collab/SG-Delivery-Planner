package com.izzyan.sgdeliveryplanner

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

internal val runnerExpenseCategories = listOf("Petrol", "Toll", "Parking", "Maintenance", "Other")

internal data class RunnerExpense(
    val id: String,
    val date: String,
    val category: String,
    val amount: String,
    val currency: String,
    val rateToMyr: String,
    val note: String = ""
) {
    val rmAmount: BigDecimal get() {
        val value = BigDecimal(amount)
        val converted = if (currency == "SGD") value.multiply(BigDecimal(rateToMyr)) else value
        return converted.setScale(2, RoundingMode.HALF_UP)
    }
}

internal fun validRunnerExpense(expense: RunnerExpense): Boolean = runCatching {
    require(expense.id.length in 4..100)
    LocalDate.parse(expense.date)
    require(expense.category in runnerExpenseCategories)
    require(expense.currency == "SGD" || expense.currency == "MYR")
    require(expense.note.length <= 160)
    val amount = BigDecimal(expense.amount)
    val rate = BigDecimal(expense.rateToMyr)
    require(amount > BigDecimal.ZERO && amount <= BigDecimal("1000000.00") &&
        amount.scale() in 0..2)
    require(rate >= BigDecimal("1.00") && rate <= BigDecimal("10.00") &&
        rate.scale() in 0..5)
    true
}.getOrDefault(false)

internal fun createRunnerExpense(
    date: String, category: String, amount: String, currency: String,
    rateToMyr: String, note: String = ""
): RunnerExpense? {
    val item = RunnerExpense(
        UUID.randomUUID().toString(), date, category,
        normalizedCurrency(amount) ?: return null,
        currency, rateToMyr, note.trim().take(160)
    )
    return item.takeIf(::validRunnerExpense)
}

/** In MYR; independent of COD collections, which are never runner revenue. */
internal data class RunnerEarningsTotals(
    val grossRm: String,
    val expensesRm: String,
    val estimatedNetRm: String,
    val expenseCount: Int
)

internal fun monthlyRunnerEarnings(
    payments: List<PaymentRecord>, expenses: List<RunnerExpense>,
    year: Int, month: Int
): RunnerEarningsTotals {
    val gross = monthlyPaymentTotals(payments, year, month).grandTotalPaymentRm
        .let(::BigDecimal)
    val selected = expenses.filter {
        val date = LocalDate.parse(it.date)
        date.year == year && date.monthValue == month
    }
    val costs = selected.fold(BigDecimal.ZERO) { sum, item -> sum.add(item.rmAmount) }
    fun format(value: BigDecimal) = value.setScale(2, RoundingMode.HALF_UP).toPlainString()
    return RunnerEarningsTotals(format(gross), format(costs), format(gross - costs), selected.size)
}

/** Separate durable ledger; never mutates existing PaymentRecord fields. */
internal class RunnerExpenseLedger(context: Context) {
    private val prefs = context.getSharedPreferences("runner_expense_ledger", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<RunnerExpense>>() {}.type

    @Synchronized fun read(): List<RunnerExpense> {
        val records = gson.fromJson<List<RunnerExpense>>(prefs.getString("records", "[]"), type)
            ?: error("Saved expense history invalid.")
        require(records.size <= 10000 && records.all(::validRunnerExpense) &&
            records.map { it.id }.distinct().size == records.size) {
            "Saved expense history invalid."
        }
        return records
    }

    @Synchronized fun append(item: RunnerExpense): Boolean {
        require(validRunnerExpense(item))
        val previous = read()
        require(previous.size < 10000 && previous.none { it.id == item.id })
        return prefs.edit().putString("records", gson.toJson(previous + item)).commit()
    }

    @Synchronized fun delete(id: String): Boolean {
        val previous = read()
        require(previous.any { it.id == id })
        return prefs.edit().putString("records", gson.toJson(previous.filterNot { it.id == id })).commit()
    }

    @Synchronized fun mergeRestored(records: List<RunnerExpense>): Int {
        require(records.size <= 10000 && records.all(::validRunnerExpense))
        require(records.map { it.id }.distinct().size == records.size)
        val current = read()
        val existing = current.associateBy { it.id }
        require(records.all { existing[it.id] == null || existing[it.id] == it }) {
            "An expense ID already exists with different values; no data replaced."
        }
        val additions = records.filterNot { it.id in existing }
        require(current.size + additions.size <= 10000)
        if (additions.isNotEmpty())
            check(prefs.edit().putString("records", gson.toJson(current + additions)).commit()) {
                "Expense restore could not be saved."
            }
        return additions.size
    }
}
