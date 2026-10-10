package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

enum class WhatsAppChoice(val label: String, val packageName: String) {
    PERSONAL("WhatsApp", "com.whatsapp"), BUSINESS("WhatsApp Business", "com.whatsapp.w4b")
}
class WhatsAppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("whatsapp_preferences", Context.MODE_PRIVATE)
    var default by androidx.compose.runtime.mutableStateOf(prefs.getString("default", null)?.let { saved -> WhatsAppChoice.entries.firstOrNull { it.name == saved } })
        private set
    fun updateDefault(choice: WhatsAppChoice?) {
        default = choice
        prefs.edit().apply { if (choice == null) remove("default") else putString("default", choice.name) }.apply()
    }
}
fun customerWhatsAppIntent(phone: String, choice: WhatsAppChoice = WhatsAppChoice.PERSONAL): Intent {
    val normalized = requireNotNull(normalizeCustomerPhone(phone))
    return Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/${normalized.removePrefix("+")}"))
        .setPackage(choice.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
fun openCustomerWhatsApp(context: Context, phone: String, choice: WhatsAppChoice = WhatsAppChoice.PERSONAL): Boolean {
    if (normalizeCustomerPhone(phone) == null) { Toast.makeText(context,"Customer phone number unavailable.",Toast.LENGTH_LONG).show(); return false }
    try {
        val intent = customerWhatsAppIntent(phone,choice)
        if (intent.resolveActivity(context.packageManager) != null) { context.startActivity(intent); return true }
    } catch (_: android.content.ActivityNotFoundException) { }
    catch (_: SecurityException) { }
    Toast.makeText(context,"${choice.label} is not installed.",Toast.LENGTH_LONG).show()
    return false
}
