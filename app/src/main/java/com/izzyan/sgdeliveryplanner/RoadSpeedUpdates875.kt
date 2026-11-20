package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** The validated Official data channel is independent of APK updates. */
internal object RoadSpeedRemote875 {
    const val ROOT="https://github.com/izzyanfenester-collab/SG-Delivery-Planner/releases/download/sg-road-speed-limits/"
    private fun connection(path:String):HttpURLConnection=(URL(ROOT+path).openConnection() as HttpURLConnection).apply {
        connectTimeout=15000;readTimeout=30000
        setRequestProperty("User-Agent","SG-Delivery-Road-Speed-875")
    }
    fun update(store:RoadSpeedStore875):Boolean {
        val current=store.ensureBundled()
        val manifestConnection=connection("sg-road-speed-limits-875-manifest.json")
        val manifest=try {
            require(manifestConnection.responseCode==200)
            require(manifestConnection.contentLengthLong<=100_000)
            val bytes=manifestConnection.inputStream.use{it.readNBytesBounded875(100_000)}
            RoadSpeedManifest875.parse(bytes.toString(Charsets.UTF_8))
        } finally {manifestConnection.disconnect()}
        if(manifest.version<=current.version)return false
        val data=connection("sg-road-speed-limits-875.db.gz")
        try {
            require(data.responseCode==200 && (data.contentLengthLong<0 || data.contentLengthLong==manifest.gzipBytes))
            return data.inputStream.use{store.install(it,manifest)}
        }finally{data.disconnect()}
    }
    private fun java.io.InputStream.readNBytesBounded875(max:Int):ByteArray {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val n=read(buffer);if(n<0)break;require(out.size()+n<=max);out.write(buffer,0,n)}
        return out.toByteArray()
    }
}
internal class RoadSpeedUpdateWorker875(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        runCatching{RoadSpeedStore875(applicationContext).use{RoadSpeedRemote875.update(it)}}
            .fold({Result.success()},{
                android.util.Log.w("SGRoadSpeed875","Update failed; retaining previous validated offline catalog",it)
                Result.retry()
            })
    }
}
internal object RoadSpeedUpdateScheduler875 {
    fun schedule(context:Context){
        val request=PeriodicWorkRequestBuilder<RoadSpeedUpdateWorker875>(7,TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,TimeUnit.HOURS).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "sg_road_speed_limit_weekly_875",ExistingPeriodicWorkPolicy.KEEP,request)
    }
}
