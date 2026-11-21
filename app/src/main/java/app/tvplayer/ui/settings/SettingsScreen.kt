package app.tvplayer.ui.settings

import app.tvplayer.data.ChannelCheckRepository
import app.tvplayer.data.ChannelsRepository
import app.tvplayer.data.BackupRepository
import app.tvplayer.data.SubtitlesRepository
import app.tvplayer.data.UpdateRepository
import app.tvplayer.ui.components.EntryPoint
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tvplayer.data.MetadataRepository
import app.tvplayer.data.PlaylistRepository
import app.tvplayer.data.SettingsRepository
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.LocalAutoFocus
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import app.tvplayer.ui.theme.ThemeId

enum class SettingsSection(val label: String) {
    PLAYLISTS("Playlists"),
    FILM_INFO("Film info (TMDB)"),
    APPEARANCE("Appearance"),
    PLAYER("Player"),
    MAINTENANCE("Refresh and check"),
    BACKUP("Backup and restore"),
    UPDATES("Updates"),
}

/** Settings: a list of sections on the left, the chosen section on the right. */
@Composable
fun SettingsScreen(
    themeId: ThemeId,
    onThemeChange: (ThemeId) -> Unit,
    playlists: PlaylistRepository,
    settings: SettingsRepository,
    metadata: MetadataRepository,
    updates: UpdateRepository,
    subtitles: SubtitlesRepository,
    backup: BackupRepository? = null,
    channels: ChannelsRepository? = null,
    check: ChannelCheckRepository? = null,
) {
    val colors = AppTheme.colors
    var section by rememberSaveable { mutableStateOf(SettingsSection.PLAYLISTS) }
    val firstFocus = remember { FocusRequester() }
    EntryPoint(firstFocus)
    val autoFocus = LocalAutoFocus.current
    LaunchedEffect(Unit) { if (autoFocus()) runCatching { firstFocus.requestFocus() } }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(modifier = Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(
                text = "Settings",
                style = AppType.headline,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            )
            SettingsSection.entries.forEach { item ->
                val current = item == section
                FocusSurface(
                    onClick = { section = item },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .then(if (current) Modifier.focusRequester(firstFocus) else Modifier),
                    container = if (current) colors.glassStrong else Color.Transparent,
                    outlined = false,
                    focusScale = 1.02f,
                ) {
                    Label(
                        text = item.label,
                        style = AppType.title,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(horizontal = 12.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            when (section) {
                SettingsSection.PLAYLISTS -> PlaylistsPane(repo = playlists)
                SettingsSection.FILM_INFO -> TmdbPane(settings = settings, metadata = metadata)
                SettingsSection.APPEARANCE -> AppearancePane(themeId = themeId, onThemeChange = onThemeChange, settings = settings)
                SettingsSection.PLAYER -> PlayerPane(settings = settings, subtitles = subtitles)
                SettingsSection.MAINTENANCE -> if (channels != null && check != null) MaintenancePane(settings, channels, check, playlists)
                SettingsSection.BACKUP -> backup?.let { BackupPane(it) }
                SettingsSection.UPDATES -> UpdatesPane(updates = updates)
            }
        }
    }
}
