package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory

/** Small native bitmap icons. Created once per style, never on per-frame GPS updates. */
internal object CameraSafetyMapIcons {
    fun icon(context:Context,category:String):Icon {
        val bitmap=Bitmap.createBitmap(48,56,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val red=Color.rgb(231,55,68)
        val amber=Color.rgb(235,165,41)
        val border=Color.WHITE
        paint.color=Color.argb(235,19,31,48)
        canvas.drawRoundRect(RectF(4f,3f,44f,44f),12f,12f,paint)
        paint.style=Paint.Style.STROKE;paint.color=border;paint.strokeWidth=2.6f
        canvas.drawRoundRect(RectF(4f,3f,44f,44f),12f,12f,paint)
        paint.style=Paint.Style.FILL
        when(category) {
            "RED_LIGHT_CAMERA" -> {
                paint.color=Color.DKGRAY;canvas.drawRoundRect(RectF(15f,7f,33f,39f),7f,7f,paint)
                for((cy,c) in listOf(13f to red,23f to amber,33f to Color.rgb(52,202,96))){
                    paint.color=c;canvas.drawCircle(24f,cy,3.5f,paint)
                }
            }
            "AVERAGE_SPEED_ZONE" -> {
                paint.color=amber;paint.textSize=10f;paint.isFakeBoldText=true
                paint.textAlign=Paint.Align.CENTER;canvas.drawText("AVG",24f,26f,paint)
            }
            "MOBILE_ENFORCEMENT_LOCATION","POLICE_LASER_LOCATION" -> {
                paint.color=amber;canvas.drawRoundRect(RectF(11f,14f,37f,32f),4f,4f,paint)
                paint.color=Color.BLACK;canvas.drawCircle(24f,23f,5f,paint)
                paint.color=Color.WHITE;canvas.drawCircle(24f,23f,2f,paint)
            }
            else -> {
                paint.color=red;canvas.drawRoundRect(RectF(9f,13f,39f,33f),5f,5f,paint)
                paint.color=Color.WHITE;canvas.drawCircle(24f,23f,7f,paint)
                paint.color=Color.BLACK;canvas.drawCircle(24f,23f,3.5f,paint)
            }
        }
        paint.color=if(category=="RED_LIGHT_CAMERA")red else amber
        val triangle=android.graphics.Path().apply {
            moveTo(17f,44f);lineTo(31f,44f);lineTo(24f,53f);close()
        }
        canvas.drawPath(triangle,paint)
        return IconFactory.getInstance(context).fromBitmap(bitmap)
    }
}
