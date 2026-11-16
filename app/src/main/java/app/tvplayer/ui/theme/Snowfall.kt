package app.tvplayer.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** One flake or sparkle. Positions are worked out from the time, so nothing is stored per frame. */
private class Flake(
    val x: Float,
    val y: Float,
    val radius: Float,
    val speed: Float,
    val sway: Float,
    val swayRate: Float,
    val phase: Float,
    val twinkleRate: Float,
    val sparkle: Boolean,
)

/**
 * The Christmas theme's background: a warm red glow from below and a green one from the top, with
 * snow drifting down and a few red and gold sparkles twinkling. Only drawing work runs each frame;
 * the screens themselves aren't redrawn.
 */
@Composable
fun Snowfall(
    modifier: Modifier = Modifier,
    count: Int = 70,
    /** False on less powerful boxes: the snow stays still, as redrawing the screen every frame slows everything else. */
    animate: Boolean = true,
) {
    val flakes = remember(count) {
        val random = Random(25)
        List(count) {
            val sparkle = random.nextFloat() < 0.18f
            Flake(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = if (sparkle) 1.2f + random.nextFloat() * 1.4f else 1.0f + random.nextFloat() * 2.4f,
                speed = 0.012f + random.nextFloat() * 0.03f,
                sway = 0.004f + random.nextFloat() * 0.012f,
                swayRate = 0.2f + random.nextFloat() * 0.5f,
                phase = random.nextFloat() * 2f * PI.toFloat(),
                twinkleRate = 0.6f + random.nextFloat() * 1.6f,
                sparkle = sparkle,
            )
        }
    }
    // An endless clock in seconds (it starts again every hour; flakes just carry on from a new place).
    // Read only while drawing, so each frame redraws the snow without rebuilding anything else.
    val seconds: androidx.compose.runtime.State<Float>? = if (animate) {
        rememberInfiniteTransition(label = "snow").animateFloat(
            initialValue = 0f,
            targetValue = 3_600f,
            animationSpec = infiniteRepeatable(tween(3_600_000, easing = LinearEasing), RepeatMode.Restart),
            label = "seconds",
        )
    } else {
        null
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Soft glows, like stage lights through mist.
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFD0102A).copy(alpha = 0.38f), Color.Transparent), center = Offset(w * 0.55f, h * 1.05f), radius = h * 0.9f),
            radius = h * 0.9f,
            center = Offset(w * 0.55f, h * 1.05f),
        )
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF1F7A4D).copy(alpha = 0.30f), Color.Transparent), center = Offset(w * 0.3f, h * 0.05f), radius = h * 0.8f),
            radius = h * 0.8f,
            center = Offset(w * 0.3f, h * 0.05f),
        )
        val t = seconds?.value ?: 0f
        val scale = density
        for (f in flakes) {
            val y = ((f.y + f.speed * t) % 1f) * (h + 20f) - 10f
            val x = (f.x + sin(t * f.swayRate + f.phase) * f.sway) * w
            val twinkle = 0.55f + 0.45f * sin(t * f.twinkleRate + f.phase)
            if (f.sparkle) {
                val colour = if (f.phase > PI) Color(0xFFFF6B8B) else Color(0xFFFFD27A)
                drawCircle(colour.copy(alpha = 0.25f * twinkle), radius = f.radius * scale * 3f, center = Offset(x, y))
                drawCircle(colour.copy(alpha = 0.9f * twinkle), radius = f.radius * scale, center = Offset(x, y))
            } else {
                drawCircle(Color.White.copy(alpha = 0.35f + 0.4f * twinkle), radius = f.radius * scale, center = Offset(x, y))
            }
        }
    }
}
