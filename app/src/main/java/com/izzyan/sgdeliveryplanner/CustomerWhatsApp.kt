package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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
/** Do not synthesize missing order items: older saved routes need the WhatsApp order re-imported. */
fun orderMessageDraft(order: CustomerOrder): String? =
    order.originalMessage?.takeIf { it.isNotBlank() }?.trim()

/**
 * Compose the outgoing message using this specific delivery stop, never a postcode lookup.
 * Updated ETA is preferred; for a newly created route without an update, use planned arrival.
 * The imported text stays intact, including individual order items and customer details.
 * Missing price/order text cannot be guessed and must be fixed before sending.
 */
fun deliveryWhatsAppMessageDraft(stop: Stop): String? {
    val order = stop.order ?: return null
    val original = orderMessageDraft(order) ?: return null
    val amount = order.parcelPrice ?: return null
    val etaText = stop.etaArrival?.takeIf { it.isNotBlank() } ?: stop.arrival
    val eta = runCatching { LocalDateTime.parse(etaText) }.getOrNull() ?: return null
    val formatter = DateTimeFormatter.ofPattern("h.mma", Locale.ENGLISH)
    fun formatted(value: LocalDateTime) = value.format(formatter).lowercase(Locale.ENGLISH)
    return buildString {
        appendLine("Hi! I’ll be delivering your COD parcel today.")
        appendLine()
        append("*COD Amount: S$").append(amount.setScale(2, RoundingMode.HALF_UP).toPlainString()).appendLine("*")
        append("*ETA : ").append(formatted(eta)).append(" - ").append(formatted(eta.plusMinutes(10))).appendLine("*")
        appendLine()
        appendLine("Order Details :")
        appendLine("=================================")
        appendLine(original)
        appendLine("=================================")
        appendLine("To make the delivery process easier and faster, please have the exact cash ready.")
        appendLine()
        append("Thank you for your purchase with us! 🙏 We truly appreciate your support. We hope to serve you again for your next order. 😊")
    }
}

/** Only launches an editable WhatsApp draft. Never sends a message automatically. */
fun customerWhatsAppIntent(phone: String, choice: WhatsAppChoice = WhatsAppChoice.PERSONAL, draft: String? = null): Intent {
    val normalized = requireNotNull(normalizeCustomerPhone(phone))
    val link = Uri.Builder().scheme("https").authority("wa.me").appendPath(normalized.removePrefix("+")).apply {
        if (!draft.isNullOrBlank()) appendQueryParameter("text", draft)
    }.build()
    return Intent(Intent.ACTION_VIEW,link)
        .setPackage(choice.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
fun openCustomerWhatsApp(context: Context, phone: String, choice: WhatsAppChoice = WhatsAppChoice.PERSONAL, draft: String? = null): Boolean {
    if (normalizeCustomerPhone(phone) == null) { Toast.makeText(context,"Customer phone number unavailable.",Toast.LENGTH_LONG).show(); return false }
    try {
        val intent = customerWhatsAppIntent(phone,choice,draft)
        if (intent.resolveActivity(context.packageManager) != null) { context.startActivity(intent); return true }
    } catch (_: android.content.ActivityNotFoundException) { }
    catch (_: SecurityException) { }
    Toast.makeText(context,"${choice.label} is not installed.",Toast.LENGTH_LONG).show()
    return false
}
