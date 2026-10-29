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
    appendLine("Payment Mode : ${paymentModeBadge(stop.order)?.label ?: "—"}")
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

/** True inline payment capsule; StaticLayout can move the whole span to the next
 * line when a large Cash On Delivery label won't fit after "Payment Mode : ".
 */
private class PaymentCapsuleSpan(private val badge: DeliveryBadgeStyle) : android.text.style.ReplacementSpan() {
    private val horizontalPadding = 30f
    private fun labelPaint(paint: Paint) = Paint(paint).apply { typeface = Typeface.DEFAULT_BOLD }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        kotlin.math.ceil(labelPaint(paint).measureText(badge.label) + 2f * horizontalPadding).toInt()

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
                      top: Int, y: Int, bottom: Int, paint: Paint) {
        val width = getSize(paint, text, start, end, null).toFloat()
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badge.backgroundArgb }
        val capsuleTop = top + 4f
        val capsuleBottom = bottom - 4f
        val radius = (capsuleBottom - capsuleTop) / 2f
        canvas.drawRoundRect(RectF(x, capsuleTop, x + width, capsuleBottom), radius, radius, bg)
        val textPaint = labelPaint(paint).apply { color = badge.foregroundArgb; isAntiAlias = true }
        canvas.drawText(badge.label, x + horizontalPadding, y.toFloat(), textPaint)
    }
}

/**
 * Reuse the very same local PNG badges shown on Route, Delivery and History.
 * ReplacementSpan keeps the image inline after "Payment Mode :" and expands
 * that line's metrics so neither logo overlaps adjacent report text.
 */
private class PaymentLogoSpan(private val logo: Bitmap, private val payNow: Boolean) :
    android.text.style.ReplacementSpan() {
    private val badgeWidth = if (payNow) 198f else 202f
    private val badgeHeight = if (payNow) 56f else 74f
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        fm?.let {
            // Allow the taller COD graphic to fit without clipping the row.
            val above = kotlin.math.ceil(badgeHeight * 0.78f).toInt()
            val below = kotlin.math.ceil(badgeHeight * 0.22f).toInt()
            it.ascent = minOf(it.ascent, -above)
            it.top = minOf(it.top, it.ascent)
            it.descent = maxOf(it.descent, below)
            it.bottom = maxOf(it.bottom, it.descent)
        }
        return kotlin.math.ceil(badgeWidth).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
        top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        // The drawing area follows the line metrics returned by getSize.
        val topY = (top + bottom - badgeHeight) / 2f
        val bounds = RectF(x, topY, x + badgeWidth, topY + badgeHeight)
        if (payNow) {
            // Match the white backdrop of the on-screen PayNow wordmark.
            val whiteBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRoundRect(bounds, 9f, 9f, whiteBg)
            canvas.drawBitmap(
                logo, null,
                RectF(x + 6f, topY + 8f, x + badgeWidth - 6f, topY + badgeHeight - 8f),
                logoPaint
            )
        } else {
            // COD PNG already includes its own premium red/white graphic.
            canvas.drawBitmap(logo, null, bounds, logoPaint)
        }
    }
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
        "Cash On Delivery" -> BitmapFactory.decodeResource(resources, R.drawable.cod_premium_logo)
        "PayNow" -> BitmapFactory.decodeResource(resources, R.drawable.paynow_logo)
        else -> null
    }

    // Keep the compact title and status capsule aligned in the same top row.
    private val bodyText = text.substringAfter('\n').trimEnd('\n', '\r')
    private val styled = android.text.SpannableString(bodyText).apply {
        val firstEnd = bodyText.indexOf('\n').takeIf { it >= 0 } ?: bodyText.length
        setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, firstEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        paymentMode?.let { badge ->
            val start = bodyText.indexOf("Payment Mode : ").takeIf { it >= 0 }?.plus("Payment Mode : ".length)
            if (start != null && bodyText.regionMatches(start, badge.label, 0, badge.label.length)) {
                val graphic = paymentLogo?.let { PaymentLogoSpan(it, badge.label == "PayNow") }
                    ?: PaymentCapsuleSpan(badge)
                setSpan(graphic, start, start + badge.label.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
    // Bring the Stop line directly below the one-line title (normal 1.0 line spacing).
    private val bodyStartY = 132f
    private val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, bodyInk, 960)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1f).build()
    private val twoColumns = (payment != null && delivery != null) || (payment == null && delivery == null)
    private val imageWidth = if (twoColumns) 468f else 960f
    private fun photoHeight(bitmap: Bitmap?): Float =
        if (bitmap == null) 100f else (imageWidth * bitmap.height.toFloat() / bitmap.width.toFloat()).coerceAtMost(900f)
    private val proofHeight = maxOf(photoHeight(payment), photoHeight(delivery))
    // No extra blank vertical gap after the report-generated line.
    private val proofStartY = bodyStartY + layout.height
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
        val generated = LocalDateTime.now(ZoneId.of("Asia/Singapore")).format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a",java.util.Locale.ENGLISH))
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
