package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.FileProvider
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SummaryDefaultWhatsApp871Test {
    private val app:Application get()=ApplicationProvider.getApplicationContext()
    @Before fun reset(){
        app.getSharedPreferences("whatsapp_preferences",0).edit().clear().commit()
        val cache=FileProvider::class.java.getDeclaredField("sCache").apply{isAccessible=true}
        (cache.get(null) as MutableMap<*,*>).clear()
    }
    private fun completed()=schedule(
        listOf(depot.copy(postal="460011")),
        List(2){Leg(1.0,100.0,0.0)},
        LocalDateTime.of(2026,11,18,9,0),10,"Normal",emptyList()
    ).copy(remark="Previously saved summary; do not reopen delivery",
        cashOnHand="120",tax="8.50")

    private class RecordingContext(app:Application):ContextWrapper(app) {
        val launched=mutableListOf<Intent>()
        var unavailable:String?=null
        override fun startActivity(intent:Intent) {
            if(unavailable!=null && intent.`package`==unavailable)throw ActivityNotFoundException()
            launched+=Intent(intent)
        }
    }

    @Test fun messengerAndBusinessOpenDirectlyWithTheSamePngAndOriginalSummaryData() {
        val plan=completed()
        val before=PlanJson.encode(plan)
        val ctx=RecordingContext(app)
        val prefs=WhatsAppPreferences(app)
        for(choice in WhatsAppChoice.entries) {
            prefs.updateDefault(choice)
            val intent=summaryReportShareIntent(app,plan)
            assertEquals("image/png",intent.type)
            assertNull(openSummaryReportShare(ctx,intent))
            val actual=ctx.launched.last()
            assertEquals(Intent.ACTION_SEND,actual.action)
            assertEquals(choice.packageName,actual.`package`)
            assertEquals("image/png",actual.type)
            assertEquals("Delivery Report Summary",actual.getStringExtra(Intent.EXTRA_SUBJECT))
            assertNull(actual.getStringExtra(Intent.EXTRA_TEXT))
            assertTrue(actual.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            val uri=requireNotNull(actual.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertEquals("content",uri.scheme)
            assertEquals("${app.packageName}.excel-files",uri.authority)
            assertEquals(uri,actual.clipData?.getItemAt(0)?.uri)
            app.contentResolver.openInputStream(uri)!!.use{
                val actualBytes=it.readBytes()
                assertTrue(actualBytes.size>3000)
                assertEquals(0x89,actualBytes[0].toInt() and 0xff)
                assertEquals(0x50,actualBytes[1].toInt() and 0xff)
            }
            assertEquals(before,PlanJson.encode(plan))
        }
        assertEquals(2,ctx.launched.size)
        assertEquals(WhatsAppChoice.BUSINESS,WhatsAppPreferences(app).default)
    }

    @Test fun unavailableSelectedAppReturnsConsentDialogRequestWithoutChooserOrChangingPrefs() {
        val plan=completed();val before=PlanJson.encode(plan)
        val ctx=RecordingContext(app)
        val prefs=WhatsAppPreferences(app)
        prefs.updateDefault(WhatsAppChoice.BUSINESS)
        ctx.unavailable=WhatsAppChoice.BUSINESS.packageName
        val share=summaryReportShareIntent(app,plan)
        val failure=requireNotNull(openSummaryReportShare(ctx,share))
        assertEquals(WhatsAppChoice.BUSINESS,failure.unavailable)
        assertTrue(ctx.launched.isEmpty()) // UI must request consent
        assertEquals("image/png",failure.send.type)
        assertTrue(tryWhatsAppImageShare(ctx,failure.send,WhatsAppChoice.PERSONAL))
        assertEquals("com.whatsapp",ctx.launched.single().`package`)
        assertEquals(WhatsAppChoice.BUSINESS,WhatsAppPreferences(app).default)
        assertEquals(before,PlanJson.encode(plan))
    }

    @Test fun userAsksEveryTimeStillGetsUnrestrictedSystemChooser() {
        val ctx=RecordingContext(app)
        WhatsAppPreferences(app).updateDefault(null)
        val share=summaryReportShareIntent(app,completed())
        assertNull(openSummaryReportShare(ctx,share))
        val chooser=ctx.launched.single()
        assertEquals(Intent.ACTION_CHOOSER,chooser.action)
        val send=requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND,send.action)
        assertNull(send.`package`)
        assertNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }

    @Test fun savedHistoricalSummaryKeepsItsOldDateAndSameGeneratedImageUri() {
        val plan=completed()
        val saved=PlanJson.decode(PlanJson.encode(plan))
        val unchanged=PlanJson.encode(saved)
        val png=generateSummaryReportImage(app,saved)
        val bitmap=BitmapFactory.decodeFile(png.absolutePath)
        assertNotNull(bitmap);assertEquals(1080,bitmap!!.width);bitmap.recycle()
        val send=singleDeliveryShareIntent(app,png)
        val ctx=RecordingContext(app)
        WhatsAppPreferences(app).updateDefault(WhatsAppChoice.PERSONAL)
        assertNull(openDefaultWhatsAppImageShare(ctx,send,"Share Historical Summary Report"))
        val uri=requireNotNull(ctx.launched.single().getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        app.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read()>0) }
        assertEquals(unchanged,PlanJson.encode(saved))
        assertTrue(png.isFile)
    }
}
