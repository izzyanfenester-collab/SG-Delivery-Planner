package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

fun customerWhatsAppIntent(phone: String): Intent {
    val normalized = requireNotNull(normalizeCustomerPhone(phone))
    return Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/${normalized.removePrefix("+")}"))
        .setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
fun openCustomerWhatsApp(context: Context, phone: String): Boolean {
    if (normalizeCustomerPhone(phone) == null) { Toast.makeText(context,"Customer phone number unavailable.",Toast.LENGTH_LONG).show(); return false }
    try {
        val intent = customerWhatsAppIntent(phone)
        if (intent.resolveActivity(context.packageManager) != null) { context.startActivity(intent); return true }
    } catch (_: android.content.ActivityNotFoundException) { }
    catch (_: SecurityException) { }
    Toast.makeText(context,"WhatsApp is not installed.",Toast.LENGTH_LONG).show()
    return false
}
