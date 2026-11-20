package com.izzyan.sgdeliveryplanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as NativeColor
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/** Non-exported DEBUG-only local renderer. No routing/GPS engine or network sources. */
class ErpMapVerificationActivity876:ComponentActivity() {
    internal lateinit var mapView:MapView
    internal var nativeMap:MapLibreMap? by mutableStateOf(null)
    internal var ready by mutableStateOf(false)
    internal lateinit var erpState:ErpNavigationState876
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        erpState=ErpNavigationState876(this)
        mapView=MapView(this,MapLibreMapOptions().textureMode(true)).apply{onCreate(savedInstanceState)}
        setContent {IzzDeliveryTheme(dark=true){
            Box(Modifier.fillMaxSize()){
                AndroidView(factory={mapView},modifier=Modifier.fillMaxSize())
                ErpMapIntegration876(nativeMap,ready,erpState)
                Text("OFFLINE NATIVE MAP TEST · ERP 106 / 101",Modifier.align(Alignment.TopCenter)
                    .background(Color(0xE6081B33)).padding(top=30.dp,start=8.dp,end=8.dp,bottom=8.dp),color=Color.White,fontSize=11.sp)
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xED081B33)).padding(12.dp)){
                    ErpSettingsPanel876(erpState)
                }
                ErpInformationCard876(erpState)
            }
        }}
        lifecycleScope.launch {erpState.load(this@ErpMapVerificationActivity876)}
        mapView.getMapAsync {map->
            nativeMap=map
            map.uiSettings.isAttributionEnabled=false
            map.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"local-test-background","type":"background","paint":{"background-color":"#dfe9e5"}}]}""")){style->
                val point=Point.fromLngLat(103.911592577984,1.3286531600046)
                style.addSource(GeoJsonSource("erp-test-arrow-source",Feature.fromGeometry(point)))
                val d=resources.displayMetrics.density
                val arrow=Bitmap.createBitmap((40*d).toInt(),(40*d).toInt(),Bitmap.Config.ARGB_8888).apply{density=resources.displayMetrics.densityDpi}
                val drawable=requireNotNull(ContextCompat.getDrawable(this,R.drawable.ic_gps_blue_arrow_3d))
                drawable.setBounds(0,0,arrow.width,arrow.height);drawable.draw(Canvas(arrow))
                style.addImage("erp-test-original-arrow",arrow,false)
                style.addLayer(SymbolLayer("mapbox-location-foreground-layer","erp-test-arrow-source").withProperties(
                    iconImage("erp-test-original-arrow"),iconAllowOverlap(true),iconIgnorePlacement(true),iconSize(1f)))
                ready=true
            }
        }
    }
    override fun onStart(){super.onStart();mapView.onStart()}
    override fun onResume(){super.onResume();mapView.onResume()}
    override fun onPause(){mapView.onPause();super.onPause()}
    override fun onStop(){mapView.onStop();super.onStop()}
    override fun onLowMemory(){super.onLowMemory();mapView.onLowMemory()}
    override fun onDestroy(){mapView.onDestroy();super.onDestroy()}
    override fun onSaveInstanceState(outState:Bundle){super.onSaveInstanceState(outState);mapView.onSaveInstanceState(outState)}
}
