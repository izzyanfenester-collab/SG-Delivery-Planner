package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDateTime
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class SummaryReportAndroidTest {
    @org.junit.Before fun resetProviderRoots() {
        // Robolectric assigns a new app cache directory per test, unlike an Android process.
        val cache=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply {isAccessible=true}
        (cache.get(null) as MutableMap<*,*>).clear()
    }
    @Test
    fun fullEnglishReportUsesTheRequestedOrderAndSavedMoneyWithoutNetCash() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ms-MY"))
            val plan = route().copy(cashOnHand = "320", tax = "12.5", remark = "Fuel receipt retained")
            assertEquals(
                """Delivery Report Summary
Date: 3 Oct 2026
Start: 10:00 AM
Finish: 11:00 AM
Total Parcel: 4
Delivered: 1
On Hold: 1
Skipped: 1
Pending: 1
Success Rate: 25.0%
Total KM: 20.0 KM
Cash on Hand: SGD 320.00
Tax: SGD 12.50 (RM 45.00)
Rate: 3.60
Remark: Fuel receipt retained""",
                buildSummaryReport(plan)
            )
            assertFalse(buildSummaryReport(plan).contains("Net Cash", ignoreCase = true))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun multilineRemarkKeepsCompactFieldsInPreviewShareAndClipboardText() {
        val plan = route().copy(remark = "timah belum bayar\nabu xde rumah")
        val report = buildSummaryReport(plan)
        assertFalse(report.contains("\n\n"))
        assertEquals(16, report.lines().size)
        assertTrue(report.endsWith("Remark: timah belum bayar\nabu xde rumah"))
        val app = ApplicationProvider.getApplicationContext<Application>()
        copySummaryReport(app, plan)
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(report, clipboard.primaryClip!!.getItemAt(0).coerceToText(app).toString())
        assertEquals("timah belum bayar\nabu xde rumah", plan.remark)
    }

    @Test
    fun aMidnightFinishIncludesItsDateAndZeroMoneyShowsTwoDecimalPlaces() {
        val plan = route().copy(start = "2026-10-03T23:00", reviewedFinishedAt = "2026-10-04T00:15")
        val text = buildSummaryReport(plan)
        assertTrue(text.contains("Date: 3 Oct 2026\n"))
        assertTrue(text.contains("Start: 11:00 PM\n"))
        assertTrue(text.contains("Finish: 12:15 AM (4 Oct 2026)\n"))
        assertTrue(text.contains("Cash on Hand: SGD 0.00\nTax: SGD 0.00"))
    }

    @Test
    fun reportOmitsLocationsWithoutChangingSavedHomeAndHandlesNoDeliveries() {
        val home = StartLocation("HOME", 1.35, 103.8, "Home", "8 Example Road, Singapore 005003", "005003")
        val plan = route().copy(startLocation = home, stops = emptyList(), reviewedFinishedAt = null)
        val text = buildSummaryReport(plan)
        assertFalse(text.contains("Start Location"))
        assertFalse(text.contains("End Location"))
        assertTrue(text.contains("Total Parcel: 0\nDelivered: 0\nOn Hold: 0\nSkipped: 0\nPending: 0\nSuccess Rate: 0.0%"))
        assertEquals("Home — 8 Example Road, Singapore 005003", plan.startLocation.reportLabel)
    }

    @Test
    fun shareIntentContainsOnlyTheThemedPngWithReadPermission() {
        val plan = route()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val intent = summaryReportShareIntent(app,plan)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals("Delivery Report Summary", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri=requireNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        assertEquals("content",uri.scheme)
        assertEquals("${app.packageName}.excel-files",uri.authority)
        assertNotNull(intent.clipData)
    }

    @Test
    fun shareReportOpensAnAndroidChooserWithTheCompleteReport() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val plan = route()
        openSummaryReportShare(app,summaryReportShareIntent(app,plan))
        val chooser = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Share Report", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
        assertNotNull(send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
    }

    @Test
    fun copyTextCopiesTheCompleteReportAndShowsTheRequestedConfirmation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val plan = route()
        copySummaryReport(app, plan)
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = requireNotNull(clipboard.primaryClip)
        assertEquals("Delivery Report Summary", clip.description.label)
        assertEquals(1, clip.itemCount)
        assertEquals(buildSummaryReport(plan), clip.getItemAt(0).coerceToText(app).toString())
        assertEquals("Report copied to clipboard", ShadowToast.getTextOfLatestToast())
    }

    @Test fun summaryPngHasNavyGoldThemeAndWrappingRemarkExpandsWithoutChangingSavedValues() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val original=route().copy(cashOnHand="400.00",tax="15.00",exchangeRate="3.59",remark="timah belum bayar\nabu xde rumah")
        val before=PlanJson.encode(original)
        val file=generateSummaryReportImage(app,original)
        val png=android.graphics.BitmapFactory.decodeFile(file.path)
        assertEquals(1080,png.width)
        assertEquals(android.graphics.Color.rgb(7,23,45),png.getPixel(0,0))
        assertEquals(android.graphics.Color.rgb(16,39,68),png.getPixel(35,png.height/2))
        var gold=false;var letters=false
        for(y in 20 until png.height-20 step 2) for(x in 20 until png.width-20 step 2) {
            val pixel=png.getPixel(x,y)
            if(android.graphics.Color.red(pixel)>180 && android.graphics.Color.green(pixel)>130 && android.graphics.Color.blue(pixel)<130) gold=true
            if(android.graphics.Color.red(pixel)>210 && android.graphics.Color.green(pixel)>210 && android.graphics.Color.blue(pixel)>210) letters=true
        }
        assertTrue("Gold accents missing",gold);assertTrue("Summary text not rendered",letters)
        val more=generateSummaryReportImage(app,original.copy(remark=(1..20).joinToString("\n") {"Additional remark $it"}))
        val longer=android.graphics.BitmapFactory.decodeFile(more.path)
        assertTrue(longer.height > png.height)
        png.recycle();longer.recycle()
        assertEquals(before,PlanJson.encode(original))
        assertTrue(buildSummaryReport(original).contains("Tax: SGD 15.00 (RM 53.85)"))
    }

    private fun route(): Plan {
        val plan = schedule(
            (1..4).map { index -> Place("00500$index", 1.35, 103.8, "$index", "Example area", "$index Example Road") },
            List(5) { Leg(4.0, 300.0, 60.0) },
            LocalDateTime.of(2026, 10, 3, 10, 0),
            8,
            "Normal Traffic",
            emptyList()
        )
        val statuses = listOf("DELIVERED", "ON_HOLD", "SKIPPED", "PENDING")
        return plan.copy(
            stops = plan.stops.mapIndexed { index, stop -> stop.copy(status = statuses[index]) },
            reviewedFinishedAt = "2026-10-03T11:00"
        )
    }
}
