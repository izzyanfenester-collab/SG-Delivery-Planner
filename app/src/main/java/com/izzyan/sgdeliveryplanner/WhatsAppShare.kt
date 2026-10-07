package com.izzyan.sgdeliveryplanner

import android.content.Intent
import android.os.Bundle

/** Consume text once and erase the sensitive share payload from the activity's retained intent. */
internal fun consumeWhatsAppShare(intent: Intent): WhatsAppImportAnalysis? {
    if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return null
    val analysis = analyzeWhatsAppImport(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
    intent.replaceExtras(null as Bundle?)
    intent.clipData = null
    intent.data = null
    intent.action = Intent.ACTION_MAIN
    return analysis
}
