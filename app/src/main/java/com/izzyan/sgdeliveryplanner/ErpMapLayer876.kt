package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.graphics.*
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.abs
import kotlin.math.ceil

internal object ErpMapIcon876 {
    const val SIGN_WIDTH_DP=42f
    const val PADDING_DP=2f
    fun bitmap(context:Context):Bitmap {
        val metrics=context.resources.displayMetrics;val scale=metrics.density
        val original=context.assets.open("erp_reference_876.jpg").use{BitmapFactory.decodeStream(it)}
            ?:error("Original ERP icon is not decodable")
        // The supplied sign is already a clean rectangular blue/white design.
        // Embed its unchanged image with its original aspect ratio; never recolour it.
        val width=SIGN_WIDTH_DP*scale;val height=width*original.height/original.width
        val padding=PADDING_DP*scale
        val result=Bitmap.createBitmap(ceil(width+padding*2).toInt(),ceil(height+padding*2).toInt(),Bitmap.Config.ARGB_8888)
        result.density=metrics.densityDpi
        val canvas=Canvas(result);val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val bounds=RectF(padding,padding,padding+width,padding+height)
        paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=scale
        canvas.drawRect(bounds,paint);paint.style=Paint.Style.FILL
        canvas.drawBitmap(original,null,bounds,paint);original.recycle()
        return result
    }
}

/** One native GeoJSON source/image/layer per style. GPS and zoom never rebuild it. */
internal class ErpMapLayer876(private val context:Context,private val map:MapLibreMap) {
    private var activeStyle:Style?=null
    private var dataVersion:String?=null
    private var previousVisibility:Boolean?=null
    private val image by lazy{ErpMapIcon876.bitmap(context)}
    var sourceWrites=0;private set
    var layerInstalls=0;private set
    fun sync(style:Style,catalog:ErpCatalog876,visible:Boolean) {
        if(activeStyle!==style){activeStyle=style;dataVersion=null;previousVisibility=null}
        if(style.getSource(SOURCE)==null || dataVersion!=catalog.version) {
            val features=catalog.sites.map{site->Feature.fromGeometry(Point.fromLngLat(site.point.lon,site.point.lat)).apply{
                addStringProperty("site_key",site.key)
                addStringProperty("unique_ids",site.records.joinToString(","){it.uniqueId})
                addNumberProperty("record_count",site.records.size)
            }}
            val collection=FeatureCollection.fromFeatures(features)
            val existing=style.getSourceAs<GeoJsonSource>(SOURCE)
            if(existing==null)style.addSource(GeoJsonSource(SOURCE,collection)) else existing.setGeoJson(collection)
            sourceWrites++;dataVersion=catalog.version
        }
        if(style.getLayer(LAYER)==null) {
            style.addImage(IMAGE,image,false)
            val layer=SymbolLayer(LAYER,SOURCE).withProperties(
                iconImage(IMAGE),iconSize(1f),iconAnchor(Property.ICON_ANCHOR_CENTER),
                iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                iconAllowOverlap(true),iconIgnorePlacement(true),iconPadding(0f),iconOpacity(1f))
            // Native line/casing annotations are inserted BEFORE this points layer,
            // including lines created later by reroutes. ERP must be ABOVE it.
            val annotations=style.layers.firstOrNull{
                it.id.endsWith(".annotations.points")
            }
            if(annotations==null)style.addLayer(layer) else style.addLayerAbove(layer,annotations.id)
            layerInstalls++;previousVisibility=null
        }
        if(previousVisibility!=visible){
            style.getLayer(LAYER)?.setProperties(visibility(if(visible)Property.VISIBLE else Property.NONE))
            previousVisibility=visible
        }
    }
    fun siteAt(tap:LatLng,catalog:ErpCatalog876):ErpSite876? {
        val pixel=map.projection.toScreenLocation(tap)
        // Existing annotation selection keeps priority over the independent ERP layer.
        val d=context.resources.displayMetrics.density
        if(map.markers.any{m->val p=map.projection.toScreenLocation(m.position)
            abs(p.x-pixel.x)<=28*d && pixel.y-p.y in -76*d..12*d})return null
        return map.queryRenderedFeatures(pixel,LAYER).mapNotNull{f->
            f.getStringProperty("site_key")?.let{catalog.byKey[it]}
        }.minByOrNull{site->val p=map.projection.toScreenLocation(LatLng(site.point.lat,site.point.lon))
            val x=p.x-pixel.x;val y=p.y-pixel.y;x*x+y*y}
    }
    fun close(){
        activeStyle?.let{style->runCatching{style.removeLayer(LAYER)};runCatching{style.removeSource(SOURCE)};runCatching{style.removeImage(IMAGE)}}
        activeStyle=null;dataVersion=null
    }
    companion object {
        const val SOURCE="sg-erp-source-876"
        const val LAYER="sg-navigation-erp-876"
        const val IMAGE="sg-erp-original-876"
    }
}

@Composable
internal fun ErpMapIntegration876(map:MapLibreMap?,styleReady:Boolean,state:ErpNavigationState876?,styleVersion:Int=0) {
    val context=LocalContext.current
    val overlay=remember(map,context){map?.let{ErpMapLayer876(context,it)}}
    val style=remember(map,styleReady,styleVersion){if(styleReady)map?.style else null}
    LaunchedEffect(overlay,style,state?.catalog,state?.visible) {
        val owner=state?:return@LaunchedEffect
        val catalog=owner.catalog?:return@LaunchedEffect
        if(overlay!=null && style!=null)try{
            overlay.sync(style,catalog,owner.visible);owner.mapResult(null)
        }catch(e:Exception){
            android.util.Log.e("SGErp876","ERP overlay unavailable; navigation layers retained",e)
            owner.mapResult("ERP map layer unavailable. Existing navigation remains active.")
        }
    }
    DisposableEffect(overlay,state) {
        val listener=MapLibreMap.OnMapClickListener{tap->
            val owner=state
            if(owner==null || !owner.visible || overlay==null)false else {
                val catalog=owner.catalog
                val site=if(catalog==null)null else runCatching{overlay.siteAt(tap,catalog)}
                    .onFailure{android.util.Log.w("SGErp876","ERP hit test unavailable",it)}.getOrNull()
                if(site==null)false else if(!owner.canInspect()) {
                    Toast.makeText(context,"Stop safely and wait for a reliable GPS fix to view ERP details",Toast.LENGTH_SHORT).show();false
                }else owner.select(site.key)
            }
        }
        map?.addOnMapClickListener(listener)
        onDispose{map?.removeOnMapClickListener(listener);overlay?.close()}
    }
}
