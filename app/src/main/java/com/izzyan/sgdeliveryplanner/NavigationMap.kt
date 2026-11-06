package com.izzyan.sgdeliveryplanner

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.maplibre.android.annotations.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnLocationCameraTransitionListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

private const val NIGHT_MAP_STYLE = "https://tiles.openfreemap.org/styles/dark"

private fun nightInSingapore() = NavigationDisplayTuning.isNightSingapore(
    ZonedDateTime.now(ZoneId.of("Asia/Singapore")).hour)

/** GPS and camera animations happen in MapLibre's native location layer, not on Compose frames. */
@Composable
internal fun NavigationMap(session: NavigationSession, modifier: Modifier, locationPermitted: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var nightMode by remember { mutableStateOf(nightInSingapore()) }
    var styleRetry by remember { mutableIntStateOf(0) }
    var destinationMarker by remember { mutableStateOf<Marker?>(null) }
    var fuelMarker by remember { mutableStateOf<Marker?>(null) }
    var routeLine by remember { mutableStateOf<Polyline?>(null) }
    var lastBearing by remember { mutableStateOf<Float?>(null) }
    var lastZoom by remember { mutableDoubleStateOf(Double.NaN) }
    var mapHeightPixels by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nightMode = nightInSingapore()
        }
    }

    DisposableEffect(view, lifecycle) {
        var started = false
        var resumed = false
        fun sync() {
            val shouldStart = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val shouldResume = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed && !shouldResume) { view.onPause(); resumed = false }
            if (started && !shouldStart) { view.onStop(); started = false }
            if (!started && shouldStart) { view.onStart(); started = true }
            if (!resumed && shouldResume) { view.onResume(); resumed = true }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer)
        sync()
        val failure = MapView.OnDidFailLoadingMapListener {
            session.mapMessage = "Map unavailable. Check the connection and retry navigation."
        }
        view.addOnDidFailLoadingMapListener(failure)
        view.getMapAsync { native ->
            map = native
            native.uiSettings.isAttributionEnabled = true
            native.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE)
                    session.cameraMode = "free"
            }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            view.removeOnDidFailLoadingMapListener(failure)
            if (resumed) view.onPause()
            if (started) view.onStop()
            map = null
            view.onDestroy()
        }
    }

    LaunchedEffect(map, nightMode, styleRetry, locationPermitted) {
        val native = map ?: return@LaunchedEffect
        styleReady = false
        if (!locationPermitted) {
            session.mapMessage = "Allow precise location to use built-in GPS."
            return@LaunchedEffect
        }
        native.setStyle(Style.Builder().fromUri(if (nightMode) NIGHT_MAP_STYLE else NAVIGATION_STYLE)) { style ->
            try {
                val locationComponent = native.locationComponent
                locationComponent.activateLocationComponent(
                    LocationComponentActivationOptions.builder(context, style)
                        .useDefaultLocationEngine(false).build())
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED) {
                    locationComponent.isLocationComponentEnabled = true
                } else {
                    session.mapMessage = "Allow precise location to use built-in GPS."
                    return@setStyle
                }
                locationComponent.applyStyle(LocationComponentOptions.builder(context)
                    // Show ONLY the transparent, 3D Axia vehicle. MapLibre's other
                    // location-puck assets otherwise leave a white disk beneath it.
                    .gpsDrawable(R.drawable.ic_gps_axia_yellow_marker)
                    .foregroundDrawable(R.drawable.ic_gps_transparent)
                    .backgroundDrawable(R.drawable.ic_gps_transparent)
                    .bearingDrawable(R.drawable.ic_gps_transparent)
                    .foregroundDrawableStale(R.drawable.ic_gps_transparent)
                    .backgroundDrawableStale(R.drawable.ic_gps_transparent)
                    .accuracyAlpha(0f)
                    .pulseEnabled(false)
                    .elevation(0f)
                    .enableStaleState(false)
                    // Keep the established GPS icon size, bearing and camera behavior.
                    .minZoomIconScale(1.8f).maxZoomIconScale(1.8f).build())
                locationComponent.renderMode = RenderMode.GPS
                locationComponent.setMaxAnimationFps(60)
                lastZoom = Double.NaN
                styleReady = true
                session.mapMessage = null
            } catch (_: Exception) {
                session.mapMessage = "GPS display could not start. Try reopening navigation."
            }
        }
    }

    LaunchedEffect(styleReady, session.route, session.destination, nightMode) {
        val native = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        destinationMarker?.let { runCatching { native.removeMarker(it) } }
        destinationMarker = null
        val dest = session.destination
        if (GpsPoint(dest.lat, dest.lon).valid()) {
            destinationMarker = native.addMarker(MarkerOptions().position(LatLng(dest.lat, dest.lon))
                .title("${dest.postal} • ${dest.address}"))
            if (session.location == null) native.moveCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(dest.lat, dest.lon), 14.0))
        }
        routeLine?.let { runCatching { native.removePolyline(it) } }
        routeLine = null
        if (session.shape.size >= 2) {
            routeLine = native.addPolyline(PolylineOptions()
                .addAll(session.shape.map { LatLng(it.lat, it.lon) })
                .color(android.graphics.Color.rgb(0, 190, 245)).width(9f))
        }
    }

    LaunchedEffect(styleReady, session.fuelPreview, nightMode) {
        val native = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        fuelMarker?.let { runCatching { native.removeMarker(it) } }
        fuelMarker = null
        session.fuelPreview?.let { station ->
            fuelMarker = native.addMarker(MarkerOptions()
                .position(LatLng(station.point.lat, station.point.lon))
                .title("Petrol: ${station.name}"))
        }
    }

    // Feed raw, accurate GPS positions to MapLibre's interpolating vehicle puck.
    // Only the DISPLAY bearing is filtered; routing, progress and arrival use original fixes.
    LaunchedEffect(styleReady, session.location) {
        val native = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val fix = session.location ?: return@LaunchedEffect
        val displayed = Location(fix)
        lastBearing = NavigationDisplayTuning.smoothBearing(
            lastBearing, fix.bearing.takeIf { fix.hasBearing() }, fix.speed.takeIf { fix.hasSpeed() })
        lastBearing?.let { displayed.bearing = it }
        native.locationComponent.forceLocationUpdate(displayed)
        if (session.cameraMode == "follow") {
            val zoom = session.zoomOverride ?:
                NavigationDisplayTuning.zoomForSpeed(fix.speed.takeIf { fix.hasSpeed() })
            if (lastZoom.isNaN() || abs(lastZoom - zoom) >= 0.4) {
                native.locationComponent.zoomWhileTracking(zoom, 900)
                lastZoom = zoom
            }
        }
    }

    LaunchedEffect(styleReady, session.cameraMode, session.cameraRequest, session.route,
        session.zoomOverride, session.fuelPreview, nightMode, mapHeightPixels) {
        val native = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val component = native.locationComponent
        when (session.cameraMode) {
            "follow" -> {
                val fix = session.location
                val zoom = session.zoomOverride ?:
                    NavigationDisplayTuning.zoomForSpeed(fix?.speed?.takeIf { fix.hasSpeed() })
                val padding = NavigationDisplayTuning.trackingPadding(mapHeightPixels)
                val alreadyTracking = component.cameraMode == CameraMode.TRACKING_GPS
                component.setCameraMode(CameraMode.TRACKING_GPS, 850, zoom, null, 54.0,
                    object : OnLocationCameraTransitionListener {
                        override fun onLocationCameraTransitionFinished(cameraMode: Int) {
                            if (cameraMode == CameraMode.TRACKING_GPS && session.cameraMode == "follow" &&
                                mapHeightPixels > 0) component.paddingWhileTracking(padding, 350)
                        }
                        override fun onLocationCameraTransitionCanceled(cameraMode: Int) = Unit
                    })
                if (alreadyTracking && mapHeightPixels > 0)
                    component.paddingWhileTracking(padding, 350)
                lastZoom = zoom
            }
            "overview" -> {
                component.cameraMode = CameraMode.NONE
                if (session.shape.size >= 2) native.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        LatLngBounds.Builder().includes(session.shape.map { LatLng(it.lat, it.lon) }).build(), 60), 700)
            }
            "fuel" -> {
                component.cameraMode = CameraMode.NONE
                session.fuelPreview?.let { station ->
                    native.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        LatLng(station.point.lat, station.point.lon), 17.8), 700)
                }
            }
            else -> component.cameraMode = CameraMode.NONE
        }
    }

    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.matchParentSize()
            .onSizeChanged { mapHeightPixels = it.height })
        session.mapMessage?.let { error ->
            Surface(Modifier.align(Alignment.Center).padding(12.dp), color = PremiumNavy) {
                Column(Modifier.padding(12.dp)) {
                    Text(error, color = PremiumGold)
                    TextButton(onClick = { styleRetry++ }) { Text("Retry map") }
                }
            }
        }
    }
}
