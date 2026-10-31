package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

fun singleDeliveryReportText(stop: Stop, index: Int, generated: String): String = buildString {
    appendLine("Delivery Report")
    appendLine("Stop ${index+1} · ${stop.place.postal}")
    appendLine("Customer : ${stop.order?.customerName ?: "—"}")
    appendLine("Phone : ${displayCustomerPhone(stop.order?.phoneNumber)}")
    appendLine("Address : ${customerAddress(stop)}")
    appendLine("Parcel Price : ${orderPrice(stop.order)}")
    holdDetails(stop)?.let { appendLine("On hold: $it") }
    appendLine("Report generated: $generated (Singapore)")
}

/** Preview and shared PNG
 * use this same rendered image; no alternate screenshot is generated.
 */
internal object DeliveryReportTypography {
    const val bodyPx = 48f   // original 32
    const val proofPx = 45f  // original 30
    const val statusPx = 46.5f // original 31
    const val titlePx = statusPx
    const val bodySp = 16 // 48px body at the report preview scale
}

/**
 * Keep the original event timestamp on historical delivery reports. Previously
 * every preview/re-share used LocalDateTime.now(), silently changing the date.
 * DELIVERED uses the immutable completedAt set when runner marked delivery;
 * other reviewed stops use reviewedAt. Never invent a time for an old delivered
 * record whose original completedAt was not saved.
 */
internal fun deliveryReportGeneratedTime(stop: Stop, currentTime: LocalDateTime): String {
    val recorded = if (normalizedStatus(stop.status) == "DELIVERED") stop.completedAt
        else stop.reviewedAt
    val savedTime = recorded?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    return when {
        savedTime != null -> savedTime.format(REPORT_GENERATED_FORMAT)
        normalizedStatus(stop.status) == "DELIVERED" -> "Original time not recorded"
        else -> currentTime.format(REPORT_GENERATED_FORMAT)
    }
}
private val REPORT_GENERATED_FORMAT =
    DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", java.util.Locale.ENGLISH)

/** Restore the exact Build #717 COD vector in exported reports. */
private fun reportStampBitmap(context: Context, paymentMode: String): Bitmap? {
    val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
    if (paymentMode == "COD") {
        val drawable = androidx.core.content.ContextCompat.getDrawable(
            context, R.drawable.cod_circle_stamp
        ) ?: return null
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
    } else {
        drawSlantedPaymentStamp(Canvas(bitmap), paymentMode, 240f)
    }
    return bitmap
}

