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
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** Uses OpenFreeMap vector tiles, not the OSM Foundation's blocked MAPNIK raster server.
 * This is the Route Map page only, and never changes built-in GPS navigation.
 */
private const val ROUTE_MAP_STYLE = "https://tiles.openfreemap.org/styles/dark"

@Composable
internal fun OnlineRouteMap(plan: Plan, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember(plan.id) { mutableStateOf<String?>(null) }
    val mapView = remember(context, plan.id) {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(mapView, lifecycle, plan.id) {
        var started = false
        var resumed = false
        fun syncLifecycle() {
            val start = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val resume = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed && !resume) { mapView.onPause(); resumed = false }
            if (started && !start) { mapView.onStop(); started = false }
            if (!started && start) { mapView.onStart(); started = true }
            if (!resumed && resume) { mapView.onResume(); resumed = true }
        }
        val observer = LifecycleEventObserver { _, _ -> syncLifecycle() }
        lifecycle.addObserver(observer)
        syncLifecycle()
        val failed = MapView.OnDidFailLoadingMapListener {
            error = "Online map unavailable. Retry with Internet or install the Singapore Offline Maps package in Settings."
        }
        mapView.addOnDidFailLoadingMapListener(failed)
        mapView.getMapAsync { map ->
            map.uiSettings.isAttributionEnabled = true
            map.uiSettings.isLogoEnabled = true
            map.setStyle(Style.Builder().fromUri(ROUTE_MAP_STYLE)) {
                try {
                    error = null
                    val validRoute = plan.geometry.mapNotNull { coord ->
                        if (coord.size >= 2 && coord[0].isFinite() && coord[1].isFinite())
                            LatLng(coord[1], coord[0]) else null
                    }
                    if (validRoute.size >= 2) {
                        map.addPolyline(
                            PolylineOptions().addAll(validRoute)
                                .color(android.graphics.Color.rgb(24, 104, 218))
                                .width(7f)
                        )
                    }
                    val iconFactory = IconFactory.getInstance(context)
                    plan.stops.forEachIndexed { i, stop ->
                        val markerIcon = numberedMapStopIcon(context.resources, i + 1).bitmap
                        map.addMarker(
                            MarkerOptions().position(LatLng(stop.place.lat, stop.place.lon))
                                .title("Stop " + (i + 1) + ": " + stop.place.postal)
                                .snippet(stop.place.address)
                                .icon(iconFactory.fromBitmap(markerIcon))
                        )
                    }
                    val start = plan.startLocation.asPlace()
                    val end = plan.endPlace
                    val points = plan.stops.map { LatLng(it.place.lat, it.place.lon) } +
                        LatLng(start.lat, start.lon) + LatLng(end.lat, end.lon)
                    if (!plan.endAtLastDelivery) {
                        map.addMarker(MarkerOptions().position(LatLng(end.lat, end.lon))
                            .title("END — " + plan.endDisplayName))
                    }
                    val bounds = LatLngBounds.Builder().includes(points).build()
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 70))
                } catch (_: Exception) {
                    error = "The route could not be displayed on the online map."
                }
            }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.removeOnDidFailLoadingMapListener(failed)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        error?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                color = SGColors.SolidCard
            ) {
                Text(message, Modifier.padding(12.dp), color = SGColors.White)
            }
        }
    }
}
