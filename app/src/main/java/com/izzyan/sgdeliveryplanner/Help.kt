package com.izzyan.sgdeliveryplanner

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Compact main-screen header access; shares the existing blank ChatGPT handoff. */
@Composable
fun ChatGptHeaderButton() {
    val context = LocalContext.current
    IconButton(onClick = { openChatGpt(context) }, modifier = Modifier.size(48.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_chatgpt),
            contentDescription = "Open ChatGPT",
            tint = PremiumGold,
            modifier = Modifier.size(34.dp)
        )
    }
}

/** Open ChatGPT's home without a question, share payload or route context. */
internal fun chatGptIntent(packageName: String? = null): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")).apply {
        packageName?.let { setPackage(it) }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

internal fun openChatGpt(context: Context): Boolean {
    if (tryOpenChatGpt(context, chatGptIntent("com.openai.chatgpt"))) return true
    if (tryOpenChatGpt(context, chatGptIntent())) return true
    Toast.makeText(
        context,
        "ChatGPT could not be opened. Please install ChatGPT or a web browser and try again.",
        Toast.LENGTH_LONG
    ).show()
    return false
}

private fun tryOpenChatGpt(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
