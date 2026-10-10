package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.content.*
import android.content.pm.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import com.google.gson.JsonParser
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderProofAndReportTest {
    @org.junit.Before fun resetProviderRoots() {
        // Robolectric assigns a new app cache directory per test, unlike an Android process.
        val cache=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply {isAccessible=true}
        (cache.get(null) as MutableMap<*,*>).clear()
    }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun plan(): Plan {
        val place=Place("650417",1.35,103.8,"417","Area","OneMap address")
        val p=schedule(listOf(place,place),List(3){Leg(1.0,120.0,0.0)},LocalDateTime.of(2026,10,10,10,0),8,"Normal",emptyList())
        return p.copy(stops=p.stops.mapIndexed { i,s -> s.copy(orderId="order-$i",order=CustomerOrder(orderId="order-$i",postalCode=place.postal,customerName=if(i==0) "Helen" else "OtherCustomer",phoneNumber=if(i==0) "+6583836087" else "+6581984289",fullAddress=if(i==0) "Helen Unit #10-288" else "Other address",parcelPrice=java.math.BigDecimal(if(i==0) "55.00" else "99.00"),paymentStatus="COD")) })
    }
    private fun image(color:Int):File {
        val file=File(app.cacheDir,"source-$color.png")
        val bitmap=Bitmap.createBitmap(80,120,Bitmap.Config.ARGB_8888).apply {eraseColor(color)}
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}; bitmap.recycle()
        return file
    }
    @Test fun bothProofsPersistIndependentlyForExactOrderAndSurviveStatusTransitions() {
        val original=plan()
        val payment=updateOrderProof(original,"order-0",ProofKind.PAYMENT,"payment_one.jpg","2026-10-10T10:00:00")
        val both=updateOrderProof(payment,"order-0",ProofKind.DELIVERY,"delivery_one.jpg","2026-10-10T10:01:00")
        assertEquals(original.stops[1],both.stops[1]); assertEquals("PENDING",both.stops[0].status)
        val replaced=updateOrderProof(both,"order-0",ProofKind.PAYMENT,"payment_two.jpg","2026-10-10T10:02:00")
        assertEquals("delivery_one.jpg",replaced.stops[0].order!!.proofFileId)
        val replacedDelivery=updateOrderProof(replaced,"order-0",ProofKind.DELIVERY,"delivery_two.jpg","2026-10-10T10:03:00")
        assertEquals("payment_two.jpg",replacedDelivery.stops[0].order!!.paymentProofFileId)
        for(action in listOf("DELIVERED","ON_HOLD","SKIP")) {
            val restored=PlanJson.decode(PlanJson.encode(reviewStop(replacedDelivery,action,"2026-10-10T10:04:00","Other","Note")))
            assertEquals(replacedDelivery.stops[0].order,restored.stops[0].order)
            assertEquals(original.stops[1].order,restored.stops[1].order)
        }
    }
    @Test fun legacyDecodeUsesStableSeparateIdsAndWorksWithoutCustomerFields() {
        val root=JsonParser.parseString(PlanJson.encode(plan())).asJsonObject
        root.getAsJsonArray("stops").forEach {it.asJsonObject.remove("order");it.asJsonObject.remove("orderId")}
        val a=PlanJson.decode(root.toString()); val b=PlanJson.decode(root.toString())
        assertEquals(a.stops.map{it.orderId},b.stops.map{it.orderId});assertEquals(2,a.stops.map{it.orderId}.distinct().size)
        assertNull(a.stops[0].order); assertEquals("OneMap address",customerAddress(a.stops[0]))
        assertTrue(generateSingleDeliveryReport(app,a.stops[0],0).length()>0)
    }
    @Test fun imageStorageOwnsTheCopyAndRejectsUnsafeFileIdsAndInvalidReplacement() {
        val store=OrderProofStore(app);val source=image(Color.RED)
        val id=store.import(Uri.fromFile(source),ProofKind.PAYMENT)
        source.delete(); assertTrue(store.file(id)!!.length()>0)
        assertNull(store.file("../outside.jpg"));assertNull(store.file("/tmp/photo.jpg"))
        val bad=File(app.cacheDir,"bad.txt").apply{writeText("not an image")}
        try {store.import(Uri.fromFile(bad),ProofKind.PAYMENT);fail("Invalid image accepted")}catch(_:Exception){}
        assertNotNull(store.file(id)); assertTrue(store.decode(store.file(id)!!,256)!!.width>0)
    }
    @Test fun reportContainsActualBothImagesAndOnlySelectedCustomerAndUsesPngShareIntent() {
        val store=OrderProofStore(app)
        val payment=store.import(Uri.fromFile(image(Color.RED)),ProofKind.PAYMENT)
        val delivery=store.import(Uri.fromFile(image(Color.BLUE)),ProofKind.DELIVERY)
        val stop=plan().stops[0].copy(status="DELIVERED",completedAt="2026-10-10T10:30:00",order=plan().stops[0].order!!.copy(paymentProofFileId=payment,proofFileId=delivery))
        val text=singleDeliveryReportText(stop,0,"10 Oct 2026, 10:35 AM")
        assertFalse(text.contains("Runner Route Planning")); assertTrue(text.startsWith("Delivery Report\n")); assertTrue(text.contains("Payment Mode : COD")); assertFalse(text.contains("\n\n"));
        assertTrue(text.contains("Helen"));assertTrue(text.contains("$55.00"));assertTrue(text.contains("COD"));assertTrue(text.contains("DELIVERED"))
        assertFalse(text.contains("OtherCustomer"));assertFalse(text.contains("+65 8198"));assertFalse(text.contains("Other address"));assertFalse(text.contains("$99.00"))
        val file=generateSingleDeliveryReport(app,stop,0)
        val png=BitmapFactory.decodeFile(file.path)
        assertEquals(1080,png.width)
        var red=false;var blue=false; var redY=-1; var blueY=-1; var redX=-1; var blueX=-1
        assertEquals(Color.rgb(7,23,45),png.getPixel(0,0))
        for(y in 0 until png.height step 8) for(x in 0 until png.width step 8) {
            val pixel=png.getPixel(x,y)
            if(Color.red(pixel)>200 && Color.blue(pixel)<50) { red=true; if(redY<0) {redY=y;redX=x} }
            if(Color.blue(pixel)>200 && Color.red(pixel)<50) { blue=true; if(blueY<0) {blueY=y;blueX=x} }
        }
        assertEquals("Proofs must occupy the same row",redY,blueY); assertTrue(redX < blueX)
        png.recycle();assertTrue("Payment image missing",red);assertTrue("Delivery image missing",blue)
        val intent=singleDeliveryShareIntent(app,file)
        assertEquals("image/png",intent.type);assertEquals(Intent.ACTION_SEND,intent.action)
        assertNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM));assertNotNull(intent.clipData)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(file.name.endsWith(".png"))
    }
    @Test fun roomReopenRetainsCustomerBothProofsAndFilesAfterDelivered() = kotlinx.coroutines.runBlocking {
        val store=OrderProofStore(app)
        val id=store.import(Uri.fromFile(image(Color.GREEN)),ProofKind.DELIVERY)
        val updated=updateOrderProof(updateOrderProof(plan(),"order-0",ProofKind.PAYMENT,id,"2026-10-10T10:00:00"),"order-0",ProofKind.DELIVERY,id,"2026-10-10T10:01:00")
        val delivered=reviewStop(updated,"DELIVERED","2026-10-10T10:02:00")
        Repository(app).save(delivered)
        val reopened=Repository(app)
        val loaded=reopened.decode(reopened.dao.route(delivered.id)!!)
        assertEquals(delivered.stops[0].order,loaded.stops[0].order)
        assertEquals("DELIVERED",loaded.stops[0].status)
        assertEquals("Helen Unit #10-288",customerAddress(loaded.stops[0]))
        assertNotNull(OrderProofStore(app).file(loaded.stops[0].order!!.proofFileId))
        assertEquals("OtherCustomer",loaded.stops[1].order!!.customerName)
    }
    @Test fun businessWhatsAppUsesExactPhoneWithoutContextAndHandlesMissingApp() {
        val intent=customerWhatsAppIntent("9152 5714",WhatsAppChoice.BUSINESS)
        assertEquals("com.whatsapp.w4b",intent.`package`)
        assertEquals("https://wa.me/6591525714",intent.data.toString())
        assertNull(intent.extras); assertNull(intent.data!!.query)
        assertFalse(openCustomerWhatsApp(app,"9152 5714",WhatsAppChoice.BUSINESS))
        assertEquals("WhatsApp Business is not installed.",ShadowToast.getTextOfLatestToast())
    }
    @Test fun whatsappDefaultPersistsAndAskEveryTimeRestoresChooser() {
        val prefs=WhatsAppPreferences(app)
        assertNull(prefs.default)
        prefs.updateDefault(WhatsAppChoice.BUSINESS)
        assertEquals(WhatsAppChoice.BUSINESS,WhatsAppPreferences(app).default)
        customerWhatsAppIntent("91525714",WhatsAppChoice.PERSONAL) // Just once has no preference mutation.
        assertEquals(WhatsAppChoice.BUSINESS,prefs.default)
        assertFalse(openCustomerWhatsApp(app,"91525714",prefs.default!!))
        assertEquals(WhatsAppChoice.BUSINESS,WhatsAppPreferences(app).default)
        prefs.updateDefault(null)
        assertNull(WhatsAppPreferences(app).default)
    }
    @Test fun whatsappTargetsExactPhoneAndNeverPrefillsMessageAndMissingAppIsSafe() {
        val intent=customerWhatsAppIntent("+65 8383 6087")
        assertEquals("com.whatsapp",intent.`package`);assertEquals("https://wa.me/6583836087",intent.data.toString())
        assertNull(intent.data!!.query);assertNull(intent.extras)
        assertFalse(openCustomerWhatsApp(app,"+6583836087"));assertEquals("WhatsApp is not installed.",ShadowToast.getTextOfLatestToast())
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(intent,ResolveInfo().apply{activityInfo=ActivityInfo().apply{packageName="com.whatsapp";name="ChatActivity";exported=true}})
        assertTrue(openCustomerWhatsApp(app,"+6583836087"))
        assertEquals("https://wa.me/6583836087",Shadows.shadowOf(app).nextStartedActivity.data.toString())
    }
}
