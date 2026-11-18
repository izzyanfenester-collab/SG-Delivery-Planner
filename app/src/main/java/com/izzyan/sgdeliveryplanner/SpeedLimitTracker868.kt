package com.izzyan.sgdeliveryplanner

/** No general posted limit is inferred from proximity to a speed camera. */
internal data class SpeedLimitState868(
    val verifiedKmh:Int?=null,
    val referenceKmh:Int?=null,
    val referenceId:String?=null,
    val referenceStatus:String?=null,
    val referenceSource:String?=null,
    val overspeedVisual:Boolean=false,
    val diagnostics:String="No verified road segment speed limit"
) {
    val isReferenceOnly get()=verifiedKmh==null && referenceKmh!=null
}

/** Driven by already matched road + direction + route geometry from the live GPS session. */
internal class SpeedLimitTracker868 {
    private var pendingId:String?=null
    private var pendingCount=0
    private var stableId:String?=null
    private var missed=0
    private var overspeedSamples=0
    private var withinSamples=0
    private var wasOverspeed=false
    private var previous:SpeedLimitState868=SpeedLimitState868()
    fun reset(){
        pendingId=null;pendingCount=0;stableId=null;missed=0
        overspeedSamples=0;withinSamples=0;wasOverspeed=false
        previous=SpeedLimitState868()
    }
    fun observe(
        approaches:List<CameraApproach>,
        records:List<SpeedLimitRow868>,
        accuracyMetres:Float,
        speedMetresPerSecond:Float?,
        speedAccuracyMetresPerSecond:Float?,
        // True ONLY for a separately verified directional road-segment geometry match.
        authoritativeSegmentMatch:Boolean=false
    ):SpeedLimitState868 {
        if(accuracyMetres>15f || !accuracyMetres.isFinite()) {
            reset()
            return previous.copy(diagnostics="GPS accuracy too low: speed limit unknown")
        }
        val referenceByCamera=records.associateBy{it.cameraId}
        val match=approaches.asSequence().filter {
            it.directionConfirmed && it.alertAllowed &&
            !it.coordinateFallbackUsed && it.routeMetresAhead in 0.0..350.0 &&
            !it.camera.isAverage
        }.mapNotNull { approach->
            val reference=referenceByCamera[approach.camera.id]
            if(reference==null || reference.reducedPrecision) null
            else reference to approach.routeMetresAhead
        }.minByOrNull{it.second}?.first

        if(match==null){
            missed++
            if(missed>=2)reset()
            return previous
        }
        missed=0
        if(match.cameraId!=pendingId){
            pendingId=match.cameraId
            pendingCount=1
            if(stableId!=match.cameraId)previous=SpeedLimitState868(
                diagnostics="Waiting for second directional GPS match")
            return previous
        }
        pendingCount++
        if(pendingCount<2)return previous
        stableId=match.cameraId
        val verified=match.verifiedKmh.takeIf{
            match.isOfficialSegmentVerified && authoritativeSegmentMatch
        }
        val reference=match.referenceKmh
        val reliableSpeed=speedMetresPerSecond!=null &&
            speedMetresPerSecond>=0f && speedMetresPerSecond.isFinite() &&
            speedAccuracyMetresPerSecond!=null &&
            speedAccuracyMetresPerSecond.isFinite() &&
            speedAccuracyMetresPerSecond<=1.5f
        val actualSpeedKmh=if(reliableSpeed) speedMetresPerSecond!!.toDouble()*3.6 else null
        if(verified==null || actualSpeedKmh==null){
            overspeedSamples=0;withinSamples=0;wasOverspeed=false
        } else {
            if(actualSpeedKmh>verified+4) {
                overspeedSamples++;withinSamples=0
                if(overspeedSamples>=3)wasOverspeed=true
            }else if(actualSpeedKmh<verified-2) {
                withinSamples++;overspeedSamples=0
                if(withinSamples>=3)wasOverspeed=false
            }
        }
        previous=SpeedLimitState868(
            verifiedKmh=verified,
            referenceKmh=reference,
            referenceId=match.excelId,
            referenceStatus=match.verificationStatus,
            referenceSource=match.sources,
            overspeedVisual=wasOverspeed && verified!=null,
            diagnostics=if(verified!=null)
                "Official verified speed for current directional segment"
            else if(reference!=null)
                "Camera-area speed reference only (not verified legal road limit)"
            else "Camera record has no reference speed limit"
        )
        return previous
    }
}
