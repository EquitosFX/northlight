package app.tvplayer.ui.settings

import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import app.tvplayer.data.SubtitleLook
import androidx.compose.ui.Alignment
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
    val offset by settings.catchupOffsetMinutes.collectAsStateWithLifecycle(initialValue = 0)
    val backupPlayer by settings.backupPlayer.collectAsStateWithLifecycle(initialValue = true)
    val audioDelay by settings.audioDelayMs.collectAsStateWithLifecycle(initialValue = 0)
    val matchFrameRate by settings.matchFrameRate.collectAsStateWithLifecycle(initialValue = false)
    val look by settings.subtitleLook.collectAsStateWithLifecycle(initialValue = SubtitleLook())
    fun setLook(change: SubtitleLook) {
        scope.launch { settings.setSubtitleLook(change) }
    }
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
            text = "Keeps the channel you're watching on this TV box's storage, so you can pause, rewind and catch up to live. " +
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

        Label(text = "Backup player", style = AppType.headline, modifier = Modifier.padding(top = 14.dp))
        Label(
            text = "When a channel or film won't play, Skyfall tries it again with a second player (VLC's engine), " +
                "which copes with more unusual streams.",
            style = AppType.body,
            color = colors.textDim,
            maxLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(label = "Use when needed", selected = backupPlayer) { scope.launch { settings.setBackupPlayer(true) } }
            Chip(label = "Off", selected = !backupPlayer) { scope.launch { settings.setBackupPlayer(false) } }
        }

        Label(text = "Match frame rate", style = AppType.headline, modifier = Modifier.padding(top = 14.dp))
        Label(
            text = "Switches the TV to 50 Hz for UK channels (and 24 Hz for films) while watching, for smooth movement. " +
                "Most noticeable on Google TV, which runs at 60 Hz. The screen may go dark for a second as it switches.",
            style = AppType.body,
            color = colors.textDim,
            maxLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(label = "On", selected = matchFrameRate) { scope.launch { settings.setMatchFrameRate(true) } }
            Chip(label = "Off", selected = !matchFrameRate) { scope.launch { settings.setMatchFrameRate(false) } }
        }

        Label(text = "Sound in sync", style = AppType.headline, modifier = Modifier.padding(top = 14.dp))
        Label(
            text = "If voices don't match lips (common with soundbars and some TVs), move the sound. " +
                "Also in the player: OK › Audio.",
            style = AppType.body,
            color = colors.textDim,
            maxLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(label = "Earlier", selected = false) { scope.launch { settings.setAudioDelayMs(audioDelay - 50) } }
            androidx.compose.foundation.layout.Box(modifier = Modifier.width(150.dp), contentAlignment = Alignment.Center) {
                Label(
                    text = when {
                        audioDelay == 0 -> "In step"
                        audioDelay > 0 -> "$audioDelay ms later"
                        else -> "${-audioDelay} ms earlier"
                    },
                    style = AppType.title,
                    color = if (audioDelay == 0) colors.text else colors.accent,
                )
            }
            Chip(label = "Later", selected = false) { scope.launch { settings.setAudioDelayMs(audioDelay + 50) } }
            if (audioDelay != 0) Chip(label = "Reset", selected = false) { scope.launch { settings.setAudioDelayMs(0) } }
        }

        Label(text = "Catch-up timing", style = AppType.headline, modifier = Modifier.padding(top = 14.dp))
        Label(
            text = "If catch-up from the TV guide starts on the wrong programme, your provider's clock is out. " +
                "Starts on the programme before? Move it later (+). The one after? Move it earlier (−).",
            style = AppType.body,
            color = colors.textDim,
            maxLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(label = "− 30 min", selected = false) { scope.launch { settings.setCatchupOffsetMinutes(offset - 30) } }
            androidx.compose.foundation.layout.Box(modifier = Modifier.width(150.dp), contentAlignment = Alignment.Center) {
                Label(text = offsetText(offset), style = AppType.title, color = if (offset == 0) colors.text else colors.accent)
            }
            Chip(label = "+ 30 min", selected = false) { scope.launch { settings.setCatchupOffsetMinutes(offset + 30) } }
            if (offset != 0) Chip(label = "Reset", selected = false) { scope.launch { settings.setCatchupOffsetMinutes(0) } }
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
        SubtitlePreview(look)
        Label(text = "Style", style = AppType.caption, color = colors.textDim)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Chip(label = "Netflix style", selected = look == SubtitleLook.NETFLIX) { setLook(SubtitleLook.NETFLIX) } }
            items(listOf("netflix" to "Soft shadow", "box" to "Dark box", "shadow" to "Drop shadow", "outline" to "Outline", "plain" to "Plain")) { (key, label) ->
                Chip(label = label, selected = look.style == key && look != SubtitleLook.NETFLIX) { setLook(look.copy(style = key)) }
            }
        }
        Label(text = "Font", style = AppType.caption, color = colors.textDim)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SubtitleLook.FONTS) { (key, label) ->
                Chip(label = label, selected = look.font == key) { setLook(look.copy(font = key)) }
            }
        }
        Label(text = "Size and colour", style = AppType.caption, color = colors.textDim)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(75 to "Small", 90 to "Medium-small", 100 to "Medium", 115 to "Medium-large", 130 to "Large", 150 to "Extra large")) { (size, label) ->
                Chip(label = label, selected = look.size == size) { setLook(look.copy(size = size)) }
            }
            items(listOf("white" to "White", "grey" to "Soft white", "yellow" to "Yellow")) { (key, label) ->
                Chip(label = label, selected = look.colour == key) { setLook(look.copy(colour = key)) }
            }
        }
        Label(text = "Shadow or outline strength, box darkness and height on screen", style = AppType.caption, color = colors.textDim)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(1 to "Soft edge", 2 to "Normal edge", 3 to "Strong edge")) { (edge, label) ->
                Chip(label = label, selected = look.edge == edge) { setLook(look.copy(edge = edge)) }
            }
            items(listOf(40 to "Light box", 60 to "Box", 80 to "Dark box", 100 to "Solid box")) { (opacity, label) ->
                Chip(label = label, selected = look.style == "box" && look.boxOpacity == opacity) { setLook(look.copy(style = "box", boxOpacity = opacity)) }
            }
            items(listOf(0 to "Low", 1 to "Normal height", 2 to "Higher", 3 to "Highest")) { (lift, label) ->
                Chip(label = label, selected = look.lift == lift) { setLook(look.copy(lift = lift)) }
            }
        }
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

/** "No change", "1 h 30 min later", "30 min earlier". */
private fun offsetText(minutes: Int): String {
    if (minutes == 0) return "No change"
    val m = kotlin.math.abs(minutes)
    val text = listOfNotNull((m / 60).takeIf { it > 0 }?.let { "$it h" }, (m % 60).takeIf { it > 0 }?.let { "$it min" }).joinToString(" ")
    return if (minutes > 0) "$text later" else "$text earlier"
}

/** A sample subtitle in the chosen look, drawn exactly as in the player, over a stand-in picture. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun SubtitlePreview(look: SubtitleLook) {
    val sample = remember {
        listOf(androidx.media3.common.text.Cue.Builder().setText("I'll be home before the snow comes in.\nKeep the fire going.").build())
    }
    Box(
        modifier = Modifier
            .width(448.dp)
            .height(252.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF8BA3BF), Color(0xFF4A5A73), Color(0xFF1E2533)))),
    ) {
        app.tvplayer.player.SubtitleOverlay(cues = sample, look = look)
    }
}
