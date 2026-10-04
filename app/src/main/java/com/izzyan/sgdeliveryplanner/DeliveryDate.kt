package com.izzyan.sgdeliveryplanner

import android.app.DatePickerDialog
import android.content.Context
import java.time.LocalDate

/** Both Home and Settings pass the same ViewModel date and selection callback. */
internal fun deliveryDatePicker(
    context: Context,
    selected: LocalDate,
    onSelected: (LocalDate) -> Unit
): DatePickerDialog = DatePickerDialog(
    context,
    { _, year, month, day -> onSelected(LocalDate.of(year, month + 1, day)) },
    selected.year, selected.monthValue - 1, selected.dayOfMonth
)
