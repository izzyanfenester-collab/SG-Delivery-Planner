package com.izzyan.sgdeliveryplanner

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal object ErpOverspeedEvidence877 {
    val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    val app get()=instrumentation.targetContext
    fun shell(cmd:String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd))
        .bufferedReader().use{it.readText()}
    fun save(name:String,data:ByteArray,mime:String) {
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/SGDelivery877ErpOverspeed")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val resolver=app.contentResolver
        val uri=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        requireNotNull(resolver.openOutputStream(uri)).use{it.write(data)}
        resolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
    }
    fun json(name:String,value:JSONObject)=save(name,value.toString(2).toByteArray(),"application/json")
    fun png(name:String,bitmap:Bitmap){val out=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);save(name,out.toByteArray(),"image/png")}
    fun verifyNetworkBlocked():JSONObject {
        val expected=InstrumentationRegistry.getArguments().getString("blockedUid")?.toInt()
        assertEquals("Firewall must cover the process executing live app code",expected,Process.myUid())
        var blocked=false
        var reason=""
        val connection=URL("https://github.com/izzyanfenester-collab/SG-Delivery-Planner/releases/latest").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=3000;connection.readTimeout=3000;connection.requestMethod="HEAD"
            connection.responseCode
        }catch(e:java.io.IOException){blocked=true;reason=e.javaClass.simpleName}
        finally{connection.disconnect()}
        assertTrue("Test app still has internet access",blocked)
        return JSONObject().put("uid",Process.myUid()).put("internetBlocked",blocked).put("failure",reason)
    }
}
