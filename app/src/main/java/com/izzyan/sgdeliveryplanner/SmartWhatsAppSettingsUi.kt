package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.util.UUID

@Composable
internal fun SmartWhatsAppSettings(vm: PlannerViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember(context) { SmartSharePreferences(context) }
    var enabled by remember(prefs) { mutableStateOf(prefs.enabled) }
    var autoReturn by remember(prefs) { mutableStateOf(prefs.autoReturn) }
    var permission by remember { mutableStateOf(smartAccessibilityEnabled(context)) }
    var report by remember(prefs) { mutableStateOf(prefs.group(SmartShareKind.REPORT)) }
    var route by remember(prefs) { mutableStateOf(prefs.group(SmartShareKind.ROUTE)) }
    var editing by remember { mutableStateOf<SmartShareKind?>(null) }
    var fallback by remember { mutableStateOf<WhatsAppImageShareFallbackRequest?>(null) }
    val choice = vm.whatsappPreferences.default
    DisposableEffect(lifecycle,context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = smartAccessibilityEnabled(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    CompactSettingsSection("WhatsApp Auto Send", if (enabled) "ON · Review & Send required" else "OFF", Icons.Outlined.Chat) {
        CompactSettingsMetric("Accessibility Service", if (permission && SmartShareSession.connected) "Enabled" else "Disabled")
        CompactSettingsMetric("Default WhatsApp", choice?.label ?: "Ask every time")
        CompactSettingsDetail("Experimental: prepares an exact group search in the WhatsApp share picker. WhatsApp does not expose a reliable group ID or attachment identity; you must verify the group and image and press Send. No chats are collected or read in the background.")
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Enable Auto Send Setup") }
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("Auto Send Report & Route Photo",Modifier.weight(1f),fontSize=14.sp)
            Switch(enabled,{ enabled=it; prefs.enabled=it; if (!it) SmartShareSession.cancel() })
        }
        GroupSettingRow("Delivery Report Group",report,choice,{ editing=SmartShareKind.REPORT }) {
            prefs.remove(SmartShareKind.REPORT); report=null
        }
        GroupSettingRow("Route Photo Group",route,choice,{ editing=SmartShareKind.ROUTE }) {
            prefs.remove(SmartShareKind.ROUTE); route=null
        }
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("Auto Return to SG Delivery",Modifier.weight(1f),fontSize=14.sp)
            Switch(autoReturn,{ autoReturn=it; prefs.autoReturn=it })
        }
        CompactSettingsDetail("Auto Return is saved for compatible verified-send adapters. In this testing build, return manually using Android Back or Recent Apps after sending.")
        CompactSettingsDetail("Use a dedicated test group. Test image contains no customer or delivery information.")
        listOf(SmartShareKind.REPORT,SmartShareKind.ROUTE).forEach { kind ->
            OutlinedButton(onClick={
                try {
                    val image=sampleSmartShareImage(context,kind)
                    fallback=openSmartWhatsAppShare(context,singleDeliveryShareIntent(context,image),kind)
                } catch (_: Exception) { Toast.makeText(context,"Could not prepare the sample image. Try again.",Toast.LENGTH_LONG).show() }
            },enabled=enabled && prefs.eligibleGroup(kind,choice)!=null && !SmartShareSession.active,
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Text(if(kind==SmartShareKind.REPORT) "Test Report Group" else "Test Route Group")
            }
        }
        SmartShareStatus(SmartShareKind.REPORT)
        SmartShareStatus(SmartShareKind.ROUTE)
    }
    editing?.let { kind ->
        GroupConfigurationDialog(kind,prefs.group(kind),choice,onDismiss={ editing=null }) { name, selected ->
            prefs.saveVerified(kind,name,selected)
            report=prefs.group(SmartShareKind.REPORT); route=prefs.group(SmartShareKind.ROUTE); editing=null
        }
    }
    fallback?.let { WhatsAppImageShareFallbackDialog(it) { fallback=null } }
}

