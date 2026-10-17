package com.izzyan.sgdeliveryplanner

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Five visible columns; only the imported customer name is included (never phone, address or price). */
internal data class SchedulePhotoRow(
    val stop: String,
    val postalCode: String,
    val customerName: String,
    val area: String,
    val arrival: String
) {
    fun cells(): List<String> = listOf(stop, postalCode, customerName, area, arrival)
}

private val photoTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

private fun photoTime(value: String): String =
    runCatching { LocalDateTime.parse(value).format(photoTimeFormat) }.getOrDefault(value)

/**
 * Share delivery stops ONLY, in optimized order. The START/END depot remains in the
 * on-screen Route schedule, but is deliberately omitted from the shared JPEG.
 * Do not filter on depot postal: a genuine parcel at the same postal is still delivered.
 */
internal fun schedulePhotoRows(plan: Plan): List<SchedulePhotoRow> =
    plan.stops.mapIndexed { index, stop ->
        SchedulePhotoRow((index + 1).toString(), stop.place.postal,
            stop.order?.customerName?.trim()?.takeIf { it.isNotEmpty() } ?: "—",
            stop.place.area, photoTime(stop.arrival))
    }

private const val PHOTO_WIDTH = 1440
// Stop | Postal Code | Customer Name | Area | Arrival, totals 1392 + 48px side margins.
private val photoWidths = intArrayOf(112, 204, 344, 512, 220)
private const val PHOTO_MARGIN = 24f
private const val PHOTO_PADDING = 11f

private data class PhotoLine(val layouts: List<StaticLayout>, val height: Float)

private fun photoLayout(text: String, width: Int, header: Boolean): StaticLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (header) Color.WHITE else Color.rgb(15, 32, 53)
        textSize = if (header) 27f else 28f
        typeface = if (header) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }
    return StaticLayout.Builder.obtain(text, 0, text.length, paint,
        width - (2 * PHOTO_PADDING).toInt())
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .build()
}

/**
 * Produce a full-length JPEG rather than a screenshot of the visible, horizontally-scrolled table.
 * Only a temporary private cache file is created; route history and proofs are never touched.
 */
internal fun generateSchedulePhoto(context: Context, plan: Plan): File {
    val entries = listOf(SchedulePhotoRow("Stop", "Postal Code", "Customer Name", "Area", "Arrival")) +
        schedulePhotoRows(plan)
    val lines = entries.mapIndexed { index, entry ->
        val layouts = entry.cells().mapIndexed { col, cell ->
            photoLayout(cell, photoWidths[col], index == 0)
        }
        PhotoLine(layouts, (layouts.maxOf { it.height } + 26f)
            .coerceAtLeast(if (index == 0) 78f else 66f))
    }
    val imageHeight = (2 * PHOTO_MARGIN + lines.sumOf { it.height.toDouble() })
        .toInt().coerceAtLeast(1)
    require(imageHeight <= 12000) { "Schedule photo is too tall to render safely." }

    val root = File(context.cacheDir, "delivery_reports/schedule_photos")
    if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create photo export cache.")
    // Keep files shared to external apps available; only remove our old temporary JPEGs.
    val expired = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
    root.listFiles()?.filter {
        it.isFile && it.name.matches(Regex("schedule_[0-9a-f-]{36}\\.jpg")) && it.lastModified() < expired
    }?.forEach { it.delete() }

    val file = File(root, "schedule_${UUID.randomUUID()}.jpg")
    val bitmap = Bitmap.createBitmap(PHOTO_WIDTH, imageHeight, Bitmap.Config.RGB_565)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(240, 245, 252))
        val background = Paint(Paint.ANTI_ALIAS_FLAG)
        val linesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(211, 221, 235)
            strokeWidth = 1.5f
        }
        var y = PHOTO_MARGIN
        lines.forEachIndexed { index, row ->
            background.color = when {
                index == 0 -> Color.rgb(7, 23, 45)
                index % 2 == 0 -> Color.rgb(238, 243, 251)
                else -> Color.WHITE
            }
            canvas.drawRect(RectF(PHOTO_MARGIN, y, PHOTO_WIDTH - PHOTO_MARGIN, y + row.height), background)
            var x = PHOTO_MARGIN
            row.layouts.forEachIndexed { col, layout ->
                canvas.save()
                canvas.translate(x + PHOTO_PADDING, y + (row.height - layout.height) / 2f)
                layout.draw(canvas)
                canvas.restore()
                x += photoWidths[col]
                if (col < photoWidths.lastIndex) {
                    canvas.drawLine(x, y, x, y + row.height, linesPaint)
                }
            }
            canvas.drawLine(PHOTO_MARGIN, y + row.height, PHOTO_WIDTH - PHOTO_MARGIN,
                y + row.height, linesPaint)
            y += row.height
        }
        file.outputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                throw IOException("Could not encode the schedule photo.")
            }
        }
        return file
    } catch (error: Exception) {
        file.delete()
        throw error
    } finally {
        bitmap.recycle()
    }
}

/** FileProvider restricts access to the single JPEG shared through the Android share sheet. */
internal fun schedulePhotoShareIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.excel-files", file)
    return Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
