package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import java.io.File
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w400dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DeliveryStatusStamps889Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val output = File("build/reports/b889-status-stamps").apply { mkdirs() }
    private var page by mutableStateOf("Route")
    private var screenWidth by mutableIntStateOf(400)
    private var current by mutableStateOf(false)
    private var stop by mutableStateOf(sample("DELIVERED", "COD", false))
    private fun sample(status: String, payment: String?, long: Boolean): Stop {
        val order = CustomerOrder(orderId="sample-order-888", postalCode="520395",
            customerName=if (long) "SAMPLE CUSTOMER WITH A LONG DELIVERY NAME" else "SAMPLE CUSTOMER",
            phoneNumber="+6580000000",
            fullAddress=if (long) "Blk 395 Tampines Avenue 7, #05-123, Long Building Name and Additional Delivery Instructions, Singapore 520395" else "Blk 395 Tampines Avenue 7, #05-123, Singapore 520395",
            parcelPrice=BigDecimal("12.00"), paymentStatus=payment)
        return Stop(Place("520395",1.35,103.94,"395","Tampines","Tampines Avenue 7"),
            Leg(2.4,240.0,0.0),"2026-11-28T10:04:00","2026-11-28T10:14:00",2.4,
            status=status, completedAt=if(status=="DELIVERED") "2026-11-28T10:15:00" else null,
            holdReason=if(status=="ON_HOLD") "Customer not home" else null,
            holdNote=if(status=="ON_HOLD") "Awaiting customer response" else null,
            reviewedAt="2026-11-28T10:15:00", orderId=order.orderId, order=order)
    }
    private fun plan(s: Stop) = Plan("sample-route-888", "2026-11-28T10:00:00", "2026-11-28T10:00:00",
        10,"Balanced",listOf(s),Leg(0.0,0.0,0.0),"2026-11-28T10:14:00",emptyList())
    private fun mount() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val vm=PlannerViewModel(app,object: ExchangeRateProvider { override suspend fun finalSgdMyrRate()="3.60" })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f,1f), LocalContentColor provides Color.White) {
                IzzDeliveryTheme(true) {
                    MarinaBayBackdrop(Modifier.fillMaxSize()) {
                        Box(Modifier.width(screenWidth.dp).padding(16.dp)) {
                            if(page=="Route") StopCard(vm,plan(stop),stop,0,current=current,onClick={})
                            else V6DeliveryCard(stop,0,{})
                        }
                    }
                }
            }
        }
    }
    private fun capture(): Bitmap {
        compose.waitForIdle()
        val bounds=compose.onNodeWithTag("delivery-status-card",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        var result: Bitmap?=null
        compose.runOnIdle {
            val view=compose.activity.findViewById<View>(android.R.id.content)
            val full=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(full))
            result=Bitmap.createBitmap(full,bounds.left.toInt(),bounds.top.toInt(),bounds.width.toInt(),bounds.height.toInt())
            full.recycle()
        }
        return requireNotNull(result)
    }
    private fun save(bitmap: Bitmap, name: String) {
        File(output,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun pixelHash(bitmap: Bitmap): String {
        val pixels=IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val bytes=ByteBuffer.allocate(pixels.size*4)
        bytes.asIntBuffer().put(pixels)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }
    @Test fun liveCardsAndExportedReportsMatchAll18ApprovedRendersByDefault() {
        val approved=javaClass.classLoader!!.getResourceAsStream("status-stamps/approved-ui.json")!!.bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject.getAsJsonArray("comparisons").associate { item ->
                val value=item.asJsonObject; value["name"].asString to value
            }
        }
        mount()
        data class Case(val label: String,val status: String,val payment: String?,val long: Boolean,val width: Int)
        val cases=listOf("DELIVERED","ON_HOLD","PENDING").map { Case("Reference",it,"COD",false,400) } + listOf(
            Case("NarrowLongCOD","DELIVERED","COD",true,320),
            Case("NarrowLongPayNow","ON_HOLD","PAYNOW",true,320),
            Case("NarrowLongNoPayment","PENDING",null,true,320))
        val checked=mutableListOf<Map<String,Any>>()
        fun check(name: String, bitmap: Bitmap) {
            save(bitmap,"$name-AFTER")
            val expected=approved.getValue(name)
            assertEquals("$name width",expected["width"].asInt,bitmap.width)
            assertEquals("$name height",expected["height"].asInt,bitmap.height)
            val digest=pixelHash(bitmap)
            assertEquals("$name differs from the UI approved by the user",expected["approvedArgbPixelSha256"].asString,digest)
            checked+=mapOf("case" to name,"width" to bitmap.width,"height" to bitmap.height,"approvedPixelsMatch" to true,"argbPixelSha256" to digest)
            bitmap.recycle()
        }
        val app=ApplicationProvider.getApplicationContext<Application>()
        for(c in cases) {
            for(p in listOf("Route","History")) {
                compose.runOnIdle {page=p;stop=sample(c.status,c.payment,c.long);screenWidth=c.width}
                check("${c.label}-$p-${c.status}",capture())
            }
            val s=sample(c.status,c.payment,c.long)
            check("${c.label}-DeliveryReport-${c.status}",BitmapFactory.decodeFile(generateSingleDeliveryReport(app,s,0).absolutePath))
        }
        assertEquals(18,checked.size)
        File(output,"B889-runtime-ui-verification.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
            mapOf("build" to 889,"result" to "PASS","defaultLiveRendering" to true,"previewSwitchRequired" to false,
                "renderer" to "Android Compose / Android Canvas, Robolectric native graphics API 35","cases" to checked)))
    }
    private fun greenBounds(bitmap: Bitmap): Rect {
        var left=bitmap.width;var top=bitmap.height;var right=0;var bottom=0
        for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
            val pixel=bitmap.getPixel(x,y)
            if(android.graphics.Color.green(pixel)>200 && android.graphics.Color.red(pixel)<120 && android.graphics.Color.blue(pixel)<120) {
                left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x+1);bottom=maxOf(bottom,y+1)
            }
        }
        assertTrue("Delivered artwork is missing",right>left && bottom>top)
        return Rect(left.toFloat(),top.toFloat(),right.toFloat(),bottom.toFloat())
    }
    @Test fun currentDeliveryAndInPlacePaymentChangesKeepStampsClearOfText() {
        mount()
        compose.runOnIdle {screenWidth=320;current=true}
        val route=capture();save(route,"Current-Route-320")
        val art=greenBounds(route)
        val root=compose.onNodeWithTag("delivery-status-card",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val node=compose.onNodeWithText("Current delivery",useUnmergedTree=true)
        val bounds=node.fetchSemanticsNode().boundsInRoot
        val layouts=mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        val inkRight=(0 until layouts.single().lineCount).maxOf {layouts.single().getLineRight(it)}
        val ink=Rect(bounds.left-root.left,bounds.top-root.top,bounds.left-root.left+inkRight,bounds.bottom-root.top)
        assertFalse("Current delivery label overlaps stamp",art.overlaps(ink));route.recycle()
        val positions=mutableMapOf<String,Rect>()
        for(payment in listOf("COD","NONE","PAYNOW","COD")) {
            compose.runOnIdle {page="History";screenWidth=400;current=false;stop=sample("DELIVERED",payment.takeUnless{it=="NONE"},false)}
            val bitmap=capture();save(bitmap,"Payment-Change-$payment");positions[payment]=greenBounds(bitmap);bitmap.recycle()
        }
        assertTrue("Removed payment stamp still reserves its old position",positions.getValue("NONE").left>positions.getValue("COD").left+60f)
        compose.runOnIdle {page="Route";stop=sample("SKIPPED","COD",false)}
        compose.onNodeWithText("Skipped",useUnmergedTree=true).assertIsDisplayed()
    }
}
