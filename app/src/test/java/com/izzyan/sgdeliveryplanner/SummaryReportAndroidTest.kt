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
class SummaryReportAndroidTest {
    @Test
    fun fullEnglishReportUsesTheRequestedOrderAndSavedMoneyWithoutNetCash() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ms-MY"))
            val plan = route().copy(cashOnHand = "320", tax = "12.5")
            assertEquals(
                """IZZ Delivery Summary
Date: 3 Oct 2026
Start Location: Woodlands Checkpoint — 21 Woodlands Crossing, Singapore 738203
End Location: Woodlands Checkpoint — 21 Woodlands Crossing, Singapore 738203
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
Tax: SGD 12.50""",
                buildSummaryReport(plan)
            )
            assertFalse(buildSummaryReport(plan).contains("Net Cash", ignoreCase = true))
        } finally {
            Locale.setDefault(original)
        }
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
    fun reportUsesTheSavedHomeSnapshotAndHandlesNoDeliveries() {
        val home = StartLocation("HOME", 1.35, 103.8, "Home", "8 Example Road, Singapore 005003", "005003")
        val plan = route().copy(startLocation = home, stops = emptyList(), reviewedFinishedAt = null)
        val text = buildSummaryReport(plan)
        assertTrue(text.contains("Start Location: Home — 8 Example Road, Singapore 005003\n"))
        assertTrue(text.contains("End Location: Home — 8 Example Road, Singapore 005003\n"))
        assertTrue(text.contains("Total Parcel: 0\nDelivered: 0\nOn Hold: 0\nSkipped: 0\nPending: 0\nSuccess Rate: 0.0%"))
        assertEquals("Home — 8 Example Road, Singapore 005003", plan.startLocation.reportLabel)
    }

    @Test
    fun shareIntentContainsTheCompleteTextWithPlainTextMimeType() {
        val plan = route()
        val intent = summaryReportShareIntent(plan)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("IZZ Delivery Summary", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(buildSummaryReport(plan), intent.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
    }

    @Test
    fun shareReportOpensAnAndroidChooserWithTheCompleteReport() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val plan = route()
        shareSummaryReport(app, plan)
        val chooser = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Share Report", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(buildSummaryReport(plan), send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun copyTextCopiesTheCompleteReportAndShowsTheRequestedConfirmation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val plan = route()
        copySummaryReport(app, plan)
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = requireNotNull(clipboard.primaryClip)
        assertEquals("IZZ Delivery Summary", clip.description.label)
        assertEquals(1, clip.itemCount)
        assertEquals(buildSummaryReport(plan), clip.getItemAt(0).coerceToText(app).toString())
        assertEquals("Report copied to clipboard", ShadowToast.getTextOfLatestToast())
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
