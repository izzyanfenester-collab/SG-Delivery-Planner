package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun CurrentSpeedText877(speedMps:Float,red:Boolean) {
    Text((speedMps.coerceAtLeast(0f)*3.6f).roundToInt().toString(),
        modifier=Modifier.testTag("navigation-current-speed"),
        color=if(red)Color(0xFFE53935) else Color.White,fontSize=27.sp,fontWeight=FontWeight.Bold)
    Text("km/h",modifier=Modifier.testTag("navigation-speed-unit"),
        color=if(red)Color(0xFFE53935) else SGColors.Silver,fontSize=11.sp)
}
@Composable
internal fun OverspeedSettings877(controller:OverspeedNavigation877,muted:Boolean,
    update:(OverspeedOptions877)->Unit={controller.update(it)}) {
    val p=controller.options
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text("Overspeed Warning",color=SGColors.Gold,fontSize=16.sp,fontWeight=FontWeight.Bold)
        OverspeedSwitch877("Overspeed Warning",p.enabled){update(p.copy(enabled=it))}
        OverspeedSwitch877("Red Speed Font",p.redText){update(p.copy(redText=it))}
        OverspeedSwitch877("Panda Warrior Overspeed Voice",p.voice){update(p.copy(voice=it))}
        Text("GPS warning tolerance: ${p.toleranceKmh} km/h",color=SGColors.White,fontSize=12.sp)
        Slider(value=p.toleranceKmh.toFloat(),onValueChange={update(p.copy(toleranceKmh=it.roundToInt().coerceIn(1,5)))},
            valueRange=1f..5f,steps=3)
        TextButton(onClick={update(p.copy(cooldownSeconds=when(p.cooldownSeconds){90->120;120->180;else->90}))}) {
            Text("Voice Reminder Cooldown: ${p.cooldownSeconds} seconds",color=SGColors.Gold,fontSize=12.sp)
        }
        TextButton(onClick={controller.preview(muted)},enabled=controller.assetAvailable && !muted) {
            Text("Test Overspeed Voice: Preview",color=SGColors.Gold,fontSize=12.sp)
        }
        Text("Verified road limit required · 2-second confirmation",color=SGColors.Silver,fontSize=11.sp)
        Text(controller.diagnostic,color=SGColors.Gold,fontSize=10.sp)
        if(!controller.assetAvailable)Text("Authentic overspeed recording unavailable",color=SGColors.Warning,fontSize=11.sp)
    }
}
@Composable
private fun OverspeedSwitch877(name:String,checked:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=36.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(name,Modifier.weight(1f),color=SGColors.White,fontSize=12.sp)
        Switch(checked=checked,onCheckedChange=onChange)
    }
}
