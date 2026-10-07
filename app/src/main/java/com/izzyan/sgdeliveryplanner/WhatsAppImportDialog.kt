package com.izzyan.sgdeliveryplanner

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun WhatsAppImportDialog(
    initialCodes: List<String>?,
    initialAnalysis: WhatsAppImportAnalysis?,
    availableSlots: Int,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onAdd: (List<String>, WhatsAppImportAnalysis) -> Unit
) {
    val context = LocalContext.current
    // Deliberately not rememberSaveable: raw messages must not enter saved-instance-state or preferences.
    var text by remember { mutableStateOf("") }
    val initial = initialAnalysis ?: initialCodes?.let {
        WhatsAppImportAnalysis(it.distinct(), it.size, it.size - it.distinct().size, 0)
    }
    var analysis by remember { mutableStateOf(initial) }
    var selected by remember {
        mutableStateOf(initial?.postalCodes.orEmpty().toSet())
    }
    var clipboardMessage by remember { mutableStateOf<String?>(null) }

    fun preview(raw: String) {
        val result = analyzeWhatsAppImport(raw)
        text = ""
        analysis = result
        selected = result.postalCodes.toSet()
    }

    val codes = analysis?.postalCodes
    val addedCount = selected.size
    val duplicateCount = analysis?.duplicate ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WhatsApp Import") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (analysis == null) {
                    Text("Paste customer messages. Only postal codes will be imported.")
                    OutlinedTextField(
                        text,
                        { text = it },
                        label = { Text("WhatsApp messages") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 240.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val raw = runCatching { manager.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
                            if (raw.isNullOrBlank()) clipboardMessage = "No text available on the clipboard."
                            else {
                                clipboardMessage = null
                                preview(raw)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) { Text("Paste from Clipboard") }
                    clipboardMessage?.let { Text(it) }
                } else {
                    Text(
                        "Found ${analysis!!.found} / Added $addedCount / Duplicate $duplicateCount / Failed ${analysis!!.failed}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Adds to the Home planning list. $addedCount selected · $availableSlots spaces available (50-stop limit).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (addedCount > availableSlots) {
                        Text(
                            "Select fewer codes to stay within the 50-stop limit.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (analysis!!.failed > 0) {
                        Text(
                            "${analysis!!.failed} address section(s) have no detectable 6-digit postal code.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (codes!!.isEmpty()) Text("No postal codes found. Cancel and paste another message.")
                    TextButton(
                        onClick = { selected = codes.toSet() },
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("SELECT ALL") }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(codes, key = { it }) { code ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .toggleable(
                                        code in selected,
                                        enabled = enabled,
                                        role = Role.Checkbox,
                                        onValueChange = { checked ->
                                            selected = if (checked) selected + code else selected - code
                                        }
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    code in selected,
                                    onCheckedChange = null,
                                    enabled = enabled
                                )
                                Text(code, Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (analysis == null) preview(text)
                    else onAdd(codes!!.filter { it in selected }, analysis!!)
                },
                enabled = enabled && (
                    if (analysis == null) text.isNotBlank()
                    else addedCount in 1..availableSlots
                ),
                modifier = Modifier.heightIn(min = 52.dp)
            ) { Text(if (analysis == null) "PREVIEW" else "ADD TO ROUTE") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("CANCEL") }
        }
    )
}
