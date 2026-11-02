package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas as NativeCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as NativePaint
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/**
 * Shared dimmed Marina Bay Sands backdrop. The content sits above a strong
 * theme-aware scrim so text, controls, and accessibility contrast stay intact.
 */
@Composable
internal fun MarinaBayBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val lightTheme = MaterialTheme.colorScheme.background.red > 0.5f
    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.marina_bay_sands_dark),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.BottomCenter
        )
        Box(
            Modifier.fillMaxSize().background(
                if (lightTheme) Color.White.copy(alpha = 0.90f)
                else Color(0xFF06172A).copy(alpha = 0.58f)
            )
        )
        content()
    }
}

/**
 * Export the same artwork into report bitmaps, anchored at the bottom even for
 * unusually long reports. All original report fields and stamp geometry remain.
 */
internal fun drawMarinaBayReportBackground(
    context: Context,
    canvas: NativeCanvas,
    width: Int,
    height: Int
) {
    canvas.drawColor(AndroidColor.rgb(7, 23, 45))
    val options = BitmapFactory.Options().apply { inScaled = false }
    val background = BitmapFactory.decodeResource(
        context.resources, R.drawable.marina_bay_sands_dark, options
    ) ?: return
    try {
        val drawHeight = width.toFloat() * background.height / background.width
        canvas.drawBitmap(
            background,
            null,
            RectF(0f, height - drawHeight, width.toFloat(), height.toFloat()),
            NativePaint(NativePaint.ANTI_ALIAS_FLAG or NativePaint.FILTER_BITMAP_FLAG)
        )
    } finally {
        background.recycle()
    }
    canvas.drawColor(AndroidColor.argb(85, 4, 14, 27))
}
