package com.izzyan.sgdeliveryplanner

/** Native MapLibre Android polyline annotations are LINE style layers.
 * ERP symbols must be above these lines, but below the vehicle/location
 * foreground and native marker symbols when the style permits that ordering.
 */
internal data class ErpLayerSlot878(val referenceId:String?,val placeBelow:Boolean)
internal object ErpLayerOrder878 {
    fun nativeAnnotationLine(id:String,isLine:Boolean):Boolean = isLine && (
        id.startsWith("mapbox-android-") || id.startsWith("maplibre-android-") ||
        id.contains("annotation",ignoreCase=true))
    fun slot(layers:List<Pair<String,Boolean>>):ErpLayerSlot878 {
        val lastLine=layers.indexOfLast{nativeAnnotationLine(it.first,it.second)}
        val foreground=layers.indexOfFirst {
            it.first=="mapbox-location-foreground-layer" || it.first=="maplibre-location-foreground-layer"
        }
        // Choose a position immediately beneath the arrow, provided it is
        // already ABOVE the native route-polyline layer in the style.
        if(foreground>=0 && foreground>lastLine)return ErpLayerSlot878(layers[foreground].first,true)
        if(lastLine>=0)return ErpLayerSlot878(layers[lastLine].first,false)
        if(foreground>=0)return ErpLayerSlot878(layers[foreground].first,true)
        return ErpLayerSlot878(null,false)
    }
    fun correct(layers:List<Pair<String,Boolean>>,erpId:String):Boolean {
        val erp=layers.indexOfFirst{it.first==erpId}
        if(erp<0)return false
        val line=layers.indexOfLast{nativeAnnotationLine(it.first,it.second)}
        val arrow=layers.indexOfFirst{it.first=="mapbox-location-foreground-layer" ||
            it.first=="maplibre-location-foreground-layer"}
        return (line<0 || erp>line) && (arrow<0 || erp<arrow)
    }
}
