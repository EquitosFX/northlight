package app.tvplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tvplayer.core.subtitles.OpenSubtitles
import app.tvplayer.data.OpenSubtitlesAccount
import app.tvplayer.data.SettingsRepository
import app.tvplayer.data.SubtitlesRepository
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.TvTextField
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import kotlinx.coroutines.launch

/** Settings › Player: pausing live TV, and subtitles from OpenSubtitles. */
@Composable
fun PlayerPane(settings: SettingsRepository, subtitles: SubtitlesRepository) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val minutes by settings.timeshiftMinutes.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_TIMESHIFT_MINUTES)
    val language by settings.subtitleLanguage.collectAsStateWithLifecycle(initialValue = "en")
    val saved by settings.openSubtitles.collectAsStateWithLifecycle(initialValue = OpenSubtitlesAccount("", "", ""))
    var key by remember(saved) { mutableStateOf(saved.apiKey) }
    var user by remember(saved) { mutableStateOf(saved.username) }
    var password by remember(saved) { mutableStateOf(saved.password) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(text = "Pause and rewind live TV", style = AppType.headline)
        Label(
            text = "Keeps the channel you're watching on the Shield's storage, so you can pause, rewind and catch up to live. " +
                "It uses at most half the free space, and is cleared when you change channel.",
            style = AppType.body,
            color = colors.textDim,
            maxLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours").forEach { (value, label) ->
                Chip(label = label, selected = minutes == value) { scope.launch { settings.setTimeshiftMinutes(value) } }
            }
        }

        Label(text = "Subtitles", style = AppType.headline, modifier = Modifier.padding(top = 14.dp))
        Label(
            text = "Live channels show the subtitles they send. For films and series, Skyfall can also find subtitles on " +
                "OpenSubtitles.com: get a free API key at opensubtitles.com › Profile › API consumers. Signing in with a free " +
                "account raises the daily download limit.",
            style = AppType.body,
            color = colors.textDim,
            maxLines = 4,
        )
        Label(text = "Language", style = AppType.caption, color = colors.textDim)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(OpenSubtitles.LANGUAGES) { (code, name) ->
                Chip(label = name, selected = language == code) { scope.launch { settings.setSubtitleLanguage(code) } }
            }
        }
        TvTextField(
            value = key,
            onValueChange = { key = it },
            label = "OpenSubtitles API key",
            placeholder = "Press OK to type or paste",
            modifier = Modifier.width(460.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(
                value = user,
                onValueChange = { user = it },
                label = "Username (optional)",
                modifier = Modifier.width(224.dp),
            )
            TvTextField(
                value = password,
                onValueChange = { password = it },
                label = "Password (optional)",
                isPassword = true,
                modifier = Modifier.width(224.dp),
            )
        }
        status?.let { Label(text = it, style = AppType.body, color = colors.accent, maxLines = 2) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 24.dp)) {
            Chip(label = "Save and check", selected = true) {
                status = "Checking…"
                scope.launch {
                    settings.setOpenSubtitles(OpenSubtitlesAccount(key, user, password))
                    status = if (key.isBlank()) "Saved. Subtitles from OpenSubtitles are off until a key is added." else subtitles.check()
                }
            }
            if (saved.apiKey.isNotEmpty()) {
                Chip(label = "Remove", selected = false) {
                    scope.launch {
                        settings.setOpenSubtitles(OpenSubtitlesAccount("", "", ""))
                        status = "Removed."
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        container = if (selected) colors.accent else null,
        focusedContainer = if (selected) colors.accent else null,
        outlined = !selected,
    ) {
        Label(
            text = label,
            style = AppType.label,
            color = if (selected) colors.onAccent else colors.text,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}
