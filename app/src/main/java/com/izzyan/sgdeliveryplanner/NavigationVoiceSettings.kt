package com.izzyan.sgdeliveryplanner
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color

@Composable
internal fun NavigationVoiceSettings(session:NavigationSession) {
    DisposableEffect(session){onDispose{session.stopVoicePreview()}}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("Navigation Voice",color=SGColors.Gold,fontSize=16.sp,fontWeight=FontWeight.Bold)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_panda_warrior_voice),contentDescription="Panda Warrior",
                modifier=Modifier.padding(end=8.dp).size(32.dp))
            Column {
                Text("Panda Warrior – English",color=SGColors.Gold,fontSize=14.sp,fontWeight=FontWeight.Bold)
                Text(if(session.pandaAvailable) "Default Voice · Offline Ready" else "Audio pack unavailable · visual guidance only",
                    color=if(session.pandaAvailable)SGColors.Silver else SGColors.Warning,fontSize=12.sp)
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("Voice Guidance",Modifier.weight(1f),fontSize=14.sp,color=Color.White)
            Switch(!session.muted,{session.setSoundEnabled(it)},colors=SwitchDefaults.colors(checkedTrackColor=SGColors.Gold))
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text("Voice volume",Modifier.weight(1f),fontSize=14.sp,color=Color.White)
            Text("${(session.voiceVolume*100).toInt()}%",color=SGColors.Gold,fontSize=14.sp)
        }
        Slider(session.voiceVolume,{session.changeVoiceVolume(it)},colors=SliderDefaults.colors(thumbColor=SGColors.Gold,activeTrackColor=SGColors.Gold))
        OutlinedButton(onClick={if(session.previewingVoice)session.stopVoicePreview() else session.previewVoice()},
            enabled=session.pandaAvailable && !session.muted,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){
            Text(if(session.previewingVoice)"Stop Preview" else "Preview Panda Warrior",color=SGColors.Gold)
        }
        Text("Preview will not interrupt driving instructions.",fontSize=12.sp,color=SGColors.Silver)
    }
}
