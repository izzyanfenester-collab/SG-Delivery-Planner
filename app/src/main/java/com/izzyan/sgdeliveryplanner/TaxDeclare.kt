package com.izzyan.sgdeliveryplanner

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal const val TAX_DECLARE_URL = "https://m.customs.gov.sg/CustomsTravellerPortal/"

internal fun taxDeclareIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(TAX_DECLARE_URL)).apply {
    addCategory(Intent.CATEGORY_BROWSABLE)
    // Resolve as a browser rather than a portal-specific app link; the browser receives the
    // original ACTION_VIEW URL. No browser package is hard-coded and Android uses its default.
    selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

internal fun openTaxDeclare(context: Context): Boolean = try {
    context.startActivity(taxDeclareIntent())
    true
} catch (_: ActivityNotFoundException) {
    Toast.makeText(context, "No browser is available. Install or enable a browser to open the Singapore Customs Traveller Portal.", Toast.LENGTH_LONG).show()
    false
} catch (_: SecurityException) {
    Toast.makeText(context, "The Singapore Customs Traveller Portal could not be opened in your browser. Please try again.", Toast.LENGTH_LONG).show()
    false
}

@Composable
fun TaxDeclareButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Button(
        onClick = { openTaxDeclare(context) },
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, PremiumGold),
        colors = ButtonDefaults.buttonColors(containerColor = PremiumNavy, contentColor = PremiumGold),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
    ) {
        Icon(painterResource(R.drawable.ic_customs_document), contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text("Tax Declare", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
