package com.izzyan.sgdeliveryplanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.Locale

@SuppressLint("MissingPermission")
private suspend fun currentRouteLocation(context:android.content.Context):android.location.Location =
    withTimeout(18000L) {
        suspendCancellableCoroutine { cont ->
            val cancel=CancellationTokenSource()
            cont.invokeOnCancellation { cancel.cancel() }
            try {
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY,cancel.token)
                    .addOnSuccessListener { fix ->
                        if(cont.isActive) {
                            if(fix==null || !fix.hasAccuracy() || fix.accuracy>100f ||
                                SystemClock.elapsedRealtime()-fix.elapsedRealtimeNanos/1000000L>30000L)
                                cont.resumeWithException(IllegalStateException("GPS unavailable or inaccurate. Move to an open area and retry."))
                            else cont.resume(fix)
                        }
                    }.addOnFailureListener { if(cont.isActive)cont.resumeWithException(it) }
                    .addOnCanceledListener { if(cont.isActive)cont.resumeWithException(IllegalStateException("GPS request cancelled.")) }
            } catch(e:Exception) { if(cont.isActive)cont.resumeWithException(e) }
        }
    }

@Composable
internal fun CoordinateLocationCard(vm:PlannerViewModel,initial:StartLocation?,onConfirm:(StartLocation)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    fun format(v:Double)=String.format(Locale.US,"%.6f",v)
    var lat by remember { mutableStateOf(initial?.latitude?.let(::format).orEmpty()) }
    var lon by remember { mutableStateOf(initial?.longitude?.let(::format).orEmpty()) }
    var name by remember { mutableStateOf(if(initial?.type=="HOME")"Home" else "Custom Location") }
    var accuracy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var replaceHome by remember { mutableStateOf(false) }
    fun candidate():StartLocation? {
        val p=coordinateLocation(lat,lon)
        if(p==null){error="Enter valid Latitude (-90 to 90) and Longitude (-180 to 180).";return null}
        if(!supportedPlanningCoordinates(p.latitude,p.longitude)){error="Location outside Singapore/Malaysia routing coverage.";return null}
        return if(name=="Home")p.copy(type="HOME",label="Home",address=p.label) else p
    }
    fun fill(point:StartLocation) {
        lat=format(point.latitude);lon=format(point.longitude)
        name=if(point.type=="HOME")"Home" else "Custom Location";accuracy="";error=""
    }
    fun lookup(){
        if(loading)return
        loading=true;error=""
        scope.launch {
            try {
                val f=currentRouteLocation(context)
                lat=format(f.latitude);lon=format(f.longitude);name="Custom Location"
                accuracy="GPS accuracy: ±"+f.accuracy.toInt()+" m"
            } catch(e:kotlinx.coroutines.TimeoutCancellationException){error="GPS timed out. Enable location and try again."}
            catch(e:CancellationException){throw e}
            catch(e:Exception){error=e.message ?: "Unable to obtain current location."}
            finally {loading=false}
        }
    }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if(granted[Manifest.permission.ACCESS_FINE_LOCATION]==true)lookup()
        else error="Precise location permission required. Allow location or enter coordinates manually."
    }
    fun editLat(v:String){
        val pair=pastedCoordinatePair(v)
        if(pair!=null){lat=pair.first;lon=pair.second} else lat=v
        name="Custom Location";accuracy="";error=""
    }
    fun editLon(v:String){
        val pair=pastedCoordinatePair(v)
        if(pair!=null){lat=pair.first;lon=pair.second} else lon=v
        name="Custom Location";accuracy="";error=""
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Text("Longitude, Latitude / Home",color=PremiumGold,style=MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
            OutlinedTextField(lat,::editLat,Modifier.weight(1f),label={Text("Latitude")},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Text))
            OutlinedTextField(lon,::editLon,Modifier.weight(1f),label={Text("Longitude")},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Text))
        }
        Text("Location: "+name+(if(accuracy.isNotBlank())" · "+accuracy else ""),style=MaterialTheme.typography.bodySmall)
        Text("Paste a coordinate pair into either field (latitude, longitude).",style=MaterialTheme.typography.bodySmall)
        SGSecondaryButton(if(loading)"Getting GPS..." else "Use Current Location",{
            if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)lookup()
            else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
        },Modifier.fillMaxWidth(),!loading && !vm.busy,40.dp)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
            SGSecondaryButton("Use Saved Home",{vm.savedHome?.let(::fill)},Modifier.weight(1f),vm.savedHome!=null && !loading,40.dp)
            SGSecondaryButton("Save as Home",{
                candidate()?.let {
                    if(vm.savedHome!=null)replaceHome=true
                    else vm.saveCoordinateHome(it)?.let(::fill)
                }
            },Modifier.weight(1f),!loading && !vm.busy,40.dp)
        }
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        SGPrimaryButton("Confirm Location",{candidate()?.let(onConfirm)},Modifier.fillMaxWidth(),!loading && !vm.busy,40.dp)
    }
    if(replaceHome)AlertDialog(onDismissRequest={replaceHome=false},title={Text("Replace saved Home?")},
        text={Text("This changes saved Home only. Existing start/end selections and routes remain unchanged.")},
        confirmButton={TextButton(onClick={candidate()?.let{vm.saveCoordinateHome(it)?.let(::fill)};replaceHome=false}){Text("Replace Home")}},
        dismissButton={TextButton(onClick={replaceHome=false}){Text("Cancel")}})
}
