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
import androidx.compose.ui.unit.dp

@Composable
internal fun WhatsAppImportDialog(initialCodes: List<String>?, alreadyAdded: Set<String>, availableSlots: Int, enabled: Boolean,
    onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    val context = LocalContext.current
    // Deliberately not rememberSaveable: raw messages must not enter saved-instance-state or preferences.
    var text by remember { mutableStateOf("") }
    var codes by remember { mutableStateOf(initialCodes) }
    var selected by remember { mutableStateOf(initialCodes.orEmpty().filterNot { it in alreadyAdded }.toSet()) }
    var clipboardMessage by remember { mutableStateOf<String?>(null) }
    fun preview(raw: String) {
        val found = extractWhatsAppPostalCodes(raw)
        text = ""; codes = found; selected = found.filterNot { it in alreadyAdded }.toSet()
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("WhatsApp Import") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (codes == null) {
                    Text("Paste customer messages. Only postal codes will be imported.")
                    OutlinedTextField(text, { text = it }, label = { Text("WhatsApp messages") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 240.dp))
                    OutlinedButton(onClick = {
                        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val raw = runCatching { manager.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
                        if (raw.isNullOrBlank()) clipboardMessage = "No text available on the clipboard."
                        else { clipboardMessage = null; preview(raw) }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Paste from Clipboard") }
                    clipboardMessage?.let { Text(it) }
                } else {
                    Text("Postal codes found: ${codes!!.size}")
                    Text("Adds to the Home planning list. ${selected.count { it !in alreadyAdded }} selected · $availableSlots spaces available (50-stop limit).", style = MaterialTheme.typography.bodySmall)
                    if (selected.count { it !in alreadyAdded } > availableSlots) Text("Select fewer codes to stay within the 50-stop limit.", color = MaterialTheme.colorScheme.error)
                    if (codes!!.isEmpty()) Text("No postal codes found. Cancel and paste another message.")
                    TextButton(onClick = { selected = codes!!.filterNot { it in alreadyAdded }.toSet() }, enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("SELECT ALL") }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(codes!!, key = { it }) { code ->
                            val existing = code in alreadyAdded
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .toggleable(code in selected && !existing, enabled = enabled && !existing, role = Role.Checkbox,
                                    onValueChange = { checked -> selected = if (checked) selected + code else selected - code }),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(code in selected && !existing, onCheckedChange = null, enabled = enabled && !existing)
                                Text(if (existing) "$code · Already added" else code, Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (codes == null) preview(text)
                else onAdd(codes!!.filter { it in selected && it !in alreadyAdded })
            }, enabled = enabled && (if (codes == null) text.isNotBlank() else selected.count { it !in alreadyAdded } in 1..availableSlots),
                modifier = Modifier.heightIn(min = 52.dp)) { Text(if (codes == null) "PREVIEW" else "ADD TO ROUTE") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("CANCEL") } }
    )
}
