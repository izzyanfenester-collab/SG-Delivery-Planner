package com.izzyan.sgdeliveryplanner

import android.app.TimePickerDialog
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.*
import java.time.format.DateTimeFormatter
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.BoundingBox
import android.graphics.drawable.GradientDrawable
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable

class PlannerViewModel(app:Application): AndroidViewModel(app) {
 val repo=Repository(app)
 private val prefs=app.getSharedPreferences("settings",0)
 var input by mutableStateOf(prefs.getString("input","")!!)
 var screen by mutableStateOf("Home")
 var route by mutableStateOf<Plan?>(null)
 var busy by mutableStateOf(false)
 var message by mutableStateOf("")
 var service by mutableStateOf(prefs.getInt("service",8))
 var startMinute by mutableStateOf(prefs.getInt("start",600))
 var traffic by mutableStateOf(prefs.getString("traffic","Normal")!!)
 var theme by mutableStateOf(prefs.getString("theme","System")!!)
 var endpoint by mutableStateOf(prefs.getString("endpoint","https://router.project-osrm.org")!!)
 val history=repo.dao.history()
 init { viewModelScope.launch { prefs.getString("active",null)?.let { id -> repo.dao.route(id)?.let { route=repo.decode(it) } } } }
 fun persist() { prefs.edit().putInt("service",service).putInt("start",startMinute).putString("traffic",traffic).putString("theme",theme).putString("endpoint",endpoint).putString("input",input).apply() }
 fun optimize() {
  val parsed=parseInput(input)
  if(parsed.invalid.isNotEmpty() || parsed.valid.isEmpty()) { message="Enter valid six-digit postal codes. Invalid: ${parsed.invalid.joinToString()}"; return }
  persist(); busy=true
  viewModelScope.launch {
   try { route=repo.plan(parsed.valid,LocalDate.now(ZoneId.of("Asia/Singapore")).atStartOfDay().plusMinutes(startMinute.toLong()),service,traffic,endpoint) { message=it }; prefs.edit().putString("active",route!!.id).apply(); screen="Route"; message="" }
   catch(e:CancellationException){throw e}
   catch(e:Exception){message=e.message ?: "Planning failed. Check your internet connection and retry."}
   finally { busy=false }
  }
 }
 fun open(r:SavedRoute) { route=repo.decode(r); prefs.edit().putString("active",r.id).apply(); screen="Route" }
 fun progress(action:String) {
  if(busy) return
  val p=route ?: return
  if(p.current>=p.stops.size) return
  busy=true
  viewModelScope.launch {
   try {
    val now=LocalDateTime.now(ZoneId.of("Asia/Singapore")).toString()
    val stops=p.stops.toMutableList()
    if(action!="NEXT") stops[p.current]=stops[p.current].copy(status=if(action=="DELIVERED") "COMPLETED" else "SKIPPED",completedAt=if(action=="DELIVERED") now else null)
    val next=(p.current+1 until stops.size).firstOrNull { stops[it].status=="PENDING" } ?: stops.indexOfFirst { it.status=="PENDING" }.takeIf { it>=0 } ?: stops.size
    val updated=p.copy(stops=stops,current=next,actualCompletion=if(stops.all { it.status=="COMPLETED" }) now else null)
    repo.save(updated); route=updated
   } catch(e:Exception){message="Could not save progress: ${e.message}"} finally { busy=false }
  }
 }
 fun revisit(index:Int) { val p=route ?: return; viewModelScope.launch { try { val updated=p.copy(current=index); repo.save(updated); route=updated; screen="Delivery" } catch(e:Exception){message="Could not reopen stop: ${e.message}"} } }
}
class MainActivity:ComponentActivity() {
 override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); org.osmdroid.config.Configuration.getInstance().userAgentValue=packageName; setContent { val vm:PlannerViewModel=viewModel(); val dark=vm.theme=="Dark" || (vm.theme=="System" && isSystemInDarkTheme()); MaterialTheme(colorScheme=if(dark) darkColorScheme(primary=Color(0xFF90CAF9)) else lightColorScheme(primary=Color(0xFF1565C0))) { App(vm) } } }
}
fun time(value:String):String { val dt=LocalDateTime.parse(value); return dt.format(DateTimeFormatter.ofPattern("h:mm a")) + if(dt.toLocalDate()!=LocalDate.now(ZoneId.of("Asia/Singapore"))) " (${dt.toLocalDate()})" else "" }
fun duration(seconds:Double):String { val minutes=kotlin.math.ceil(seconds/60).toInt(); return if(minutes>=60) "${minutes/60} hr ${minutes%60} min" else "$minutes min" }
fun km(value:Double)="%.1f km".format(java.util.Locale.US,value)
@Composable fun App(vm:PlannerViewModel) {
 Scaffold(bottomBar={ Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { listOf("Home","Route","Delivery","Map","History","Settings").forEach { label -> TextButton(onClick={vm.screen=label},enabled=!vm.busy) { Text(label) } } } }) { padding ->
 Column(Modifier.padding(padding).fillMaxSize()) {
  Text(stringResource(R.string.app_name),style=MaterialTheme.typography.headlineSmall,modifier=Modifier.padding(16.dp))
  if(vm.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(vm.message,Modifier.padding(12.dp)) }
  if(vm.message.isNotBlank() && !vm.busy) Card(Modifier.padding(12.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)) { Text(vm.message,Modifier.padding(12.dp)); TextButton(onClick={vm.message=""}) { Text("Dismiss") } }
  when(vm.screen) {
   "Home" -> Home(vm)
   "Settings" -> Settings(vm)
   "History" -> { val saved by vm.history.collectAsState(initial=emptyList()); LazyColumn { if(saved.isEmpty()) item { Text("Your saved routes will appear here.",Modifier.padding(16.dp)) }; items(saved,key={it.id}) { record -> val p=vm.repo.decode(record); Card(Modifier.padding(12.dp).fillMaxWidth().clickable { vm.open(record) }) { Text("${p.created.take(10)} • ${time(p.start)}",Modifier.padding(12.dp)); Text("${p.stops.size} deliveries • ${km(p.totalKm)}",Modifier.padding(horizontal=12.dp)); Text("Planned finish ${time(p.stops.last().leave)}\nActual ${p.actualCompletion?.let(::time) ?: "In progress"}",Modifier.padding(12.dp)) } } } }
   else -> { val p=vm.route; if(p==null) Text("Plan a route or reopen one from History.",Modifier.padding(16.dp)) else when(vm.screen) { "Delivery" -> Delivery(vm,p); "Map" -> RouteMap(p); else -> Results(vm,p) } }
  }
 }
 }
}
@Composable fun Clock(vm:PlannerViewModel) { val context=LocalContext.current; OutlinedButton(onClick={TimePickerDialog(context,{_,h,m->vm.startMinute=h*60+m;vm.persist()},vm.startMinute/60,vm.startMinute%60,false).show()}) { Text("Start time: ${java.time.LocalTime.of(vm.startMinute/60,vm.startMinute%60).format(DateTimeFormatter.ofPattern("h:mm a"))}") } }
@Composable fun Home(vm:PlannerViewModel) {
 val parsed=parseInput(vm.input)
 Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Card(Modifier.fillMaxWidth()) { Text("START / END\nWoodlands Checkpoint\n21 Woodlands Crossing • 738203",Modifier.padding(16.dp)) }
  Clock(vm); Text("Delivery time per stop: ${vm.service} minutes")
  OutlinedTextField(vm.input,{vm.input=it;vm.persist()},label={Text("Singapore postal codes")},placeholder={Text("Paste Singapore postal codes here\n730120\n730301\n760270\n560211\n460079\n640208")},modifier=Modifier.fillMaxWidth().heightIn(min=200.dp),enabled=!vm.busy)
  Text("Valid Stops: ${parsed.valid.size} • Duplicates removed: ${parsed.duplicates}")
  if(parsed.invalid.isNotEmpty()) Text("Invalid: ${parsed.invalid.joinToString()}",color=MaterialTheme.colorScheme.error)
  Button(onClick=vm::optimize,enabled=!vm.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("OPTIMIZE ROUTE") }
  OutlinedButton(onClick={vm.input="";vm.persist()},enabled=!vm.busy) { Text("CLEAR") }
  Text("Road-based planning • Up to 50 unique deliveries\nPLANNING ESTIMATE — no live traffic",style=MaterialTheme.typography.bodySmall)
 }
}
@Composable fun Settings(vm:PlannerViewModel) {
 var serviceText by remember { mutableStateOf(vm.service.toString()) }
 Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Text("Settings",style=MaterialTheme.typography.headlineMedium)
  OutlinedTextField(serviceText,{serviceText=it;it.toIntOrNull()?.takeIf { n->n in 1..120 }?.let { n->vm.service=n;vm.persist() }},label={Text("Delivery minutes per stop (1–120)")},isError=serviceText.toIntOrNull()?.let { it in 1..120 } != true)
  Clock(vm); Text("Traffic buffer mode")
  listOf("Normal","Light Traffic","Heavy Traffic").forEach { Row(Modifier.clickable { vm.traffic=it;vm.persist() }) { RadioButton(vm.traffic==it,{vm.traffic=it;vm.persist()}); Text(it,Modifier.padding(top=12.dp)) } }
  Text("Distance units: KM\nTheme")
  Row { listOf("System","Light","Dark").forEach { FilterChip(vm.theme==it,{vm.theme=it;vm.persist()},label={Text(it)}) } }
  OutlinedTextField(vm.endpoint,{vm.endpoint=it.trim();vm.persist()},label={Text("HTTPS OSRM routing server")},modifier=Modifier.fillMaxWidth())
  Text("The public OSRM server is for evaluation and has no availability guarantee. Use your own Singapore road-data OSRM server for operational delivery planning. No API keys are stored in source. Existing route schedules retain their original settings.")
 }
}
fun navigate(context:android.content.Context,p:Place) {
 val intent=Intent(Intent.ACTION_VIEW,Uri.parse("google.navigation:q=${p.lat},${p.lon}&mode=d"))
 try { context.startActivity(intent) } catch(_:android.content.ActivityNotFoundException) { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${p.lat},${p.lon}&travelmode=driving"))) }
}
@Composable fun Delivery(vm:PlannerViewModel,p:Plan) {
 val context=LocalContext.current
 Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  if(p.current>=p.stops.size) {
   Text("All stops reviewed",style=MaterialTheme.typography.headlineMedium); Text("Completed: ${p.stops.count { it.status=="COMPLETED" }} / ${p.stops.size}")
   Button(onClick={navigate(context,depot)},modifier=Modifier.fillMaxWidth().height(64.dp)) { Text("RETURN TO WOODLANDS") }
   p.stops.forEachIndexed { i,s -> if(s.status!="COMPLETED") OutlinedButton(onClick={vm.revisit(i)}) { Text("Revisit ${s.place.postal} • ${s.status}") } }
  } else {
   val s=p.stops[p.current]
   Text("STOP ${p.current+1} / ${p.stops.size}",style=MaterialTheme.typography.titleLarge)
   Text(s.place.postal,style=MaterialTheme.typography.displayLarge)
   Text("Block ${s.place.block}\n${s.place.area}\n${s.place.address}",style=MaterialTheme.typography.titleLarge)
   Text("Arrival ${time(s.arrival)}\nDelivery ${time(s.arrival)} – ${time(s.leave)}\nPLANNING ESTIMATE")
   listOf("NAVIGATE","DELIVERED","SKIP","NEXT STOP").forEach { label -> Button(onClick={if(label=="NAVIGATE") navigate(context,s.place) else vm.progress(if(label=="NEXT STOP") "NEXT" else label)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth().height(64.dp),colors=ButtonDefaults.buttonColors(containerColor=if(label=="DELIVERED") Color(0xFF237A43) else MaterialTheme.colorScheme.primary)) { Text(label,style=MaterialTheme.typography.titleLarge) } }
   Text("NEXT STOP leaves this delivery pending. SKIP records a skipped stop. Both remain available for revisiting.",style=MaterialTheme.typography.bodySmall)
  }
 }
}
@Composable fun Results(vm:PlannerViewModel,p:Plan) {
 val context=LocalContext.current
 Column(Modifier.padding(horizontal=16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Card(Modifier.fillMaxWidth()) { Text("Woodlands Checkpoint\nStart ${time(p.start)}\n${p.stops.size} deliveries • ${km(p.totalKm)}\nEstimated finish ${time(p.stops.last().leave)}\nReturn Woodlands ${time(p.returned)}",Modifier.padding(16.dp),style=MaterialTheme.typography.titleMedium) }
  Text("PLANNING ESTIMATE • ${p.mode}")
  Text("Base driving: ${duration(p.baseSeconds)}\nTraffic/junction allowance: ${duration(p.bufferSeconds)}\nDelivery time: ${duration(p.serviceMinutes*p.stops.size*60.0)}\nTotal route time: ${duration(p.baseSeconds+p.bufferSeconds+p.serviceMinutes*p.stops.size*60.0)}\nFinal delivery: ${p.stops.last().place.postal}")
  Button(onClick={vm.screen="Delivery"},modifier=Modifier.fillMaxWidth()) { Text("START / RESUME DELIVERY") }
  // Three waypoints is the conservative supported limit on mobile browsers.
  if(p.stops.size<=3) OutlinedButton(onClick={
   val waypoints=p.stops.joinToString("|") { "${it.place.lat},${it.place.lon}" }
   context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/maps/dir/?api=1&origin=${depot.lat},${depot.lon}&destination=${depot.lat},${depot.lon}&travelmode=driving&waypoints=${Uri.encode(waypoints)}")))
  }) { Text("OPEN IN GOOGLE MAPS") } else Text("Google Maps URLs support limited waypoints (3 on mobile browsers). Your complete ${p.stops.size}-stop route stays here in its optimized order. Use NAVIGATE in Delivery Mode for each stop.")
  Text("Woodlands Checkpoint\n↓\n${p.stops.joinToString("\n↓\n") { it.place.postal }}\n↓\nWoodlands Checkpoint")
  ScheduleTable(p)
  p.stops.forEachIndexed { i,s -> Card(Modifier.fillMaxWidth().clickable { vm.revisit(i) },colors=CardDefaults.cardColors(containerColor=if(s.status=="COMPLETED") Color(0xFF237A43) else MaterialTheme.colorScheme.surfaceVariant)) { Text("${i+1}. ${s.place.postal} • ${s.status}\n${s.place.address}${s.completedAt?.let { "\nCompleted ${time(it)}" }.orEmpty()}",Modifier.padding(12.dp),color=if(s.status=="COMPLETED") Color.White else MaterialTheme.colorScheme.onSurfaceVariant) } }
 }
}
@Composable fun ScheduleTable(p:Plan) {
 val headings=listOf("Stop","Postal Code","Block","Area","Arrival","Distance From Previous","Base Drive","Traffic Buffer","Planned Travel","Delivery","Leave","Cumulative KM")
 val start=listOf("START",depot.postal,"Woodlands Checkpoint",depot.area,time(p.start),"—","—","—","—","—",time(p.start),"0.0 km")
 val rows=p.stops.mapIndexed { i,s -> listOf("${i+1}",s.place.postal,s.place.block,s.place.area,time(s.arrival),km(s.leg.km),duration(s.leg.baseSeconds),duration(s.leg.bufferSeconds),duration(s.leg.plannedSeconds),"${time(s.arrival)} – ${time(s.leave)}",time(s.leave),km(s.cumulativeKm)) }
 val end=listOf("END",depot.postal,"Woodlands Checkpoint",depot.area,time(p.returned),km(p.returnLeg.km),duration(p.returnLeg.baseSeconds),duration(p.returnLeg.bufferSeconds),duration(p.returnLeg.plannedSeconds),"—",time(p.returned),km(p.totalKm))
 Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp,MaterialTheme.colorScheme.outline)) { (listOf(headings,start)+rows+listOf(end)).forEachIndexed { index,row -> Row(Modifier.background(if(index==0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) { row.forEach { Text(it,Modifier.width(160.dp).padding(10.dp),style=if(index==0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium) } } } }
}
@Composable fun RouteMap(p:Plan) {
 val context=LocalContext.current
 val map=remember { MapView(context).apply { setMultiTouchControls(true); setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK) } }
 DisposableEffect(map) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
 Column { Text("Blue: checkpoint • Orange: current • Green: completed\n© OpenStreetMap contributors",Modifier.padding(12.dp)); AndroidView(factory={map},modifier=Modifier.fillMaxSize(),update={ view ->
  view.overlays.clear()
  val line=Polyline().apply { setPoints(p.geometry.map { GeoPoint(it[1],it[0]) }); outlinePaint.color=android.graphics.Color.rgb(21,101,192); outlinePaint.strokeWidth=7f }; view.overlays.add(line)
  fun marker(place:Place,label:String,color:Int,title:String) {
   val bitmap=Bitmap.createBitmap(84,84,Bitmap.Config.ARGB_8888); val canvas=Canvas(bitmap); val paint=Paint(Paint.ANTI_ALIAS_FLAG)
   paint.color=color; canvas.drawCircle(42f,42f,37f,paint); paint.color=android.graphics.Color.WHITE; paint.textSize=30f; paint.textAlign=Paint.Align.CENTER; canvas.drawText(label,42f,52f,paint)
   view.overlays.add(Marker(view).apply { position=GeoPoint(place.lat,place.lon); this.title=title; icon=BitmapDrawable(context.resources,bitmap); setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_CENTER) })
  }
  marker(depot,"SG",android.graphics.Color.rgb(21,101,192),"Woodlands Checkpoint")
  p.stops.forEachIndexed { i,s -> marker(s.place,"${i+1}",if(s.status=="COMPLETED") android.graphics.Color.rgb(35,122,67) else if(i==p.current) android.graphics.Color.rgb(230,120,0) else android.graphics.Color.rgb(70,90,110),"${i+1}: ${s.place.postal} ${s.place.address}") }
  val points=(p.stops.map { GeoPoint(it.place.lat,it.place.lon) }+GeoPoint(depot.lat,depot.lon))
  view.post { view.zoomToBoundingBox(BoundingBox.fromGeoPoints(points),false,80) }; view.invalidate()
 }) }
}
