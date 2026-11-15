package app.tvplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tvplayer.data.UpdateRepository
import app.tvplayer.data.UpdateState
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import java.util.Locale

/** Settings › Updates: check GitHub for a newer build and install it over this copy. */
@Composable
fun UpdatesPane(updates: UpdateRepository) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val state by updates.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label(text = "Updates", style = AppType.headline)
        Label(text = "You have build ${updates.currentBuild}.", style = AppType.body, color = colors.textDim)

        when (val s = state) {
            UpdateState.Idle -> Label(
                text = "Skyfall checks GitHub once a day and puts a dot on Settings when there's a new build.",
                style = AppType.body,
                color = colors.textDim,
                maxLines = 2,
            )
            UpdateState.Checking -> Label(text = "Checking GitHub…", style = AppType.body, color = colors.accent)
            is UpdateState.UpToDate -> Label(
                text = if (s.build > updates.currentBuild) "Build ${s.build} is installed. Open Skyfall again to use it." else "You're up to date.",
                style = AppType.body,
                color = colors.accent,
            )
            is UpdateState.Available -> Label(
                text = "Build ${s.update.build} is ready" + sizeText(s.update.sizeBytes) + ".",
                style = AppType.title,
                color = colors.accent,
            )
            is UpdateState.Downloading -> {
                Label(text = "Downloading build ${s.update.build}… ${(s.fraction * 100).toInt()}%", style = AppType.body, color = colors.accent)
                ProgressBar(s.fraction)
            }
            is UpdateState.Installing -> Label(
                text = "Installing build ${s.update.build}. Confirm on the screen that appears; Skyfall closes when it's done, so open it again.",
                style = AppType.body,
                color = colors.accent,
                maxLines = 2,
            )
            is UpdateState.NeedsPermission -> Label(
                text = "First, allow Skyfall to install apps: Settings › Device Preferences › Security & restrictions › " +
                    "Unknown sources › Skyfall. Then come back and choose Try again.",
                style = AppType.body,
                color = colors.accent,
                maxLines = 3,
            )
            is UpdateState.Failed -> Label(text = s.message, style = AppType.body, color = colors.accent, maxLines = 3)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
            when (state) {
                is UpdateState.Available -> {
                    PaneAction("Download and install", accent = true) {
                        updates.downloadAndInstall()
                    }
                    PaneAction("Check again") { updates.check() }
                }
                is UpdateState.NeedsPermission -> {
                    val screen = updates.permissionScreen()
                    if (screen != null) {
                        PaneAction("Open that setting", accent = true) {
                            runCatching { context.startActivity(screen) }
                        }
                    }
                    PaneAction("Try again", accent = screen == null) { updates.downloadAndInstall() }
                }
                is UpdateState.Failed -> {
                    if ((state as UpdateState.Failed).update != null) {
                        PaneAction("Try again", accent = true) {
                            updates.downloadAndInstall()
                        }
                    }
                    PaneAction("Check for updates", accent = (state as UpdateState.Failed).update == null) { updates.check() }
                }
                UpdateState.Idle, is UpdateState.UpToDate -> PaneAction("Check for updates", accent = true) { updates.check() }
                else -> Unit
            }
        }

        Label(
            text = "Updates come from the same GitHub release as the Downloader link and install over this copy. " +
                "Some updates reset the app's data, so you may need to add your playlist again.",
            style = AppType.caption,
            color = colors.textFaint,
            maxLines = 2,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

private fun sizeText(bytes: Long): String =
    if (bytes <= 0) "" else String.format(Locale.UK, " (%.1f MB)", bytes / 1_048_576.0)

@Composable
private fun ProgressBar(fraction: Float) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .width(360.dp)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colors.glass),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(colors.accent),
        )
    }
}

@Composable
private fun PaneAction(text: String, accent: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        container = if (accent) colors.accent else null,
        focusedContainer = if (accent) colors.accent else null,
        outlined = !accent,
    ) {
        Label(
            text = text,
            style = AppType.label,
            color = if (accent) colors.onAccent else colors.text,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}
