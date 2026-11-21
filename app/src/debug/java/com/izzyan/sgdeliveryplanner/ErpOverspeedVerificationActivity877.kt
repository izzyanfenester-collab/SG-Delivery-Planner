package com.izzyan.sgdeliveryplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.*
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.RenderMode

/** Non-exported DEBUG acceptance fixture: original renderer and real session lookup/audio. */
class ErpOverspeedVerificationActivity877:ComponentActivity() {
    internal lateinit var mapView:MapView
    internal var nativeMap:MapLibreMap? by mutableStateOf(null)
    internal var ready by mutableStateOf(false)
    internal var styleVersion by mutableIntStateOf(0)
    internal lateinit var erp:ErpNavigationState876
    internal lateinit var session:NavigationSession
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        session=NavigationSession(this,Place("460011",1.4,103.85,"11","Acceptance fixture","Controlled GPS replay"))
        erp=ErpNavigationState876(this)
        mapView=MapView(this,MapLibreMapOptions().textureMode(true)).apply{onCreate(savedInstanceState)}
        setContent{IzzDeliveryTheme(dark=true){
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory={mapView},modifier=Modifier.fillMaxSize())
                ErpMapIntegration876(nativeMap,ready,erp,styleVersion)
                Text("CONTROLLED GPS REPLAY · ERP & OVERSPEED",Modifier.align(Alignment.TopCenter)
                    .background(Color(0xEE081B33)).padding(top=28.dp,start=5.dp,end=5.dp,bottom=5.dp),color=Color.White,fontSize=10.sp)
                Column(Modifier.align(Alignment.TopEnd).padding(top=65.dp,end=12.dp)
                    .background(Color(0xAA081B33)).padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    CurrentSpeedText877(session.location?.speed?:0f,session.overspeed877.red)
                }
                Box(Modifier.align(Alignment.BottomCenter).background(Color(0xEE081B33)).padding(8.dp)) {
                    Text(session.overspeed877.diagnostic,color=Color.White,fontSize=10.sp)
                }
                ErpInformationCard876(erp)
            }
        }}
        lifecycleScope.launch{erp.load(this@ErpOverspeedVerificationActivity877)}
        mapView.getMapAsync{nativeMap=it;it.uiSettings.isAttributionEnabled=false;reloadMap()}
    }
    internal fun reloadMap() {
        val map=nativeMap?:return
        ready=false
        map.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"local-background","type":"background","paint":{"background-color":"#dee7df"}}]}""")){style->
            val component=map.locationComponent
            if(!component.isLocationComponentActivated)component.activateLocationComponent(
                LocationComponentActivationOptions.builder(this,style).useDefaultLocationEngine(false).build())
            component.applyStyle(LocationComponentOptions.builder(this)
                .gpsDrawable(R.drawable.ic_gps_blue_arrow_3d)
                .foregroundDrawable(R.drawable.ic_gps_transparent).backgroundDrawable(R.drawable.ic_gps_transparent)
                .bearingDrawable(R.drawable.ic_gps_transparent).accuracyAlpha(0f).pulseEnabled(false)
                .enableStaleState(false).elevation(0f).minZoomIconScale(1f).maxZoomIconScale(1f).build())
            if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED)
                component.isLocationComponentEnabled=true
            component.renderMode=RenderMode.GPS
            styleVersion++;ready=true
        }
    }
    override fun onStart(){super.onStart();mapView.onStart()}
    override fun onResume(){super.onResume();mapView.onResume()}
    override fun onPause(){mapView.onPause();super.onPause()}
    override fun onStop(){mapView.onStop();super.onStop()}
    override fun onLowMemory(){super.onLowMemory();mapView.onLowMemory()}
    override fun onDestroy(){session.close();mapView.onDestroy();super.onDestroy()}
}
