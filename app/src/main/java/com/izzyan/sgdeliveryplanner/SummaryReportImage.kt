package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import java.io.File
import java.util.UUID

/** Renders only saved summary data, independent of windows, cards and system chrome. */
fun generateSummaryReportImage(context: Context, plan: Plan): File {
    val text=buildSummaryReport(plan)
    val gold=Color.rgb(231,190,91)
    val ink=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(232,240,255);textSize=34f}
    val styled=SpannableString(text).apply {
        val titleEnd=text.indexOf('\n')
        setSpan(StyleSpan(Typeface.BOLD),0,titleEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(AbsoluteSizeSpan(48),0,titleEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(gold),0,titleEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val layout=StaticLayout.Builder.obtain(styled,0,styled.length,ink,960)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(6f,1f).build()
    val height=layout.height+112
    require(height in 1..16000) {"Summary is too long to render safely."}
    val bitmap=Bitmap.createBitmap(1080,height,Bitmap.Config.ARGB_8888)
    val directory=File(context.cacheDir,"delivery_reports").apply {check(mkdirs() || isDirectory)}
    directory.listFiles()?.filter {System.currentTimeMillis()-it.lastModified()>24*60*60*1000L}?.forEach(File::delete)
    val file=File(directory,"delivery_summary_${UUID.randomUUID()}.png")
    try {
        val canvas=Canvas(bitmap)
        canvas.drawColor(Color.rgb(7,23,45))
        val panel=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(16,39,68)}
        canvas.drawRoundRect(RectF(24f,24f,1056f,height-24f),24f,24f,panel)
        panel.color=gold;panel.style=Paint.Style.STROKE;panel.strokeWidth=2f
        canvas.drawRoundRect(RectF(24f,24f,1056f,height-24f),24f,24f,panel)
        canvas.save();canvas.translate(60f,56f);layout.draw(canvas);canvas.restore()
        file.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        return file
    } catch(e: Exception) {file.delete();throw e}
    finally {bitmap.recycle()}
}
