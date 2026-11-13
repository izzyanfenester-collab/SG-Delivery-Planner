package com.izzyan.sgdeliveryplanner

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** A fallback retains the original image URI and grants, never changes Settings. */
internal data class WhatsAppImageShareFallbackRequest(
    val send: Intent, val title: String, val unavailable: WhatsAppChoice
)

/** Clone the original image SEND intent so MIME, stream and ClipData survive.
 * No recipient or message is set: WhatsApp's recipient picker and Send remain user-controlled.
 */
internal fun whatsappImageIntent(send: Intent, choice: WhatsAppChoice): Intent =
    Intent(send).setPackage(choice.packageName)

private fun Context.isActivityContext(): Boolean {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return true
        val base = context.baseContext
        if (base === context) break
        context = base
    }
    return context is Activity
}

private fun launchShareActivity(context: Context, intent: Intent) {
    if (!context.isActivityContext()) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

internal fun tryWhatsAppImageShare(context: Context, send: Intent, choice: WhatsAppChoice): Boolean {
    return try {
        launchShareActivity(context, whatsappImageIntent(send, choice))
        true
    } catch (_: ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }
}

/** Always read the existing preference at the moment of sharing, including after image generation. */
internal fun openDefaultWhatsAppImageShare(context: Context, send: Intent, title: String): WhatsAppImageShareFallbackRequest? {
    val choice = WhatsAppPreferences(context).default
    if (choice == null) {
        openImageShareMenu(context, send, title)
        return null
    }
    return if (tryWhatsAppImageShare(context, send, choice)) null
    else WhatsAppImageShareFallbackRequest(Intent(send), title, choice)
}

internal fun openImageShareMenu(context: Context, send: Intent, title: String) {
    // A standard chooser must not retain the unavailable app's explicit package.
    val unrestricted = Intent(send).setPackage(null)
    launchShareActivity(context, Intent.createChooser(unrestricted, title))
}

@Composable
internal fun WhatsAppImageShareFallbackDialog(request: WhatsAppImageShareFallbackRequest, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val other = WhatsAppChoice.entries.first { it != request.unavailable }
    val otherAvailable = runCatching {
        whatsappImageIntent(request.send, other).resolveActivity(context.packageManager) != null
    }.getOrDefault(false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WhatsApp unavailable") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${request.unavailable.label} is not installed or cannot share this image. Your default WhatsApp setting has not changed.")
                if (otherAvailable) {
                    OutlinedButton(onClick = {
                        if (tryWhatsAppImageShare(context, request.send, other)) onDismiss()
                        else Toast.makeText(context, "${other.label} could not be opened. Choose Share menu.", Toast.LENGTH_LONG).show()
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open ${other.label}") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try { openImageShareMenu(context, request.send, request.title); onDismiss() }
                catch (_: Exception) { Toast.makeText(context, "Image could not be shared. Please try again.", Toast.LENGTH_LONG).show() }
            }) { Text("Share menu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
