package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Keep app and framework captions in English without changing the device language or theme. */
fun englishAppContext(base: Context): Context {
    val configuration = Configuration(base.resources.configuration).apply {
        setLocale(Locale.ENGLISH)
        setLayoutDirection(Locale.ENGLISH)
    }
    return base.createConfigurationContext(configuration)
}

class IzzDeliveryApplication : Application() {
    override fun attachBaseContext(base: Context) {
        // Formatters that use the process default should match the app's English-only UI.
        Locale.setDefault(Locale.ENGLISH)
        super.attachBaseContext(englishAppContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = getSystemService(LocaleManager::class.java)
            val english = LocaleList.forLanguageTags("en")
            if (localeManager != null && localeManager.applicationLocales != english) {
                localeManager.applicationLocales = english
            }
        }
    }
}
