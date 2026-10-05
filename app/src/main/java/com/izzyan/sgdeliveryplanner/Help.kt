package com.izzyan.sgdeliveryplanner

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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

/** Launch only the official Android app, with no prompt, URL, share payload or clipboard writes. */
internal fun openChatGpt(context: Context): Boolean {
    val message = try {
        val launch = context.packageManager.getLaunchIntentForPackage("com.openai.chatgpt")
        if (launch == null) "ChatGPT app is not installed."
        else {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
    } catch (_: ActivityNotFoundException) {
        "ChatGPT app could not be opened. Please check that it is installed."
    } catch (_: SecurityException) {
        "ChatGPT app could not be opened. Please check that it is installed."
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    return false
}
