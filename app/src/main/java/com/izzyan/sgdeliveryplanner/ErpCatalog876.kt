package com.izzyan.sgdeliveryplanner

import java.math.BigDecimal
import java.net.URI

internal data class ErpRecord876(val uniqueId:String,val gantryNo:String,val latitudeText:String,
    val longitudeText:String,val googleMaps:String,val sourceRow:Int) {
    val point get()=GpsPoint(latitudeText.toDouble(),longitudeText.toDouble())
    val coordinateKey get()=BigDecimal(latitudeText).stripTrailingZeros().toPlainString()+","+
        BigDecimal(longitudeText).stripTrailingZeros().toPlainString()
    val gantryLabel get()=if(gantryNo.isBlank()||gantryNo.equals("UNK",true))"Not specified" else gantryNo
    val supportsMapsLink get()=runCatching{URI(googleMaps).let{
        it.scheme=="https" && it.host=="maps.google.com" && it.port==-1 && it.userInfo==null &&
            it.fragment==null && it.rawQuery=="q=$latitudeText,$longitudeText"
    }}.getOrDefault(false)
}
internal data class ErpSite876(val key:String,val records:List<ErpRecord876>) {
    val point get()=records.first().point
    val latitudeText get()=records.first().latitudeText
    val longitudeText get()=records.first().longitudeText
}
internal data class ErpCatalog876(val version:String,val workbookSha256:String,val records:List<ErpRecord876>) {
    val sites:List<ErpSite876>
    val byKey:Map<String,ErpSite876>
    init {
        require(version.matches(Regex("xlsx-[a-f0-9]{12}")))
        require(workbookSha256.matches(Regex("[a-f0-9]{64}")))
        require(records.size==106 && records.map{it.uniqueId}.toSet().size==106)
        require(records.map{it.sourceRow}.toSet().size==106)
        records.forEach{
            require(it.uniqueId.matches(Regex("\\d+")) && it.sourceRow>=2)
            require(BigDecimal(it.latitudeText) in BigDecimal("1.1")..BigDecimal("1.6"))
            require(BigDecimal(it.longitudeText) in BigDecimal("103.5")..BigDecimal("104.3"))
            require(it.supportsMapsLink)
        }
        sites=records.groupBy{it.coordinateKey}.map{ErpSite876(it.key,it.value)}
        require(sites.size==101)
        val duplicates=sites.filter{it.records.size>1}.map{it.records.map{r->r.uniqueId}.toSet()}.toSet()
        require(duplicates==setOf(setOf("627","838"),setOf("645","839"),setOf("646","840"),
            setOf("705","717"),setOf("757","331")))
        byKey=sites.associateBy{it.key}
    }
}

internal data class ErpMotionFix876(val point:GpsPoint,val timeMs:Long,val accuracy:Float,
    val speed:Float?,val speedAccuracy:Float?)

/** Inspection is available only after fresh, consistently stationary GPS samples. */
internal class ErpStationaryGate876 {
    private var latest:ErpMotionFix876?=null
    private var since:Long?=null
    private var samples=0
    fun observe(fix:ErpMotionFix876) {
        val previous=latest
        if(previous!=null && fix.timeMs<=previous.timeMs)return
        latest=fix
        val speed=fix.speed;val error=fix.speedAccuracy
        if(!fix.point.valid() || fix.timeMs<=0 || !fix.accuracy.isFinite() || fix.accuracy<=0 || fix.accuracy>20 ||
            speed==null || error==null || !speed.isFinite() || !error.isFinite() || speed !in 0f..0.5f ||
            error !in 0f..1f || speed+error>1f) {since=null;samples=0;return}
        if(previous==null || since==null || fix.timeMs-previous.timeMs>5000 ||
            navigationDistance(previous.point,fix.point)>maxOf(8.0,(previous.accuracy+fix.accuracy).toDouble())) {
            since=fix.timeMs;samples=1
        }else samples++
    }
    fun allowed(nowMs:Long):Boolean {
        val fix=latest?:return false;val start=since?:return false
        return samples>=2 && fix.timeMs-start>=2000 && nowMs-fix.timeMs in 0..5000
    }
}
