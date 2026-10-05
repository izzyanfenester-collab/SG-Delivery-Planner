package com.izzyan.sgdeliveryplanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.annotations.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** The view is created once per screen; native rendering and lifecycle stay on the UI thread. */
@Composable
internal fun NavigationMap(session: NavigationSession, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var destinationMarker by remember { mutableStateOf<Marker?>(null) }
    var vehicleMarker by remember { mutableStateOf<Marker?>(null) }
    var routeLine by remember { mutableStateOf<Polyline?>(null) }
    DisposableEffect(view, lifecycle) {
        var started = false; var resumed = false
        fun sync() {
            val shouldStart = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val shouldResume = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed && !shouldResume) { view.onPause(); resumed = false }
            if (started && !shouldStart) { view.onStop(); started = false }
            if (!started && shouldStart) { view.onStart(); started = true }
            if (!resumed && shouldResume) { view.onResume(); resumed = true }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer); sync()
        val failure = MapView.OnDidFailLoadingMapListener { session.mapMessage = "Map unavailable. Check the connection and retry navigation." }
        view.addOnDidFailLoadingMapListener(failure)
        view.getMapAsync { native ->
            map = native
            native.uiSettings.isAttributionEnabled = true
            native.setStyle(Style.Builder().fromUri(NAVIGATION_STYLE)) {
                styleReady = true; session.mapMessage = null
            }
            native.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) session.cameraMode = "free"
            }
        }
        onDispose {
            lifecycle.removeObserver(observer); view.removeOnDidFailLoadingMapListener(failure)
            if (resumed) view.onPause()
            if (started) view.onStop()
            map = null; view.onDestroy()
        }
    }
    LaunchedEffect(styleReady, session.route, session.destination) {
        val native = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        destinationMarker?.let { native.removeMarker(it) }
        val dest = session.destination
        if (GpsPoint(dest.lat, dest.lon).valid()) {
            destinationMarker = native.addMarker(MarkerOptions().position(LatLng(dest.lat, dest.lon)).title("${dest.postal} • ${dest.address}"))
            if (session.location == null) native.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(dest.lat, dest.lon), 14.0))
        }
        routeLine?.let { native.removePolyline(it) }
        if (session.shape.size >= 2) {
            routeLine = native.addPolyline(PolylineOptions().addAll(session.shape.map { LatLng(it.lat, it.lon) }).color(android.graphics.Color.rgb(46, 204, 113)).width(6f))
        }
    }
    LaunchedEffect(styleReady, session.location, session.cameraMode, session.cameraRequest, session.route) {
        val native = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val fix = session.location
        if (fix != null) {
            val position = LatLng(fix.latitude, fix.longitude)
            if (vehicleMarker == null) {
                val edge = (32 * context.resources.displayMetrics.density).toInt()
                val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    paint.color = android.graphics.Color.WHITE; drawCircle(edge / 2f, edge / 2f, edge * .48f, paint)
                    paint.color = android.graphics.Color.rgb(36, 78, 166); drawCircle(edge / 2f, edge / 2f, edge * .36f, paint)
                }
                vehicleMarker = native.addMarker(MarkerOptions().position(position).icon(IconFactory.getInstance(context).fromBitmap(bitmap)).title("Current GPS position"))
            } else vehicleMarker?.position = position
            if (session.cameraMode == "follow") {
                native.moveCamera(CameraUpdateFactory.newLatLngZoom(position, 17.0))
            }
        }
        if (session.cameraMode == "overview" && session.shape.size >= 2) {
            native.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(session.shape.map { LatLng(it.lat, it.lon) }).build(), 40))
        }
    }
    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.matchParentSize())
        session.mapMessage?.let { error ->
            Surface(Modifier.align(Alignment.TopCenter).padding(8.dp), color = PremiumNavy) {
                Column(Modifier.padding(12.dp)) {
                    Text(error, color = PremiumGold)
                    TextButton(onClick = {
                        styleReady = false
                        map?.setStyle(Style.Builder().fromUri(NAVIGATION_STYLE)) { styleReady = true; session.mapMessage = null }
                    }) { Text("Retry map") }
                }
            }
        }
    }
}
