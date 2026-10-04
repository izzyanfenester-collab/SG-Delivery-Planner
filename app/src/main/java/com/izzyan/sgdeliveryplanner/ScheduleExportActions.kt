package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PENDING_EXCEL_PATH = "pending_excel_path"
private const val PENDING_PICKER_OPENED = "pending_excel_picker_opened"
private const val PENDING_DESTINATION_URI = "pending_excel_destination_uri"
private const val PENDING_PERMISSION_FLAGS = "pending_excel_permission_flags"
private const val PENDING_WRITE_GRANT_OWNED = "pending_excel_write_grant_owned"
private const val EXPORT_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

/** The workbook is prepared before opening the picker, preserving the selected route snapshot. */
class ScheduleExportViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {
    private var pendingSavePath by mutableStateOf(savedState.get<String>(PENDING_EXCEL_PATH))
    private var working by mutableStateOf(false)
    val busy: Boolean get() = working || pendingSavePath != null || shareRequest != null

    var saveRequest by mutableStateOf(
        pendingSavePath?.takeUnless { savedState.get<Boolean>(PENDING_PICKER_OPENED) == true }?.let(::File)
    )
        private set
    var shareRequest by mutableStateOf<File?>(null)
        private set
    var message by mutableStateOf("")
        private set
    var isError by mutableStateOf(false)
        private set
    var progress by mutableStateOf("")
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    init {
        // Replaying the entire immutable workbook with truncation is safe even if a provider
        // accepted only part of the original write before Android recreated this process.
        val destination = savedState.get<String>(PENDING_DESTINATION_URI)
        if (destination != null) {
            saveRequest = null
            val path = pendingSavePath
            val uri = Uri.parse(destination)
            if (path == null || uri.scheme != "content") {
                discardPendingSave()
                showError("The prepared Excel file is no longer available. Please export the schedule again.")
            } else {
                saveDocument(path, uri, savedState.get<Int>(PENDING_PERMISSION_FLAGS) ?: 0)
            }
        }
    }

