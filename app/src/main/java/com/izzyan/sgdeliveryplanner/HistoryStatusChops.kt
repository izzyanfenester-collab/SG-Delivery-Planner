package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Exactly 35 degrees clockwise, the same for all three uploaded status stamps. */
internal const val HISTORY_CHOP_CLOCKWISE_DEGREES = 35f

/** Only the supplied chop art is rendered. Asset files are transparent images
 * resized for a compact mobile display, not recreated or drawn in Compose.
 */
internal fun historyStatusChopDrawable(status: SavedDeliveryStatus): Int = when (status) {
    SavedDeliveryStatus.COMPLETED -> R.drawable.history_status_completed
    SavedDeliveryStatus.NOT_STARTED -> R.drawable.history_status_not_started
    SavedDeliveryStatus.IN_PROGRESS -> R.drawable.history_status_in_progress
}

internal fun historyStatusAccent(status: SavedDeliveryStatus): Color = when (status) {
    SavedDeliveryStatus.COMPLETED -> Color(0xFF21B55B)
    SavedDeliveryStatus.NOT_STARTED -> Color(0xFFFF4D4F)
    SavedDeliveryStatus.IN_PROGRESS -> Color(0xFFFFC736)
}

@Composable
internal fun HistoryStatusChop(status: SavedDeliveryStatus) {
    Image(
        painter = painterResource(id = historyStatusChopDrawable(status)),
        contentDescription = status.label + " delivery status",
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(76.dp).graphicsLayer {
            rotationZ = HISTORY_CHOP_CLOCKWISE_DEGREES
        }
    )
}
