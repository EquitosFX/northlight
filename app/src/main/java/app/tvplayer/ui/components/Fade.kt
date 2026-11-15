package app.tvplayer.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Fades artwork out to nothing on its left and bottom edges, so it melts into whatever is behind it
 * rather than ending in a visible line. [solidFrom] is how far across it becomes fully visible,
 * [solidUntil] how far down it stays fully visible.
 */
fun Modifier.fadeIntoBackground(solidFrom: Float = 0.45f, solidUntil: Float = 0.5f): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.horizontalGradient(0f to Color.Transparent, solidFrom to Color.Black), blendMode = BlendMode.DstIn)
            drawRect(Brush.verticalGradient(solidUntil to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }
