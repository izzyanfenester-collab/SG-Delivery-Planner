package com.izzyan.sgdeliveryplanner

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Compact camera controls live inside existing Built-in GPS settings only. */
@Composable
internal fun CameraSafetySettingsPanel(session:NavigationSession) {
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val p=session.cameraPreferences
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text("Camera & Road Safety Alerts",color=SGColors.Gold,
            fontSize=16.sp,fontWeight=FontWeight.Bold)
        CameraSafetyToggle("Master Camera Alerts",p.master) {
            session.updateCameraPreferences(p.copy(master=it))
        }
        CameraSafetyToggle("Speed Camera Alerts",p.speed) {
            session.updateCameraPreferences(p.copy(speed=it))
        }
        CameraSafetyToggle("Red-Light Camera Alerts",p.redLight) {
            session.updateCameraPreferences(p.copy(redLight=it))
        }
        CameraSafetyToggle("Mobile / Laser Location Alerts",p.mobile) {
            session.updateCameraPreferences(p.copy(mobile=it))
        }
        CameraSafetyToggle("Panda Warrior Camera Voice",p.voice) {
            session.updateCameraPreferences(p.copy(voice=it))
        }
        CameraSafetyToggle("Show Camera Icons",p.icons) {
            session.updateCameraPreferences(p.copy(icons=it))
        }
        CameraSafetyToggle("Verified Speed Limit Display",p.speedLimitDisplay) {
            session.updateCameraPreferences(p.copy(speedLimitDisplay=it))
        }
        CameraSafetyToggle("Verified Overspeed Warning",p.overspeed) {
            session.updateCameraPreferences(p.copy(overspeed=it))
        }
        Text("Speed & red-light sources: Singapore Police Force",
            color=SGColors.Silver,fontSize=11.sp)
        Text("Road speed limits: official verified source only. Unknown values remain blank.",
            color=SGColors.Silver,fontSize=11.sp)
        Text("Snapshot: ${session.cameraDatabaseVersion}",
            color=SGColors.Silver,fontSize=11.sp)
        Text("Last successful update: ${session.cameraLastUpdated}",
            color=SGColors.Silver,fontSize=11.sp)
        Text("${session.enforcementCameras.size} verified SPF published records",
            color=SGColors.Silver,fontSize=11.sp)
        if(!session.cameraAudioAvailable) Text(
            "Authentic Panda Warrior camera audio not provided: visual alerts only. Original navigation voice remains available.",
            color=SGColors.Warning,fontSize=11.sp)
        session.cameraRefreshMessage?.let { Text(it,color=SGColors.Silver,fontSize=11.sp) }
        TextButton(onClick={scope.launch { session.refreshCameraSnapshot() }},
            enabled=!session.cameraRefreshBusy) {
            Text(if(session.cameraRefreshBusy)"Checking camera data…" else "Check for Camera Updates",
                color=SGColors.Gold)
        }
        listOf(
            "View Official Speed Camera Source" to
                "https://www.police.gov.sg/Knowledge-Hub/Traffic/Traffic-Matters/Speed-Enforcement-Camera-Locations",
            "View Official Red-Light Camera Source" to
                "https://www.police.gov.sg/knowledge-hub/traffic/traffic-matters/red-light-camera-locations",
            "View Road Speed Limit Source" to
                "https://www.lta.gov.sg/content/ltagov/en/getting_around/driving_in_singapore/driving_rules_and_regulations.html"
        ).forEach { (name,url) ->
            TextButton(onClick={context.startActivity(
                Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )}) { Text(name,color=SGColors.Gold,fontSize=11.5.sp) }
        }
    }
}
@Composable
private fun CameraSafetyToggle(name:String,checked:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=36.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(name,Modifier.weight(1f),color=SGColors.White,fontSize=12.sp)
        Switch(checked=checked,onCheckedChange=onChange)
    }
}
