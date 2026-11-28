package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** The same unchanged PNG artwork is used on live cards and shared report images. */
internal object DeliveryStatusStampArtwork {
    private val cache = mutableMapOf<String, Bitmap>()
    @Synchronized
    fun bitmap(context: Context, status: String): Bitmap? {
        val name = when (normalizedStatus(status.uppercase(Locale.ENGLISH).replace(' ', '_'))) {
            "DELIVERED" -> "delivered"
            "ON_HOLD" -> "on_hold"
            "PENDING" -> "pending"
            else -> return null
        }
        cache[name]?.let { return it }
        val decoded = runCatching {
            context.assets.open("status_stamps/$name.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        cache[name] = decoded
        return decoded
    }
    fun drawFit(canvas: Canvas, bitmap: Bitmap, frame: RectF) {
        val scale = min(frame.width() / bitmap.width, frame.height() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = frame.centerX() - w / 2f
        val top = frame.centerY() - h / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
}

internal class DeliveryStatusStampLayout {
    val bounds = mutableStateMapOf<String, Rect>()
    val inkWidths = mutableStateMapOf<String, Float>()
    fun anchor(name: String): Modifier = Modifier.onGloballyPositioned { bounds[name] = it.boundsInRoot() }
    fun text(name: String, result: TextLayoutResult) {
        inkWidths[name] = (0 until result.lineCount).maxOfOrNull { result.getLineRight(it) } ?: 0f
    }
}

/** Preserve the original badge measurement and accessibility text; replace only its paint. */
@Composable
internal fun DeliveryStatusOriginalFootprint(layout: DeliveryStatusStampLayout, status: String,
    original: @Composable () -> Unit) {
    val context = LocalContext.current
    val replace = remember(context, status) { DeliveryStatusStampArtwork.bitmap(context, status) != null }
    Box(layout.anchor("slot").testTag("original-status-slot")) {
        Box(if (replace) Modifier.alpha(0f) else Modifier) { original() }
    }
}

/** Draw the approved larger stamp above the card, without changing its measured layout. */
@Composable
internal fun DeliveryStatusStampFrame(page: String, status: String,
    hasMessageButton: Boolean = false, hasPaymentStamp: Boolean = false,
    showCurrentDelivery: Boolean = false,
    content: @Composable (DeliveryStatusStampLayout) -> Unit) {
    val layout = remember { DeliveryStatusStampLayout() }
    val context = LocalContext.current
    val bitmap = remember(context, status) { DeliveryStatusStampArtwork.bitmap(context, status) }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(layout.anchor("root").testTag("delivery-status-card").drawWithContent {
        drawContent()
        val root = layout.bounds["root"] ?: return@drawWithContent
        val original = layout.bounds["slot"] ?: return@drawWithContent
        if (bitmap == null || image == null) return@drawWithContent
        val margin = 14.dp.toPx()
        val gap = 9.5.dp.toPx()
        val ratio = bitmap.width.toFloat() / bitmap.height
        val top = if (page == "Route") 11.dp.toPx() else 10.dp.toPx()
        val header = layout.bounds["header"] ?: original
        var maxWidth = 90.dp.toPx()
        val right: Float
        if (page == "Route") {
            val button = if (hasMessageButton) layout.bounds["message"] else null
            right = if (button != null) button.left - root.left - gap else size.width - margin
            val inkNames = if (showCurrentDelivery) listOf("title", "current") else listOf("title")
            val inkRight = inkNames.maxOf { name ->
                val text = layout.bounds[name]
                if (text == null) margin else text.left - root.left + (layout.inkWidths[name] ?: text.width)
            }
            maxWidth = min(maxWidth, right - inkRight - gap)
        } else {
            val payment = if (hasPaymentStamp) layout.bounds["payment"] else null
            right = if (payment != null) payment.left - root.left - gap else size.width - margin
            val inkRight = listOf("title", "customer", "phone").maxOf { name ->
                val text = layout.bounds[name]
                if (text == null) margin else text.left - root.left + (layout.inkWidths[name] ?: text.width)
            }
            maxWidth = min(maxWidth, right - inkRight - 3.dp.toPx())
        }
        maxWidth = min(maxWidth, (header.bottom - root.top - top) * (90f / 75f))
        if (maxWidth <= 0f) return@drawWithContent
        val frameWidth = maxWidth
        val frameHeight = frameWidth * (75f / 90f)
        val width = min(frameWidth, frameHeight * ratio)
        val height = width / ratio
        val left = right - frameWidth + (frameWidth - width) / 2f
        val imageTop = top + (frameHeight - height) / 2f
        drawImage(image, dstOffset = IntOffset(left.roundToInt(), imageTop.roundToInt()),
            dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)))
    }) { content(layout) }
}
