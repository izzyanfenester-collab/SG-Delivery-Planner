package com.izzyan.sgdeliveryplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

/** Non-exported DEBUG host. Instrumentation supplies recorded provider responses and
 * real coordinates along their geometry. It uses the production map matcher, progress
 * tracker, metadata parser and actual production instruction/lane/Then composables.
 * The local native map contains recorded route geometry, not a fabricated basemap.
 * No location permissions, delivery records, route requests or voice events are used.
 */
class NavigationReplayActivity885 : ComponentActivity() {
    internal lateinit var mapView: MapView
    internal var map: MapLibreMap? = null
    internal var ready by mutableStateOf(false)
    internal var widthDp by mutableIntStateOf(400)
    internal var fontScale by mutableFloatStateOf(1f)
    internal var night by mutableStateOf(true)
    internal var pitch by mutableDoubleStateOf(45.0)
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
                                Text("RECORDED GPS REPLAY · LOCAL ROUTE GEOMETRY",color=SGColors.Gold,fontSize=10.sp)
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
                            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .onSizeChanged { bottomHeight=it.height }.padding(8.dp),color=PremiumNavy) {
                                NavigationDestinationPanel873(
                                    DestinationHeading873(null,null,null,"Recorded route"),"Replay",
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
                ready=true;paint()
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
        frame=currentFrame;lastGps=gps
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

    internal fun paint() {
        val native=map ?: return
        val style=native.style ?: return
        if(shape.size<2)return
        fun line(id:String,points:List<GpsPoint>,color:String,width:Float) {
            val geo=Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.lon,it.lat) }))
            val source=style.getSourceAs<GeoJsonSource>(id)
            if(source==null) {
                style.addSource(GeoJsonSource(id,geo))
                val layer=LineLayer(id+"-layer",id).withProperties(lineColor(color),lineWidth(width))
                if(style.getLayer("recorded-arrow-layer")!=null)style.addLayerBelow(layer,"recorded-arrow-layer")
                else style.addLayer(layer)
            } else source.setGeoJson(geo)
        }
        (style.getLayer("replay-background") as org.maplibre.android.style.layers.BackgroundLayer)
            .setProperties(backgroundColor(if(night)"#132537" else "#D7E1E5"))
        line("recorded-road-casing",shape,if(night)"#33485B" else "#AABCC6",24f)
        line("recorded-active-route",frame?.remaining?.takeIf { it.size>=2 } ?: shape,"#0789FF",9f)
        frame?.traveled?.takeIf { it.size>=2 }?.let { line("recorded-travelled-route",it,"#82909D",8f) }
        val point=frame?.boundary ?: shape.first()
        val arrowSource=style.getSourceAs<GeoJsonSource>("recorded-arrow")
        val feature=Feature.fromGeometry(Point.fromLngLat(point.lon,point.lat))
        if(arrowSource==null) {
            val bitmap=android.graphics.BitmapFactory.decodeResource(resources,R.drawable.ic_gps_blue_arrow_3d)
            if(bitmap!=null)style.addImage("recorded-arrow-art",bitmap)
            else {
                val drawable=requireNotNull(androidx.core.content.ContextCompat.getDrawable(this,R.drawable.ic_gps_blue_arrow_3d))
                val image=android.graphics.Bitmap.createBitmap(100,100,android.graphics.Bitmap.Config.ARGB_8888)
                drawable.setBounds(0,0,100,100);drawable.draw(android.graphics.Canvas(image));style.addImage("recorded-arrow-art",image)
            }
            style.addSource(GeoJsonSource("recorded-arrow",feature))
            style.addLayer(SymbolLayer("recorded-arrow-layer","recorded-arrow").withProperties(
                iconImage("recorded-arrow-art"),iconSize(0.6f),iconAllowOverlap(true),iconIgnorePlacement(true)))
        } else arrowSource.setGeoJson(feature)
        native.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(point.lat,point.lon)).zoom(16.0).tilt(pitch).bearing(0.0).build()))
    }
    override fun onStart(){super.onStart();mapView.onStart()}
    override fun onResume(){super.onResume();mapView.onResume()}
    override fun onPause(){mapView.onPause();super.onPause()}
    override fun onStop(){mapView.onStop();super.onStop()}
    override fun onDestroy(){mapView.onDestroy();super.onDestroy()}
    override fun onLowMemory(){super.onLowMemory();mapView.onLowMemory()}
    override fun onSaveInstanceState(outState:Bundle){super.onSaveInstanceState(outState);mapView.onSaveInstanceState(outState)}
}