@Composable
private fun GroupSettingRow(label:String, group:SmartGroup?, choice:WhatsAppChoice?, edit:()->Unit, remove:()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(label,fontSize=14.sp,color=SGColors.White)
        Text(group?.name ?: "Not configured",fontSize=14.sp,color=SGColors.SelectedGold)
        if (group != null) CompactSettingsDetail(if(group.packageName==choice?.packageName) "User-verified name · identity requires review before Send" else "Different WhatsApp app · verify again")
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick=edit,modifier=Modifier.weight(1f).heightIn(min=48.dp)) { Text("Configure") }
            if(group!=null) OutlinedButton(onClick=remove,modifier=Modifier.weight(1f).heightIn(min=48.dp)) { Text("Remove") }
        }
    }
}
@Composable
private fun GroupConfigurationDialog(kind:SmartShareKind, group:SmartGroup?, choice:WhatsAppChoice?,
    onDismiss:()->Unit, save:(String,WhatsAppChoice)->Unit) {
    val context=LocalContext.current
    var name by remember(kind,choice) { mutableStateOf(group?.name.orEmpty()) }
    var opened by remember(kind,choice) { mutableStateOf(false) }
    var confirmed by remember(kind,choice) { mutableStateOf(false) }
    val valid=name.isNotBlank() && name.length<=100 && name.none{it.isISOControl()}
    AlertDialog(onDismissRequest=onDismiss,title={ Text("Verify WhatsApp group") },text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(choice?.label ?: "Select a default WhatsApp app first in Settings.")
            OutlinedTextField(name,{name=it; opened=false; confirmed=false},label={Text("Exact group name")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Text("Open WhatsApp and check the exact group name and membership. If another group has the same name, do not use this configuration.",fontSize=13.sp)
            OutlinedButton(onClick={
                val target=choice?.let { context.packageManager.getLaunchIntentForPackage(it.packageName) }
                if(target==null) Toast.makeText(context,"Selected WhatsApp is unavailable.",Toast.LENGTH_LONG).show()
                else try { context.startActivity(target); opened=true } catch(_:Exception) { Toast.makeText(context,"Could not open WhatsApp.",Toast.LENGTH_LONG).show() }
            },enabled=choice!=null && valid,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Open WhatsApp to verify") }
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(confirmed,{confirmed=it},enabled=opened)
                Text("I verified this exact group in the selected WhatsApp app.",fontSize=13.sp)
            }
        }
    },confirmButton={TextButton(onClick={if(choice!=null && valid && confirmed) save(name,choice)},enabled=choice!=null && valid && confirmed){Text("Save verified name")}},
        dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
@Composable
internal fun SmartShareStatus(kind:SmartShareKind) {
    if(SmartShareSession.lastKind==kind && SmartShareSession.status.isNotBlank()) {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(SmartShareSession.status,fontSize=13.sp,color=SGColors.SelectedGold)
            if(SmartShareSession.active) {
                LinearProgressIndicator(Modifier.fillMaxWidth(),color=SGColors.Gold)
                TextButton(onClick=SmartShareSession::cancel,modifier=Modifier.heightIn(min=48.dp)) { Text("Cancel Auto Send") }
            }
        }
    }
}

private fun sampleSmartShareImage(context:Context,kind:SmartShareKind):File {
    val dir=File(context.cacheDir,"delivery_reports").apply{mkdirs()}
    val file=File(dir,"smart_share_sample_"+UUID.randomUUID()+".png")
    val bitmap=Bitmap.createBitmap(800,420,Bitmap.Config.ARGB_8888)
    try {
        val canvas=Canvas(bitmap);canvas.drawColor(0xFF081B33.toInt())
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xFFD4AF37.toInt();textSize=44f}
        canvas.drawText("SG DELIVERY · SAMPLE",32f,95f,paint)
        paint.textSize=28f;paint.color=android.graphics.Color.WHITE
        canvas.drawText(if(kind==SmartShareKind.REPORT)"Delivery Report test image" else "Route Photo test image",32f,170f,paint)
        canvas.drawText("No customer data. Test group only.",32f,235f,paint)
        file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        return file
    } finally { bitmap.recycle() }
}
