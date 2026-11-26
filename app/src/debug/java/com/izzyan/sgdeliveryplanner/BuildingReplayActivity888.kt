package com.izzyan.sgdeliveryplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.routeprogress.RouteProgress
import org.maplibre.spatialk.geojson.Position

/** DEBUG-only native building replay using captured OpenFreeMap/OSM Woodlands geometry.
 * Production paint policy, instruction UI, GPS matcher/progress and original native route
 * annotation API are exercised. No network sources, delivery writes or voice events.
 */
class BuildingReplayActivity888 : ComponentActivity() {
    internal lateinit var mapView: MapView
    internal var map: MapLibreMap? = null
    internal var ready by mutableStateOf(false)
    internal var widthDp by mutableIntStateOf(400)
    internal var fontScale by mutableFloatStateOf(1f)
    internal var night by mutableStateOf(true)
    internal var pitch by mutableDoubleStateOf(58.0)
    internal var before by mutableStateOf(true)
    internal var sceneName by mutableStateOf("Woodlands")
    internal var cameraMarkersInstalled=false
    internal var cameraMarkerCount=0
    internal var sceneReady=false
    internal var activeLine:Polyline?=null
    internal var casingLine:Polyline?=null
    internal var travelledLine:Polyline?=null
    internal var baseStyleJson=""
    internal var gpsBearing=0f
    internal var cameraMoves=0
    internal var animationCancelled=false
    internal var animationFinished=false
    internal var guidance by mutableStateOf(VisualGuidanceState885())
    internal var progress by mutableStateOf<RouteProgress?>(null)
    internal var route: DirectionsRoute? = null
    internal var visuals: VisualGuidanceRoute885? = null
    internal var lastGps: GpsPoint? = null
    internal var elapsed = 1_000L
    private var shape: List<GpsPoint> = emptyList()
    private var starts: List<Double> = emptyList()
    private val tracker = NavigationRouteProgressTracker()
    private var signs: List<RouteSignMetadata> = emptyList()
    private var frame: RouteLineFrame? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        mapView = MapView(this,MapLibreMapOptions().textureMode(true)).apply { onCreate(savedInstanceState) }
        setContent {
            val baseDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(baseDensity,fontScale)) {
                IzzDeliveryTheme(dark=true) {
                    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),contentAlignment=Alignment.TopCenter) {
                        Box(Modifier.widthIn(max=widthDp.dp).fillMaxSize()) {
                            AndroidView(factory={mapView},modifier=Modifier.fillMaxSize())
                            var panelHeight by remember { mutableIntStateOf(0) }
                            var bottomHeight by remember { mutableIntStateOf(0) }
                            val density = LocalDensity.current
                            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                                .onSizeChanged { panelHeight = it.height }.padding(8.dp),
                                verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                Text("${if(before)"BEFORE · OFFICIAL #885" else "AFTER · COLOURED BUILDINGS & GREENERY"} · RECORDED GPS",color=SGColors.Gold,fontSize=10.sp)
                                val p = progress
                                val current = p?.currentLegProgress?.currentStepProgress
                                val next = current?.nextStep ?: current?.step
                                val nextIndex = (p?.stepIndex ?: 0) + if(current?.nextStep!=null)1 else 0
                                NavigationGuidanceHeader885(guidance) {
                                    NavigationPrimaryInstruction885(
                                        NavigationPanelSigns.cue(next?.maneuver?.type?.toString(),
                                            next?.maneuver?.modifier?.toString(),next?.maneuver?.instruction,
                                            signs.getOrNull(nextIndex)),
                                        false,false,p?.stepDistanceRemaining,
                                        next?.maneuver?.instruction ?: "Waiting for recorded GPS…",current?.step?.name,widthDp)
                                }
                            }
                            Column(Modifier.align(Alignment.TopEnd).fillMaxHeight()
                                .padding(top=with(density){panelHeight.toDp()}+6.dp,
                                    bottom=with(density){bottomHeight.toDp()}+6.dp,end=8.dp)
                                .testTag("replay-control-rail").verticalScroll(rememberScrollState()),
                                verticalArrangement=Arrangement.spacedBy(GpsCompactLayout880.CONTROL_GAP_DP.dp)) {
                                GpsControl(Icons.Filled.MyLocation,"Follow",true){paint()}
                                GpsControl(Icons.Filled.Map,"Route"){paint()}
                                GpsControl(Icons.Filled.Add,"Zoom +"){map?.animateCamera(CameraUpdateFactory.zoomIn())}
                                GpsControl(Icons.Filled.Remove,"Zoom −"){map?.animateCamera(CameraUpdateFactory.zoomOut())}
                                GpsControl(Icons.Filled.Traffic,"Traffic"){}
                                GpsControl(Icons.Filled.Settings,"Settings"){}
                            }
                            Text("© OpenStreetMap contributors · OpenFreeMap",Modifier.align(Alignment.BottomStart)
                                .padding(start=10.dp,bottom=with(density){bottomHeight.toDp()}+2.dp)
                                .background(androidx.compose.ui.graphics.Color(0xC0081B33)),color=SGColors.Silver,fontSize=9.sp)
                            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .onSizeChanged { bottomHeight=it.height }.padding(8.dp),color=PremiumNavy) {
                                NavigationDestinationPanel873(
                                    DestinationHeading873(null,null,null,sceneName),"Replay",
                                    metricDistance(progress?.distanceRemaining ?: 0.0),null,{},
                                    speedMeters={NavigationBottomSpeedMeters881(48,RoadSpeedState875(),false,true)})
                            }
                        }
                    }
                }
            }
        }
        mapView.getMapAsync { native ->
            map=native
            native.uiSettings.isAttributionEnabled=false
            native.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"replay-background","type":"background","paint":{"background-color":"#132537"}}]}""")) {
                ready=true
            }
        }
    }

    internal fun load(json: String) {
        val rawShape=com.google.gson.JsonParser.parseString(json).asJsonObject["routes"].asJsonArray[0]
            .asJsonObject["geometry"].asString.let(::decodeNavigationGeometry)
        val parsed=parseNavigationRoute(json,rawShape.first(),rawShape.last())
        route=parsed;shape=decodeNavigationGeometry(parsed.geometry)
        signs=NavigationPanelSigns.metadata(json);visuals=VisualGuidanceRoute885.parse(json,parsed)
        tracker.reset(shape);guidance=VisualGuidanceState885();progress=null;frame=null
        val lengths=DoubleArray(shape.size)
        for(i in 1 until shape.size)lengths[i]=lengths[i-1]+navigationDistance(shape[i-1],shape[i])
        var prior=0
        starts=parsed.legs.single().steps.map { step ->
            val point=GpsPoint(step.maneuver.location.latitude,step.maneuver.location.longitude)
            prior=(prior until shape.size).minByOrNull { navigationDistance(point,shape[it]) } ?: prior
            lengths[prior]
        }
        elapsed=1_000L
    }

    /** Actual geographic input, not an explicit demonstration step or arrow selector. */
    internal fun replayGps(gps: GpsPoint, bearing: Float?, accuracy: Float=7f) {
        val active=requireNotNull(route)
        elapsed+=10_000L
        val match=NavigationRoadMatcher.match(gps,shape,accuracy,13.4f,bearing)
        if(match==null) {guidance=VisualGuidanceState885();return}
        val currentFrame=requireNotNull(tracker.advance(match,13.4f,elapsed))
        if(!currentFrame.trustedMatch) {guidance=VisualGuidanceState885();return}
        frame=currentFrame;lastGps=gps;gpsBearing=bearing ?: gpsBearing
        val index=starts.indexOfLast { it<=currentFrame.progressedMeters+0.5 }.coerceIn(0,starts.lastIndex)
        val steps=active.legs.single().steps
        val remaining=(starts.getOrNull(index+1)?.minus(currentFrame.progressedMeters) ?: 0.0).coerceAtLeast(0.0)
        val ratio=(1-currentFrame.progressedMeters/(starts.last().coerceAtLeast(1.0))).coerceIn(0.0,1.0)
        fun points(i:Int)=steps.getOrNull(i)?.let { decodeNavigationGeometry(it.geometry).map { p->Position(p.lon,p.lat) } }
        val p=RouteProgress(active,legIndex=0,distanceRemaining=active.distance*ratio,
            currentStepPoints=requireNotNull(points(index)),upcomingStepPoints=points(index+1),stepIndex=index,
            legDistanceRemaining=active.distance*ratio,stepDistanceRemaining=remaining,intersections=null,
            currentIntersection=null,upcomingIntersection=null,currentLegAnnotation=null,intersectionDistancesAlongStep=null)
        progress=p;guidance=requireNotNull(visuals).state(p);paint()
    }

    internal fun invalidateGuidance() { guidance=VisualGuidanceState885() }

    /** The native SDK reads the exact cached binary vector tile, as in production. */
    internal fun installVectorScene(template:String,tileRootUri:String,glyphUri:String,bounds:List<Double>) {
        val json=com.google.gson.JsonParser.parseString(template).asJsonObject
        val source=com.google.gson.JsonObject().apply {
            addProperty("type","vector")
            add("tiles",com.google.gson.JsonArray().apply { add(tileRootUri+"/{z}/{x}/{y}.pbf") })
            addProperty("minzoom",14);addProperty("maxzoom",14)
            add("bounds",com.google.gson.JsonArray().apply {bounds.forEach { add(it) }})
        }
        json.add("sources",com.google.gson.JsonObject().apply {add("openmaptiles",source)})
        json.addProperty("glyphs",glyphUri);baseStyleJson=json.toString();reloadScene()
    }
    internal fun reloadScene() {
        sceneReady=false;activeLine=null;casingLine=null;travelledLine=null
        map!!.clear();cameraMarkersInstalled=false;cameraMarkerCount=0
        map!!.setStyle(Style.Builder().fromJson(baseStyleJson)) {
            applyBuildingStyle();paint();sceneReady=true
        }
    }
    internal fun applyBuildingStyle() {
        val style=map?.style ?: return
        NavigationMapPalette.apply(style,night,enhancedScenery=!before)
    }
    private fun installActualCameraMarkers(native:MapLibreMap,point:GpsPoint) {
        if(cameraMarkersInstalled)return
        val records=org.json.JSONObject(assets.open("sg_enforcement_cameras.json").bufferedReader().use {it.readText()})
            .getJSONArray("cameras")
        val seen=mutableSetOf<Pair<Double,Double>>()
        for(i in 0 until records.length()) {
            val camera=records.getJSONObject(i)
            val lat=camera.optDouble("latitude",Double.NaN);val lon=camera.optDouble("longitude",Double.NaN)
            if(!lat.isFinite() || !lon.isFinite() || kotlin.math.abs(lat-point.lat)>.009 || kotlin.math.abs(lon-point.lon)>.009)continue
            if(!seen.add(lat to lon))continue
            native.addMarker(org.maplibre.android.annotations.MarkerOptions().position(LatLng(lat,lon))
                .icon(CameraSafetyMapIcons.icon(this,camera.getString("category")))
                .title("Recorded camera: "+camera.getString("camera_id")))
            cameraMarkerCount++
        }
        cameraMarkersInstalled=true
    }
    internal fun paint() {
        val native=map ?: return
        val style=native.style ?: return
        if(shape.size<2)return
        fun line(existing:Polyline?,points:List<GpsPoint>,color:Int,width:Float):Polyline? {
            if(points.size<2)return existing
            val coordinates=points.map { LatLng(it.lat,it.lon) }
            if(existing==null)return native.addPolyline(PolylineOptions().addAll(coordinates).color(color).width(width))
            existing.points=coordinates;native.updatePolyline(existing);return existing
        }
        travelledLine=line(travelledLine,frame?.traveled.orEmpty(),android.graphics.Color.rgb(128,128,128),9f)
        casingLine=line(casingLine,frame?.remaining?.takeIf { it.size>=2 } ?: shape,android.graphics.Color.rgb(4,51,123),17f)
        activeLine=line(activeLine,frame?.remaining?.takeIf { it.size>=2 } ?: shape,android.graphics.Color.rgb(0,132,255),11f)
        val point=frame?.boundary ?: shape.first()
        installActualCameraMarkers(native,point)
        val arrowSource=style.getSourceAs<GeoJsonSource>("sg-navigation-replay-arrow")
        val feature=Feature.fromGeometry(Point.fromLngLat(point.lon,point.lat)).apply { addNumberProperty("heading",gpsBearing) }
        if(arrowSource==null) {
            val drawable=requireNotNull(androidx.core.content.ContextCompat.getDrawable(this,R.drawable.ic_gps_blue_arrow_3d))
            val d=resources.displayMetrics.density
            val image=android.graphics.Bitmap.createBitmap((44*d).toInt(),(44*d).toInt(),android.graphics.Bitmap.Config.ARGB_8888)
            image.density=resources.displayMetrics.densityDpi
            drawable.setBounds(0,0,image.width,image.height);drawable.draw(android.graphics.Canvas(image))
            style.addImage("sg-navigation-replay-arrow-art",image)
            style.addSource(GeoJsonSource("sg-navigation-replay-arrow",feature))
            style.addLayer(SymbolLayer("mapbox-location-foreground-layer","sg-navigation-replay-arrow").withProperties(
                iconImage("sg-navigation-replay-arrow-art"),iconSize(1f),iconAllowOverlap(true),iconIgnorePlacement(true),
                iconRotationAlignment("map"),iconPitchAlignment("viewport"),
                iconRotate(org.maplibre.android.style.expressions.Expression.get("heading"))))
        } else arrowSource.setGeoJson(feature)
        ErpMapLayer876(this,native).sync(style,ErpStore876(this).load(),true)
    }
    internal fun move(zoom:Double=17.15,tilt:Double=58.0,bearing:Double=22.0) {
        val point=frame?.boundary ?: shape.first()
        map!!.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(point.lat+0.00040,point.lon+0.00012)).zoom(zoom).tilt(tilt).bearing(bearing).build()))
        cameraMoves++
    }
    override fun onStart(){super.onStart();mapView.onStart()}
    override fun onResume(){super.onResume();mapView.onResume()}
    override fun onPause(){mapView.onPause();super.onPause()}
    override fun onStop(){mapView.onStop();super.onStop()}
    override fun onDestroy(){mapView.onDestroy();super.onDestroy()}
    override fun onLowMemory(){super.onLowMemory();mapView.onLowMemory()}
    override fun onSaveInstanceState(outState:Bundle){super.onSaveInstanceState(outState);mapView.onSaveInstanceState(outState)}
}

