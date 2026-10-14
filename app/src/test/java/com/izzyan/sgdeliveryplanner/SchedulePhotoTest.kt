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

    @Test fun photoContainsOnlyFourColumnsAndAllFiftyStopsInOriginalOrder() {
        val plan = route(50)
        val rows = schedulePhotoRows(plan)
        assertEquals(52, rows.size)
        assertEquals(listOf("START", "738203", "Woodlands", "11:55 PM"), rows.first().cells())
        plan.stops.forEachIndexed { index, stop ->
            val cells = rows[index + 1].cells()
            assertEquals(4, cells.size)
            assertEquals((index + 1).toString(), cells[0])
            assertEquals(stop.place.postal, cells[1])
            assertEquals(stop.place.area, cells[2])
            assertFalse(cells[3].contains("2026"))
            assertTrue(cells[3].matches(Regex("\\d{1,2}:\\d{2} [AP]M")))
        }
        assertEquals("END", rows.last().stop)
        assertEquals(plan.returned.substring(11, 13).toInt() >= 0, true)
        assertTrue(rows.last().arrival.matches(Regex("\\d{1,2}:\\d{2} [AP]M")))
        assertFalse(rows.any { it.cells().any { field -> field.contains("Example address") } })
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
                assertEquals(1080, bitmap.width)
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
