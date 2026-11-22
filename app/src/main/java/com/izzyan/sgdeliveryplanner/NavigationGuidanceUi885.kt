package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** The existing instruction is unchanged; only the optional lane row and badge are added. */
@Composable
internal fun NavigationGuidanceHeader885(
    guidance: VisualGuidanceState885,
    modifier: Modifier = Modifier,
    primary: @Composable () -> Unit
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Surface(color = PremiumNavy, shape = RoundedCornerShape(SGDimensions.RadiusCard),
            border = BorderStroke(1.dp, SGColors.Gold.copy(alpha = 0.80f))) {
            Column(Modifier.fillMaxWidth()) {
                primary()
                guidance.lanes?.let { NavigationLaneStrip885(it) }
            }
        }
        guidance.then?.let { ThenDirectionBadge885(it) }
    }
}

@Composable
internal fun NavigationLaneStrip885(strip: LaneStrip885) {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
        .testTag("gps-lane-strip")) {
        val width = maxOf(maxWidth, 34.dp * scale * strip.lanes.size)
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        // Only overflow on genuinely narrow/scaled displays. Keep the first valid
        // lane visible without shrinking arrows; ordinary 2–8-lane strips fit.
        LaunchedEffect(strip, width, maxWidth) {
            val first = strip.lanes.indexOfFirst { it.valid }.coerceAtLeast(0)
            val target = with(density) { (width / strip.lanes.size * first - maxWidth / 2).toPx() }.toInt()
            scroll.scrollTo(target.coerceAtLeast(0))
        }
        Row(Modifier.horizontalScroll(scroll).width(width),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            strip.lanes.forEachIndexed { index, lane ->
                Canvas(Modifier.weight(1f).height(44.dp * scale)
                    .testTag("gps-lane-$index")
                    .semantics {
                        selected = lane.valid
                        contentDescription = "Lane ${index + 1} from left: " +
                            lane.directions.joinToString { it.name.lowercase().replace('_', ' ') } +
                            if (lane.valid) "; suitable for route" else "; not for this route"
                    }) {
                    // Fixed glyph proportions, even when a two-lane road has wide cells.
                    val glyphWidth = minOf(size.width - 6.dp.toPx(), size.height * 0.82f)
                    val sx = glyphWidth / 100f
                    val xInset = (size.width - glyphWidth) / 2f
                    fun glyphX(x:Float) = xInset + x * sx
                    val sy = size.height / 100f
                    fun drawBranch(direction: LaneDirection885, color: Color) {
                        var ex = 50f; var ey = 13f; var tx = 50f; var ty = 30f
                        val path = Path().apply {
                            moveTo(glyphX(50f), 87f * sy)
                            when (direction) {
                                LaneDirection885.STRAIGHT -> lineTo(glyphX(ex), ey * sy)
                                LaneDirection885.UTURN -> {
                                    ex = 80f; ey = 48f; tx = 80f; ty = 28f
                                    lineTo(glyphX(50f), 32f * sy)
                                    cubicTo(glyphX(50f), 5f * sy, glyphX(80f), 5f * sy, glyphX(80f), 32f * sy)
                                    lineTo(glyphX(ex), ey * sy)
                                }
                                else -> {
                                    val left = direction in setOf(LaneDirection885.LEFT, LaneDirection885.SLIGHT_LEFT,
                                        LaneDirection885.SHARP_LEFT, LaneDirection885.MERGE_LEFT)
                                    val slight = direction in setOf(LaneDirection885.SLIGHT_LEFT, LaneDirection885.SLIGHT_RIGHT,
                                        LaneDirection885.MERGE_LEFT, LaneDirection885.MERGE_RIGHT)
                                    val sharp = direction in setOf(LaneDirection885.SHARP_LEFT, LaneDirection885.SHARP_RIGHT)
                                    ex = if (left) 17f else 83f
                                    ey = if (slight) 14f else if (sharp) 53f else 31f
                                    tx = if (left) ex + 15f else ex - 15f
                                    ty = if (slight) ey + 17f else if (sharp) ey - 13f else ey
                                    lineTo(glyphX(50f), 60f * sy)
                                    cubicTo(glyphX(50f), (if (sharp) 23f else 31f) * sy,
                                        glyphX(tx), ty * sy, glyphX(ex), ey * sy)
                                }
                            }
                        }
                        drawPath(path, color, style = Stroke(6.5f * sy, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        val tip = Offset(glyphX(ex), ey * sy)
                        val angle = atan2((ey - ty) * sy, (ex - tx) * sx)
                        val length = 16f * sy
                        val wing = 8f * sy
                        val bx = tip.x - cos(angle) * length
                        val by = tip.y - sin(angle) * length
                        drawPath(Path().apply {
                            moveTo(tip.x, tip.y)
                            lineTo(bx - sin(angle) * wing, by + cos(angle) * wing)
                            lineTo(bx + sin(angle) * wing, by - cos(angle) * wing)
                            close()
                        }, color)
                    }
                    lane.directions.forEach { drawBranch(it, Color.White.copy(alpha = 0.30f)) }
                    lane.directions.filter { it in lane.highlighted }.forEach { drawBranch(it, Color.White) }
                    // Fine divider ticks match the reference without enclosing each lane.
                    if (index < strip.lanes.lastIndex) drawLine(Color.White.copy(alpha = 0.25f),
                        Offset(size.width - 1.dp.toPx(), size.height * 0.83f),
                        Offset(size.width - 1.dp.toPx(), size.height), 1.dp.toPx())
                }
            }
        }
    }
}

@Composable
internal fun ThenDirectionBadge885(direction: ThenDirection885) {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Surface(color = PremiumNavy, shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
        modifier = Modifier.testTag("gps-then-badge")) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Then", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Icon(painterResource(direction.cue.sign.drawable),
                contentDescription = "Following direction: " + direction.cue.sign.name.lowercase().replace('_', ' '),
                tint = Color.White,
                modifier = Modifier.size(30.dp * scale).testTag("gps-then-arrow")
                    .graphicsLayer { scaleX = if (direction.cue.mirrorHorizontally) -1f else 1f })
        }
    }
}
