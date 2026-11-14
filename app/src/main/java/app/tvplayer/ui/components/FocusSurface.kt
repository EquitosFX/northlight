package app.tvplayer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.tvplayer.ui.theme.AppTheme

/**
 * The one focusable building block for the remote.
 * Focus is shown three ways at once (lighter fill, white ring, slight scale-up) so it never relies on colour alone.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(9.dp),
    container: Color? = null,
    focusedContainer: Color? = null,
    outlined: Boolean = true,
    focusScale: Float = 1.04f,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val colors = AppTheme.colors
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) focusScale else 1f, label = "focusScale")

    val fill = if (focused) focusedContainer ?: colors.glassStrong else container ?: colors.glass
    val ringWidth = if (focused) 2.dp else 1.dp
    val ringColor = when {
        focused -> colors.focusRing
        outlined -> colors.glassBorder
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { state -> focused = state.isFocused }
            .clip(shape)
            .background(fill)
            .border(width = ringWidth, color = ringColor, shape = shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content(focused)
    }
}
