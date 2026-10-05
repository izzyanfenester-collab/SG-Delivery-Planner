package com.izzyan.sgdeliveryplanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun TrafficCameraDialog(session: NavigationSession, onDismiss: () -> Unit) {
    var snapshot by remember { mutableStateOf<CameraSnapshot?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val images = remember { cameraHttpClient() }
    val position = session.location?.let { GpsPoint(it.latitude, it.longitude) }
    // Freeze the selection while reading; don't churn images on each GPS fix.
    val current = remember(retry) { position }
    val shape = remember(retry) { session.shape }
    LaunchedEffect(retry) { snapshot = null; snapshot = session.trafficCameras.snapshot() }
    val selected = remember(snapshot) { nearbyTrafficCameras(snapshot?.cameras.orEmpty(), current, shape) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = PremiumNavy, titleContentColor = PremiumGold,
        title = { Text("Traffic Cameras") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Singapore public traffic images · data.gov.sg", color = Color.White)
                if (snapshot == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (snapshot?.unavailable == true) Text("Traffic camera service unavailable. Navigation continues normally. Cached images may be older.", color = Color(0xFFFFD492))
                if (snapshot != null && selected.isEmpty()) Text("No nearby traffic cameras available.", color = Color.White)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(selected, key = { it.camera.id }) { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Camera ${item.camera.id} · ${metricDistance(item.meters)} from ${if (item.relativeToRoute) "route" else "current position"}", color = PremiumGold)
                            val updated = item.camera.timestamp?.atZone(ZoneId.of("Asia/Singapore"))?.format(DateTimeFormatter.ofPattern("d MMM, h:mm:ss a", Locale.ENGLISH))
                            Text("Updated: ${updated ?: "unavailable"} (SGT)", color = Color.White, style = MaterialTheme.typography.bodySmall)
                            TrafficCameraImage(item.camera, images)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { retry++ }) { Text("Refresh") } }
    )
}

@Composable
private fun TrafficCameraImage(camera: TrafficCamera, client: OkHttpClient) {
    var bitmap by remember(camera.imageUrl) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(camera.imageUrl) { mutableStateOf(true) }
    LaunchedEffect(camera.imageUrl) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(Request.Builder().url(camera.imageUrl).build()).awaitCameraResponse(4 * 1024 * 1024)
                if (response.code !in 200..299) return@withContext null
                // Decode at most 1024px per edge; only visible selected items load.
                val bytes = response.bytes
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        loading = false
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), "Traffic camera ${camera.id}", Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Fit)
    else Text(if (loading) "Loading image…" else "Image unavailable.", color = Color.White)
}
