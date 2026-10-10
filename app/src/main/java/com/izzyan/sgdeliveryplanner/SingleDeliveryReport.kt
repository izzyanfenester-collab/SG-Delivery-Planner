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
    appendLine("Stop ${index+1} · ${stop.place.postal} · ${normalizedStatus(stop.status).replace('_',' ')}")
    appendLine("Customer : ${stop.order?.customerName ?: "—"}")
    appendLine("Phone : ${displayCustomerPhone(stop.order?.phoneNumber)}")
    appendLine("Address : ${customerAddress(stop)}")
    appendLine("Parcel Price : ${orderPrice(stop.order)}")
    appendLine("Payment Mode : ${orderPayment(stop.order)}")
    appendLine("Planned arrival: ${time(stop.arrival)}")
    appendLine("Updated ETA: ${stop.etaArrival?.let(::time) ?: "—"}")
    appendLine("Actual / Delivered at: ${stop.completedAt?.let(::time) ?: "—"}")
    appendLine("Distance from previous stop: ${km(stop.leg.km)}")
    holdDetails(stop)?.let { appendLine("On hold: $it") }
    appendLine("Report generated: $generated (Singapore)")
}

/** Unattached dedicated report view: never captures any screen, other cards or system chrome. */
private class SingleDeliveryReportView(context: Context, text: String, private val payment: Bitmap?, private val delivery: Bitmap?) : View(context) {
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232,240,255); textSize = 32f }
    private val gold = Color.rgb(231,190,91)
    private val styled = android.text.SpannableString(text).apply {
        val titleEnd=text.indexOf('\n')
        setSpan(android.text.style.StyleSpan(Typeface.BOLD),0,titleEnd,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(android.text.style.AbsoluteSizeSpan(48),0,titleEnd,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(android.text.style.ForegroundColorSpan(gold),0,titleEnd,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val statusEnd=text.indexOf("Customer")
        setSpan(android.text.style.StyleSpan(Typeface.BOLD),titleEnd,statusEnd,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    private val layout=StaticLayout.Builder.obtain(styled,0,styled.length,ink,960).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f,1f).build()
    private val both=payment!=null && delivery!=null
    private val imageWidth=if(both) 468f else 960f
    private fun photoHeight(bitmap: Bitmap?)=if(bitmap==null) 64f else (imageWidth*bitmap.height/bitmap.width).coerceAtMost(900f)
    private val proofHeight=maxOf(photoHeight(payment),photoHeight(delivery))
    val reportHeight=layout.height+proofHeight.toInt()+240
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(7,23,45))
        val panel=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(16,39,68)}
        canvas.drawRoundRect(RectF(24f,24f,1056f,reportHeight-24f),24f,24f,panel)
        panel.color=gold; panel.style=Paint.Style.STROKE; panel.strokeWidth=2f
        canvas.drawRoundRect(RectF(24f,24f,1056f,reportHeight-24f),24f,24f,panel)
        canvas.save(); canvas.translate(60f,56f); layout.draw(canvas); canvas.restore()
        val y=layout.height+116f
        fun proof(label: String, bitmap: Bitmap?, x: Float, width: Float) {
            ink.color=gold; ink.textSize=30f; ink.typeface=Typeface.DEFAULT_BOLD
            canvas.drawText(label,x,y,ink)
            ink.color=Color.rgb(232,240,255); ink.typeface=Typeface.DEFAULT
            if(bitmap==null) canvas.drawText("No proof uploaded",x,y+50f,ink)
            else {
                val height=photoHeight(bitmap); val actualWidth=height*bitmap.width/bitmap.height
                canvas.drawBitmap(bitmap,null,RectF(x+(width-actualWidth)/2,y+24f,x+(width+actualWidth)/2,y+24f+height),Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
        if(both) {
            proof("Proof of Payment",payment,60f,468f)
            proof("Proof of Delivery",delivery,552f,468f)
        } else if(payment!=null) proof("Proof of Payment",payment,60f,960f)
        else if(delivery!=null) proof("Proof of Delivery",delivery,60f,960f)
        else { proof("Proof of Payment",null,60f,468f); proof("Proof of Delivery",null,552f,468f) }
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
