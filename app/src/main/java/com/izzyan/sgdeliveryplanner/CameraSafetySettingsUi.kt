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
        Text("All camera categories: 2.0 KM ahead (route distance)",
            color=SGColors.Silver,fontSize=11.sp)
        CameraSafetyToggle("Secondary 500M Camera Reminder",p.coordinateReminder) {
            session.updateCameraPreferences(p.copy(coordinateReminder=it))
        }
        CameraSafetyToggle("Average Speed Zone Voice",p.averageVoice) {
            session.updateCameraPreferences(p.copy(averageVoice=it))
        }
        CameraSafetyToggle("Show Camera Icons",p.icons) {
            session.updateCameraPreferences(p.copy(icons=it))
        }
        CameraSafetyToggle("Road Speed Limit Display",p.speedLimitDisplay) {
            session.updateCameraPreferences(p.copy(speedLimitDisplay=it))
        }
        Text("Road catalog: ${session.roadSpeedCatalogInfo875}",
            color=SGColors.Silver,fontSize=11.sp)
        Text("Legacy camera references retained: ${session.speedLimitSourceCount868}/100. Not road limits.",
            color=SGColors.Silver,fontSize=10.sp)
        Text("Live Speed Limit: ${session.roadSpeedLimitState875.diagnostics}",
            color=SGColors.Gold,fontSize=10.sp)
        Text("Overspeed Warning",color=SGColors.Gold,fontSize=14.sp,fontWeight=FontWeight.Bold)
        CameraSafetyToggle("Overspeed Warning",p.overspeed) {
            session.updateCameraPreferences(p.copy(overspeed=it))
        }
        CameraSafetyToggle("Red Speed Font",session.redSpeedFontEnabled878) {
            session.setRedSpeedFont878(it)
        }
        CameraSafetyToggle("Panda Warrior Overspeed Voice",p.verifiedOverspeedVoice) {
            session.updateCameraPreferences(p.copy(verifiedOverspeedVoice=it))
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Voice Cooldown",Modifier.weight(1f),color=SGColors.Silver,fontSize=12.sp)
            listOf(90,120,180).forEach { seconds ->
                TextButton(onClick={session.setOverspeedCooldown878(seconds)}) {
                    Text("${seconds}s",
                        color=if(session.overspeedVoiceCooldownSeconds878==seconds)SGColors.Gold else SGColors.Silver,
                        fontSize=11.sp)
                }
            }
        }
        TextButton(onClick={session.previewCameraVoice("overspeed_warning")},
            enabled=session.cameraAudioAvailable && !session.muted) {
            Text("Test Panda Warrior Overspeed Voice",color=SGColors.Gold,fontSize=12.sp)
        }
        Text("Red font: confirmed current road with OFFICIAL or validated MAP DATA speed limit. Voice: OFFICIAL only, +3 km/h for 2s. Unknown: no false warning.",
            color=SGColors.Silver,fontSize=10.sp)
        Text("Live Overspeed: ${session.overspeedDiagnostic879}",
            color=if(session.overspeedVisualState879.red)androidx.compose.ui.graphics.Color(0xFFFF3030)
                else SGColors.Silver,fontSize=10.sp)
        if(!p.overspeed || !session.redSpeedFontEnabled878) Text(
            "Visual overspeed warning disabled in saved Settings.",
            color=SGColors.Warning,fontSize=10.sp)
        Text("Speed & red-light sources: Singapore Police Force",
            color=SGColors.Silver,fontSize=11.sp)
        Text("Road limits: LTA/statute, validated OSM (ODbL), or applicable SG general rule. Unknown: CHECK SIGNS.",
            color=SGColors.Silver,fontSize=11.sp)
        Text("Snapshot: ${session.cameraDatabaseVersion}",
            color=SGColors.Silver,fontSize=11.sp)
        Text("Last successful update: ${session.cameraLastUpdated}",
            color=SGColors.Silver,fontSize=11.sp)
        Text("Live Voice Diagnosis: ${session.cameraVoiceDiagnostic}",
            color=SGColors.Gold,fontSize=11.sp)
        Text("${session.enforcementCameras.size} verified SPF published records",
            color=SGColors.Silver,fontSize=11.sp)
        val verifiedZoneBoundaries=session.enforcementCameras.count{it.verifiedZoneBoundary}
        if(verifiedZoneBoundaries==0) Text(
            "Average-speed entry/exit voice awaits verified zone boundaries. Published camera points remain visible.",
            color=SGColors.Warning,fontSize=11.sp)
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