/** Render one dedicated report image, never a screenshot or another customer's card. */
private class SingleDeliveryReportView(
    context: Context,
    text: String,
    private val status: DeliveryBadgeStyle,
    private val paymentMode: DeliveryBadgeStyle?,
    private val payment: Bitmap?,
    private val delivery: Bitmap?
) : View(context) {
    private val gold = Color.rgb(231,190,91)
    private val white = Color.rgb(232,240,255)
    private val bodyInk = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = DeliveryReportTypography.bodyPx }
    private val titleInk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gold; textSize = DeliveryReportTypography.titlePx; typeface = Typeface.DEFAULT_BOLD
    }
    // Decode the exact icon assets already bundled in the installed app.
    private val paymentLogo = when (paymentMode?.label) {
        "Cash On Delivery" -> reportStampBitmap(context, "COD")
        "PayNow" -> reportStampBitmap(context, "PAYNOW")
        else -> null
    }

    // Keep the compact title and status capsule aligned in the same top row.
    private val bodyText = text.substringAfter('\n').trimEnd('\n', '\r')
    private val styled = android.text.SpannableString(bodyText).apply {
        val firstEnd = bodyText.indexOf('\n').takeIf { it >= 0 } ?: bodyText.length
        setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, firstEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    // Reserve the right-hand side for the circular badge without hiding any text.
    private val bodyStartY = 132f
    private val stampSize = 220f
    private val stampLeft = 800f
    private val stampTop = bodyStartY + 160f
    private val layoutWidth = if (paymentLogo == null) 960 else 720
    private val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, bodyInk, layoutWidth)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1f).build()
    private val twoColumns = (payment != null && delivery != null) || (payment == null && delivery == null)
    private val imageWidth = if (twoColumns) 468f else 960f
    private fun photoHeight(bitmap: Bitmap?): Float =
        if (bitmap == null) 100f else (imageWidth * bitmap.height.toFloat() / bitmap.width.toFloat()).coerceAtMost(900f)
    private val proofHeight = maxOf(photoHeight(payment), photoHeight(delivery))
    // No extra blank vertical gap after the report-generated line.
    private val proofStartY = maxOf(bodyStartY + layout.height,
        if (paymentLogo == null) 0f else stampTop + stampSize + 22f)
    private val proofLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gold; textSize = DeliveryReportTypography.proofPx; typeface = Typeface.DEFAULT_BOLD
    }
    private val wrapProofLabels = twoColumns && maxOf(
        proofLabelPaint.measureText("Proof of Payment"),
        proofLabelPaint.measureText("Proof of Delivery")
    ) > imageWidth
    private val headerHeight = if (wrapProofLabels) 120f else 75f
    val reportHeight = kotlin.math.ceil(proofStartY + headerHeight + proofHeight + 90f).toInt()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(7, 23, 45))
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16,39,68) }
        canvas.drawRoundRect(RectF(24f,24f,1056f,reportHeight-24f),24f,24f,panel)
        panel.color=gold; panel.style=Paint.Style.STROKE; panel.strokeWidth=2f
        canvas.drawRoundRect(RectF(24f,24f,1056f,reportHeight-24f),24f,24f,panel)

        // 1.5x original status font and capsule, safely separated from the report title.
        val statusInk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = status.foregroundArgb; textSize = DeliveryReportTypography.statusPx; typeface = Typeface.DEFAULT_BOLD
        }
        val capsuleWidth = statusInk.measureText(status.label) + 78f
        val right = 1030f
        val top = 55f
        val bottom = 142f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = status.backgroundArgb }
        canvas.drawRoundRect(RectF(right - capsuleWidth, top, right, bottom), 43.5f, 43.5f, bg)
        val fm = statusInk.fontMetrics
        val baseline = (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(status.label, right - capsuleWidth + 39f, baseline, statusInk)

        // Title shares the top row with the right-aligned Delivered / On Hold capsule.
        canvas.drawText("Delivery Report", 60f, baseline, titleInk)
        canvas.save()
        canvas.translate(60f, bodyStartY)
        layout.draw(canvas)
        canvas.restore()
        paymentLogo?.let {
            canvas.drawBitmap(it, null,
                RectF(stampLeft, stampTop, stampLeft + stampSize, stampTop + stampSize),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }

        fun proof(label: String, bitmap: Bitmap?, x: Float, width: Float) {
            // Keep both proof labels on one line at 1.5x whenever they fit.
            if (wrapProofLabels) {
                canvas.drawText("Proof of", x, proofStartY + 48f, proofLabelPaint)
                canvas.drawText(if (label.endsWith("Payment")) "Payment" else "Delivery",
                    x, proofStartY + 98f, proofLabelPaint)
            } else {
                canvas.drawText(label, x, proofStartY + 52f, proofLabelPaint)
            }
            val photoY = proofStartY + headerHeight
            if (bitmap == null) {
                val missingInk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = white; textSize = DeliveryReportTypography.proofPx
                }
                if (missingInk.measureText("No proof uploaded") <= width) {
                    canvas.drawText("No proof uploaded", x, photoY + 56f, missingInk)
                } else {
                    canvas.drawText("No proof", x, photoY + 48f, missingInk)
                    canvas.drawText("uploaded", x, photoY + 94f, missingInk)
                }
            } else {
                val height = photoHeight(bitmap)
                val actualWidth = height * bitmap.width.toFloat() / bitmap.height.toFloat()
                canvas.drawBitmap(bitmap, null,
                    RectF(x + (width-actualWidth)/2f, photoY,
                        x + (width+actualWidth)/2f, photoY + height),
                    Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
        if (twoColumns) {
            proof("Proof of Payment", payment,60f,468f)
            proof("Proof of Delivery",delivery,552f,468f)
        } else if (payment!=null) proof("Proof of Payment",payment,60f,960f)
        else proof("Proof of Delivery",delivery,60f,960f)
    }
}
fun generateSingleDeliveryReport(context: Context, stop: Stop, index: Int): File {
    val store = OrderProofStore(context)
    val payment = store.file(stop.order?.paymentProofFileId)?.let { store.decode(it,1024) }
    val delivery = store.file(stop.order?.proofFileId)?.let { store.decode(it,1024) }
    try {
        val generated = deliveryReportGeneratedTime(stop, LocalDateTime.now(ZoneId.of("Asia/Singapore")))
        val report = SingleDeliveryReportView(context,singleDeliveryReportText(stop,index,generated),reportStatusBadge(stop.status),paymentModeBadge(stop.order),payment,delivery)
        require(report.reportHeight in 1..16000) { "Report is too long to render safely." }
        val bitmap = Bitmap.createBitmap(1080,report.reportHeight,Bitmap.Config.ARGB_8888)
        val directory = File(context.cacheDir,"delivery_reports").apply { mkdirs() }
        directory.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>24*60*60*1000L }?.forEach(File::delete)
        val file = File(directory,"delivery_report_${stop.place.postal}_${UUID.randomUUID()}.png")
        try {
            report.layout(0,0,bitmap.width,bitmap.height); report.draw(Canvas(bitmap))
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } catch(e: Exception) { file.delete(); throw e }
        finally { bitmap.recycle() }
        return file
    } finally { payment?.recycle(); delivery?.recycle() }
}
fun singleDeliveryShareIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context,"${context.packageName}.excel-files",file)
    return Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Delivery Report",uri) }
}
