package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDateTime
import java.util.Locale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SchedulePhotoTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun resetProviderCache() {
        // Robolectric may allocate a new cache path each test; FileProvider caches old roots.
        val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(null) as MutableMap<Any, Any>
        synchronized(cache) { cache.clear() }
    }

    private fun route(count: Int): Plan {
        val stops = (1..count).map { index ->
            Place(String.format(Locale.US, "%06d", index),
                1.32, 103.81, "Block $index", "Area $index", "Example address")
        }
        return schedule(stops, List(count + 1) { Leg(1.0, 300.0, 30.0) },
            LocalDateTime.of(2026, 10, 14, 23, 55), 8, "Normal", emptyList())
    }

    @Test fun photoContainsOnlyDeliveryRowsAndAllFiftyStopsInOriginalOrder() {
        val plan = route(50)
        val rows = schedulePhotoRows(plan)
        assertEquals("No START or END depot rows in shared photo", 50, rows.size)
        plan.stops.forEachIndexed { index, stop ->
            val cells = rows[index].cells()
            assertEquals(5, cells.size)
            assertEquals((index + 1).toString(), cells[0])
            assertEquals(stop.place.postal, cells[1])
            assertEquals("—", cells[2]) // Manual postal has no customer name.
            assertEquals(stop.place.area, cells[3])
            assertFalse(cells[4].contains("2026"))
            assertTrue(cells[4].matches(Regex("""\d{1,2}:\d{2} [AP]M – \d{1,2}:\d{2} [AP]M""")))
            val formatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
            val expectedPeriod = listOf(stop.arrival, stop.leave).joinToString(" – ") {
                LocalDateTime.parse(it).format(formatter)
            }
            assertEquals("Delivery period must match this stop's arrival and departure", expectedPeriod, cells[4])
        }
        assertEquals("1", rows.first().stop)
        assertEquals("50", rows.last().stop)
        assertFalse(rows.any { it.stop == "START" || it.stop == "END" })
        assertFalse(rows.any { it.cells().any { field -> field.contains("Example address") } })
        // Keep the full depot-to-depot plan intact in Route page and persisted history.
        assertEquals(50, plan.stops.size)
        assertEquals("738203", plan.startLocation.postalCode)
        assertTrue(plan.returned.isNotBlank())
    }

    @Test fun deliveryPeriodShowsStartAndFinishEvenWhenCrossingMidnight() {
        val original = route(1)
        val adjustedStop = original.stops.single().copy(
            arrival = "2026-10-18T23:57:00",
            leave = "2026-10-19T00:05:00"
        )
        val rows = schedulePhotoRows(original.copy(stops = listOf(adjustedStop)))
        assertEquals("11:57 PM – 12:05 AM", rows.single().deliveryPeriod)
        assertEquals(5, rows.single().cells().size)
        // The shared image changes presentation only; the stored stop is unchanged.
        assertEquals("2026-10-18T23:57:00", adjustedStop.arrival)
        assertEquals("2026-10-19T00:05:00", adjustedStop.leave)
    }

    @Test fun deliveryAtSamePostalAsDepotStillAppearsInPhoto() {
        val original = route(2)
        val actualDepotDelivery = original.stops[0].copy(
            place = original.stops[0].place.copy(postal = "738203", area = "Woodlands"),
            order = CustomerOrder(postalCode = "738203", customerName = "Test Recipient"))
        val plan = original.copy(stops = listOf(actualDepotDelivery, original.stops[1]))
        val rows = schedulePhotoRows(plan)
        assertEquals(2, rows.size)
        assertEquals("738203", rows[0].postalCode)
        assertEquals("Test Recipient", rows[0].customerName)
        assertEquals("1", rows[0].stop)
        assertEquals("2", rows[1].stop)
    }

    @Test fun customerNameAppearsBesidePostalForSpecificParcelIncludingDuplicatePostals() {
        val original = route(3)
        val duplicatePostal = original.stops[0].place.postal
        val otherPostal = original.stops[2].place.postal
        val named = original.copy(stops = listOf(
            original.stops[0].copy(
                order = CustomerOrder(postalCode = duplicatePostal, customerName = "Cik Jamaliah Hassan",
                    phoneNumber = "+6591234567", fullAddress = "Private address A",
                    parcelPrice = java.math.BigDecimal("200.00"), originalMessage = "Private order A")
            ),
            original.stops[1].copy(
                place = original.stops[0].place,
                order = CustomerOrder(postalCode = duplicatePostal, customerName = "Nurul Atikah Binti Abidin",
                    phoneNumber = "+6599999999", fullAddress = "Private address B",
                    originalMessage = "Private order B")
            ),
            original.stops[2].copy(
                order = CustomerOrder(postalCode = otherPostal,
                    customerName = "    ", originalMessage = "No name")
            )
        ))
        val rows = schedulePhotoRows(named)
        assertEquals(3, rows.size)
        assertEquals("1", rows[0].stop)
        assertEquals("2", rows[1].stop)
        assertEquals("3", rows[2].stop)
        assertEquals(duplicatePostal, rows[0].postalCode)
        assertEquals("Cik Jamaliah Hassan", rows[0].customerName)
        assertEquals(duplicatePostal, rows[1].postalCode)
        assertEquals("Nurul Atikah Binti Abidin", rows[1].customerName)
        assertEquals("—", rows[2].customerName)
        assertFalse(rows.any { it.stop == "START" || it.stop == "END" })
        assertFalse(rows.any { it.cells().any { cell ->
            cell.contains("Private address") || cell.contains("Private order") ||
                cell.contains("+65") || cell.contains("200.00")
        } })
        // Customer names must not be replaced by another parcel at a shared postal code.
        assertEquals(listOf("Cik Jamaliah Hassan", "Nurul Atikah Binti Abidin", "—"),
            rows.map { it.customerName })
    }

    @Test fun veryLongCustomerNameWrapsAndFullJpegIncludesAllStops() {
        val original = route(50)
        val longName = "Nurul Atikah Binti Abidin / Second Name Mohd Zulkarnaini " +
            "and additional long customer text safely wrapped"
        val modified = original.copy(stops = original.stops.mapIndexed { index, stop ->
            if (index == 17) stop.copy(
                order = CustomerOrder(postalCode = stop.place.postal, customerName = longName))
            else stop
        })
        assertEquals(longName, schedulePhotoRows(modified)[17].customerName)
        val baselineHeight = generateSchedulePhoto(context, original).let { normal ->
            try {
                val image = requireNotNull(BitmapFactory.decodeFile(normal.absolutePath))
                image.height.also { image.recycle() }
            } finally { normal.delete() }
        }
        val expanded = generateSchedulePhoto(context, modified)
        try {
            val bitmap = requireNotNull(BitmapFactory.decodeFile(expanded.absolutePath))
            try {
                assertEquals(1440, bitmap.width)
                assertTrue("Long names must not be truncated", bitmap.height > baselineHeight)
            } finally { bitmap.recycle() }
        } finally { expanded.delete() }
    }

    @Test fun photoIsRealJpegAndShareIntentGrantsReadOnlyAccessToOneFile() {
        val plan = route(2)
        val file = generateSchedulePhoto(context, plan)
        try {
            assertTrue(file.isFile)
            assertEquals("jpg", file.extension)
            assertTrue(file.absolutePath.contains("delivery_reports/schedule_photos/"))
            val bytes = file.readBytes()
            assertEquals(0xFF, bytes[0].toInt() and 0xFF)
            assertEquals(0xD8, bytes[1].toInt() and 0xFF)
            val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
            try {
                assertEquals(1440, bitmap.width)
                assertTrue(bitmap.height > 250)
            } finally {
                bitmap.recycle()
            }
            val intent = schedulePhotoShareIntent(context, file)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("image/jpeg", intent.type)
            val uri = requireNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.excel-files", uri.authority)
            assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals("image/jpeg", context.contentResolver.getType(uri))
        } finally {
            file.delete()
        }
    }
}
