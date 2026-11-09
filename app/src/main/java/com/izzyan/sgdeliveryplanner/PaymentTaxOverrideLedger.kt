package com.izzyan.sgdeliveryplanner

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDate

/**
 * Per-day manual Tax overrides live independently of saved payments and route reports.
 * An absent key means that the Payment Calculator follows the latest SAVED Delivery Summary.
 * Data is kept in the existing payment_ledger preferences, and exported in backup v3.
 */
class PaymentTaxOverrideLedger(context: Context) {
    private val prefs = context.getSharedPreferences("payment_ledger", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<Map<String, String>>() {}.type
    private val key = "tax_overrides_by_date"

    fun read(): Map<String, String> {
        val parsed = gson.fromJson<Map<String, String>>(prefs.getString(key, "{}"), type)
            ?: error("Saved Tax overrides are invalid.")
        require(validTaxOverrides(parsed)) { "Saved Tax overrides are invalid." }
        return parsed.toMap()
    }

    /** Commit before changing observable UI state so a failed write never appears saved. */
    fun save(date: LocalDate, amount: String): Boolean {
        val value = normalizedCurrency(amount) ?: return false
        val updated = read() + (date.toString() to value)
        require(validTaxOverrides(updated)) { "Invalid Tax override." }
        return prefs.edit().putString(key, gson.toJson(updated)).commit()
    }

    /** Remove the override entirely: future report changes become the source again. */
    fun resetToReport(date: LocalDate): Boolean {
        val updated = read() - date.toString()
        return prefs.edit().putString(key, gson.toJson(updated)).commit()
    }

    /** Existing phone values win conflicts during restore; re-importing is idempotent. */
    fun mergeRestored(imported: Map<String, String>): Int {
        require(validTaxOverrides(imported)) { "Invalid backed-up Tax overrides." }
        val existing = read()
        val added = imported.filterKeys { it !in existing }
        if (added.isEmpty()) return 0
        val updated = existing + added
        check(prefs.edit().putString(key, gson.toJson(updated)).commit()) {
            "Tax overrides could not be saved. Retry restore."
        }
        return added.size
    }
}

fun validTaxOverrides(values: Map<String, String>): Boolean =
    values.size <= 10000 && values.all { (date, amount) ->
        runCatching { LocalDate.parse(date).toString() == date }.getOrDefault(false) &&
            normalizedCurrency(amount) == amount
    }
