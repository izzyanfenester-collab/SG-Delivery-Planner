package com.izzyan.sgdeliveryplanner

import android.graphics.Color as NativeColor
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/** Offline overview renderer for the existing saved road geometry and numbered stops.
 * Online overview stays unchanged, and no OSMDroid remote tile request occurs here.
 */
@Composable
internal fun OfflineRouteOverview(plan: Plan) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val localStyle = remember(context) { singaporeOfflineMapStyle(context, true) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val view = remember { MapLibre.getInstance(context); MapView(context).apply { onCreate(null) } }
    DisposableEffect(view, lifecycle) {
        var started = false; var resumed = false
        fun sync() {
            val start = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val resume = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed && !resume) { view.onPause(); resumed = false }
            if (started && !start) { view.onStop(); started = false }
            if (!started && start) { view.onStart(); started = true }
            if (!resumed && resume) { view.onResume(); resumed = true }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer); sync()
        view.getMapAsync { map = it }
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) view.onPause()
            if (started) view.onStop()
            view.onDestroy()
        }
    }
    LaunchedEffect(map, localStyle) {
        val native = map ?: return@LaunchedEffect
        if (localStyle == null) { error = "Offline map package is not installed."; return@LaunchedEffect }
        native.setStyle(Style.Builder().fromJson(localStyle)) {
            styleReady = true
            error = ""
        }
    }
    LaunchedEffect(map, styleReady, plan.id, plan.current, plan.stops.size) {
        val native = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        native.clear()
        if (plan.geometry.size > 1) {
            native.addPolyline(PolylineOptions()
                .addAll(plan.geometry.map { LatLng(it[1], it[0]) })
                .color(NativeColor.rgb(22, 133, 255)).width(6f))
        }
        val icons = IconFactory.getInstance(context)
        plan.stops.forEachIndexed { index, stop ->
            val bitmap = numberedMapStopIcon(context.resources, index + 1).bitmap
            native.addMarker(
                MarkerOptions().position(LatLng(stop.place.lat, stop.place.lon))
                    .title("Stop ${index + 1}: ${stop.place.postal}")
                    .snippet(stop.place.address)
                    .icon(icons.fromBitmap(bitmap))
            )
        }
        val landmarks = mutableListOf(
            LatLng(plan.startLocation.latitude, plan.startLocation.longitude),
            LatLng(plan.endPlace.lat, plan.endPlace.lon))
        plan.stops.forEach { landmarks.add(LatLng(it.place.lat, it.place.lon)) }
        val bounds = LatLngBounds.Builder().apply { landmarks.forEach(::include) }.build()
        view.post { runCatching { native.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 75)) } }
    }
    Column(Modifier.fillMaxSize()) {
        Surface(color = SGColors.Background) {
            Text("OFFLINE — Local Singapore map & saved road route · © OpenStreetMap contributors",
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                color = SGColors.Silver, style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            if (error.isNotBlank()) {
                Surface(Modifier.align(Alignment.TopCenter).padding(10.dp),
                    color = SGColors.Card) {
                    Text(error, Modifier.padding(10.dp), color = SGColors.Warning)
                }
            }
        }
    }
}
