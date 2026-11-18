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
        CameraSafetyToggle("GPS Coordinate Camera Alerts (2KM)",p.coordinateCameraAlerts) {
            session.updateCameraPreferences(p.copy(coordinateCameraAlerts=it))
        }
        CameraSafetyToggle("Panda Warrior Camera Voice",p.voice) {
            session.updateCameraPreferences(p.copy(voice=it))
        }
        Text("Primary GPS camera warning: 2.0 KM ahead (route distance)",
            color=SGColors.Silver,fontSize=11.sp)
        CameraSafetyToggle("Secondary 500M Camera Reminder",p.coordinateReminder) {
            session.updateCameraPreferences(p.copy(coordinateReminder=it))
        }
        CameraSafetyToggle("Average Speed Zone Voice",p.averageVoice) {
            session.updateCameraPreferences(p.copy(averageVoice=it))
        }
        CameraSafetyToggle("Verified Overspeed Voice",p.verifiedOverspeedVoice) {
            session.updateCameraPreferences(p.copy(verifiedOverspeedVoice=it))
        }
        CameraSafetyToggle("Show Camera Icons",p.icons) {
            session.updateCameraPreferences(p.copy(icons=it))
        }
        CameraSafetyToggle("Verified Speed Limit Display",p.speedLimitDisplay) {
            session.updateCameraPreferences(p.copy(speedLimitDisplay=it))
        }
        Text("Speed Reference DB: ${session.speedLimitSourceCount868}/100 imported offline",
            color=SGColors.Silver,fontSize=11.sp)
        Text("91 provisional references · 9 unknown · 12 low-precision points",
            color=SGColors.Silver,fontSize=10.sp)
        Text("Live Speed Limit: ${session.speedLimitState868.diagnostics}",
            color=SGColors.Gold,fontSize=10.sp)
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
        Text("Live Voice Diagnosis: ${session.cameraVoiceDiagnostic}",
            color=SGColors.Gold,fontSize=11.sp)
        Text("${session.enforcementCameras.size} verified SPF published records",
            color=SGColors.Silver,fontSize=11.sp)
        Text("${session.coordinateCatalogMatchCount} matched speed enforcement reference points (100 expected)",
            color=SGColors.Silver,fontSize=11.sp)
        if(!session.cameraAudioAvailable) Text(
            "Camera MP3 package incomplete: voice alerts disabled; original 185-clip navigation unaffected.",
            color=SGColors.Warning,fontSize=11.sp)
        else Text("Panda Warrior V2 · 209 real MP3 clips available offline",
            color=SGColors.Silver,fontSize=11.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            TextButton(onClick={session.previewCameraVoice("speed_camera_ahead")},
                enabled=session.cameraAudioAvailable && !session.muted,
                modifier=Modifier.weight(1f)) {
                Text("Preview Speed",color=SGColors.Gold,fontSize=12.sp)
            }
            TextButton(onClick={session.previewCameraVoice("red_light_camera_ahead")},
                enabled=session.cameraAudioAvailable && !session.muted,
                modifier=Modifier.weight(1f)) {
                Text("Preview Red-Light",color=SGColors.Gold,fontSize=12.sp)
            }
        }
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
