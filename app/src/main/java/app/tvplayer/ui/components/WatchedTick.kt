package app.tvplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType

/** Which films and episodes have been watched, and a way to tick or untick one. */
class WatchedMarks(val urls: Set<String>, val mark: (url: String, watched: Boolean) -> Unit) {
    operator fun contains(url: String?) = url != null && url in urls
}

val LocalWatched = compositionLocalOf { WatchedMarks(emptySet()) { _, _ -> } }

/**
 * TMDB's poster for a film or series, for posters whose playlist picture is missing or won't load
 * (the playlist's own picture is always tried first).
 */
val LocalPosterLookup = compositionLocalOf<suspend (app.tvplayer.data.db.VodRow) -> String?> { { null } }

/** Series in My list with new episodes (by My list key), and a way to say one has been looked at. */
class NewEpisodeMarks(val keys: Set<String>, val seen: (key: String) -> Unit)

val LocalNewEpisodes = compositionLocalOf { NewEpisodeMarks(emptySet()) { } }

/** The small tick in the corner of a poster or episode picture that's been watched. */
@Composable
fun WatchedTick(modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Box(
        modifier = modifier
            .padding(6.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(colors.accent)
            .border(1.dp, Color.Black.copy(alpha = 0.35f), CircleShape),
    ) {
        Canvas(Modifier.size(12.dp).align(Alignment.Center)) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.2f
            val ink = Color(0xFF0A0F1E)
            drawLine(ink, Offset(w * 0.08f, h * 0.55f), Offset(w * 0.38f, h * 0.85f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(ink, Offset(w * 0.38f, h * 0.85f), Offset(w * 0.92f, h * 0.18f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/** "NEW EPISODES", on a series poster with episodes added since the viewer last looked. */
@Composable
fun NewEpisodesBadge(modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Box(
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.accent)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Label(text = "NEW EPISODES", style = AppType.overline, color = Color(0xFF0A0F1E))
    }
}