    fun prepare(plan: Plan, share: Boolean) {
        if (busy) return
        val snapshot = plan.copy(stops = plan.stops.toList(), geometry = emptyList())
        working = true
        message = ""
        isError = false
        progress = "Preparing Excel file…"
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    createExcelSnapshot(getApplication(), snapshot, pendingSavePath)
                }
                if (share) {
                    shareRequest = file
                } else {
                    pendingSavePath = file.absolutePath
                    savedState[PENDING_EXCEL_PATH] = file.absolutePath
                    savedState[PENDING_PICKER_OPENED] = false
                    saveRequest = file
                    progress = "Choose where to save your Excel file."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError("The Excel file could not be created. Please try again.")
            } finally {
                working = false
            }
        }
    }

    fun savePickerOpened() {
        // The pending cache path stays in SavedStateHandle while Android owns the picker.
        saveRequest = null
        savedState[PENDING_PICKER_OPENED] = true
    }

    fun savePickerUnavailable() {
        saveRequest = null
        discardPendingSave()
        showError("Your device could not open the save dialog. Try Share Excel instead.")
    }

    fun saveResult(uri: Uri?, permissionFlags: Int = 0) {
        // ActivityResultRegistry may deliver the result again after the saved write resumed.
        if (working && savedState.get<String>(PENDING_DESTINATION_URI) != null) return
        saveRequest = null
        val path = pendingSavePath
        if (uri == null) {
            discardPendingSave()
            progress = ""
            return
        }
        if (path == null) {
            showError("The prepared Excel file is no longer available. Please export the schedule again.")
            return
        }
        if (uri.scheme != "content") {
            discardPendingSave()
            showError("The selected save location is unavailable. Please export the schedule again.")
            return
        }
        // Save this before starting IO, so a write in progress can resume after recreation.
        savedState[PENDING_DESTINATION_URI] = uri.toString()
        savedState[PENDING_PERMISSION_FLAGS] = permissionFlags
        saveDocument(path, uri, permissionFlags)
    }

    private fun saveDocument(path: String, uri: Uri, permissionFlags: Int) {
        working = true
        progress = "Saving Excel file…"
        viewModelScope.launch {
            var keepForRecovery = false
            try {
                if (savedState.get<Boolean>(PENDING_WRITE_GRANT_OWNED) != true) {
                    val retainedWriteGrant = withContext(Dispatchers.IO) {
                        retainExcelWriteGrant(getApplication(), uri, permissionFlags)
                    }
                    savedState[PENDING_WRITE_GRANT_OWNED] = retainedWriteGrant
                }
                withContext(Dispatchers.IO) {
                    writeExcelSnapshotToDocument(getApplication(), path, uri)
                }
                message = "Excel file saved successfully"
                isError = false
                notice = message
            } catch (cancelled: CancellationException) {
                keepForRecovery = true
                throw cancelled
            } catch (_: SecurityException) {
                showError("Access to the selected save location is no longer available. Export the schedule again and choose a location.")
            } catch (_: Exception) {
                showError("The Excel file could not be saved. Choose another location and try again.")
            } finally {
                if (!keepForRecovery) discardPendingSave()
                progress = ""
                working = false
            }
        }
    }

    fun shareOpened() {
        shareRequest = null
        progress = ""
    }

    fun shareUnavailable() {
        shareRequest = null
        showError("The Excel file could not be shared. Install an app that can share files and try again.")
    }

    fun noticeShown() {
        notice = null
    }

    private fun showError(text: String) {
        message = text
        isError = true
        progress = ""
        notice = text
    }

    private fun discardPendingSave() {
        val path = pendingSavePath
        val destination = savedState.get<String>(PENDING_DESTINATION_URI)
        val releaseWriteGrant = savedState.get<Boolean>(PENDING_WRITE_GRANT_OWNED) == true
        pendingSavePath = null
        savedState[PENDING_EXCEL_PATH] = null
        savedState[PENDING_PICKER_OPENED] = false
        savedState[PENDING_DESTINATION_URI] = null
        savedState[PENDING_PERMISSION_FLAGS] = 0
        savedState[PENDING_WRITE_GRANT_OWNED] = false
        if (path != null || releaseWriteGrant) {
            viewModelScope.launch(Dispatchers.IO) {
                if (releaseWriteGrant && destination != null) {
                    runCatching {
                        getApplication<Application>().contentResolver.releasePersistableUriPermission(
                            Uri.parse(destination), Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                }
                runCatching {
                    path?.let { existingExcelSnapshot(getApplication(), it) }?.let { file ->
                        file.delete()
                        file.parentFile?.delete()
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduleExportHost() {
    val context = LocalContext.current
    val export: ScheduleExportViewModel = viewModel()
    val documentPicker = rememberLauncherForActivityResult(
        CreateExcelDocument(),
        onResult = { result -> export.saveResult(result?.uri, result?.permissionFlags ?: 0) }
    )
    val saveRequest = export.saveRequest
    LaunchedEffect(saveRequest) {
        if (saveRequest != null) {
            try {
                documentPicker.launch(saveRequest.name)
                export.savePickerOpened()
            } catch (_: ActivityNotFoundException) {
                export.savePickerUnavailable()
            } catch (_: SecurityException) {
                export.savePickerUnavailable()
            }
        }
    }
    val shareRequest = export.shareRequest
    LaunchedEffect(shareRequest) {
        if (shareRequest != null) {
            try {
                context.startActivity(Intent.createChooser(excelShareIntent(context, shareRequest), "Share Excel"))
                export.shareOpened()
            } catch (_: ActivityNotFoundException) {
                export.shareUnavailable()
            } catch (_: SecurityException) {
                export.shareUnavailable()
            } catch (_: IllegalArgumentException) {
                export.shareUnavailable()
            }
        }
    }
    val notice = export.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
            export.noticeShown()
        }
    }
}

internal data class ExcelDocumentDestination(val uri: Uri, val permissionFlags: Int)

/** Keeps the SAF grant flags, which the standard Uri-only CreateDocument result drops. */
internal class CreateExcelDocument : ActivityResultContract<String, ExcelDocumentDestination?>() {
    override fun createIntent(context: Context, input: String): Intent =
        ActivityResultContracts.CreateDocument(EXCEL_MIME_TYPE).createIntent(context, input).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): ExcelDocumentDestination? {
        if (resultCode != Activity.RESULT_OK) return null
        val uri = intent?.data ?: return null
        return ExcelDocumentDestination(uri, intent.flags)
    }
}

/** Retains only a newly offered write grant; a pre-existing app grant must not be revoked later. */
private fun retainExcelWriteGrant(context: Context, uri: Uri, permissionFlags: Int): Boolean {
    val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    if (permissionFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0 || permissionFlags and write == 0) {
        return false
    }
    val resolver = context.contentResolver
    if (resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) return false
    return try {
        resolver.takePersistableUriPermission(uri, write)
        true
    } catch (_: SecurityException) {
        // Some providers offer only a transient grant. Saving can still succeed in this session;
        // restoration handles the eventual loss of access with a clear error instead of hanging.
        false
    }
}

@Composable
fun ScheduleExportActions(plan: Plan, enabled: Boolean = true) {
    val export: ScheduleExportViewModel = viewModel()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { export.prepare(plan, share = false) },
            enabled = enabled && !export.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, PremiumGold),
            colors = ButtonDefaults.buttonColors(containerColor = PremiumNavy, contentColor = PremiumGold),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
        ) {
            Text("Export to Excel", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(
            onClick = { export.prepare(plan, share = true) },
            enabled = enabled && !export.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, PremiumGold),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = PremiumNavy, contentColor = PremiumGold)
        ) {
            Text("Share Excel")
        }
        if (export.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = PremiumGold)
            Text(export.progress.ifEmpty { "Preparing Excel file…" }, style = MaterialTheme.typography.bodySmall)
        }
        if (export.message.isNotEmpty()) {
            Text(
                export.message,
                color = if (export.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Grants recipients access only to this file; no public storage access is needed. */
internal fun excelShareIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.excel-files", file)
    return Intent(Intent.ACTION_SEND).apply {
        type = EXCEL_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Runner Route Planning - Complete Delivery Schedule")
        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun excelCacheDirectory(context: Context): File = File(context.cacheDir, "exports")

/** Each export has its own immutable file, so a new export cannot overwrite a shared workbook. */
internal fun createExcelSnapshot(context: Context, plan: Plan, pendingPath: String? = null): File {
    val root = excelCacheDirectory(context)
    if (!root.exists() && !root.mkdirs()) throw IOException("Export cache is unavailable")
    val expiry = System.currentTimeMillis() - EXPORT_RETENTION_MILLIS
    root.listFiles()?.forEach { directory ->
        // Keep recent shares alive for recipients and always preserve an open document picker.
        if (directory.isDirectory && directory.canonicalFile.parentFile == root.canonicalFile &&
            directory.name.matches(Regex("[0-9a-f-]{36}")) && directory.lastModified() < expiry &&
            directory.listFiles()?.none { it.absolutePath == pendingPath } == true
        ) {
            directory.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
            directory.delete()
        }
    }
    val directory = File(root, UUID.randomUUID().toString())
    if (!directory.mkdirs()) throw IOException("Export cache is unavailable")
    val file = File(directory, deliveryExcelFileName(plan))
    try {
        file.outputStream().use { writeDeliveryScheduleXlsx(plan, it) }
        return file
    } catch (error: Exception) {
        file.delete()
        directory.delete()
        throw error
    }
}

internal fun existingExcelSnapshot(context: Context, path: String): File? {
    val root = excelCacheDirectory(context).canonicalPath + File.separator
    return File(path).takeIf { it.canonicalPath.startsWith(root) && it.isFile && it.extension == "xlsx" }
}

/** Used only after the document picker returns a destination URI; callers run this on IO. */
internal fun writeExcelSnapshotToDocument(context: Context, path: String, uri: Uri) {
    val file = existingExcelSnapshot(context, path)
        ?: throw IOException("Prepared workbook is unavailable")
    val output = context.contentResolver.openOutputStream(uri, "wt")
        ?: throw IOException("Document provider did not provide an output stream")
    // Both streams close before returning, including document-provider flush errors.
    output.use { destination -> file.inputStream().use { it.copyTo(destination) } }
}
