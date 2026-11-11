package com.izzyan.sgdeliveryplanner

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
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
    var traveledLine by remember { mutableStateOf<Polyline?>(null) }
    val routeProgress = remember { NavigationRouteProgressTracker() }
    val trafficPolylines = remember { mutableListOf<Polyline>() }
    // One camera director owns zoom/pitch transitions. MapLibre LocationComponent
    // owns interpolated heading/position, eliminating competing camera animators.
    val cameraDirector = remember { NavigationCameraDirector() }
    val visualTracker = remember { NavigationVisualTracker() }
    var lastPaintedProgress by remember { mutableDoubleStateOf(Double.NaN) }
    var mapHeightPixels by remember { mutableIntStateOf(0) }

    LaunchedEffect(session.route) {
        // Rerouting starts a fresh travelled/remaining division; map style changes do not.
        routeProgress.reset(session.shape)
        visualTracker.clearRouteMatch()
        cameraDirector.reset()
        lastPaintedProgress = Double.NaN
    }

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
                    // 3D uses an actual pre-rendered vehicle bitmap, not a tilted 2D arrow.
                    .gpsDrawable(if (session.threeDimensional)
                        R.drawable.ic_gps_car_3d_marker else R.drawable.ic_gps_vehicle_3d)
                    .foregroundDrawable(R.drawable.ic_gps_transparent)
                    .backgroundDrawable(R.drawable.ic_gps_transparent)
                    .bearingDrawable(R.drawable.ic_gps_transparent)
                    .foregroundDrawableStale(R.drawable.ic_gps_transparent)
                    .backgroundDrawableStale(R.drawable.ic_gps_transparent)
                    .accuracyAlpha(0f)
                    .pulseEnabled(false)
                    .elevation(0f)
                    .enableStaleState(false)
                    .minZoomIconScale(if (session.threeDimensional) 1.0f else 1.8f)
                    .maxZoomIconScale(if (session.threeDimensional) 1.0f else 1.8f).build())
                locationComponent.renderMode = RenderMode.GPS
                // Native location animation owns the per-frame interpolation;
                // actual refresh rate remains hardware and scheduler dependent.
                locationComponent.setMaxAnimationFps(60)
                cameraDirector.reset()
                lastPaintedProgress = Double.NaN
                styleReady = true
                session.mapMessage = null
            } catch (_: Exception) {
                session.mapMessage = "GPS display could not start. Try reopening navigation."
            }
        }
    }

    // Switching 2D/3D changes both the physical vehicle icon and the map pitch.
    // Reconfigure only the GPS drawable; do not reload the map or interrupt the route.
    LaunchedEffect(styleReady, session.threeDimensional) {
        val native = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val component = native.locationComponent
        val scale = if (session.threeDimensional) 1.0f else 1.8f
        component.applyStyle(component.locationComponentOptions.toBuilder()
            .gpsDrawable(if (session.threeDimensional)
                R.drawable.ic_gps_car_3d_marker else R.drawable.ic_gps_vehicle_3d)
            .minZoomIconScale(scale).maxZoomIconScale(scale).build())
        component.renderMode = RenderMode.GPS
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
    }

    // Route annotations are recreated only when geometry/style actually changes,
    // NOT every time a traffic band or GPS speed sample changes.
    LaunchedEffect(styleReady, session.route, nightMode) {
        val native = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        routeLine?.let { runCatching { native.removePolyline(it) } }
        traveledLine?.let { runCatching { native.removePolyline(it) } }
        routeLine = null
        traveledLine = null
        lastPaintedProgress = Double.NaN
        val frame = routeProgress.current()
        traveledLine = refreshRouteSection(native, null, frame?.traveled.orEmpty(),
            android.graphics.Color.rgb(128, 128, 128))
        routeLine = refreshRouteSection(native, null, frame?.remaining ?: session.shape,
            android.graphics.Color.rgb(22, 133, 255))
    }

    // Local, opt-in traffic overlays are updated separately. Keep the solid
    // route geometry in place during traffic changes, avoiding blue-line flicker.
    LaunchedEffect(styleReady, session.trafficState, session.localTrafficVisible,
        session.route, nightMode) {
        val native = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        trafficPolylines.forEach { runCatching { native.removePolyline(it) } }
        trafficPolylines.clear()
        if (session.localTrafficVisible) {
            for (band in session.trafficState.bands) {
                val routePoints = session.shape.drop(band.fromIndex)
                    .take(band.toIndex - band.fromIndex + 1)
                if (routePoints.size < 2) continue
                val color = when (band.level) {
                    LocalTrafficLevel.SMOOTH -> android.graphics.Color.argb(120, 34, 197, 94)
                    LocalTrafficLevel.SLOW -> android.graphics.Color.argb(120, 250, 204, 21)
                    LocalTrafficLevel.JAMMED -> android.graphics.Color.argb(120, 239, 68, 68)
                    LocalTrafficLevel.UNKNOWN -> continue
                }
                trafficPolylines += native.addPolyline(PolylineOptions()
                    .addAll(routePoints.map { LatLng(it.lat, it.lon) })
                    .color(color).width(15f))
            }
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

    // The displayed puck is gently snapped to a compatible route segment. Raw FusedLocation
    // fixes remain untouched in NavigationSession for off-route detection, ETA and arrival.
    // Updating when session.route changes also reprojects the existing fix on a fresh route.
    LaunchedEffect(styleReady, session.location, session.route) {
        val native = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val fix = session.location ?: return@LaunchedEffect
        val visual = visualTracker.update(
            GpsPoint(fix.latitude, fix.longitude), session.shape,
            fix.accuracy, fix.speed.takeIf { fix.hasSpeed() },
            fix.bearing.takeIf { fix.hasBearing() })
        val frame = routeProgress.advance(visual.routeMatch,
            fix.speed.takeIf { fix.hasSpeed() }, fix.elapsedRealtimeNanos / 1_000_000L)
        // Both puck and blue/grey route use the SAME geographic progress boundary.
        // If GPS is untrustworthy or off-route, retain the last valid route progress.
        val puckPosition = if (frame?.trustedMatch == true &&
            visual.routeMatch != null) frame.boundary else visual.position
        val displayed = Location(fix).apply {
            latitude = puckPosition.lat
            longitude = puckPosition.lon
            visual.bearingDegrees?.let { bearing = it }
        }
        native.locationComponent.forceLocationUpdate(displayed)
        // Keep the route split tied to the same trusted puck boundary, but
        // avoid rebuilding a long polyline for sub-meter GPS noise.
        if (frame != null && (lastPaintedProgress.isNaN() ||
            abs(frame.progressedMeters - lastPaintedProgress) >= 1.6)) {
            traveledLine = refreshRouteSection(native, traveledLine, frame.traveled,
                android.graphics.Color.rgb(128, 128, 128))
            routeLine = refreshRouteSection(native, routeLine, frame.remaining,
                android.graphics.Color.rgb(22, 133, 255))
            lastPaintedProgress = frame.progressedMeters
        }
        if (session.cameraMode == "follow" &&
            native.locationComponent.cameraMode == CameraMode.TRACKING_GPS) {
            val corner = NavigationCornerForecast.next(session.shape, visual.routeMatch)
            val command = cameraDirector.choose(
                nowMs = SystemClock.elapsedRealtime(),
                currentZoom = native.cameraPosition.zoom,
                currentPitch = native.cameraPosition.tilt,
                speedMps = fix.speed.takeIf { fix.hasSpeed() },
                turnMeters = session.turnMeters,
                instruction = session.instruction,
                remainingMeters = session.remainingMeters,
                corner = corner,
                threeDimensional = session.threeDimensional,
                manualZoom = session.zoomOverride
            )
            when {
                command?.zoom != null ->
                    native.locationComponent.zoomWhileTracking(command.zoom, command.durationMs)
                command?.pitch != null ->
                    native.locationComponent.tiltWhileTracking(command.pitch, command.durationMs)
            }
        }
    }

    LaunchedEffect(styleReady, session.cameraMode, session.cameraRequest,
        session.threeDimensional, session.fuelPreview, nightMode, mapHeightPixels) {
        val native = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val component = native.locationComponent
        when (session.cameraMode) {
            "follow" -> {
                val fix = session.location
                val zoom = session.zoomOverride ?: NavigationCameraPolicy.targetZoom(
                    fix?.speed?.takeIf { fix.hasSpeed() }, session.turnMeters,
                    session.instruction, session.remainingMeters)
                val desiredPitch = if (session.threeDimensional) 54.0 else 0.0
                val padding = NavigationDisplayTuning.trackingPadding(mapHeightPixels)
                val alreadyTracking = component.cameraMode == CameraMode.TRACKING_GPS
                // Do not restart tracking on every new route or GPS sample:
                // existing MapLibre native heading-up animations remain active.
                cameraDirector.reset(native.cameraPosition.zoom, native.cameraPosition.tilt)
                if (!alreadyTracking) {
                    component.setCameraMode(CameraMode.TRACKING_GPS, 750, zoom, null,
                        desiredPitch, object : OnLocationCameraTransitionListener {
                            override fun onLocationCameraTransitionFinished(cameraMode: Int) {
                                if (cameraMode == CameraMode.TRACKING_GPS &&
                                    session.cameraMode == "follow" && mapHeightPixels > 0)
                                    component.paddingWhileTracking(padding, 350)
                            }
                            override fun onLocationCameraTransitionCanceled(cameraMode: Int) = Unit
                        })
                } else {
                    if (mapHeightPixels > 0) component.paddingWhileTracking(padding, 350)
                    // Manual zoom wins until Recenter; do not compete with pitch
                    // animations by requesting both in the same frame.
                    if (session.zoomOverride != null &&
                        abs(native.cameraPosition.zoom - zoom) > 0.12)
                        component.zoomWhileTracking(zoom, 600)
                    else if (abs(native.cameraPosition.tilt - desiredPitch) > 2.0)
                        component.tiltWhileTracking(desiredPitch, 650)
                }
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


/** Update existing map annotations in place (no remove/add flicker for each GPS fix). */
private fun refreshRouteSection(
    map: MapLibreMap,
    existing: Polyline?,
    points: List<GpsPoint>,
    color: Int
): Polyline? {
    if (points.size < 2) {
        existing?.let { map.removePolyline(it) }
        return null
    }
    val latLngs = points.map { LatLng(it.lat, it.lon) }
    if (existing == null) return map.addPolyline(
        PolylineOptions().addAll(latLngs).color(color).width(9f))
    existing.points = latLngs
    map.updatePolyline(existing)
    return existing
}
