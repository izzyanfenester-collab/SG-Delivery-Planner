package com.izzyan.sgdeliveryplanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.Locale

/** Safely export/restore archive using the Android document picker, never broad file permissions. */
@Composable
fun BackupRestoreActions(vm:PlannerViewModel) {
    var restoreConfirmation by remember { mutableStateOf(false) }
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null) vm.exportHistory(uri)
    }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) vm.restoreHistory(uri)
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={create.launch("SG-Delivery-History-"+LocalDate.now()+".zip")},
            enabled=!vm.busy,modifier=Modifier.weight(1f).heightIn(min=54.dp)) { Text("Backup All History") }
        OutlinedButton(onClick={restoreConfirmation=true},enabled=!vm.busy,
            modifier=Modifier.weight(1f).heightIn(min=54.dp)) { Text("Restore Backup") }
    }
    Text("Your backup ZIP includes route history, customer details, proof photos and Payment records. Keep it private; it is not encrypted.",
        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(vm.backupNotice.isNotBlank()) Text(vm.backupNotice,style=MaterialTheme.typography.bodyMedium)
    if(restoreConfirmation) AlertDialog(
        onDismissRequest={restoreConfirmation=false},
        title={Text("Restore SG Delivery Backup?")},
        text={Text("Restore routes, proof photos and Payment records from your backup ZIP. Existing routes and payments will be preserved without overwriting them. Older backup ZIPs are also supported.")},
        confirmButton={Button(onClick={
            restoreConfirmation=false
            open.launch(arrayOf("application/zip","application/x-zip-compressed","application/octet-stream"))
        }) { Text("Choose Backup") }},
        dismissButton={TextButton(onClick={restoreConfirmation=false}){Text("Cancel")}}
    )
}

/** Route history is displayed as a read-only snapshot; the active route stays untouched. */
@Composable
fun HistoryViewOnly(plan: Plan, onClose: () -> Unit) {
    var reportIndex by remember(plan.id) { mutableStateOf<Int?>(null) }
    var summaryReport by remember(plan.id) { mutableStateOf(false) }
    HistoryViewV6(
        plan = plan,
        onClose = onClose,
        onReport = { index -> reportIndex = index },
        onSummaryReport = { summaryReport = true }
    )
    reportIndex?.let { index -> HistoryReportImageDialog(plan, index) { reportIndex = null } }
    if (summaryReport) HistoryReportImageDialog(plan, null) { summaryReport = false }
}

@Composable
private fun ReadOnlyHistoryProof(id:String?,title:String) {
    val context=LocalContext.current
    var enlarge by remember(id) { mutableStateOf(false) }
    val small by produceState<Bitmap?>(initialValue=null,id) {
        value=withContext(Dispatchers.IO) {
            OrderProofStore(context).file(id)?.let { OrderProofStore(context).decode(it,400) }
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text(title,fontWeight=FontWeight.SemiBold)
        if(id==null) Text("Not uploaded",style=MaterialTheme.typography.bodySmall)
        else if(small==null) Text("Proof photo unavailable",style=MaterialTheme.typography.bodySmall)
        else {
            Image(small!!.asImageBitmap(),title,
                Modifier.fillMaxWidth().height(112.dp).clickable{enlarge=true},
                contentScale=ContentScale.Fit)
            TextButton(onClick={enlarge=true}){Text("View Photo")}
        }
    }
    if(enlarge) {
        val large by produceState<Bitmap?>(null,id) {
            value=withContext(Dispatchers.IO) {
                OrderProofStore(context).file(id)?.let { OrderProofStore(context).decode(it,2048) }
            }
        }
        Dialog(onDismissRequest={enlarge=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
            Column(Modifier.fillMaxSize().background(PremiumNavy).padding(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(title,Modifier.weight(1f),color=PremiumGold)
                    TextButton(onClick={enlarge=false}) {Text("Close")}
                }
                large?.let {Image(it.asImageBitmap(),title,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
                    ?: Text("Unable to load image.",color=Color.White)
            }
        }
    }
}

/** Both report previews are regenerated on demand using the saved stop and proof file IDs. */
@Composable
private fun HistoryReportImageDialog(plan:Plan,stopIndex:Int?,onClose:()->Unit) {
    val context=LocalContext.current
    var report by remember(plan.id,stopIndex) { mutableStateOf<File?>(null) }
    var bitmap by remember(plan.id,stopIndex) { mutableStateOf<Bitmap?>(null) }
    var error by remember(plan.id,stopIndex) { mutableStateOf(false) }
    LaunchedEffect(plan.id,stopIndex) {
        try {
            val result=withContext(Dispatchers.IO) {
                val file=if(stopIndex==null) generateSummaryReportImage(context,plan)
                    else generateSingleDeliveryReport(context,plan.stops[stopIndex],stopIndex)
                file to BitmapFactory.decodeFile(file.absolutePath)
            }
            report=result.first;bitmap=result.second
        } catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(_:Exception){error=true}
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.fillMaxSize().background(PremiumNavy).padding(12.dp)) {
            Text(if(stopIndex==null)"Delivery Report Summary" else "Delivery Report",
                color=PremiumGold,style=MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                bitmap?.let{Image(it.asImageBitmap(),"Historical report",Modifier.fillMaxWidth(),contentScale=ContentScale.FillWidth)}
                    ?: Text(if(error)"Could not generate old report." else "Preparing history report…",color=Color.White)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onClose,modifier=Modifier.weight(1f)){Text("Close")}
                Button(onClick={
                    report?.let { file ->
                        runCatching {
                            context.startActivity(Intent.createChooser(
                                singleDeliveryShareIntent(context,file),"Share Historical Report"))
                        }
                    }
                },enabled=report!=null,modifier=Modifier.weight(1f)){Text("Share Report")}
            }
        }
    }
}
