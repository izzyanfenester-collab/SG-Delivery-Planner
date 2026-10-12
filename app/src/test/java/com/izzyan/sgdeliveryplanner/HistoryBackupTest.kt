package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class HistoryBackupTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private val databases=mutableListOf<PlannerDb>()
    private fun db()=Room.inMemoryDatabaseBuilder(app,PlannerDb::class.java).build().also(databases::add)
    @After fun close(){databases.forEach{it.close()}}
    private fun plan():Plan {
        val place=Place("735689",1.44,103.77,"689","Woodlands","689 Woodlands Drive")
        val scheduled=schedule(listOf(place,place),List(3){Leg(1.0,60.0,0.0)},
            LocalDateTime.of(2026,10,4,10,0),8,"Normal",emptyList())
        return scheduled.copy(stops=scheduled.stops.mapIndexed { i,stop ->
            stop.copy(orderId="order-"+i,order=CustomerOrder(orderId="order-"+i,
                postalCode=place.postal,customerName="Customer "+i,phoneNumber="+6588888888",
                paymentStatus=if(i==0)"COD" else "PAYNOW",proofFileId=if(i==0)"delivery_backup_test.jpg" else null,
                paymentProofFileId=if(i==0)"payment_backup_test.jpg" else null))
        })
    }
    private fun createProof(name:String,color:Int):File {
        val f=File(app.filesDir,"order_proofs/"+name)
        check(f.parentFile!!.isDirectory||f.parentFile!!.mkdirs())
        val image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply {eraseColor(color)}
        f.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG,95,it) }
        image.recycle()
        return f
    }
    @Test fun backupRestoresMultipleIndependentOrdersBothProofPhotosAndNeverOverwritesExistingRoute()=runBlocking {
        val payment=createProof("payment_backup_test.jpg",Color.RED)
        val delivery=createProof("delivery_backup_test.jpg",Color.GREEN)
        val original=plan()
        val first=db()
        first.dao().save(SavedRoute(original.id,PlanJson.encode(original),original.created))
        val out=File(app.cacheDir,"history-export-"+UUID.randomUUID()+".zip")
        try {
            val result=HistoryBackup(app,first.dao()).export(Uri.fromFile(out))
            assertEquals(1,result.routes);assertEquals(2,result.photos);assertEquals(0,result.missing)
            ZipFile(out).use { zip ->
                assertNotNull(zip.getEntry("manifest.json"))
                assertNotNull(zip.getEntry("routes.json"))
                assertNotNull(zip.getEntry("proofs/payment_backup_test.jpg"))
                assertNotNull(zip.getEntry("proofs/delivery_backup_test.jpg"))
            }
            payment.delete();delivery.delete()
            val second=db()
            val restored=HistoryBackup(app,second.dao()).restore(Uri.fromFile(out))
            assertEquals(1,restored.restored);assertEquals(0,restored.skipped)
            assertTrue(payment.isFile);assertTrue(delivery.isFile)
            val recovered=PlanJson.decode(requireNotNull(second.dao().route(original.id)).json)
            assertEquals(original,recovered)
            assertEquals(2,recovered.stops.count { it.place.postal=="735689" })
            assertEquals(2,recovered.stops.map { it.orderId }.distinct().size)
            assertTrue(generateSingleDeliveryReport(app,recovered.stops[0],0).isFile)
            val current=original.copy(remark="TODAY'S UNSENT CUSTOMER NOTES")
            second.dao().save(SavedRoute(current.id,PlanJson.encode(current),current.created))
            val next=HistoryBackup(app,second.dao()).restore(Uri.fromFile(out))
            assertEquals(0,next.restored);assertEquals(1,next.skipped)
            assertEquals(current,PlanJson.decode(requireNotNull(second.dao().route(current.id)).json))
        } finally {out.delete();payment.delete();delivery.delete()}
    }

    @Test fun maliciousArchiveEntryIsRejectedWithoutTouchingExistingProof()=runBlocking {
        val existing=createProof("payment_backup_test.jpg",Color.BLUE)
        val dest=db()
        val out=File(app.cacheDir,"invalid-history-"+UUID.randomUUID()+".zip")
        try {
            java.util.zip.ZipOutputStream(out.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("../evil"))
                zip.write(byteArrayOf(3,4,5))
                zip.closeEntry()
            }
            try {HistoryBackup(app,dest.dao()).restore(Uri.fromFile(out));fail("Malicious ZIP accepted")}
            catch(e:Exception){assertTrue(e.message.orEmpty().contains("Unexpected"))}
            assertTrue(existing.isFile)
        }finally{out.delete();existing.delete()}
    }
}
