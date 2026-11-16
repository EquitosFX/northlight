package app.tvplayer.ui.settings

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tvplayer.core.live.Lineup
import app.tvplayer.data.ChannelCheckRepository
import app.tvplayer.data.ChannelsRepository
import app.tvplayer.data.SettingsRepository
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Settings › Refresh and check: how often playlists refresh, and a check for channels that don't work. */
@Composable
fun MaintenancePane(settings: SettingsRepository, channels: ChannelsRepository, check: ChannelCheckRepository) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val hours by settings.autoRefreshHours.collectAsStateWithLifecycle(initialValue = 24)
    val progress by check.progress.collectAsStateWithLifecycle()
    val dead by check.dead.collectAsStateWithLifecycle()
    val lineup by produceState<Lineup?>(initialValue = null) { value = channels.lineup() }
    val favourites by produceState(initialValue = emptySet<String>()) { value = channels.favouriteKeys.first() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(text = "Keep playlists up to date", style = AppType.headline)
        Label(
            text = "Skyfall downloads your playlists again by itself, so new channels, films and series appear. It happens quietly while the app is open.",
            style = AppType.body,
            color = colors.textDim,
            maxLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Off", 12 to "Every 12 hours", 24 to "Every day", 72 to "Every 3 days", 168 to "Every week").forEach { (value, label) ->
                SettingChip(label = label, selected = hours == value) { scope.launch { settings.setAutoRefreshHours(value) } }
            }
        }

        Label(text = "Check channels", style = AppType.headline, modifier = Modifier.padding(top = 16.dp))
        Label(
            text = "Opens each channel for a moment to see whether it sends a picture, one at a time as most providers allow only one " +
                "connection. Channels that don't respond get a red mark in the TV guide. Stop anything you're watching first.",
            style = AppType.body,
            color = colors.textDim,
            maxLines = 3,
        )
        val all = lineup?.channels.orEmpty()
        if (progress.running) {
            Label(
                text = "Checking ${progress.label}: ${progress.checked} of ${progress.total}" +
                    if (progress.failed.isNotEmpty()) " · ${progress.failed.size} not working" else "",
                style = AppType.title,
                color = colors.accent,
            )
            val fraction = if (progress.total > 0) progress.checked.toFloat() / progress.total else 0f
            Box(Modifier.width(420.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(colors.accent))
            }
            ActionButton("Stop") { check.stop() }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val favouriteChannels = all.filter { it.key in favourites }
                if (favouriteChannels.isNotEmpty()) {
                    item { SettingChip("Favourites (${favouriteChannels.size})", false) { check.check("Favourites", favouriteChannels) } }
                }
                items(all.mapNotNull { it.group }.distinct()) { group ->
                    val inGroup = all.filter { it.group == group }
                    SettingChip("$group (${inGroup.size})", false) { check.check(group, inGroup) }
                }
                if (all.isNotEmpty()) item { SettingChip("All channels (${all.size})", false) { check.check("all channels", all) } }
            }
            if (progress.total > 0) {
                Label(
                    text = "Last check (${progress.label}): ${progress.total - progress.failed.size} of ${progress.total} working.",
                    style = AppType.title,
                    color = colors.accent,
                )
            }
        }
        if (progress.failed.isNotEmpty()) {
            Label(text = "Not working: " + progress.failed.take(40).joinToString(", "), style = AppType.body, color = colors.textDim, maxLines = 4)
        }
        if (dead.isNotEmpty() && !progress.running) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Label(text = "${dead.size} channels marked as not working.", style = AppType.body, color = colors.textDim)
                ActionButton("Clear the marks") { check.clearMarks() }
            }
        }
    }
}

@Composable
private fun SettingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    app.tvplayer.ui.components.FocusSurface(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        container = if (selected) colors.accent else null,
        focusedContainer = if (selected) colors.accent else null,
    ) { focused ->
        Label(
            text = label,
            style = AppType.label,
            color = if (selected) colors.onAccent else colors.text,
            modifier = Modifier.align(androidx.compose.ui.Alignment.Center).padding(horizontal = 14.dp),
        )
    }
}
