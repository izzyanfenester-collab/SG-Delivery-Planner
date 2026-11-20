package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class ErpNavigationState876(context:Context) {
    private val preferences=ErpPreferences876(context)
    private val gate=ErpStationaryGate876()
    var visible by mutableStateOf(preferences.visible());private set
    var catalog by mutableStateOf<ErpCatalog876?>(null);private set
    var loadError by mutableStateOf<String?>(null);private set
    var selected by mutableStateOf<ErpSite876?>(null);private set
    var stationary by mutableStateOf(false);private set
    fun setVisible(value:Boolean){visible=value;preferences.setVisible(value);if(!value)selected=null}
    suspend fun load(context:Context){
        try{catalog=withContext(Dispatchers.IO){ErpStore876(context).load()};loadError=null}
        catch(e:CancellationException){throw e}
        catch(e:Exception){loadError="ERP database unavailable";Log.e("SGErp876","ERP data load failed; no records silently skipped",e)}
    }
    fun observe(fix:Location?){
        fix?.let{gate.observe(ErpMotionFix876(GpsPoint(it.latitude,it.longitude),it.elapsedRealtimeNanos/1_000_000,
            it.accuracy,it.speed.takeIf{_->it.hasSpeed()},it.speedAccuracyMetersPerSecond.takeIf{_->it.hasSpeedAccuracy()}))}
        refreshStationary()
    }
    fun refreshStationary(){stationary=gate.allowed(SystemClock.elapsedRealtime());if(!stationary)selected=null}
    fun canInspect():Boolean=gate.allowed(SystemClock.elapsedRealtime())
    fun select(key:String):Boolean {
        if(!visible || !canInspect())return false
        selected=catalog?.byKey?.get(key);return selected!=null
    }
    fun dismiss(){selected=null}
}

@Composable
internal fun rememberErpNavigation876(fix:Location?):ErpNavigationState876 {
    val context=LocalContext.current
    val state=remember(context){ErpNavigationState876(context)}
    LaunchedEffect(state){state.load(context)}
    LaunchedEffect(state,fix){state.observe(fix)}
    LaunchedEffect(state){while(true){delay(1000);state.refreshStationary()}}
    return state
}

@Composable
internal fun ErpSettingsPanel876(state:ErpNavigationState876) {
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text("ERP Gantry Display",color=SGColors.Gold,fontSize=16.sp,fontWeight=FontWeight.Bold)
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
            Text("Show ERP Gantry Icons",Modifier.weight(1f),color=SGColors.White,fontSize=14.sp)
            Switch(checked=state.visible,onCheckedChange=state::setVisible)
        }
        state.catalog?.let{
            Text("ERP Database: ${it.records.size} records / ${it.sites.size} map locations",color=SGColors.Silver,fontSize=11.sp)
            Text("Dataset: ${it.version}",color=SGColors.Silver,fontSize=10.sp)
        }?:Text(state.loadError?:"Loading local ERP database…",color=SGColors.Silver,fontSize=11.sp)
    }
}

@Composable
internal fun ErpInformationCard876(state:ErpNavigationState876) {
    val site=state.selected?:return
    if(!state.stationary)return
    val context=LocalContext.current
    AlertDialog(onDismissRequest=state::dismiss,containerColor=PremiumNavy,
        title={Text("ERP GANTRY",color=SGColors.Gold,fontWeight=FontWeight.Bold)},
        text={Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(5.dp)){
            if(site.records.size>1)Text("${site.records.size} records share this coordinate",color=SGColors.Silver,fontSize=12.sp)
            Text("Latitude: ${site.latitudeText}",color=SGColors.White,fontSize=13.sp)
            Text("Longitude: ${site.longitudeText}",color=SGColors.White,fontSize=13.sp)
            site.records.forEach{record->
                HorizontalDivider(color=SGColors.Silver.copy(alpha=0.3f))
                Text("Unique ID: ${record.uniqueId}",color=SGColors.Gold,fontWeight=FontWeight.Bold)
                Text("Gantry No: ${record.gantryLabel}",color=SGColors.White)
                TextButton(onClick={
                    if(state.canInspect() && record.supportsMapsLink)runCatching{
                        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(record.googleMaps)))
                    }.onFailure{android.widget.Toast.makeText(context,"No supported map application available",android.widget.Toast.LENGTH_SHORT).show()}
                }){Text("View Location",color=SGColors.Gold)}
            }
            Text("Location reference only. Charges, hours and current operation were not supplied.",color=SGColors.Silver,fontSize=11.sp)
        }},
        confirmButton={TextButton(onClick=state::dismiss){Text("Done",color=SGColors.Gold)}})
}
