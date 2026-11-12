package com.izzyan.sgdeliveryplanner

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
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** Online vector map; never touches the Singapore delivery map or active Plan. */
@Composable
internal fun FreeLocationMap(
    origin: StartLocation?, destination: StartLocation?, initial: StartLocation,
    choosingStart: Boolean, route: List<GpsPoint>, onChoose: (GpsPoint) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestChoose by rememberUpdatedState(onChoose)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val mapView = remember(context, reload) {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(mapView, lifecycle) {
        var started = false
        var resumed = false
        var disposed = false
        fun sync() {
            val start = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val resume = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed && !resume) { mapView.onPause(); resumed = false }
            if (started && !start) { mapView.onStop(); started = false }
            if (!started && start) { mapView.onStart(); started = true }
            if (!resumed && resume) { mapView.onResume(); resumed = true }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer)
        sync()
        val failed = MapView.OnDidFailLoadingMapListener { if (!disposed) error = true }
        mapView.addOnDidFailLoadingMapListener(failed)
        mapView.getMapAsync { ready ->
            if (!disposed) {
                ready.uiSettings.isAttributionEnabled = true
                ready.uiSettings.isLogoEnabled = true
                ready.addOnMapClickListener { point ->
                    latestChoose(GpsPoint(point.latitude, point.longitude)); true
                }
                ready.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/bright")) {
                    if (!disposed) {
                        ready.moveCamera(CameraUpdateFactory.newLatLngZoom(
                            LatLng(initial.latitude, initial.longitude), 12.0))
                        error = false; map = ready
                    }
                }
            }
        }
        onDispose {
            disposed = true; map = null
            lifecycle.removeObserver(observer)
            mapView.removeOnDidFailLoadingMapListener(failed)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(map, origin, destination, route, choosingStart) {
        val active = map ?: return@LaunchedEffect
        active.clear()
        val points = route.map { LatLng(it.lat, it.lon) }
        if (points.size >= 2) {
            active.addPolyline(PolylineOptions().addAll(points)
                .color(android.graphics.Color.rgb(24, 104, 218)).width(7f))
        }
        listOf("Start" to origin, "Destination" to destination).forEach { (title, location) ->
            location?.let {
                active.addMarker(MarkerOptions().position(LatLng(it.latitude, it.longitude))
                    .title(title + " — " + it.displayName))
            }
        }
        if (points.size >= 2) {
            runCatching { active.moveCamera(CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.Builder().includes(points).build(), 50)) }
        } else {
            val selected = if (choosingStart) origin else destination
            selected?.let { active.animateCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(it.latitude, it.longitude), 15.0)) }
        }
    }
    Box(Modifier.fillMaxWidth().height(340.dp)) {
        key(reload) { AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize()) }
        if (error) Surface(Modifier.align(Alignment.TopCenter).padding(10.dp), color = SGColors.SolidCard) {
            Column(Modifier.padding(10.dp)) {
                Text("Online map unavailable. Check internet or retry.")
                TextButton(onClick = { error = false; reload++ }) { Text("Retry map") }
            }
        }
    }
}
