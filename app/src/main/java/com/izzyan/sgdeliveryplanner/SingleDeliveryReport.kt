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
    appendLine("Planned arrival: ${time(stop.arrival)}")
    appendLine("Updated ETA: ${stop.etaArrival?.let(::time) ?: "—"}")
    appendLine("Actual / Delivered at: ${stop.completedAt?.let(::time) ?: "—"}")
    appendLine("Distance from previous stop: ${km(stop.leg.km)}")
    holdDetails(stop)?.let { appendLine("On hold: $it") }
    appendLine("Report generated: $generated (Singapore)")
}

/** Draw real rounded capsules within the Android text layout of the shared PNG preview/export. */
private class PaymentCapsuleSpan(private val badge: DeliveryBadgeStyle) : android.text.style.ReplacementSpan() {
    private val horizontalPadding = 40f
    private fun labelPaint(paint: Paint) = Paint(paint).apply { typeface = Typeface.DEFAULT_BOLD }
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        kotlin.math.ceil(labelPaint(paint).measureText(badge.label) + 2 * horizontalPadding).toInt()

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
                      top: Int, y: Int, bottom: Int, paint: Paint) {
        val width = getSize(paint, text, start, end, null).toFloat()
        val capsule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badge.backgroundArgb }
        canvas.drawRoundRect(RectF(x, top + 2f, x + width, bottom - 2f), 36f, 36f, capsule)
        val lettering = labelPaint(paint).apply { color = badge.foregroundArgb; isAntiAlias = true }
        canvas.drawText(badge.label, x + horizontalPadding, y.toFloat(), lettering)
    }
}

/** Unattached dedicated report view; the very same bitmap is previewed and shared. */
private class SingleDeliveryReportView(
    context: Context,
    text: String,
    private val status: DeliveryBadgeStyle,
    private val paymentMode: DeliveryBadgeStyle?,
    private val payment: Bitmap?,
    private val delivery: Bitmap?
) : View(context) {
    // These report-only text sizes are exactly 2x the previous 32/48/31/30 px.
    private val bodyTextSize = 64f
    private val titleTextSize = 96f
    private val statusTextSize = 62f
    private val proofTextSize = 60f
    private val inkColor = Color.rgb(232,240,255)
    private val gold = Color.rgb(231,190,91)
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = inkColor; textSize = bodyTextSize }
    private val bodyText = text.substringAfter('\n')
    private val styled = android.text.SpannableString(bodyText).apply {
        // Only the customer data lives here: reserve a separate, non-overlapping status header.
        val stopLineEnd = bodyText.indexOf('\n').takeIf { it >= 0 } ?: bodyText.length
        setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, stopLineEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        paymentMode?.let { badge ->
            val labelStart = bodyText.indexOf("Payment Mode : ").takeIf { it >= 0 }
                ?.plus("Payment Mode : ".length)
            if (labelStart != null && bodyText.regionMatches(labelStart, badge.label, 0, badge.label.length)) {
                setSpan(
                    PaymentCapsuleSpan(badge), labelStart, labelStart + badge.label.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }
    private val layout = StaticLayout.Builder.obtain(styled,0,styled.length,ink,960)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(10f,1f).build()

    private val bodyTop = 296f
    private val showTwoColumns = (payment != null && delivery != null) || (payment == null && delivery == null)
    private val imageWidth = if (showTwoColumns) 468f else 960f
    private fun photoHeight(bitmap: Bitmap?): Float = if (bitmap == null) 64f
        else (imageWidth * bitmap.height / bitmap.width).coerceAtMost(900f)
    private val proofY = bodyTop + layout.height + 64f
    private val photoOffset = if (showTwoColumns) 165f else 108f
    private val proofHeight = maxOf(photoHeight(payment), photoHeight(delivery))
    val reportHeight = (proofY + photoOffset + proofHeight + 100f).toInt()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(7,23,45))
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16,39,68) }
        canvas.drawRoundRect(RectF(24f,24f,1056f,reportHeight-24f),24f,24f,panel)
        panel.color = gold; panel.style = Paint.Style.STROKE; panel.strokeWidth = 2f
        canvas.drawRoundRect(RectF(24f,24f,1056f,reportHeight-24f),24f,24f,panel)

        // Report title: 48 -> 96 px. Status: 31 -> 62 px, with a doubled capsule.
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gold; textSize = titleTextSize; typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("Delivery Report",60f,136f,titlePaint)
        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = status.foregroundArgb; textSize = statusTextSize; typeface = Typeface.DEFAULT_BOLD
        }
        val capsuleWidth = statusPaint.measureText(status.label) + 104f
        val capsuleLeft = 1030f - capsuleWidth
        val capsuleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = status.backgroundArgb }
        canvas.drawRoundRect(RectF(capsuleLeft,166f,1030f,274f),54f,54f,capsuleBg)
        canvas.drawText(status.label,capsuleLeft+52f,240f,statusPaint)

        canvas.save()
        canvas.translate(60f,bodyTop)
        layout.draw(canvas)
        canvas.restore()

        fun proof(label: String, bitmap: Bitmap?, x: Float, width: Float) {
            val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = gold; textSize = proofTextSize; typeface = Typeface.DEFAULT_BOLD
            }
            // 60 px proof headings fit 2-column images by wrapping caption text, not shrinking.
            if (showTwoColumns) {
                canvas.drawText("Proof of",x,proofY+54f,caption)
                canvas.drawText(label.removePrefix("Proof of "),x,proofY+121f,caption)
            } else {
                canvas.drawText(label,x,proofY+60f,caption)
            }
            val imageTop = proofY + photoOffset
            if (bitmap == null) {
                caption.color = inkColor; caption.typeface = Typeface.DEFAULT
                canvas.drawText("No proof",x,imageTop+58f,caption)
                canvas.drawText("uploaded",x,imageTop+123f,caption)
            } else {
                val height = photoHeight(bitmap)
                val actualWidth = height * bitmap.width / bitmap.height
                canvas.drawBitmap(
                    bitmap, null,
                    RectF(x+(width-actualWidth)/2,imageTop,x+(width+actualWidth)/2,imageTop+height),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
            }
        }
        if (showTwoColumns) {
            proof("Proof of Payment",payment,60f,468f)
            proof("Proof of Delivery",delivery,552f,468f)
        } else if (payment != null) proof("Proof of Payment",payment,60f,960f)
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
