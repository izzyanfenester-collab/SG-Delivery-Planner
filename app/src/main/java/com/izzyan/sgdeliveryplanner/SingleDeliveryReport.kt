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
    appendLine("Runner Route Planning\nDelivery Report\n")
    appendLine("Stop ${index+1} · ${stop.place.postal}\n${normalizedStatus(stop.status).replace('_',' ')}")
    appendLine("Customer\n${stop.order?.customerName ?: "—"}\n")
    appendLine("Phone\n${displayCustomerPhone(stop.order?.phoneNumber)}\n")
    appendLine("Address\n${customerAddress(stop)}\n")
    appendLine("Parcel Price\n${orderPrice(stop.order)}\nPayment Status\n${orderPayment(stop.order)}\n")
    appendLine("Planned arrival: ${time(stop.arrival)}")
    appendLine("Updated ETA: ${stop.etaArrival?.let(::time) ?: "—"}")
    appendLine("Actual / Delivered at: ${stop.completedAt?.let(::time) ?: "—"}")
    appendLine("Distance from previous stop: ${km(stop.leg.km)}")
    holdDetails(stop)?.let { appendLine("On hold: $it") }
    appendLine("Report generated: $generated (Singapore)")
}

/** Unattached dedicated report view: never captures any screen, other cards or system chrome. */
private class SingleDeliveryReportView(context: Context, text: String, private val payment: Bitmap?, private val delivery: Bitmap?) : View(context) {
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(7,23,45); textSize = 32f }
    private val styled = android.text.SpannableString(text).apply {
        val statusEnd = text.indexOf("Customer")
        if (statusEnd > 0) { setSpan(android.text.style.StyleSpan(Typeface.BOLD),0,statusEnd,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); setSpan(android.text.style.AbsoluteSizeSpan(42),0,statusEnd,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
    private val layout = StaticLayout.Builder.obtain(styled,0,styled.length,ink,1000).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(8f,1f).build()
    private fun photoHeight(bitmap: Bitmap?) = if (bitmap == null) 80 else (1000f*bitmap.height/bitmap.width).coerceAtMost(1000f).toInt()
    val reportHeight = layout.height + photoHeight(payment) + photoHeight(delivery) + 300
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        canvas.save(); canvas.translate(40f,40f); layout.draw(canvas); canvas.restore()
        var y = layout.height + 90f
        fun proof(label: String, bitmap: Bitmap?, missing: String) {
            ink.typeface = Typeface.DEFAULT_BOLD; canvas.drawText(label,40f,y,ink); y += 25f
            ink.typeface = Typeface.DEFAULT
            if (bitmap == null) { canvas.drawText(missing,40f,y+35f,ink); y += 80f }
            else {
                val height = photoHeight(bitmap).toFloat()
                val width = height*bitmap.width/bitmap.height
                canvas.drawBitmap(bitmap,null,RectF(40f+(1000-width)/2,y,40f+(1000+width)/2,y+height),Paint(Paint.FILTER_BITMAP_FLAG))
                y += height
            }
            y += 60f
        }
        proof("Proof of Payment",payment,"No payment proof uploaded")
        proof("Proof of Delivery",delivery,"No delivery proof uploaded")
    }
}
fun generateSingleDeliveryReport(context: Context, stop: Stop, index: Int): File {
    val store = OrderProofStore(context)
    val payment = store.file(stop.order?.paymentProofFileId)?.let { store.decode(it,1024) }
    val delivery = store.file(stop.order?.proofFileId)?.let { store.decode(it,1024) }
    try {
        val generated = LocalDateTime.now(ZoneId.of("Asia/Singapore")).format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a",java.util.Locale.ENGLISH))
        val report = SingleDeliveryReportView(context,singleDeliveryReportText(stop,index,generated),payment,delivery)
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
