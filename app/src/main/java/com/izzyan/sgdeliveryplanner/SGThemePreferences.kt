package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Default to the unchanged B857 appearance; this preference is UI-only. */
object SGThemePreferences {
    private const val PREFS = "sg_delivery_premium_theme"
    private const val KEY = "burgundy_sport_enabled"
    var isBurgundy by mutableStateOf(false)
        private set
    fun initialize(context: Context) {
        isBurgundy = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    }
    fun select(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
        isBurgundy = enabled
    }
}
