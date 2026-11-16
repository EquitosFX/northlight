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
    val matchFrameRate by settings.matchFrameRate.collectAsStateWithLifecycle(initialValue = true)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(label = "Netflix style", selected = look == SubtitleLook.NETFLIX) { setLook(SubtitleLook.NETFLIX) }
            Chip(label = "Dark box", selected = look.style == "box") { setLook(look.copy(style = "box")) }
            Chip(label = "Shadow, no box", selected = look.style == "shadow") { setLook(look.copy(style = "shadow")) }
            Chip(label = "Outline, no box", selected = look.style == "outline") { setLook(look.copy(style = "outline")) }
        }
        Label(text = "Size", style = AppType.caption, color = colors.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(80 to "Small", 100 to "Medium", 125 to "Large", 150 to "Extra large").forEach { (size, label) ->
                Chip(label = label, selected = look.size == size) { setLook(look.copy(size = size)) }
            }
        }
        Label(text = "Font and colour", style = AppType.caption, color = colors.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(label = "Standard", selected = look.font == "standard") { setLook(look.copy(font = "standard")) }
            Chip(label = "Bold", selected = look.font == "bold") { setLook(look.copy(font = "bold")) }
            Chip(label = "Serif", selected = look.font == "serif") { setLook(look.copy(font = "serif")) }
            Chip(label = "White", selected = look.colour == "white") { setLook(look.copy(colour = "white")) }
            Chip(label = "Yellow", selected = look.colour == "yellow") { setLook(look.copy(colour = "yellow")) }
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

/** A sample line in the chosen subtitle look, over a stand-in for the picture. */
@Composable
private fun SubtitlePreview(look: SubtitleLook) {
    val text = if (look.colour == "yellow") Color(0xFFFFE650) else Color.White
    val family = if (look.font == "serif") FontFamily.Serif else FontFamily.SansSerif
    val weight = if (look.font == "bold") FontWeight.Bold else FontWeight.Medium
    val shadow = when (look.style) {
        "shadow" -> Shadow(Color.Black, Offset(2f, 3f), 8f)
        "outline" -> Shadow(Color.Black, Offset.Zero, 4f)
        else -> null
    }
    Box(
        modifier = Modifier
            .width(520.dp)
            .height(92.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF6A7F9A), Color(0xFF2B3446)))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        BasicText(
            text = "I'll be home before the snow comes in.",
            style = TextStyle(
                color = text,
                fontSize = (17f * look.size / 100f).sp,
                fontFamily = family,
                fontWeight = weight,
                shadow = shadow,
            ),
            modifier = Modifier
                .padding(bottom = 10.dp)
                .then(if (look.style == "box") Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp) else Modifier),
        )
    }
}
