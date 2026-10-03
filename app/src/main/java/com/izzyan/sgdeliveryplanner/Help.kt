package com.izzyan.sgdeliveryplanner

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun AskChatGptButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(50.dp)
    val gold = Color(0xFFD8B970)
    Button(
        onClick = { openChatGpt(context) },
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xFF244EA6), Color(0xFF111E42))), shape),
        shape = shape,
        border = BorderStroke(1.dp, gold),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp, pressedElevation = 1.dp)
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(28.dp)) {
                val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                drawRoundRect(gold, Offset(size.width * .08f, size.height * .08f), Size(size.width * .84f, size.height * .66f), CornerRadius(4.dp.toPx()), style = stroke)
                val tail = Path().apply {
                    moveTo(size.width * .28f, size.height * .75f)
                    lineTo(size.width * .24f, size.height * .93f)
                    lineTo(size.width * .46f, size.height * .76f)
                }
                drawPath(tail, gold, style = stroke)
                listOf(.3f, .5f, .7f).forEach { x -> drawCircle(Color.White, 1.2.dp.toPx(), Offset(size.width * x, size.height * .41f)) }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Ask ChatGPT", style = MaterialTheme.typography.titleMedium)
                Text("Get delivery help", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE6EAF5))
            }
        }
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
