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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/**
 * Shared Marina Bay Sands backdrop. A moderate image-only Navy scrim keeps
 * the skyline visible behind transparent cards while preserving contrast.
 */
@Composable
internal fun MarinaBayBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
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
                SGColors.Background.copy(alpha = 0.42f)
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
    canvas.drawColor(AndroidColor.rgb(8, 27, 51))
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
    // Reports need a brighter skyline than the app background; the card and text shadows
    // keep the white/gold report details legible even when exporting the PNG.
    canvas.drawColor(AndroidColor.argb(66, 8, 27, 51))
}
