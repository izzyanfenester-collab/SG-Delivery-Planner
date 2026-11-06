package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun PostalUpdateSettingsCard() {
    val context = LocalContext.current
    val credentials = remember(context) { OneMapCredentialStore(context) }
    val settings = remember(context) { PostalUpdatePreferences(context) }
    val scope = rememberCoroutineScope()
    var hasAccount by remember { mutableStateOf(credentials.configured()) }
    var autoUpdate by remember { mutableStateOf(settings.enabled) }
    var lastCheck by remember { mutableLongStateOf(settings.lastSuccessfulCheck) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }

    PremiumCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Map & Postal Data", style = MaterialTheme.typography.titleLarge)
            Text("Map: OpenStreetMap (online tiles, automatic provider cache updates)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            Text("Postal codes: OneMap Singapore (free account required)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            Text(if (hasAccount) "OneMap account: saved securely on this phone" else "OneMap account: not configured",
                style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("OneMap registered email") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("OneMap password") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        busy = true
                        feedback = "Validating OneMap account..."
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    credentials.save(email, password)
                                    // Verify the new account and token; future tokens renew automatically.
                                    createOneMapApi(context).search("018956")
                                }
                                hasAccount = true
                                feedback = "OneMap connected. Token renewal is automatic."
                            } catch (error: Exception) {
                                hasAccount = credentials.configured()
                                feedback = "Account saved but verification failed: " +
                                    englishError(error, "Please check your email, password and connection.")
                            } finally {
                                password = ""
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Save & verify") }
                OutlinedButton(
                    onClick = {
                        credentials.clear()
                        hasAccount = false
                        email = ""
                        password = ""
                        feedback = "OneMap account removed from this phone."
                    },
                    enabled = !busy && hasAccount
                ) { Text("Remove") }
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic postal refresh", style = MaterialTheme.typography.titleMedium)
                    Text("Weekly, online only. Rechecks used postal codes older than 30 days.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = autoUpdate, onCheckedChange = {
                    autoUpdate = it
                    settings.enabled = it
                    PostalUpdateScheduler.configure(context)
                })
            }
            Text("Last completed postal check: " + if (lastCheck == 0L) "Not yet" else
                DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
                    .withZone(ZoneId.of("Asia/Singapore"))
                    .format(Instant.ofEpochMilli(lastCheck)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Button(onClick = {
                busy = true
                feedback = "Checking previously used postal codes..."
                scope.launch {
                    try {
                        val report = withContext(Dispatchers.IO) {
                            Repository(context).refreshPostalCache(force = true, limit = 12)
                        }
                        if (report.failed == 0) {
                            settings.recordSuccess()
                            lastCheck = settings.lastSuccessfulCheck
                        }
                        feedback = "Checked ${report.checked}, updated ${report.updated}, failed ${report.failed}." +
                            if (report.failed > 0) " Retry later or verify your OneMap account." else ""
                    } catch (error: Exception) {
                        feedback = englishError(error, "Postal check failed. Please retry.")
                    } finally { busy = false }
                }
            }, enabled = !busy && hasAccount, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Checking..." else "Check postal data now")
            }
            OutlinedButton(onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { Repository(context).refreshRoutingCache() }
                        feedback = "Cached road distances cleared. Your next route will request new routing data. Saved routes are unchanged."
                    } catch (error: Exception) {
                        feedback = englishError(error, "Could not refresh the routing cache.")
                    } finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh routing data for next plan")
            }
            Text("No paid subscription required. Map tiles follow the provider's cache policy; updates may not appear immediately. Previous delivery records are not changed.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
