package app.tvplayer.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.tvplayer.ui.theme.AppTheme

/**
 * Fades the bottom of a list into the background, so a row cut off by the bottom of the screen shows
 * there's more below. Only while [more] is true (the list can scroll further).
 *
 * Drawn as the app's own background over the bottom edge, in thin bands that get stronger towards the
 * bottom, lined up with the background behind (a gradient across the whole screen). That costs a few
 * rectangles a frame, unlike fading the list itself, which needs the whole list drawn twice.
 */
fun Modifier.fadeBottomEdge(height: Dp = 56.dp, more: () -> Boolean = { true }): Modifier = composed {
    val colors = AppTheme.colors
    var origin by remember { mutableStateOf(Offset.Zero) }
    var screen by remember { mutableStateOf(IntSize.Zero) }
    this
        .onGloballyPositioned { coordinates ->
            origin = coordinates.positionInRoot()
            var root = coordinates
            while (root.parentLayoutCoordinates != null) root = root.parentLayoutCoordinates!!
            screen = root.size
        }
        .drawWithContent {
            drawContent()
            if (!more() || screen == IntSize.Zero) return@drawWithContent
            // The background's gradient, placed as it is behind the list.
            val ground = Brush.linearGradient(
                listOf(colors.groundTop, colors.groundMid, colors.groundBottom),
                start = Offset(-origin.x, -origin.y),
                end = Offset(screen.width - origin.x, screen.height - origin.y),
            )
            val band = height.toPx().coerceAtMost(size.height)
            val steps = 14
            val step = band / steps
            for (i in 0 until steps) {
                val strength = (i + 1f) / steps
                drawRect(
                    brush = ground,
                    topLeft = Offset(0f, size.height - band + i * step),
                    size = Size(size.width, step + 1f),
                    alpha = strength * strength * 0.85f + 0.0f,
                )
            }
        }
}
