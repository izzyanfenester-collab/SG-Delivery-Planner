package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDateTime
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DefaultWhatsAppSharing850Test {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    @Before fun reset() {
        app.getSharedPreferences("whatsapp_preferences",0).edit().clear().commit()
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*,*>).clear()
    }
    private fun route() = schedule(listOf(depot.copy(postal="001234")),
        List(2) { Leg(1.0,100.0,0.0) }, LocalDateTime.of(2026,11,13,9,0),10,"Normal",emptyList())

    @Test fun bothActualImagesFollowCurrentDefaultWithoutChooserAndKeepReadGrants() {
        val ctx = RecordingContext(app)
        val plan = route()
        val report = generateSingleDeliveryReport(app,plan.stops[0],0)
        val photo = generateSchedulePhoto(app,plan)
        for (file in listOf(report,photo)) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            assertNotNull(bitmap); assertTrue(bitmap!!.width > 0 && bitmap.height > 0); bitmap.recycle()
        }
        val sends = listOf(singleDeliveryShareIntent(app,report),schedulePhotoShareIntent(app,photo))
        assertEquals(listOf("image/png","image/jpeg"), sends.map { it.type })
        val before = PlanJson.encode(plan)
        val prefs = WhatsAppPreferences(app)
        for (choice in WhatsAppChoice.entries) {
            prefs.updateDefault(choice)
            for (send in sends) {
                assertNull(openDefaultWhatsAppImageShare(ctx,send,"Share image"))
                val actual = ctx.started.last()
                assertEquals(Intent.ACTION_SEND,actual.action)
                assertEquals(choice.packageName,actual.`package`)
                assertEquals(send.type,actual.type)
                val uri = requireNotNull(actual.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                assertEquals("content",uri.scheme)
                assertEquals("${app.packageName}.excel-files",uri.authority)
                assertEquals(uri,actual.clipData!!.getItemAt(0).uri)
                assertEquals(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM),uri)
                assertTrue(actual.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertNull(send.`package`)
                assertNull(actual.getStringExtra(Intent.EXTRA_TEXT))
                app.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read() >= 0) }
            }
        }
        assertEquals(4,ctx.started.size)
        assertEquals(before,PlanJson.encode(plan))
    }

    @Test fun missingDefaultOffersOtherAppOrUnrestrictedChooserWithoutChangingPreference() {
        val ctx = RecordingContext(app)
        val send = singleDeliveryShareIntent(app,generateSingleDeliveryReport(app,route().stops[0],0))
        val prefs = WhatsAppPreferences(app)
        prefs.updateDefault(WhatsAppChoice.BUSINESS)
        ctx.unavailable = WhatsAppChoice.BUSINESS.packageName
        val failure = requireNotNull(openDefaultWhatsAppImageShare(ctx,send,"Share Delivery Report"))
        assertEquals(WhatsAppChoice.BUSINESS,failure.unavailable)
        assertTrue(ctx.started.isEmpty())
        assertTrue(tryWhatsAppImageShare(ctx,failure.send,WhatsAppChoice.PERSONAL))
        assertEquals(WhatsAppChoice.PERSONAL.packageName,ctx.started.last().`package`)
        assertEquals(WhatsAppChoice.BUSINESS,WhatsAppPreferences(app).default)
        openImageShareMenu(ctx,failure.send,failure.title)
        val chooser = ctx.started.last()
        assertEquals(Intent.ACTION_CHOOSER,chooser.action)
        val inner = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertNull(inner.`package`)
        assertEquals(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM),inner.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(WhatsAppChoice.BUSINESS,WhatsAppPreferences(app).default)
    }

    @Test fun askEveryTimeUsesOriginalAndroidChooser() {
        val ctx = RecordingContext(app)
        val send = schedulePhotoShareIntent(app,generateSchedulePhoto(app,route()))
        assertNull(WhatsAppPreferences(app).default)
        assertNull(openDefaultWhatsAppImageShare(ctx,send,"Share Photo"))
        assertEquals(Intent.ACTION_CHOOSER,ctx.started.single().action)
        val inner = requireNotNull(ctx.started.single().getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals("image/jpeg",inner.type)
        assertNull(inner.`package`)
    }
    private class RecordingContext(app: Application): ContextWrapper(app) {
        val started = mutableListOf<Intent>()
        var unavailable: String? = null
        override fun startActivity(intent: Intent) {
            if (unavailable != null && intent.`package` == unavailable) throw ActivityNotFoundException()
            started.add(Intent(intent))
        }
    }
}
